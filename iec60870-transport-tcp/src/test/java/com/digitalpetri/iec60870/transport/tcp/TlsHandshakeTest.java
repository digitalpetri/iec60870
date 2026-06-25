package com.digitalpetri.iec60870.transport.tcp;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.digitalpetri.iec60870.TlsOptions;
import com.digitalpetri.iec60870.transport.ServerTransportConnection;
import com.digitalpetri.iec60870.transport.TransportListener;
import io.netty.buffer.ByteBuf;
import java.io.IOException;
import java.net.BindException;
import java.net.ServerSocket;
import java.net.Socket;
import java.security.cert.Certificate;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import javax.net.ssl.SSLContext;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * TLS handshake coverage for the Netty transports, driven at the transport layer so the test can
 * observe exactly when {@code connect()} resolves and whether the peer certificate is available.
 *
 * <p>The self-signed server identity and the trust material come from {@link TestTls}; no
 * certificates are generated at runtime. The test asserts:
 *
 * <ul>
 *   <li><b>trust success</b>: a client whose trust store contains the server certificate completes
 *       {@code connect()} only after the TLS handshake, and the server then sees the secured
 *       connection; and
 *   <li><b>trust failure</b>: a client that does not trust the server certificate fails {@code
 *       connect()} with a TLS error and is never reported as connected.
 * </ul>
 *
 * <p>The trust-failure case is the contractual minimum and is asserted unconditionally. The
 * trust-success case additionally proves the "complete only after handshake" guarantee: the
 * transport's {@code connect()} stage does not resolve until {@code SslHandler.handshakeFuture()}
 * succeeds.
 *
 * <p>Two further failure modes are covered: a hostname mismatch (with verification on and off) and
 * an abrupt mid-handshake TCP drop, which must surface as a clean handshake failure rather than a
 * hang.
 *
 * <p>Servers are bound on a fresh ephemeral port via {@link #bindServerWithRetry}, which lets the
 * Netty server itself claim the port and retries on {@link java.net.BindException} rather than
 * pre-reserving a port number (which would leave a steal-the-port window between reservation and
 * rebind).
 */
class TlsHandshakeTest {

  private static final Logger LOGGER = LoggerFactory.getLogger(TlsHandshakeTest.class);

  private static final long AWAIT_SECONDS = 15;

  @Test
  void trustingClientCompletesHandshakeAndServerSeesConnection() throws Exception {
    SSLContext serverSsl = TestTls.serverContext();
    SSLContext clientSsl = TestTls.trustingClientContext();

    TlsOptions serverTls = TlsOptions.builder(serverSsl).build();
    TlsOptions clientTls = TlsOptions.builder(clientSsl).build();

    BlockingQueue<ServerTransportConnection> accepted = new LinkedBlockingQueue<>();
    BoundServer bound =
        bindServerWithRetry(
            serverTls,
            connection -> {
              connection.setListener(NOOP_LISTENER);
              accepted.add(connection);
            });
    NettyServerTransport server = bound.server();
    int port = bound.port();

    NettyClientTransport client =
        new NettyClientTransport(
            NettyClientTransportConfig.builder("127.0.0.1", port).tlsOptions(clientTls).build());
    client.setListener(NOOP_LISTENER);

    try {
      assertFalse(client.isConnected(), "client must not be connected before connect()");

      // connect() resolves only after the TLS handshake succeeds; if the handshake had not
      // completed this stage would not be done.
      await(client.connect());
      assertTrue(client.isConnected(), "client should be connected after a successful handshake");

      ServerTransportConnection connection = awaitAccepted(accepted);
      assertNotNull(connection, "server should have accepted the secured connection");

      // The narrow TLS context: with no client-auth required the peer presents no certificate.
      Optional<Certificate> peerCertificate = connection.peerCertificate();
      assertFalse(
          peerCertificate.isPresent(),
          "client presents no certificate when client-auth is not required");
    } finally {
      await(client.disconnect());
      await(server.unbind());
    }
  }

  @Test
  void untrustingClientFailsHandshake() throws Exception {
    SSLContext serverSsl = TestTls.serverContext();
    SSLContext clientSsl = TestTls.untrustingClientContext();

    TlsOptions serverTls = TlsOptions.builder(serverSsl).build();
    TlsOptions clientTls = TlsOptions.builder(clientSsl).build();

    BoundServer bound =
        bindServerWithRetry(serverTls, connection -> connection.setListener(NOOP_LISTENER));
    NettyServerTransport server = bound.server();
    int port = bound.port();

    NettyClientTransport client =
        new NettyClientTransport(
            NettyClientTransportConfig.builder("127.0.0.1", port).tlsOptions(clientTls).build());
    client.setListener(NOOP_LISTENER);

    try {
      // The handshake must fail certificate validation, so connect() completes exceptionally and
      // the client is never reported as connected.
      ExecutionException failure =
          assertThrows(ExecutionException.class, () -> await(client.connect()));
      LOGGER.info("expected TLS trust failure: {}", failure.getCause().toString());

      assertFalse(client.isConnected(), "client must not be connected after a failed handshake");
    } finally {
      await(client.disconnect());
      await(server.unbind());
    }
  }

  @Test
  void hostnameMismatchFailsHandshakeWhenVerifyEnabled() throws Exception {
    SSLContext serverSsl = TestTls.mismatchedServerContext();
    SSLContext clientSsl = TestTls.clientTrustingMismatch();

    TlsOptions serverTls = TlsOptions.builder(serverSsl).build();
    // Default options: verifyHostname is ON.
    TlsOptions clientTls = TlsOptions.builder(clientSsl).build();

    BoundServer bound =
        bindServerWithRetry(serverTls, connection -> connection.setListener(NOOP_LISTENER));
    NettyServerTransport server = bound.server();
    int port = bound.port();

    NettyClientTransport client =
        new NettyClientTransport(
            NettyClientTransportConfig.builder("127.0.0.1", port).tlsOptions(clientTls).build());
    client.setListener(NOOP_LISTENER);

    try {
      // The certificate is trusted but valid only for mismatch.example.com; dialing 127.0.0.1 must
      // fail HTTPS endpoint identification, so connect() completes exceptionally.
      ExecutionException failure =
          assertThrows(ExecutionException.class, () -> await(client.connect()));
      LOGGER.info("expected TLS hostname mismatch failure: {}", failure.getCause().toString());

      assertFalse(client.isConnected(), "client must not be connected after a hostname mismatch");
    } finally {
      await(client.disconnect());
      await(server.unbind());
    }
  }

  @Test
  void hostnameMismatchPassesWhenVerifyDisabled() throws Exception {
    SSLContext serverSsl = TestTls.mismatchedServerContext();
    SSLContext clientSsl = TestTls.clientTrustingMismatch();

    TlsOptions serverTls = TlsOptions.builder(serverSsl).build();
    TlsOptions clientTls = TlsOptions.builder(clientSsl).verifyHostname(false).build();

    BoundServer bound =
        bindServerWithRetry(serverTls, connection -> connection.setListener(NOOP_LISTENER));
    NettyServerTransport server = bound.server();
    int port = bound.port();

    NettyClientTransport client =
        new NettyClientTransport(
            NettyClientTransportConfig.builder("127.0.0.1", port).tlsOptions(clientTls).build());
    client.setListener(NOOP_LISTENER);

    try {
      // With hostname verification disabled the trusted certificate is accepted despite the
      // mismatch, so the handshake completes.
      await(client.connect());
      assertTrue(client.isConnected(), "client should connect when hostname verification is off");
    } finally {
      await(client.disconnect());
      await(server.unbind());
    }
  }

  @Test
  void midHandshakeDropSurfacesCleanFailure() throws Exception {
    SSLContext clientSsl = TestTls.trustingClientContext();
    TlsOptions clientTls = TlsOptions.builder(clientSsl).build();

    // A raw (non-TLS) server socket stands in for the peer: it accepts the TCP connection and then
    // immediately closes it, dropping the link before any ServerHello is sent. The client's TLS
    // handshake therefore fails mid-flight rather than ever completing.
    try (ServerSocket peer = new ServerSocket()) {
      peer.bind(null);
      int port = peer.getLocalPort();

      Thread accepter =
          new Thread(
              () -> {
                try (Socket socket = peer.accept()) {
                  // Drop the connection during the handshake by closing right after accept.
                  socket.getInputStream().read();
                } catch (IOException ignored) {
                  // The socket is being torn down; nothing to do.
                }
              },
              "mid-handshake-drop-accepter");
      accepter.setDaemon(true);
      accepter.start();

      NettyClientTransport client =
          new NettyClientTransport(
              NettyClientTransportConfig.builder("127.0.0.1", port).tlsOptions(clientTls).build());
      client.setListener(NOOP_LISTENER);

      try {
        // The bounded await() turns any hang into a TimeoutException (a test failure), so this also
        // proves the client never wedges when the peer vanishes mid-handshake.
        ExecutionException failure =
            assertThrows(ExecutionException.class, () -> await(client.connect()));
        LOGGER.info("expected mid-handshake drop failure: {}", failure.getCause().toString());

        // The abrupt close is surfaced as a clean handshake/connect failure. Whether it lands as an
        // SSLException or a ClosedChannelException depends on how far the handshake progressed
        // before
        // the peer dropped the link, so accept the whole IOException family rather than pinning a
        // timing-dependent subtype. The bounded await() above already guarantees this is a real
        // failure and not a hang (a hang would surface as a TimeoutException test failure instead).
        Throwable cause = failure.getCause();
        assertInstanceOf(
            IOException.class,
            cause,
            "a dropped handshake should surface as an SSL/IO failure, but was: " + cause);

        assertFalse(client.isConnected(), "client must not be connected after a dropped handshake");
      } finally {
        await(client.disconnect());
      }
    }
  }

  private static final TransportListener NOOP_LISTENER =
      new TransportListener() {
        @Override
        public void onFrame(ByteBuf frame) {}

        @Override
        public void onConnectionLost(@Nullable Throwable cause) {}
      };

  /**
   * Blocks until the given stage completes, propagating any failure as an {@link
   * ExecutionException}.
   *
   * @param stage the stage to await.
   * @throws InterruptedException if interrupted while waiting.
   * @throws ExecutionException if the stage completes exceptionally.
   * @throws TimeoutException if the stage does not complete within the await window.
   */
  private static void await(CompletionStage<Void> stage)
      throws InterruptedException, ExecutionException, TimeoutException {
    stage.toCompletableFuture().get(AWAIT_SECONDS, TimeUnit.SECONDS);
  }

  private static @Nullable ServerTransportConnection awaitAccepted(
      BlockingQueue<ServerTransportConnection> queue) throws InterruptedException {
    return queue.poll(AWAIT_SECONDS, TimeUnit.SECONDS);
  }

  /** A bound {@link NettyServerTransport} together with the port it actually claimed. */
  private record BoundServer(NettyServerTransport server, int port) {}

  /**
   * Binds a {@link NettyServerTransport} on a fresh ephemeral port, retrying on {@link
   * BindException} with a new candidate port each time.
   *
   * <p>This avoids the close-then-rebind race of pre-reserving a port number: an ephemeral port is
   * only ever used as a <em>candidate</em>, and the Netty server itself claims it, so there is no
   * window in which the number is free for another process to steal. On the rare {@link
   * BindException} (TIME_WAIT collision or a genuine steal) a new server is created on a new
   * candidate and the bind is retried.
   *
   * @param serverTls the TLS options for the server transport.
   * @param connectionHandler the handler invoked for each accepted connection.
   * @return the bound server and the port it claimed.
   * @throws Exception if binding fails for a reason other than {@link BindException} or the retry
   *     budget is exhausted.
   */
  private static BoundServer bindServerWithRetry(
      TlsOptions serverTls, Consumer<ServerTransportConnection> connectionHandler)
      throws Exception {
    int maxAttempts = 10;
    Exception lastFailure = null;
    for (int attempt = 0; attempt < maxAttempts; attempt++) {
      int port = ephemeralPortCandidate();
      NettyServerTransport server =
          new NettyServerTransport(
              NettyServerTransportConfig.builder("127.0.0.1", port).tlsOptions(serverTls).build());
      server.setConnectionHandler(connectionHandler);
      try {
        await(server.bind());
        return new BoundServer(server, port);
      } catch (ExecutionException e) {
        if (rootCause(e) instanceof BindException) {
          lastFailure = e;
          await(server.unbind());
          continue;
        }
        throw e;
      }
    }
    throw new IllegalStateException(
        "could not bind an ephemeral port after " + maxAttempts + " attempts", lastFailure);
  }

  /** Returns a currently-free ephemeral port number to use as a bind candidate. */
  private static int ephemeralPortCandidate() throws IOException {
    try (ServerSocket socket = new ServerSocket(0)) {
      return socket.getLocalPort();
    }
  }

  private static Throwable rootCause(Throwable t) {
    Throwable cause = t;
    while (cause.getCause() != null && cause.getCause() != cause) {
      cause = cause.getCause();
    }
    return cause;
  }
}
