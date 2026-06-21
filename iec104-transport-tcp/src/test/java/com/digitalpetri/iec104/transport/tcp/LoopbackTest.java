package com.digitalpetri.iec104.transport.tcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.digitalpetri.iec104.address.CommonAddress;
import com.digitalpetri.iec104.address.PointAddress;
import com.digitalpetri.iec104.asdu.Cause;
import com.digitalpetri.iec104.client.CommandResult;
import com.digitalpetri.iec104.client.Iec104Client;
import com.digitalpetri.iec104.client.InterrogationResult;
import com.digitalpetri.iec104.point.PointCapability;
import com.digitalpetri.iec104.point.PointType;
import com.digitalpetri.iec104.point.PointValue;
import com.digitalpetri.iec104.server.CommandDecision;
import com.digitalpetri.iec104.server.CommandRequest;
import com.digitalpetri.iec104.server.Iec104Server;
import com.digitalpetri.iec104.server.PointDefinition;
import com.digitalpetri.iec104.server.ServerContext;
import com.digitalpetri.iec104.server.ServerHandler;
import com.digitalpetri.iec104.server.Station;
import java.io.IOException;
import java.net.ServerSocket;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Plaintext (non-TLS) loopback integration test exercising the TCP transport end to end.
 *
 * <p>A {@link TcpIec104Server} hosting a single station is bound on an ephemeral loopback port,
 * then a {@link TcpIec104Client} connects to it. The test asserts that:
 *
 * <ul>
 *   <li>{@link Iec104Client#connect()} succeeds and (with {@code startDataTransferOnConnect}) the
 *       {@code STARTDT} handshake completes, leaving the client connected;
 *   <li>a station (general) interrogation returns the station's reported points; and
 *   <li>a single command is confirmed positively by the server's command handler.
 * </ul>
 *
 * <p>Because {@link TcpIec104Server#builder()} returns the core {@link Iec104Server} interface
 * (which deliberately exposes no Netty types or bound-port accessor), the test reserves a free port
 * with a momentarily-open {@link ServerSocket} and binds the server to it. This is the standard
 * pattern for an ephemeral loopback port without leaking transport internals into the public API.
 */
class LoopbackTest {

  private static final CommonAddress STATION = CommonAddress.of(1);
  private static final PointAddress MONITOR_POINT = PointAddress.of(1, 100);
  private static final PointAddress COMMAND_POINT = PointAddress.of(1, 200);

  /** A handler that accepts single commands on the commandable point, updating its image. */
  private static final ServerHandler COMMAND_ACCEPTING_HANDLER =
      new ServerHandler() {
        @Override
        public CommandDecision onCommand(ServerContext context, CommandRequest request) {
          if (request.target().equals(COMMAND_POINT)) {
            return CommandDecision.acceptAndUpdate(PointValue.single(true));
          }
          return CommandDecision.reject(Cause.UNKNOWN_INFORMATION_OBJECT_ADDRESS);
        }
      };

  @Test
  void connectInterrogateAndCommandOverPlaintext() throws Exception {
    int port = reserveEphemeralPort();

    PointDefinition<Boolean> monitorDefinition =
        PointDefinition.of(
            MONITOR_POINT,
            PointType.SINGLE_POINT,
            PointValue.single(true),
            PointCapability.REPORTED);

    PointDefinition<Boolean> commandDefinition =
        PointDefinition.of(
            COMMAND_POINT,
            PointType.SINGLE_POINT,
            PointValue.single(false),
            PointCapability.REPORTED,
            PointCapability.COMMANDABLE);

    var station =
        Station.builder(STATION).point(monitorDefinition).point(commandDefinition).build();

    try (Iec104Server server =
            TcpIec104Server.builder()
                .bindAddress("127.0.0.1")
                .port(port)
                .addStation(station)
                .handler(COMMAND_ACCEPTING_HANDLER)
                .build();
        Iec104Client client =
            TcpIec104Client.builder()
                .host("127.0.0.1")
                .port(port)
                .startDataTransferOnConnect(true)
                .build()) {
      server.start();

      // connect() also performs the STARTDT handshake (startDataTransferOnConnect defaults true).
      client.connect();
      assertTrue(client.isConnected(), "client should be connected after connect()");

      // General (station) interrogation must return the station's reported points.
      InterrogationResult result = client.interrogate(STATION);
      assertTrue(result.terminated(), "interrogation should end with ACT_TERM");

      List<InterrogationResult.PointEntry> points = result.pointValues();
      assertEquals(
          2, points.size(), "both reported points should be returned by general interrogation");
      assertTrue(
          points.stream().anyMatch(p -> p.address().equals(MONITOR_POINT)),
          "monitor point should be reported");
      assertTrue(
          points.stream().anyMatch(p -> p.address().equals(COMMAND_POINT)),
          "command point (also reported) should be reported");

      // A single command on the commandable point must be accepted.
      CommandResult commandResult = client.commands().single(COMMAND_POINT, true);
      assertTrue(commandResult.positive(), "single command should be confirmed positively");
      assertEquals(COMMAND_POINT, commandResult.target());
    }
  }

  /**
   * Reserves a free TCP port on the loopback interface by opening and immediately closing a server
   * socket bound to port {@code 0}.
   *
   * @return a port number that was free at reservation time.
   * @throws IOException if a free port cannot be obtained.
   */
  private static int reserveEphemeralPort() throws IOException {
    try (ServerSocket socket = new ServerSocket(0)) {
      socket.setReuseAddress(true);
      int port = socket.getLocalPort();
      assertFalse(port <= 0, "reserved port must be positive");
      return port;
    }
  }
}
