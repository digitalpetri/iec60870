package com.digitalpetri.iec60870.tests;

import static java.util.Objects.requireNonNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.digitalpetri.iec60870.ConnectionClosedException;
import com.digitalpetri.iec60870.address.CommonAddress;
import com.digitalpetri.iec60870.address.PointAddress;
import com.digitalpetri.iec60870.client.ClientEvent;
import com.digitalpetri.iec60870.client.Iec60870Client;
import com.digitalpetri.iec60870.client.InterrogationResult;
import com.digitalpetri.iec60870.point.PointCapability;
import com.digitalpetri.iec60870.point.PointType;
import com.digitalpetri.iec60870.point.PointValue;
import com.digitalpetri.iec60870.server.Iec60870Server;
import com.digitalpetri.iec60870.server.InterrogationRequest;
import com.digitalpetri.iec60870.server.InterrogationResponse;
import com.digitalpetri.iec60870.server.PointDefinition;
import com.digitalpetri.iec60870.server.ServerContext;
import com.digitalpetri.iec60870.server.ServerHandler;
import com.digitalpetri.iec60870.server.Station;
import com.digitalpetri.iec60870.transport.tcp.TcpIec104Client;
import com.digitalpetri.iec60870.transport.tcp.TcpIec104Server;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelOption;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.util.concurrent.GlobalEventExecutor;
import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Real-Netty integration test that an in-flight, never-confirmed request fails with {@link
 * ConnectionClosedException} when the transport is abruptly dropped (hard RST) mid-flight, that
 * exactly one {@link ClientEvent.ConnectionClosed} is published, and that no frame buffer leaks.
 *
 * <p>The pending request is created without any wire hackery: the server's {@link ServerHandler}
 * answers {@code STARTDT} normally (so the session reaches data-transfer-started and the client's
 * interrogation I-frame is genuinely transmitted and registered as pending) but its {@code
 * onInterrogationAsync} returns a future that is never completed, so the client's request stays
 * in-flight. The test then closes the captured server-side child channel with {@code SO_LINGER=0}
 * (hard RST) and asserts the pending {@code interrogateAsync} future fails with {@link
 * ConnectionClosedException}.
 *
 * <p>The drop routes through {@code Session.Events.onConnectionLost}, which fails all pending
 * requests with {@code ConnectionClosedException} and publishes a single {@code ConnectionClosed}
 * (guaranteed once by the one-shot publish CAS in the client). This is the transport-loss path,
 * which does NOT call {@code transport.disconnect()}; to keep this test isolated from the ~1s
 * reconnect backoff, the client is closed immediately after the failure + single-close are
 * observed.
 *
 * <p>NON-FLAKY discipline: assertions are on the future failing with a specific exception type and
 * on event COUNTS, never on durations. The PARANOID Netty leak detector ({@link
 * ParanoidLeakDetection}, auto-registered) surfaces any orphaned {@code ByteBuf} as a {@code LEAK:}
 * log line during the build.
 */
class AbruptClosePendingRequestIntegrationTest {

  private static final CommonAddress STATION = CommonAddress.of(1);
  private static final PointAddress SINGLE_POINT = PointAddress.of(1, 100);

  private final ChannelGroup acceptedChildChannels =
      new DefaultChannelGroup("accepted-children", GlobalEventExecutor.INSTANCE);

  /**
   * Captures the never-completed server-side interrogation future so tearDown can complete it,
   * avoiding an orphaned future after the test.
   */
  private final AtomicReference<@Nullable CompletableFuture<InterrogationResponse>> withheld =
      new AtomicReference<>();

  private @Nullable Iec60870Server server;
  private @Nullable Iec60870Client client;

  @AfterEach
  void tearDown() {
    CompletableFuture<InterrogationResponse> pending = withheld.getAndSet(null);
    if (pending != null) {
      pending.cancel(false);
    }
    if (client != null) {
      client.close();
    }
    if (server != null) {
      server.close();
    }
  }

  @Test
  void inFlightRequestFailsWithConnectionClosedOnAbruptDropExactlyOnceNoLeak() throws Exception {
    int port = reserveEphemeralPort();
    EventCollector events = startServerAndClient(port);
    Iec60870Client client = requireNonNull(this.client);

    // 1. Establish the session (connect() performs STARTDT). The session is now data-transfer
    // started, so the interrogation issued below is actually written on the wire as an I-frame and
    // registered as pending in the client.
    client.connect();
    assertTrue(client.isConnected(), "client should be connected after connect()");
    awaitWithin(
        "DataTransferStarted",
        () -> count(events, ClientEvent.DataTransferStarted.class) >= 1,
        Await.DEFAULT_TIMEOUT);
    assertTrue(
        acceptedChildChannels.stream().anyMatch(Channel::isActive),
        "the accepted server-side child channel should have been captured and be active");

    // 2. Issue an interrogation the server will NEVER confirm (its handler returns a future that is
    // never completed). The async API returns immediately; the returned stage is the in-flight,
    // not-yet-confirmed future we will assert on.
    CompletionStage<InterrogationResult> inFlight = client.interrogateAsync(STATION);
    CompletableFuture<InterrogationResult> inFlightFuture = inFlight.toCompletableFuture();

    // Confirm the request reached the server (its withheld handler was entered) and is genuinely
    // pending (not yet done) before we drop the connection.
    awaitWithin(
        "server received the interrogation (handler entered, future withheld)",
        () -> withheld.get() != null,
        Await.DEFAULT_TIMEOUT);
    assertFalse(inFlightFuture.isDone(), "the interrogation must still be pending before the drop");

    // 3. Abruptly drop the connection from the server side (SO_LINGER=0 -> hard RST). No graceful
    // STOPDT, no ACT_CON/ACT_TERM for the interrogation.
    acceptedChildChannels.close().awaitUninterruptibly();

    // 4a. The pending interrogation future must complete exceptionally with
    // ConnectionClosedException (failAllPending on the transport-loss path).
    ExecutionException failure =
        assertThrows(
            ExecutionException.class,
            () -> inFlightFuture.get(Await.DEFAULT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS),
            "the in-flight interrogation must fail when the connection is abruptly dropped");
    assertInstanceOf(
        ConnectionClosedException.class,
        failure.getCause(),
        "the pending request must fail with ConnectionClosedException on an abrupt drop");

    // 4b. Exactly one ConnectionClosed must be published for the drop (one-shot publish CAS). Wait
    // for it to arrive, then assert the count is exactly one.
    awaitWithin(
        "ConnectionClosed after the abrupt drop",
        () -> count(events, ClientEvent.ConnectionClosed.class) >= 1,
        Await.DEFAULT_TIMEOUT);

    // Close the client now, BEFORE the ~1s reconnect backoff could fire a second cycle, so this
    // test stays isolated to the single drop. close() is the protocol self-close path; any
    // ConnectionClosed it would publish is suppressed by the same one-shot CAS already tripped by
    // the drop, so the count stays at exactly one.
    client.close();
    assertEquals(
        1,
        count(events, ClientEvent.ConnectionClosed.class),
        "exactly one ConnectionClosed must be published for the single abrupt drop");

    // No-leak: the PARANOID leak detector reports any orphaned frame buffer as a LEAK: log line.
    // Nudge a GC so any unreleased buffer is collected and surfaces during the build.
    System.gc();
  }

  private EventCollector startServerAndClient(int port) {
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
        TcpIec104Server.builder()
            .bindAddress("127.0.0.1")
            .port(port)
            .addStation(station)
            // Answer STARTDT normally, but never confirm the interrogation: return a future that
            // is never completed (captured for tearDown). The interrogation stays pending on the
            // client.
            .handler(new WithholdingHandler(withheld))
            .serverBootstrapCustomizer(
                bootstrap -> {
                  // SO_LINGER=0 makes a later close() of an accepted child an abrupt hard RST.
                  bootstrap.childOption(ChannelOption.SO_LINGER, 0);
                  // Capture accepted children via handler() on the PARENT channel; childHandler
                  // (the IEC pipeline) is left untouched.
                  bootstrap.handler(
                      new ChannelInboundHandlerAdapter() {
                        @Override
                        public void channelRead(ChannelHandlerContext ctx, Object msg)
                            throws Exception {
                          if (msg instanceof Channel child) {
                            acceptedChildChannels.add(child);
                          }
                          super.channelRead(ctx, msg);
                        }
                      });
                })
            .build();
    server.start();

    client =
        TcpIec104Client.builder()
            .host("127.0.0.1")
            .port(port)
            .startDataTransferOnConnect(true)
            .build();

    EventCollector events = new EventCollector();
    client.events().subscribe(events);
    return events;
  }

  /**
   * A handler whose interrogation never completes, capturing the future so a test can release it.
   */
  private static final class WithholdingHandler implements ServerHandler {

    private final AtomicReference<@Nullable CompletableFuture<InterrogationResponse>> withheld;

    WithholdingHandler(
        AtomicReference<@Nullable CompletableFuture<InterrogationResponse>> withheld) {
      this.withheld = withheld;
    }

    @Override
    public CompletionStage<InterrogationResponse> onInterrogationAsync(
        ServerContext context, InterrogationRequest request) {
      CompletableFuture<InterrogationResponse> future = new CompletableFuture<>();
      withheld.set(future);
      return future; // never completed -> the client's interrogation stays pending.
    }
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
