package com.digitalpetri.iec60870.examples;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.digitalpetri.iec60870.client.CommandResult;
import com.digitalpetri.iec60870.client.Iec60870Client;
import com.digitalpetri.iec60870.client.InterrogationResult;
import com.digitalpetri.iec60870.point.PointType;
import com.digitalpetri.iec60870.server.Iec60870Server;
import com.digitalpetri.iec60870.tcp.TcpIec104Client;
import java.io.IOException;
import java.net.ServerSocket;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Proves that the {@link ServerExample} and {@link ClientExample} logic interoperate on a loopback
 * connection.
 *
 * <p>The test starts the server example programmatically on an ephemeral port, connects a client
 * built the same way the client example builds it, and asserts that a general interrogation
 * succeeds and reports every reported monitor point type, that the commandable point accepts a
 * command, and that the clock synchronization is confirmed without throwing.
 */
class ExampleInteropTest {

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
  void interrogationAndCommandSucceedAgainstExampleServer() throws Exception {
    int port = freePort();
    server = ServerExample.start(port);

    Iec60870Client client =
        TcpIec104Client.builder()
            .host("127.0.0.1")
            .port(port)
            .startDataTransferOnConnect(true)
            .build();
    this.client = client;

    client.connect();
    assertTrue(client.isConnected(), "client should be connected after connect()");

    InterrogationResult snapshot = client.interrogate(ServerExample.STATION);
    assertTrue(snapshot.terminated(), "interrogation should end with an activation termination");
    assertFalse(
        snapshot.pointValues().isEmpty(), "interrogation should report the station's points");

    // The example server reports one point of every monitor type; a station interrogation should
    // surface each of them (the integrated-totals counter is delivered out-of-band, not here).
    Set<PointType> reportedTypes =
        snapshot.pointValues().stream()
            .map(entry -> entry.value().type())
            .collect(Collectors.toCollection(() -> EnumSet.noneOf(PointType.class)));
    assertTrue(
        reportedTypes.containsAll(
            EnumSet.of(
                PointType.SINGLE_POINT,
                PointType.DOUBLE_POINT,
                PointType.STEP_POSITION,
                PointType.BITSTRING32,
                PointType.NORMALIZED,
                PointType.SCALED,
                PointType.SHORT_FLOAT)),
        "interrogation should report every reported monitor point type; saw " + reportedTypes);

    CommandResult command = client.commands().single(ServerExample.SWITCH, true);
    assertTrue(command.positive(), "the commandable point should accept the command");

    // Clock sync must complete without throwing a NegativeConfirmationException.
    client.synchronizeClock(ServerExample.STATION, Instant.now());
  }

  private static int freePort() throws IOException {
    try (ServerSocket socket = new ServerSocket(0)) {
      return socket.getLocalPort();
    }
  }
}
