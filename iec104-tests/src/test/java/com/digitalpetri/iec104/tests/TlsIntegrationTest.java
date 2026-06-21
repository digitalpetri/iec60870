package com.digitalpetri.iec104.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.digitalpetri.iec104.TlsOptions;
import com.digitalpetri.iec104.address.CommonAddress;
import com.digitalpetri.iec104.address.PointAddress;
import com.digitalpetri.iec104.client.Iec104Client;
import com.digitalpetri.iec104.client.InterrogationResult;
import com.digitalpetri.iec104.point.PointCapability;
import com.digitalpetri.iec104.point.PointType;
import com.digitalpetri.iec104.point.PointValue;
import com.digitalpetri.iec104.server.Iec104Server;
import com.digitalpetri.iec104.server.PointDefinition;
import com.digitalpetri.iec104.server.Station;
import com.digitalpetri.iec104.transport.tcp.TcpIec104Client;
import com.digitalpetri.iec104.transport.tcp.TcpIec104Server;
import java.io.IOException;
import java.net.ServerSocket;
import java.util.List;
import javax.net.ssl.SSLContext;
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
 *       connect()} and is never reported as connected.
 * </ul>
 */
class TlsIntegrationTest {

  private static final CommonAddress STATION = CommonAddress.of(1);
  private static final PointAddress MONITOR_POINT = PointAddress.of(1, 100);

  private Iec104Server server;
  private Iec104Client client;

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

    int port = reserveEphemeralPort();
    startServer(port, serverSsl);

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

    int port = reserveEphemeralPort();
    startServer(port, serverSsl);

    client =
        TcpIec104Client.builder()
            .host("127.0.0.1")
            .port(port)
            .tls(TlsOptions.builder(untrustingSsl).build())
            .startDataTransferOnConnect(true)
            .build();

    // The handshake fails certificate validation, so connect() throws and the client never reports
    // itself connected.
    assertThrows(RuntimeException.class, () -> client.connect());
    assertFalse(client.isConnected(), "client must not be connected after a failed handshake");
  }

  private void startServer(int port, SSLContext serverSsl) {
    Station station =
        Station.builder(STATION)
            .point(
                PointDefinition.of(
                    MONITOR_POINT,
                    PointType.SINGLE_POINT,
                    PointValue.single(true),
                    PointCapability.REPORTED))
            .build();

    server =
        TcpIec104Server.builder()
            .bindAddress("127.0.0.1")
            .port(port)
            .tls(TlsOptions.builder(serverSsl).build())
            .addStation(station)
            .build();
    server.start();
  }

  private static int reserveEphemeralPort() throws IOException {
    try (ServerSocket socket = new ServerSocket(0)) {
      socket.setReuseAddress(true);
      return socket.getLocalPort();
    }
  }
}
