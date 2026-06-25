package com.digitalpetri.iec60870.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.digitalpetri.iec60870.TlsOptions;
import com.digitalpetri.iec60870.address.CommonAddress;
import com.digitalpetri.iec60870.address.PointAddress;
import com.digitalpetri.iec60870.client.Iec60870Client;
import com.digitalpetri.iec60870.client.InterrogationResult;
import com.digitalpetri.iec60870.point.PointCapability;
import com.digitalpetri.iec60870.point.PointType;
import com.digitalpetri.iec60870.point.PointValue;
import com.digitalpetri.iec60870.server.Iec60870Server;
import com.digitalpetri.iec60870.server.PointDefinition;
import com.digitalpetri.iec60870.server.Station;
import com.digitalpetri.iec60870.transport.tcp.TcpIec104Client;
import com.digitalpetri.iec60870.transport.tcp.TcpIec104Server;
import java.io.IOException;
import java.net.BindException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import javax.net.ssl.SSLContext;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * TLS integration coverage over a loopback connection, using a certificate minted in-test by {@link
 * SelfSignedTls} (no checked-in keystores).
 *
 * <p>Two cases are asserted:
 *
 * <ul>
 *   <li><b>trust success</b>: a client that trusts the server's self-signed certificate completes
 *       {@code connect()} only after the TLS handshake, and a full station interrogation over the
 *       secured channel returns the configured points; and
 *   <li><b>trust failure</b>: a client that does not trust the server's certificate fails {@code
 *       connect()} and is never reported as connected;
 *   <li><b>client-auth (mTLS) failure</b>: a server requiring client authentication rejects a
 *       client that presents an untrusted client certificate, even though the client trusts the
 *       server, so the failure isolates to client auth; and
 *   <li><b>mid-handshake drop</b>: a peer that accepts the TCP connection then drops it before the
 *       TLS handshake completes makes {@code connect()} fail cleanly rather than hang.
 * </ul>
 *
 * <p>Servers are started on a fresh ephemeral port via {@link #startServerOnEphemeralPort}, which
 * retries on {@link java.net.BindException} rather than pre-reserving a port number (which would
 * leave a steal-the-port window between reservation and bind).
 */
class TlsIntegrationTest {

  private static final CommonAddress STATION = CommonAddress.of(1);
  private static final PointAddress MONITOR_POINT = PointAddress.of(1, 100);

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
  void trustingClientHandshakesAndInterrogatesOverTls() throws Exception {
    SelfSignedTls tls = SelfSignedTls.generate();
    SSLContext serverSsl = tls.serverContext();
    SSLContext clientSsl = tls.trustingClientContext();

    int port = startServerOnEphemeralPort(serverSsl);

    client =
        TcpIec104Client.builder()
            .host("127.0.0.1")
            .port(port)
            .tls(TlsOptions.builder(clientSsl).build())
            .startDataTransferOnConnect(true)
            .build();

    // connect() resolves only after the TLS handshake succeeds; on return the secured session is
    // up and data transfer has started.
    client.connect();
    assertTrue(client.isConnected(), "client should be connected after a successful TLS handshake");

    InterrogationResult interrogation = client.interrogate(STATION);
    assertTrue(interrogation.terminated(), "interrogation over TLS should end with ACT_TERM");

    List<PointAddress> reported =
        interrogation.pointValues().stream().map(InterrogationResult.PointEntry::address).toList();
    assertEquals(1, reported.size(), "the single reported point should come back over TLS");
    assertTrue(reported.contains(MONITOR_POINT), "the monitor point should be reported over TLS");
  }

  @Test
  void untrustingClientFailsToConnect() throws Exception {
    SelfSignedTls tls = SelfSignedTls.generate();
    SSLContext serverSsl = tls.serverContext();
    SSLContext untrustingSsl = tls.untrustingClientContext();

    int port = startServerOnEphemeralPort(serverSsl);

    client =
        TcpIec104Client.builder()
            .host("127.0.0.1")
            .port(port)
            .tls(TlsOptions.builder(untrustingSsl).build())
            .startDataTransferOnConnect(true)
            .build();

    // The handshake fails certificate validation, so connect() throws and the client is never
    // reported as connected.
    assertThrows(RuntimeException.class, () -> client.connect());
    assertFalse(client.isConnected(), "client must not be connected after a failed handshake");
  }

  @Test
  void clientAuthRequiredRejectsUntrustedClientCertificate() throws Exception {
    SelfSignedTls serverTls = SelfSignedTls.generate();
    SelfSignedTls trustedClient = SelfSignedTls.generate();
    SelfSignedTls untrustedClient = SelfSignedTls.generate();

    // Server requires client auth and accepts only trustedClient's certificate as a client anchor.
    SSLContext serverSsl = serverTls.clientAuthServerContext(trustedClient.certificate());
    // The client trusts the server (so server trust succeeds) but presents the untrusted identity,
    // isolating the failure to client authentication.
    SSLContext untrustedClientSsl = serverTls.clientContextPresenting(untrustedClient);

    int port =
        startServerOnEphemeralPort(TlsOptions.builder(serverSsl).clientAuthRequired(true).build());

    client =
        TcpIec104Client.builder()
            .host("127.0.0.1")
            .port(port)
            .tls(TlsOptions.builder(untrustedClientSsl).build())
            .startDataTransferOnConnect(true)
            .build();

    // The server rejects the untrusted client certificate, so the handshake fails and connect()
    // surfaces it; the client is never reported as connected.
    assertThrows(RuntimeException.class, () -> client.connect());
    assertFalse(client.isConnected(), "client must not be connected after a client-auth rejection");
  }

  @Test
  void midHandshakeDropFailsConnectCleanly() throws Exception {
    SelfSignedTls tls = SelfSignedTls.generate();
    SSLContext clientSsl = tls.trustingClientContext();

    // A raw (non-TLS) server socket accepts the TCP connection, then closes it before sending any
    // ServerHello, so the client's TLS handshake fails mid-flight instead of completing.
    try (ServerSocket peer = new ServerSocket()) {
      peer.bind(null);
      int port = peer.getLocalPort();

      Thread accepter =
          new Thread(
              () -> {
                try (Socket socket = peer.accept()) {
                  socket.getInputStream().read();
                } catch (IOException ignored) {
                  // The socket is being torn down; nothing to do.
                }
              },
              "mid-handshake-drop-accepter");
      accepter.setDaemon(true);
      accepter.start();

      client =
          TcpIec104Client.builder()
              .host("127.0.0.1")
              .port(port)
              .tls(TlsOptions.builder(clientSsl).build())
              .startDataTransferOnConnect(true)
              .build();

      // connect() must surface the dropped handshake as a failure and never hang; the test's own
      // bounded run guards against a wedge.
      assertThrows(RuntimeException.class, () -> client.connect());
      assertFalse(client.isConnected(), "client must not be connected after a dropped handshake");
    }
  }

  private int startServerOnEphemeralPort(SSLContext serverSsl) throws IOException {
    return startServerOnEphemeralPort(TlsOptions.builder(serverSsl).build());
  }

  /**
   * Builds and starts a TLS server on a fresh ephemeral port, retrying on {@link BindException}
   * with a new candidate port rather than pre-reserving a port number (which would leave a window
   * for another process to steal it between reservation and bind).
   *
   * @param serverTls the server TLS options.
   * @return the port the server actually claimed.
   * @throws IOException if no ephemeral port can be allocated as a candidate.
   */
  private int startServerOnEphemeralPort(TlsOptions serverTls) throws IOException {
    Station station =
        Station.builder(STATION)
            .point(
                PointDefinition.of(
                    MONITOR_POINT,
                    PointType.SINGLE_POINT,
                    PointValue.single(true),
                    PointCapability.REPORTED))
            .build();

    int maxAttempts = 10;
    RuntimeException lastFailure = null;
    for (int attempt = 0; attempt < maxAttempts; attempt++) {
      int port = ephemeralPortCandidate();
      Iec60870Server candidate =
          TcpIec104Server.builder()
              .bindAddress("127.0.0.1")
              .port(port)
              .tls(serverTls)
              .addStation(station)
              .build();
      try {
        candidate.start();
        server = candidate;
        return port;
      } catch (RuntimeException e) {
        candidate.close();
        if (hasCause(e, BindException.class)) {
          lastFailure = e;
          continue;
        }
        throw e;
      }
    }
    throw new IllegalStateException(
        "could not bind an ephemeral port after " + maxAttempts + " attempts", lastFailure);
  }

  private static int ephemeralPortCandidate() throws IOException {
    try (ServerSocket socket = new ServerSocket(0)) {
      return socket.getLocalPort();
    }
  }

  private static boolean hasCause(Throwable t, Class<? extends Throwable> type) {
    for (Throwable cause = t; cause != null; cause = cause.getCause()) {
      if (type.isInstance(cause)) {
        return true;
      }
      if (cause.getCause() == cause) {
        break;
      }
    }
    return false;
  }
}
