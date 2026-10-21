package com.digitalpetri.iec60870.test.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.digitalpetri.iec60870.address.CommonAddress;
import com.digitalpetri.iec60870.address.PointAddress;
import com.digitalpetri.iec60870.asdu.Asdu;
import com.digitalpetri.iec60870.asdu.Cause;
import com.digitalpetri.iec60870.asdu.object.SingleCommand;
import com.digitalpetri.iec60870.client.Command;
import com.digitalpetri.iec60870.client.CommandCompletion;
import com.digitalpetri.iec60870.client.CommandMode;
import com.digitalpetri.iec60870.client.CommandOptions;
import com.digitalpetri.iec60870.client.CommandOutcome;
import com.digitalpetri.iec60870.client.CommandResult;
import com.digitalpetri.iec60870.client.Iec60870Client;
import com.digitalpetri.iec60870.client.SelectedCommand;
import com.digitalpetri.iec60870.point.PointCapability;
import com.digitalpetri.iec60870.point.PointType;
import com.digitalpetri.iec60870.point.PointValue;
import com.digitalpetri.iec60870.server.CommandDecision;
import com.digitalpetri.iec60870.server.CommandRequest;
import com.digitalpetri.iec60870.server.Iec60870Server;
import com.digitalpetri.iec60870.server.PointDefinition;
import com.digitalpetri.iec60870.server.ServerContext;
import com.digitalpetri.iec60870.server.ServerHandler;
import com.digitalpetri.iec60870.server.Station;
import com.digitalpetri.iec60870.tcp.TcpIec104Client;
import com.digitalpetri.iec60870.tcp.TcpIec104Server;
import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * ACT_TERM completion and the select, execute, and cancel handle over a loopback {@link
 * TcpIec104Server}.
 *
 * <p>The built-in server terminates every accepted execute. The raw hook stands in for outstations
 * that terminate negatively, never terminate, or answer a deactivation with DEACT_CON.
 */
class CommandTerminationIntegrationTest {

  private static final CommonAddress STATION = CommonAddress.of(1);
  private static final PointAddress TERMINATING = PointAddress.of(1, 200);
  private static final PointAddress NEGATIVE_TERMINATION = PointAddress.of(1, 210);
  private static final PointAddress NO_TERMINATION = PointAddress.of(1, 220);
  private static final PointAddress CANCELLABLE = PointAddress.of(1, 230);

  private static final CommandOptions AWAIT_TERMINATION =
      CommandOptions.defaults().withCompletion(CommandCompletion.TERMINATION);

  private final List<CommandRequest> commands = new CopyOnWriteArrayList<>();
  private @Nullable Iec60870Server server;
  private @Nullable Iec60870Client client;

  @BeforeEach
  void setUp() throws IOException {
    int port = reserveEphemeralPort();
    Station.Builder station = Station.builder(STATION);
    for (PointAddress point : List.of(TERMINATING, CANCELLABLE)) {
      station.point(
          PointDefinition.of(
              point,
              PointType.SINGLE_POINT,
              PointValue.single(false),
              PointCapability.COMMANDABLE));
    }
    server =
        TcpIec104Server.builder()
            .bindAddress("127.0.0.1")
            .port(port)
            .addStation(station.build())
            .handler(new Handler())
            .build();
    server.start();
    client =
        TcpIec104Client.builder()
            .host("127.0.0.1")
            .port(port)
            .commandTerminationTimeout(Duration.ofMillis(300))
            .build();
    client.connect();
  }

  @AfterEach
  void tearDown() {
    if (client != null) {
      client.close();
    }
    if (server != null) {
      server.close();
    }
  }

  private Iec60870Client client() {
    return Objects.requireNonNull(client);
  }

  @Test
  void directCommandTerminates() {
    CommandResult result =
        client()
            .commands()
            .send(
                Command.single(TERMINATING, true), CommandMode.directExecute(), AWAIT_TERMINATION);

    assertEquals(CommandOutcome.TERMINATED, result.outcome());
    assertEquals(Cause.ACTIVATION_TERMINATION, result.termination().orElseThrow().cause());
    assertEquals(Cause.ACTIVATION_CONFIRMATION, result.confirmation().orElseThrow().cause());
  }

  @Test
  void negativeTerminationIsNotPositive() {
    CommandResult result =
        client()
            .commands()
            .send(
                Command.single(NEGATIVE_TERMINATION, true),
                CommandMode.directExecute(),
                AWAIT_TERMINATION);

    assertEquals(CommandOutcome.TERMINATED_NEGATIVE, result.outcome());
    assertFalse(result.positive());
  }

  @Test
  void missingTerminationTimesOutAsAnOutcome() {
    CommandResult result =
        client()
            .commands()
            .send(
                Command.single(NO_TERMINATION, true),
                CommandMode.directExecute(),
                AWAIT_TERMINATION);

    assertEquals(CommandOutcome.TERMINATION_MISSING, result.outcome());
    assertTrue(result.confirmation().isPresent());
  }

  @Test
  void selectThenExecute() {
    SelectedCommand selected =
        client().commands().select(Command.single(TERMINATING, true), AWAIT_TERMINATION);
    assertEquals(1, commands.size());
    assertEquals(com.digitalpetri.iec60870.server.CommandMode.SELECT, commands.get(0).mode());

    CommandResult result = selected.execute();

    assertEquals(CommandOutcome.TERMINATED, result.outcome());
    assertEquals(2, commands.size());
    assertEquals(com.digitalpetri.iec60870.server.CommandMode.EXECUTE, commands.get(1).mode());
  }

  @Test
  void selectThenCancel() {
    SelectedCommand selected = client().commands().select(Command.single(CANCELLABLE, true));

    CommandResult result = selected.cancel();

    assertEquals(CommandOutcome.CONFIRMED, result.outcome());
    assertEquals(Cause.DEACTIVATION_CONFIRMATION, result.cause());
    assertEquals(1, commands.size(), "the deactivation never reached onCommand");
  }

  @Test
  void builtInServerRejectsDeactivation() {
    SelectedCommand selected = client().commands().select(Command.single(TERMINATING, true));

    CommandResult result = selected.cancel();

    assertEquals(CommandOutcome.REJECTED, result.outcome());
    assertEquals(Cause.UNKNOWN_CAUSE, result.cause());
  }

  /**
   * Accepts commands through {@code onCommand}, and claims in the raw hook the executes that need a
   * non-standard termination and the deactivations of {@link #CANCELLABLE}.
   */
  private final class Handler implements ServerHandler {

    @Override
    public CommandDecision onCommand(ServerContext context, CommandRequest request) {
      commands.add(request);
      return CommandDecision.accept();
    }

    @Override
    public boolean onRawAsdu(ServerContext context, Asdu asdu) {
      if (asdu.objects().size() != 1 || !(asdu.objects().get(0) instanceof SingleCommand command)) {
        return false;
      }
      int ioa = command.address().value().intValue();
      if (asdu.cause() == Cause.DEACTIVATION
          && ioa == CANCELLABLE.objectAddress().value().intValue()) {
        context.send(echo(asdu, Cause.DEACTIVATION_CONFIRMATION, false));
        return true;
      }
      if (asdu.cause() != Cause.ACTIVATION || command.qualifier().select()) {
        return false;
      }
      if (ioa == NEGATIVE_TERMINATION.objectAddress().value().intValue()) {
        context.send(echo(asdu, Cause.ACTIVATION_CONFIRMATION, false));
        context.send(echo(asdu, Cause.ACTIVATION_TERMINATION, true));
        return true;
      }
      if (ioa == NO_TERMINATION.objectAddress().value().intValue()) {
        context.send(echo(asdu, Cause.ACTIVATION_CONFIRMATION, false));
        return true;
      }
      return false;
    }
  }

  private static Asdu echo(Asdu request, Cause cause, boolean negative) {
    return new Asdu(
        request.type(),
        false,
        cause,
        negative,
        false,
        request.originatorAddress(),
        request.commonAddress(),
        request.objects());
  }

  private static int reserveEphemeralPort() throws IOException {
    try (ServerSocket socket = new ServerSocket(0)) {
      socket.setReuseAddress(true);
      return socket.getLocalPort();
    }
  }
}
