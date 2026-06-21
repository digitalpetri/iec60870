package com.digitalpetri.iec104.transport.tcp;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.digitalpetri.iec104.TlsOptions;
import com.digitalpetri.iec104.transport.ServerTransportConnection;
import com.digitalpetri.iec104.transport.TransportListener;
import java.io.IOException;
import java.net.ServerSocket;
import java.security.cert.Certificate;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.SSLContext;
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
 */
class TlsHandshakeTest {

  private static final Logger LOGGER = LoggerFactory.getLogger(TlsHandshakeTest.class);

  private static final long AWAIT_SECONDS = 15;

  @Test
  void trustingClientCompletesHandshakeAndServerSeesConnection() throws Exception {
    int port = reserveEphemeralPort();

    SSLContext serverSsl = TestTls.serverContext();
    SSLContext clientSsl = TestTls.trustingClientContext();

    TlsOptions serverTls = TlsOptions.builder(serverSsl).build();
    TlsOptions clientTls = TlsOptions.builder(clientSsl).build();

    NettyServerTransport server =
        new NettyServerTransport(
            NettyServerTransportConfig.builder("127.0.0.1", port).tlsOptions(serverTls).build());

    AtomicReference<ServerTransportConnection> accepted = new AtomicReference<>();
    server.setConnectionHandler(
        connection -> {
          connection.setListener(NOOP_LISTENER);
          accepted.set(connection);
        });

    NettyClientTransport client =
        new NettyClientTransport(
            NettyClientTransportConfig.builder("127.0.0.1", port).tlsOptions(clientTls).build());
    client.setListener(NOOP_LISTENER);

    try {
      await(server.bind());

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
    int port = reserveEphemeralPort();

    SSLContext serverSsl = TestTls.serverContext();
    SSLContext clientSsl = TestTls.untrustingClientContext();

    TlsOptions serverTls = TlsOptions.builder(serverSsl).build();
    TlsOptions clientTls = TlsOptions.builder(clientSsl).build();

    NettyServerTransport server =
        new NettyServerTransport(
            NettyServerTransportConfig.builder("127.0.0.1", port).tlsOptions(serverTls).build());
    server.setConnectionHandler(connection -> connection.setListener(NOOP_LISTENER));

    NettyClientTransport client =
        new NettyClientTransport(
            NettyClientTransportConfig.builder("127.0.0.1", port).tlsOptions(clientTls).build());
    client.setListener(NOOP_LISTENER);

    try {
      await(server.bind());

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

  private static final TransportListener NOOP_LISTENER =
      new TransportListener() {
        @Override
        public void onApdu(com.digitalpetri.iec104.apci.Apdu apdu) {}

        @Override
        public void onConnectionLost(Throwable cause) {}
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

  private static ServerTransportConnection awaitAccepted(
      AtomicReference<ServerTransportConnection> ref) throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(AWAIT_SECONDS);
    while (ref.get() == null && System.nanoTime() < deadline) {
      Thread.sleep(20);
    }
    return ref.get();
  }

  private static int reserveEphemeralPort() throws IOException {
    try (ServerSocket socket = new ServerSocket(0)) {
      socket.setReuseAddress(true);
      return socket.getLocalPort();
    }
  }
}
