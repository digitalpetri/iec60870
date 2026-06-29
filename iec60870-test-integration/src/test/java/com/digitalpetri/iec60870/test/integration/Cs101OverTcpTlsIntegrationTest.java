package com.digitalpetri.iec60870.test.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.digitalpetri.iec60870.ProtocolProfile;
import com.digitalpetri.iec60870.TlsOptions;
import com.digitalpetri.iec60870.address.CommonAddress;
import com.digitalpetri.iec60870.address.PointAddress;
import com.digitalpetri.iec60870.client.Iec60870Client;
import com.digitalpetri.iec60870.client.InterrogationResult;
import com.digitalpetri.iec60870.cs101.LinkSettings;
import com.digitalpetri.iec60870.point.PointCapability;
import com.digitalpetri.iec60870.point.PointType;
import com.digitalpetri.iec60870.point.PointValue;
import com.digitalpetri.iec60870.server.Iec60870Server;
import com.digitalpetri.iec60870.server.PointDefinition;
import com.digitalpetri.iec60870.server.Station;
import com.digitalpetri.iec60870.tcp.TcpIec101Client;
import com.digitalpetri.iec60870.tcp.TcpIec101Server;
import java.io.IOException;
import java.net.BindException;
import java.net.ServerSocket;
import java.util.List;
import javax.net.ssl.SSLContext;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** TLS loopback coverage for the balanced 101-over-TCP assembly. */
class Cs101OverTcpTlsIntegrationTest {

  private static final CommonAddress STATION = CommonAddress.of(1);
  private static final PointAddress MONITOR_POINT = PointAddress.of(1, 100);
  private static final ProtocolProfile PROFILE = new ProtocolProfile(1, 1, 2, 255);
  private static final LinkSettings LINK = LinkSettings.balanced().build();

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

    int port = startServerOnEphemeralPort(TlsOptions.builder(serverSsl).build());

    client =
        TcpIec101Client.builder()
            .host("127.0.0.1")
            .port(port)
            .profile(PROFILE)
            .linkSettings(LINK)
            .tls(TlsOptions.builder(clientSsl).build())
            .startDataTransferOnConnect(true)
            .build();

    client.connect();
    assertTrue(client.isConnected(), "client should be connected after TLS + link reset");

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

    int port = startServerOnEphemeralPort(TlsOptions.builder(serverSsl).build());

    client =
        TcpIec101Client.builder()
            .host("127.0.0.1")
            .port(port)
            .profile(PROFILE)
            .linkSettings(LINK)
            .tls(TlsOptions.builder(untrustingSsl).build())
            .startDataTransferOnConnect(true)
            .build();

    assertThrows(RuntimeException.class, () -> client.connect());
    assertFalse(client.isConnected(), "client must not be connected after a failed handshake");
  }

  @Test
  void clientAuthRequiredRejectsUntrustedClientCertificate() throws Exception {
    SelfSignedTls serverTls = SelfSignedTls.generate();
    SelfSignedTls trustedClient = SelfSignedTls.generate();
    SelfSignedTls untrustedClient = SelfSignedTls.generate();

    SSLContext serverSsl = serverTls.clientAuthServerContext(trustedClient.certificate());
    SSLContext untrustedClientSsl = serverTls.clientContextPresenting(untrustedClient);

    int port =
        startServerOnEphemeralPort(TlsOptions.builder(serverSsl).clientAuthRequired(true).build());

    client =
        TcpIec101Client.builder()
            .host("127.0.0.1")
            .port(port)
            .profile(PROFILE)
            .linkSettings(LINK)
            .tls(TlsOptions.builder(untrustedClientSsl).build())
            .startDataTransferOnConnect(true)
            .build();

    assertThrows(RuntimeException.class, () -> client.connect());
    assertFalse(client.isConnected(), "client must not be connected after a client-auth rejection");
  }

  private int startServerOnEphemeralPort(TlsOptions tlsOptions) throws IOException {
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
          TcpIec101Server.builder()
              .bindAddress("127.0.0.1")
              .port(port)
              .profile(PROFILE)
              .linkSettings(LINK)
              .tls(tlsOptions)
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
