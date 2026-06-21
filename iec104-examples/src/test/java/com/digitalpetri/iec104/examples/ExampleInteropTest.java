package com.digitalpetri.iec104.examples;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.digitalpetri.iec104.client.CommandResult;
import com.digitalpetri.iec104.client.Iec104Client;
import com.digitalpetri.iec104.client.InterrogationResult;
import com.digitalpetri.iec104.server.Iec104Server;
import com.digitalpetri.iec104.transport.tcp.TcpIec104Client;
import java.io.IOException;
import java.net.ServerSocket;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Proves that the {@link ServerExample} and {@link ClientExample} logic interoperate on a loopback
 * connection.
 *
 * <p>The test starts the server example programmatically on an ephemeral port, connects a client
 * built the same way the client example builds it, and asserts that a general interrogation
 * succeeds and reports the example's points, that the commandable point accepts a command, and that
 * the clock synchronization is confirmed without throwing.
 */
class ExampleInteropTest {

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
  void interrogationAndCommandSucceedAgainstExampleServer() throws Exception {
    int port = freePort();
    server = ServerExample.start(port);

    client =
        TcpIec104Client.builder()
            .host("127.0.0.1")
            .port(port)
            .startDataTransferOnConnect(true)
            .build();

    client.connect();
    assertTrue(client.isConnected(), "client should be connected after connect()");

    InterrogationResult snapshot = client.interrogate(ServerExample.STATION);
    assertTrue(snapshot.terminated(), "interrogation should end with an activation termination");
    assertFalse(
        snapshot.pointValues().isEmpty(), "interrogation should report the station's points");

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
