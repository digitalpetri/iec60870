package com.digitalpetri.iec60870.transport.tcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.digitalpetri.iec60870.ConnectionClosedException;
import com.digitalpetri.iec60870.diagnostics.ConnectionDiagnostics;
import com.digitalpetri.iec60870.diagnostics.LoggingContext;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** Tests the connect and rejection events the Netty transports report. */
class TransportDiagnosticsTest {

  private static final long TIMEOUT_SECONDS = 5;

  private final List<String> log = Collections.synchronizedList(new ArrayList<>());
  private final List<Throwable> failures = Collections.synchronizedList(new ArrayList<>());
  private final List<SocketAddress[]> connected = Collections.synchronizedList(new ArrayList<>());

  private final ConnectionDiagnostics diagnostics =
      new ConnectionDiagnostics() {
        @Override
        public void onConnectAttempt(SocketAddress remote) {
          log.add("attempt " + LoggingContext.format(remote));
        }

        @Override
        public void onConnected(SocketAddress local, SocketAddress remote) {
          connected.add(new SocketAddress[] {local, remote});
          log.add("connected");
        }

        @Override
        public void onConnectFailed(SocketAddress remote, Throwable cause) {
          failures.add(cause);
          log.add("failed");
        }
      };

  @Test
  void successfulAttemptReportsBothAddresses() throws Exception {
    try (ServerSocket server = new ServerSocket(0)) {
      NettyClientTransport transport = client(server.getLocalPort());
      try {
        transport.connect().toCompletableFuture().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        server.accept().close();

        assertEquals(List.of("attempt 127.0.0.1:" + server.getLocalPort(), "connected"), log);
        SocketAddress[] addresses = connected.get(0);
        assertEquals(server.getLocalPort(), ((InetSocketAddress) addresses[1]).getPort());
        assertTrue(((InetSocketAddress) addresses[0]).getPort() > 0);
      } finally {
        transport.close();
      }
    }
  }

  @Test
  void refusedAttemptReportsItsCause() throws Exception {
    int port;
    try (ServerSocket reserved = new ServerSocket(0)) {
      port = reserved.getLocalPort();
    }
    NettyClientTransport transport = client(port);
    try {
      assertThrows(
          ExecutionException.class,
          () -> transport.connect().toCompletableFuture().get(TIMEOUT_SECONDS, TimeUnit.SECONDS));

      assertEquals(List.of("attempt 127.0.0.1:" + port, "failed"), log);
      assertInstanceOf(ConnectException.class, failures.get(0));
    } finally {
      transport.close();
    }
  }

  @Test
  void serverReportsConnectionsRejectedAtTheLimit() throws Exception {
    int port;
    try (ServerSocket reserved = new ServerSocket(0)) {
      port = reserved.getLocalPort();
    }
    List<SocketAddress> rejected = Collections.synchronizedList(new ArrayList<>());
    var rejectedCause = new CompletableFuture<Throwable>();
    NettyServerTransport server =
        new NettyServerTransport(
            NettyServerTransportConfig.builder("127.0.0.1", port)
                .maxConnections(1)
                .diagnostics(
                    remote ->
                        new ConnectionDiagnostics() {
                          @Override
                          public void onConnectFailed(SocketAddress address, Throwable cause) {
                            rejected.add(address);
                            rejectedCause.complete(cause);
                          }
                        })
                .build());
    server.setConnectionHandler(connection -> {});
    server.bind().toCompletableFuture().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    try (Socket first = new Socket("127.0.0.1", port);
        Socket second = new Socket("127.0.0.1", port)) {
      Throwable cause = rejectedCause.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

      assertInstanceOf(ConnectionClosedException.class, cause);
      assertEquals(second.getLocalPort(), ((InetSocketAddress) rejected.get(0)).getPort());
      InputStream in = second.getInputStream();
      assertEquals(-1, in.read(), "the rejected connection is closed");
      assertTrue(first.isConnected());
    } finally {
      server.unbind().toCompletableFuture().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }
  }

  private NettyClientTransport client(int port) {
    return new NettyClientTransport(
        NettyClientTransportConfig.builder("127.0.0.1", port).diagnostics(diagnostics).build());
  }
}
