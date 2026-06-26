package com.digitalpetri.iec60870.test.integration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.digitalpetri.iec60870.address.CommonAddress;
import com.digitalpetri.iec60870.address.PointAddress;
import com.digitalpetri.iec60870.point.PointCapability;
import com.digitalpetri.iec60870.point.PointType;
import com.digitalpetri.iec60870.point.PointValue;
import com.digitalpetri.iec60870.server.Iec60870Server;
import com.digitalpetri.iec60870.server.PointDefinition;
import com.digitalpetri.iec60870.server.Station;
import com.digitalpetri.iec60870.tcp.TcpIec104Server;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.util.concurrent.GlobalEventExecutor;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Real-Netty integration test for the connection cap enforced by {@link TcpIec104Server}: with a
 * cap of one, a second concurrent connection is admitted by TCP but immediately closed by the
 * server (the over-cap child is closed before any IEC handshake), and a slot freed by closing an
 * admitted connection is reused by a subsequent connection.
 *
 * <p>The cap is enforced in {@code NettyServerTransport.initChildChannel} by an atomic
 * increment-then-rollback: a child that pushes the count over {@code maxConnections} is rolled back
 * and {@code channel.close()}d before the connection handler sees it; an admitted child registers a
 * {@code closeFuture} listener that decrements the count, freeing a slot exactly when it closes.
 *
 * <p>Plain {@link Socket}s are used rather than full IEC clients: only real TCP connections are
 * needed to exercise the cap, and the over-cap close happens before any IEC handshake, so the
 * cleanest, most deterministic signal is the over-cap socket observing server-side EOF ({@code
 * read() == -1}) shortly after connecting, while the admitted socket stays open.
 *
 * <p>NON-FLAKY discipline: {@code maxConnections=1} makes the admitted-vs-rejected math unambiguous
 * (admission is atomic, so even simultaneous accepts yield exactly one admitted). Assertions are on
 * EOF / staying-open OUTCOMES via bounded polling, never on durations. There is a benign window
 * where the over-cap socket briefly looks connected before the server closes it, so the test
 * asserts on the eventual EOF, not on connect failing.
 */
class ServerSlotExhaustionIntegrationTest {

  private static final CommonAddress STATION = CommonAddress.of(1);
  private static final PointAddress SINGLE_POINT = PointAddress.of(1, 100);

  /** Bounded deadline for a server-side close (EOF) to be observed on an over-cap socket. */
  private static final Duration EOF_TIMEOUT = Duration.ofSeconds(10);

  /**
   * Window over which an admitted socket is confirmed to stay open. It is short enough to finish
   * well before the server's idle {@code t3}/{@code t1} timers (defaults 20s/15s) could close an
   * unresponsive raw socket, so staying-open is attributable to admission, not timer slack.
   */
  private static final Duration STAYS_OPEN_WINDOW = Duration.ofMillis(750);

  private final List<Socket> openedSockets = new ArrayList<>();

  /**
   * Every server-side child channel the acceptor admits to its pipeline (the IEC childHandler still
   * runs). Used to await the admitted child's {@code closeFuture}, which fires the slot-freeing
   * decrement, so the slot-reuse step does not race the server noticing the client's close.
   */
  private final ChannelGroup acceptedChildChannels =
      new DefaultChannelGroup("accepted-children", GlobalEventExecutor.INSTANCE);

  private @Nullable Iec60870Server server;

  @AfterEach
  void tearDown() {
    for (Socket socket : openedSockets) {
      closeQuietly(socket);
    }
    if (server != null) {
      server.close();
    }
  }

  @Test
  void overCapConnectionIsRejectedAndAFreedSlotIsReused() throws Exception {
    int port = startServer(/* maxConnections= */ 1);

    // 1. Open the first connection: it fills the single slot and must stay open. Capture the
    // admitted server-side child channel so step 3 can await it actually closing.
    Socket admitted = connect(port);
    assertStaysOpen(admitted, "the first (admitted) connection should stay open under cap=1");
    Channel admittedChild = awaitFirstCapturedChild();

    // 2. Open a second connection concurrently: it exceeds the cap, so the server admits the TCP
    // connection then immediately closes it. The over-cap socket observes server-side EOF.
    Socket overCap = connect(port);
    assertEofWithin(
        overCap,
        "the over-cap connection must be closed by the server (cap=1, EOF expected)",
        EOF_TIMEOUT);
    // The originally admitted connection is unaffected by the rejected one.
    assertTrue(admitted.isConnected() && !admitted.isClosed(), "the admitted socket should remain");

    // 3. Free the slot by closing the admitted connection. Await the admitted child's closeFuture
    // on the SERVER side: that listener fires the slot-freeing decrement, so awaiting it removes
    // any race between the client's close and the server reclaiming the slot. Only THEN open a new
    // connection, which must now be admitted (stays open) — proving the slot was reused.
    admitted.close();
    admittedChild.closeFuture().awaitUninterruptibly(EOF_TIMEOUT.toMillis());
    assertFalse(
        admittedChild.isActive(), "the admitted server-side child should be closed (slot freed)");

    Socket reused = connect(port);
    assertStaysOpen(
        reused,
        "a connection opened after an admitted one closed should reuse the freed slot and stay open");
  }

  /** Awaits and returns the first server-side child channel the acceptor captured. */
  private Channel awaitFirstCapturedChild() {
    String what = "the admitted connection's server-side child channel";
    long deadline = System.nanoTime() + EOF_TIMEOUT.toNanos();
    while (System.nanoTime() < deadline) {
      Channel child = acceptedChildChannels.stream().findFirst().orElse(null);
      if (child != null) {
        return child;
      }
      try {
        //noinspection BusyWait
        Thread.sleep(20);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new AssertionError("interrupted while awaiting " + what, e);
      }
    }
    throw new AssertionError("timed out awaiting " + what);
  }

  private int startServer(int maxConnections) throws IOException {
    int port = reserveEphemeralPort();
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
            .maxConnections(maxConnections)
            .serverBootstrapCustomizer(
                bootstrap ->
                    // Capture EVERY accepted child via handler() on the PARENT channel (the
                    // childHandler IEC pipeline is left untouched). The first captured child is the
                    // admitted one; the over-cap child is also captured but the server closes it.
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
                        }))
            .build();
    server.start();
    return port;
  }

  private Socket connect(int port) throws IOException {
    Socket socket = new Socket();
    socket.connect(new InetSocketAddress("127.0.0.1", port), (int) EOF_TIMEOUT.toMillis());
    openedSockets.add(socket);
    return socket;
  }

  /**
   * Asserts the socket reaches end-of-stream (server-side close) within the deadline. Uses a short
   * read timeout so the blocking {@link InputStream#read()} polls toward the deadline.
   */
  private static void assertEofWithin(Socket socket, String message, Duration timeout)
      throws IOException {
    socket.setSoTimeout(100);
    InputStream in = socket.getInputStream();
    long deadline = System.nanoTime() + timeout.toNanos();
    while (System.nanoTime() < deadline) {
      try {
        if (in.read() == -1) {
          return; // EOF: the server closed the connection.
        }
        // Any byte read would be unexpected for an over-cap close, but tolerate and keep polling.
      } catch (java.net.SocketTimeoutException timeoutException) {
        // No data yet and not closed; keep polling toward the deadline.
      } catch (IOException closed) {
        // A reset/closed stream also counts as the server having dropped the connection.
        return;
      }
    }
    throw new AssertionError(
        "timed out after " + timeout.toMillis() + " ms: " + message + " (no EOF observed)");
  }

  /**
   * Asserts the socket stays open (no server-side EOF) for the whole window. A non-blocking-ish
   * read with a short timeout detects an unexpected close; a timeout means still-open, which is the
   * expected outcome for an admitted connection.
   */
  private static void assertStaysOpen(Socket socket, String message) throws IOException {
    socket.setSoTimeout(50);
    InputStream in = socket.getInputStream();
    long deadline = System.nanoTime() + STAYS_OPEN_WINDOW.toNanos();
    while (System.nanoTime() < deadline) {
      try {
        if (in.read() == -1) {
          throw new AssertionError(message + " but the server closed it (unexpected EOF)");
        }
        // Read a byte (e.g. a server-initiated frame); the connection is still open, keep going.
      } catch (java.net.SocketTimeoutException timeoutException) {
        // Expected: no data and no close. Keep polling.
      }
    }
    assertFalse(socket.isClosed(), message + " but the local socket was closed");
    assertTrue(socket.isConnected(), message);
  }

  private static void closeQuietly(Socket socket) {
    try {
      socket.close();
    } catch (IOException ignored) {
      // Best-effort cleanup.
    }
  }

  private static int reserveEphemeralPort() throws IOException {
    try (ServerSocket socket = new ServerSocket(0)) {
      socket.setReuseAddress(true);
      return socket.getLocalPort();
    }
  }
}
