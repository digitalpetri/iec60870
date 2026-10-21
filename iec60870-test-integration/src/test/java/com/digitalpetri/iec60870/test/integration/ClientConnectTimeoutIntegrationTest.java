package com.digitalpetri.iec60870.test.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.digitalpetri.iec60870.ConnectionClosedException;
import com.digitalpetri.iec60870.address.CommonAddress;
import com.digitalpetri.iec60870.address.PointAddress;
import com.digitalpetri.iec60870.client.ClientEvent;
import com.digitalpetri.iec60870.client.ConnectionState;
import com.digitalpetri.iec60870.client.Iec60870Client;
import com.digitalpetri.iec60870.client.ReconnectPolicy;
import com.digitalpetri.iec60870.cs104.ApciSettings;
import com.digitalpetri.iec60870.point.PointCapability;
import com.digitalpetri.iec60870.point.PointType;
import com.digitalpetri.iec60870.point.PointValue;
import com.digitalpetri.iec60870.server.Iec60870Server;
import com.digitalpetri.iec60870.server.PointDefinition;
import com.digitalpetri.iec60870.server.Station;
import com.digitalpetri.iec60870.tcp.TcpIec104Client;
import com.digitalpetri.iec60870.tcp.TcpIec104Server;
import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.joou.UShort;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Real-Netty integration test for the connect-establishment path of {@link TcpIec104Client}: a
 * failed initial connect fails the {@code connect()} call with a typed exception that carries the
 * transport's cause, and the client then keeps retrying per its {@link ReconnectPolicy}, so a
 * server appearing later on the same port is picked up without any further {@code connect()} call.
 *
 * <p>The client's {@code t0} (connection-establishment timeout) is wired straight through to
 * Netty's {@code CONNECT_TIMEOUT_MILLIS}, so a short {@code t0} bounds each attempt. The test does
 * not depend on whether the OS reports a refused port or a timeout; it asserts that the connect
 * failed, that retries were scheduled, and that the client connects on its own once the server is
 * up.
 */
class ClientConnectTimeoutIntegrationTest {

  private static final CommonAddress STATION = CommonAddress.of(1);
  private static final PointAddress SINGLE_POINT = PointAddress.of(1, 100);

  /**
   * Short connection-establishment timeout so a failed connect resolves quickly. This is wired to
   * Netty's {@code CONNECT_TIMEOUT_MILLIS}; the other timers keep their defaults.
   */
  private static final Duration SHORT_T0 = Duration.ofMillis(300);

  /** Generous deadline for the retries to pick up the server that appears after the failure. */
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
  void failedConnectFailsTheCallThenTheClientRetriesUntilTheServerAppears() throws Exception {
    // Reserve a port and leave NOTHING listening on it, so the first connect cannot succeed.
    int port = reserveEphemeralPort();

    EventCollector events = new EventCollector();
    Iec60870Client client =
        TcpIec104Client.builder()
            .host("127.0.0.1")
            .port(port)
            .apci(
                new ApciSettings(
                    UShort.valueOf(12),
                    UShort.valueOf(8),
                    SHORT_T0,
                    Duration.ofSeconds(15),
                    Duration.ofSeconds(10),
                    Duration.ofSeconds(20)))
            .startDataTransferOnConnect(true)
            .reconnectPolicy(
                ReconnectPolicy.exponentialBackoff(Duration.ofMillis(100), Duration.ofMillis(500)))
            .build();
    this.client = client;
    client.events().subscribe(events);

    // 1. The initial connect fails promptly with a typed exception carrying the transport cause.
    ExecutionException failure =
        assertThrows(
            ExecutionException.class,
            () -> client.connectAsync().toCompletableFuture().get(10, TimeUnit.SECONDS),
            "the initial connect to a dead port must fail exceptionally");
    assertInstanceOf(ConnectionClosedException.class, failure.getCause());
    assertNotNull(failure.getCause().getCause(), "the transport's failure is the cause");
    assertFalse(client.isConnected(), "client must not be connected after a failed connect");

    // 2. The client schedules retries on its own, each announced with its attempt and cause.
    awaitWithin(
        "ReconnectScheduled events for the failed attempts",
        () -> events.hasMatch(ClientEvent.ReconnectScheduled.class, r -> r.attempt() >= 2),
        RETRY_TIMEOUT);
    assertTrue(
        events.hasMatch(
            ClientEvent.ReconnectScheduled.class,
            r -> r.attempt() == 1 && r.cause() instanceof ConnectionClosedException),
        "the first scheduled attempt reports the failure of the first connect");
    assertNoConnectionClosedBeforeAnOpen(events);

    // 3. THE load-bearing retry proof. Without calling connect() again, bring up a real server on
    // the SAME port. The client must connect on its own, arm the session, and run STARTDT.
    startServer(port);

    awaitWithin(
        "ConnectionOpened after the server appears, with no further connect()",
        () -> events.hasAny(ClientEvent.ConnectionOpened.class),
        RETRY_TIMEOUT);
    awaitWithin(
        "DataTransferStarted on the retried connection (STARTDT ran)",
        () -> events.hasAny(ClientEvent.DataTransferStarted.class),
        Await.DEFAULT_TIMEOUT);
    awaitWithin("client reports connected", client::isConnected, Await.DEFAULT_TIMEOUT);
    assertEquals(ConnectionState.CONNECTED, client.connectionState());
    assertTrue(client.interrogate(STATION).terminated());
  }

  /** A failed attempt never opened a connection, so it must not publish ConnectionClosed. */
  private static void assertNoConnectionClosedBeforeAnOpen(EventCollector events) {
    assertFalse(
        events.hasAny(ClientEvent.ConnectionClosed.class),
        "failed attempts publish ReconnectScheduled, not ConnectionClosed");
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

  private static void awaitWithin(String description, BooleanSupplier condition, Duration timeout) {
    long deadline = System.nanoTime() + timeout.toNanos();
    while (System.nanoTime() < deadline) {
      if (condition.getAsBoolean()) {
        return;
      }
      try {
        //noinspection BusyWait
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
