package com.digitalpetri.iec60870.tests;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.digitalpetri.iec60870.address.CommonAddress;
import com.digitalpetri.iec60870.address.PointAddress;
import com.digitalpetri.iec60870.client.ClientEvent;
import com.digitalpetri.iec60870.client.Iec60870Client;
import com.digitalpetri.iec60870.cs104.ApciSettings;
import com.digitalpetri.iec60870.point.PointCapability;
import com.digitalpetri.iec60870.point.PointType;
import com.digitalpetri.iec60870.point.PointValue;
import com.digitalpetri.iec60870.server.Iec60870Server;
import com.digitalpetri.iec60870.server.PointDefinition;
import com.digitalpetri.iec60870.server.Station;
import com.digitalpetri.iec60870.transport.tcp.TcpIec104Client;
import com.digitalpetri.iec60870.transport.tcp.TcpIec104Server;
import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.joou.UShort;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Real-Netty integration test for the connect-establishment path of {@link TcpIec104Client}: a
 * failed initial connect must surface as an exceptionally-completed {@code connectAsync()} future,
 * and the persistent {@code ChannelFsm} must keep retrying on its own so that a server appearing
 * later on the same port is picked up without any further {@code connect()} call.
 *
 * <p>The client's {@code t0} (connection-establishment timeout) is wired straight through to
 * Netty's {@code CONNECT_TIMEOUT_MILLIS}, so a short {@code t0} bounds the connect attempt
 * deterministically.
 *
 * <p>SCOPED-DOWN, deliberately: this asserts the OUTCOMES that hold regardless of whether the OS
 * surfaces a true SYN-drop timeout ({@code ConnectTimeoutException}) or an immediate
 * connection-refused ({@code ConnectException}) — namely that the connect did NOT succeed and that
 * the persistent FSM stays in its retry loop. Asserting specifically on {@code
 * ConnectTimeoutException} would require a filled-accept-backlog trick whose queue depth and
 * drop-vs-reset behavior are kernel-tunable, hence environment-fragile; that variant is
 * intentionally omitted. The retry loop is proven the strongest, non-fragile way: a real server is
 * started on the same port and the FSM auto-connects with no extra {@code connect()} call.
 *
 * <p>NON-FLAKY discipline: assertions are on the connect future failing and on
 * event/connection-state outcomes, never on durations. The only timing element is the
 * netty-channel-fsm reconnect backoff (~1s first retry, not tunable through any public knob), which
 * is absorbed by a generous bounded wait, exactly as {@link ReconnectIntegrationTest} does.
 */
class ClientConnectTimeoutIntegrationTest {

  private static final CommonAddress STATION = CommonAddress.of(1);
  private static final PointAddress SINGLE_POINT = PointAddress.of(1, 100);

  /**
   * Short connection-establishment timeout so a failed connect resolves quickly. This is wired to
   * Netty's {@code CONNECT_TIMEOUT_MILLIS}; the other timers keep their defaults.
   */
  private static final Duration SHORT_T0 = Duration.ofMillis(300);

  /**
   * Generous deadline for the persistent FSM to retry and pick up the server that appears on the
   * port after the initial failure. The first reconnect fires after ~1s (fixed backoff, not
   * shortenable via any public knob), so this is well above {@link Await#DEFAULT_TIMEOUT}.
   */
  private static final Duration RETRY_TIMEOUT = Duration.ofSeconds(20);

  private @Nullable Iec60870Server server;
  private @Nullable Iec60870Client client;

  @AfterEach
  void tearDown() {
    if (client != null) {
      client.close();
    }
    if (server != null) {
      server.close();
    }
  }

  @Test
  void failedConnectFailsTheFutureThenThePersistentFsmRetriesUntilTheServerAppears()
      throws Exception {
    // Reserve a port and leave NOTHING listening on it, so the first connect cannot succeed.
    int port = reserveEphemeralPort();

    EventCollector events = new EventCollector();
    Iec60870Client client =
        TcpIec104Client.builder()
            .host("127.0.0.1")
            .port(port)
            // Short t0 -> short CONNECT_TIMEOUT_MILLIS so a SYN-timeout (if the OS drops) is
            // bounded;
            // a refused port fails even faster. Either way the connect attempt resolves promptly.
            .apci(
                new ApciSettings(
                    UShort.valueOf(12),
                    UShort.valueOf(8),
                    SHORT_T0,
                    Duration.ofSeconds(15),
                    Duration.ofSeconds(10),
                    Duration.ofSeconds(20)))
            .startDataTransferOnConnect(true)
            .build();
    this.client = client;
    client.events().subscribe(events);

    // 1. The initial connect must FAIL (the handleConnectFailureEvent path completes the first
    // connect() future exceptionally). We do not assert the concrete exception type because
    // refused-vs-timeout is OS-dependent; we assert only that the future completed exceptionally.
    ExecutionException failure =
        assertThrows(
            ExecutionException.class,
            () ->
                client
                    .connectAsync()
                    .toCompletableFuture()
                    // Bound a little over t0 to absorb scheduling jitter; the connect itself is
                    // capped by CONNECT_TIMEOUT_MILLIS, so a real timeout here would indicate a
                    // hang.
                    .get(10, TimeUnit.SECONDS),
            "the initial connect to a dead port must fail exceptionally");
    // Sanity: a cause is present (the connect failure) and the client is not connected.
    org.junit.jupiter.api.Assertions.assertNotNull(
        failure.getCause(), "the connect failure should carry a cause");
    assertFalse(client.isConnected(), "client must not be connected after a failed connect");

    // 2. THE load-bearing retry proof. Without calling connect() again, bring up a real server on
    // the SAME port. The persistent, non-lazy ChannelFsm must, on its own, transition
    // ReconnectWait -> Reconnecting -> Connected and re-establish the TCP socket. isConnected()
    // reflects FSM State.Connected, so its flipping to true with NO further connect() call is
    // direct
    // evidence the failed connect left the FSM in its retry loop. (The IEC session/ConnectionOpened
    // is only re-armed by an explicit connect(), exercised in step 3; a pure FSM reconnect does not
    // by itself publish ConnectionOpened.) If the FSM had parked instead of retrying, isConnected()
    // would stay false forever and this await would time out.
    startServer(port);

    awaitWithin(
        "persistent FSM auto-reconnected the TCP socket to the late-arriving server (isConnected) "
            + "without an explicit connect() — proves the failed connect left it in its retry loop",
        client::isConnected,
        RETRY_TIMEOUT);

    // 3. Now that the FSM has the socket back, an explicit connect() reuses the auto-reconnected
    // channel (the FSM is already Connected) and arms the IEC session over it: a successful STARTDT
    // proves the retried socket is fully usable end to end.
    client.connect();
    awaitWithin(
        "ConnectionOpened after the session is armed on the FSM-reconnected socket",
        () -> count(events, ClientEvent.ConnectionOpened.class) >= 1,
        Await.DEFAULT_TIMEOUT);
    awaitWithin(
        "DataTransferStarted on the FSM-reconnected socket (STARTDT ran)",
        () -> count(events, ClientEvent.DataTransferStarted.class) >= 1,
        Await.DEFAULT_TIMEOUT);
    assertTrue(client.isConnected(), "client should be connected after the session is armed");
  }

  private void startServer(int port) {
    Station station =
        Station.builder(STATION)
            .point(
                PointDefinition.of(
                    SINGLE_POINT,
                    PointType.SINGLE_POINT,
                    PointValue.single(false),
                    PointCapability.REPORTED))
            .build();
    server =
        TcpIec104Server.builder().bindAddress("127.0.0.1").port(port).addStation(station).build();
    server.start();
  }

  private static int count(EventCollector events, Class<? extends ClientEvent> type) {
    return (int) events.events().stream().filter(type::isInstance).count();
  }

  private static void awaitWithin(
      String description, java.util.function.BooleanSupplier condition, Duration timeout) {
    long deadline = System.nanoTime() + timeout.toNanos();
    while (System.nanoTime() < deadline) {
      if (condition.getAsBoolean()) {
        return;
      }
      try {
        Thread.sleep(20);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new AssertionError("interrupted while polling for: " + description, e);
      }
    }
    if (!condition.getAsBoolean()) {
      throw new AssertionError(
          "timed out after " + timeout.toMillis() + " ms waiting for: " + description);
    }
  }

  private static int reserveEphemeralPort() throws IOException {
    try (ServerSocket socket = new ServerSocket(0)) {
      socket.setReuseAddress(true);
      return socket.getLocalPort();
    }
  }
}
