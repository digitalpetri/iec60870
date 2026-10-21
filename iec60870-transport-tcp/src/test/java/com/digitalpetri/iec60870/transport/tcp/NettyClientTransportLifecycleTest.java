package com.digitalpetri.iec60870.transport.tcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.digitalpetri.iec60870.ConnectionClosedException;
import com.digitalpetri.iec60870.transport.TransportListener;
import io.netty.buffer.ByteBuf;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/** Tests that the client transport dials once per request and that only close() is terminal. */
class NettyClientTransportLifecycleTest {

  private static final long TIMEOUT_SECONDS = 5;

  @Test
  void neverRedialsAfterThePeerCloses() throws Exception {
    try (ServerSocket server = new ServerSocket(0)) {
      NettyClientTransport transport = transport(server.getLocalPort(), null);
      RecordingListener listener = new RecordingListener();
      transport.setListener(listener);
      try {
        transport.connect().toCompletableFuture().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        server.accept().close();

        Throwable cause = listener.lost.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertNull(cause, "a peer FIN is reported as a loss without a cause");
        assertFalse(transport.isConnected());

        server.setSoTimeout(2_000);
        assertThrows(SocketTimeoutException.class, server::accept, "the transport redialed");
      } finally {
        transport.close();
      }
    }
  }

  @Test
  void disconnectIsNotTerminalAndIsNotReportedAsALoss() throws Exception {
    try (ServerSocket server = new ServerSocket(0)) {
      NettyClientTransport transport = transport(server.getLocalPort(), null);
      RecordingListener listener = new RecordingListener();
      transport.setListener(listener);
      try {
        transport.connect().toCompletableFuture().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        Socket first = server.accept();
        transport.disconnect().toCompletableFuture().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertEquals(-1, first.getInputStream().read(), "the connection was closed");

        // The private event loop group survives a disconnect, so the next connect dials again.
        transport.connect().toCompletableFuture().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        server.accept().close();
        listener.lost.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertEquals(1, listener.losses.get(), "only the peer close was reported");
      } finally {
        transport.close();
      }
    }
  }

  @Test
  void connectAfterCloseFailsFast() throws Exception {
    try (ServerSocket server = new ServerSocket(0)) {
      NettyClientTransport transport = transport(server.getLocalPort(), null);
      transport.connect().toCompletableFuture().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
      server.accept();

      transport.close();

      var error =
          assertThrows(
              ExecutionException.class,
              () -> transport.connect().toCompletableFuture().get(1, TimeUnit.SECONDS));
      assertInstanceOf(ConnectionClosedException.class, error.getCause());
      assertFalse(transport.isConnected());
    }
  }

  @Test
  void closeLeavesACallerSuppliedEventLoopGroupRunning() throws Exception {
    EventLoopGroup group = new NioEventLoopGroup(1);
    try (ServerSocket server = new ServerSocket(0)) {
      NettyClientTransport transport = transport(server.getLocalPort(), group);
      transport.connect().toCompletableFuture().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
      server.accept();

      transport.close();

      assertFalse(group.isShuttingDown());
    } finally {
      group.shutdownGracefully(0, 0, TimeUnit.SECONDS);
    }
  }

  @Test
  void failedAttemptCanBeRetried() throws Exception {
    int port;
    try (ServerSocket reserved = new ServerSocket(0)) {
      port = reserved.getLocalPort();
    }
    NettyClientTransport transport = transport(port, null);
    try {
      var error =
          assertThrows(
              ExecutionException.class,
              () ->
                  transport.connect().toCompletableFuture().get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
      assertInstanceOf(IOException.class, error.getCause());

      try (ServerSocket server = new ServerSocket(port)) {
        transport.connect().toCompletableFuture().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        server.accept();
        assertTrue(transport.isConnected());
      }
    } finally {
      transport.close();
    }
  }

  @Test
  void disconnectAbortsAnAttemptInProgress() throws Exception {
    // A non-routable address leaves the TCP connect pending until the connect timeout.
    NettyClientTransport transport =
        new NettyClientTransport(
            NettyClientTransportConfig.builder("10.255.255.1", 2404)
                .connectTimeout(Duration.ofSeconds(30))
                .build());
    try {
      CompletableFuture<Void> attempt = transport.connect().toCompletableFuture();
      transport.disconnect();

      var error =
          assertThrows(
              ExecutionException.class, () -> attempt.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
      assertInstanceOf(ConnectionClosedException.class, error.getCause());
    } finally {
      transport.close();
    }
  }

  private static NettyClientTransport transport(int port, @Nullable EventLoopGroup group) {
    return new NettyClientTransport(
        NettyClientTransportConfig.builder("127.0.0.1", port)
            .connectTimeout(Duration.ofSeconds(2))
            .sharedEventLoopGroup(group)
            .build());
  }

  private static final class RecordingListener implements TransportListener {

    private final CompletableFuture<@Nullable Throwable> lost = new CompletableFuture<>();
    private final AtomicInteger losses = new AtomicInteger();

    @Override
    public void onFrame(ByteBuf frame) {}

    @Override
    public void onConnectionLost(@Nullable Throwable cause) {
      losses.incrementAndGet();
      lost.complete(cause);
    }
  }
}
