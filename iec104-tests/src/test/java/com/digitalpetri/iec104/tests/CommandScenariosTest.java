package com.digitalpetri.iec104.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.digitalpetri.iec104.NegativeConfirmationException;
import com.digitalpetri.iec104.address.CommonAddress;
import com.digitalpetri.iec104.address.PointAddress;
import com.digitalpetri.iec104.asdu.Cause;
import com.digitalpetri.iec104.client.Command;
import com.digitalpetri.iec104.client.CommandMode;
import com.digitalpetri.iec104.client.CommandResult;
import com.digitalpetri.iec104.client.Iec104Client;
import com.digitalpetri.iec104.point.PointCapability;
import com.digitalpetri.iec104.point.PointType;
import com.digitalpetri.iec104.point.PointValue;
import com.digitalpetri.iec104.server.CommandDecision;
import com.digitalpetri.iec104.server.CommandRequest;
import com.digitalpetri.iec104.server.Iec104Server;
import com.digitalpetri.iec104.server.InterrogationRequest;
import com.digitalpetri.iec104.server.InterrogationResponse;
import com.digitalpetri.iec104.server.PointDefinition;
import com.digitalpetri.iec104.server.ServerContext;
import com.digitalpetri.iec104.server.ServerHandler;
import com.digitalpetri.iec104.server.Station;
import com.digitalpetri.iec104.transport.tcp.TcpIec104Client;
import com.digitalpetri.iec104.transport.tcp.TcpIec104Server;
import java.io.IOException;
import java.net.ServerSocket;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Focused command-procedure scenarios over a loopback connection: direct-execute acceptance,
 * select-before-operate, a protocol-rejected command, and a negative interrogation confirmation.
 *
 * <p>The server hosts one commandable point that its handler accepts and one that it rejects, plus
 * a second station whose interrogation the handler declines. A control command that the station
 * rejects surfaces as a non-positive {@link CommandResult} (the confirming ASDU's P/N bit is set),
 * whereas a request-style operation such as interrogation surfaces a rejection as a {@link
 * NegativeConfirmationException}. Both rejection paths are asserted here, along with the causes the
 * server returned.
 */
class CommandScenariosTest {

  private static final CommonAddress STATION = CommonAddress.of(1);
  private static final CommonAddress REJECTING_STATION = CommonAddress.of(2);

  private static final PointAddress ACCEPTED_COMMAND = PointAddress.of(1, 200);
  private static final PointAddress REJECTED_COMMAND = PointAddress.of(1, 201);

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
  void directExecutePositive() throws Exception {
    startServerAndClient();
    client.connect();

    CommandResult result =
        client.commands().send(Command.single(ACCEPTED_COMMAND, true), CommandMode.directExecute());

    assertTrue(result.positive(), "direct-execute command should be confirmed positively");
    assertEquals(ACCEPTED_COMMAND, result.target());
    assertEquals(Cause.ACTIVATION_CONFIRMATION, result.cause());
    assertTrue(result.confirmation().isPresent(), "a confirming ASDU should be present");
  }

  @Test
  void selectBeforeOperatePositive() throws Exception {
    startServerAndClient();
    client.connect();

    CommandResult result =
        client
            .commands()
            .send(Command.single(ACCEPTED_COMMAND, true), CommandMode.selectBeforeOperate());

    assertTrue(result.positive(), "select-before-operate command should be confirmed positively");
    assertEquals(ACCEPTED_COMMAND, result.target());
    assertEquals(Cause.ACTIVATION_CONFIRMATION, result.cause());
  }

  @Test
  void rejectedCommandYieldsNonPositiveResult() throws Exception {
    startServerAndClient();
    client.connect();

    CommandResult result =
        client.commands().send(Command.single(REJECTED_COMMAND, true), CommandMode.directExecute());

    assertFalse(result.positive(), "a rejected command must yield a non-positive result");
    assertEquals(REJECTED_COMMAND, result.target());
    assertEquals(
        Cause.UNKNOWN_INFORMATION_OBJECT_ADDRESS,
        result.cause(),
        "the negative confirmation should carry the handler's reject cause");
  }

  @Test
  void rejectedSelectBeforeOperateStopsAtSelectPhase() throws Exception {
    startServerAndClient();
    client.connect();

    // The select activation is rejected, so the service never sends the execute activation; the
    // result reflects that negative select confirmation.
    CommandResult result =
        client
            .commands()
            .send(Command.single(REJECTED_COMMAND, true), CommandMode.selectBeforeOperate());

    assertFalse(result.positive(), "a rejected select activation must yield a non-positive result");
    assertEquals(Cause.UNKNOWN_INFORMATION_OBJECT_ADDRESS, result.cause());
  }

  @Test
  void negativeInterrogationConfirmationThrows() throws Exception {
    startServerAndClient();
    client.connect();

    // The handler rejects interrogation of the second station, so interrogate() surfaces the
    // negative confirmation as an exception rather than an empty result.
    NegativeConfirmationException failure =
        assertThrows(
            NegativeConfirmationException.class, () -> client.interrogate(REJECTING_STATION));

    assertEquals(
        Cause.UNKNOWN_COMMON_ADDRESS,
        failure.cause(),
        "the rejection cause should match what the handler returned");
  }

  private void startServerAndClient() throws IOException {
    int port = reserveEphemeralPort();

    Station station =
        Station.builder(STATION)
            .point(
                PointDefinition.of(
                    ACCEPTED_COMMAND,
                    PointType.SINGLE_POINT,
                    PointValue.single(false),
                    PointCapability.COMMANDABLE))
            .point(
                PointDefinition.of(
                    REJECTED_COMMAND,
                    PointType.SINGLE_POINT,
                    PointValue.single(false),
                    PointCapability.COMMANDABLE))
            .build();

    Station rejectingStation =
        Station.builder(REJECTING_STATION)
            .point(
                PointDefinition.of(
                    PointAddress.of(2, 300),
                    PointType.SINGLE_POINT,
                    PointValue.single(false),
                    PointCapability.REPORTED))
            .build();

    server =
        TcpIec104Server.builder()
            .bindAddress("127.0.0.1")
            .port(port)
            .addStation(station)
            .addStation(rejectingStation)
            .handler(new ScenarioHandler())
            .build();
    server.start();

    client =
        TcpIec104Client.builder()
            .host("127.0.0.1")
            .port(port)
            .startDataTransferOnConnect(true)
            .build();
  }

  /**
   * A handler that accepts commands to {@link #ACCEPTED_COMMAND}, rejects all other commands with
   * {@link Cause#UNKNOWN_INFORMATION_OBJECT_ADDRESS}, and declines interrogation of the {@link
   * #REJECTING_STATION} with {@link Cause#UNKNOWN_COMMON_ADDRESS}.
   */
  private static final class ScenarioHandler implements ServerHandler {

    @Override
    public CommandDecision onCommand(ServerContext context, CommandRequest request) {
      if (request.target().equals(ACCEPTED_COMMAND)) {
        return CommandDecision.acceptAndUpdate(PointValue.single(true));
      }
      return CommandDecision.reject(Cause.UNKNOWN_INFORMATION_OBJECT_ADDRESS);
    }

    @Override
    public InterrogationResponse onInterrogation(
        ServerContext context, InterrogationRequest request) {
      if (request.station().equals(REJECTING_STATION)) {
        return InterrogationResponse.reject(Cause.UNKNOWN_COMMON_ADDRESS);
      }
      return context.defaultInterrogation(request);
    }
  }

  private static int reserveEphemeralPort() throws IOException {
    try (ServerSocket socket = new ServerSocket(0)) {
      socket.setReuseAddress(true);
      return socket.getLocalPort();
    }
  }
}
