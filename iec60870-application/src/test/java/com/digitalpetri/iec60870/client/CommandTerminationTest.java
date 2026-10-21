package com.digitalpetri.iec60870.client;

import static com.digitalpetri.iec60870.client.CommandHarness.assertFailure;
import static com.digitalpetri.iec60870.client.CommandHarness.join;
import static com.digitalpetri.iec60870.client.CommandHarness.reply;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.digitalpetri.iec60870.ConnectionClosedException;
import com.digitalpetri.iec60870.ProtocolTimeoutException;
import com.digitalpetri.iec60870.RequestInProgressException;
import com.digitalpetri.iec60870.address.CommonAddress;
import com.digitalpetri.iec60870.address.InformationObjectAddress;
import com.digitalpetri.iec60870.address.PointAddress;
import com.digitalpetri.iec60870.asdu.Asdu;
import com.digitalpetri.iec60870.asdu.Cause;
import com.digitalpetri.iec60870.asdu.InformationObject;
import com.digitalpetri.iec60870.asdu.element.DoubleCommandState;
import com.digitalpetri.iec60870.asdu.element.NormalizedValue;
import com.digitalpetri.iec60870.asdu.element.QualifierOfCommand;
import com.digitalpetri.iec60870.asdu.element.StepCommandState;
import com.digitalpetri.iec60870.asdu.object.SingleCommand;
import com.digitalpetri.iec60870.asdu.object.SingleCommandWithCp56Time;
import com.digitalpetri.iec60870.asdu.time.Cp56Time2a;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

/** The command completion policy: ACT_CON or ACT_TERM, the termination timeout, and rejections. */
class CommandTerminationTest {

  private static final PointAddress POINT =
      new PointAddress(CommonAddress.of(1), InformationObjectAddress.of(100));
  private static final PointAddress OTHER =
      new PointAddress(CommonAddress.of(1), InformationObjectAddress.of(101));
  private static final Instant TIME = Instant.parse("2024-06-01T12:00:00Z");
  private static final Duration TERMINATION_TIMEOUT = Duration.ofMillis(200);
  private static final long COMMAND_TIMEOUT_MILLIS = CommandHarness.COMMAND_TIMEOUT.toMillis();
  private static final TimeUnit MILLIS = TimeUnit.MILLISECONDS;

  private static final CommandOptions AWAIT_TERMINATION =
      CommandOptions.defaults().withCompletion(CommandCompletion.TERMINATION);

  private static CommandHarness terminating() {
    return new CommandHarness(
        b ->
            b.commandCompletion(CommandCompletion.TERMINATION)
                .setpointCompletion(CommandCompletion.TERMINATION)
                .commandTerminationTimeout(TERMINATION_TIMEOUT));
  }

  @Test
  void defaultCompletesOnActConAndPublishesTheLaterActTerm() {
    try (var h = new CommandHarness()) {
      CompletableFuture<CommandResult> result =
          h.send(Command.single(POINT, true), CommandMode.directExecute());
      Asdu execute = h.lastSent();
      h.reply(Cause.ACTIVATION_CONFIRMATION, false);

      CommandResult r = join(result);
      assertEquals(CommandOutcome.CONFIRMED, r.outcome());
      assertTrue(r.positive());
      assertEquals(Cause.ACTIVATION_CONFIRMATION, r.cause());
      assertTrue(r.termination().isEmpty());
      assertEquals(0, h.client.pendingRequestCount());

      int before = h.events.size();
      h.deliver(reply(execute, Cause.ACTIVATION_TERMINATION, false));
      assertEquals(1, h.events.size() - before, "only AsduReceived is published");
      assertTrue(h.events.get(h.events.size() - 1) instanceof ClientEvent.AsduReceived);
    }
  }

  @Test
  void negativeActTermUnderConfirmationIsPublishedAfterThePositiveResult() {
    try (var h = new CommandHarness()) {
      CompletableFuture<CommandResult> result =
          h.send(Command.single(POINT, true), CommandMode.directExecute());
      Asdu execute = h.lastSent();
      h.reply(Cause.ACTIVATION_CONFIRMATION, false);
      h.deliver(reply(execute, Cause.ACTIVATION_TERMINATION, true));

      assertTrue(join(result).positive());
      assertEquals(1, h.negativeConfirmationEvents());
    }
  }

  @Test
  void terminationCompletesOnMatchingActTerm() {
    try (var h = terminating()) {
      CompletableFuture<CommandResult> result =
          h.send(Command.single(POINT, true), CommandMode.directExecute());
      Asdu execute = h.lastSent();
      h.reply(Cause.ACTIVATION_CONFIRMATION, false);
      assertFalse(result.isDone(), "a positive ACT_CON waits for ACT_TERM");
      assertEquals(1, h.client.pendingRequestCount());

      Asdu termination = reply(execute, Cause.ACTIVATION_TERMINATION, false);
      h.deliver(termination);

      CommandResult r = join(result);
      assertEquals(CommandOutcome.TERMINATED, r.outcome());
      assertTrue(r.positive());
      assertEquals(Cause.ACTIVATION_TERMINATION, r.cause());
      assertEquals(Optional.of(termination), r.termination());
      assertEquals(Cause.ACTIVATION_CONFIRMATION, r.confirmation().orElseThrow().cause());
      assertEquals(0, h.client.pendingRequestCount());
      assertEquals(1, h.sent().size());
    }
  }

  @Test
  void actTermBeforeActConIsIgnored() {
    try (var h = terminating()) {
      CompletableFuture<CommandResult> result =
          h.send(Command.single(POINT, true), CommandMode.directExecute());
      Asdu execute = h.lastSent();
      h.deliver(reply(execute, Cause.ACTIVATION_TERMINATION, false));
      h.reply(Cause.ACTIVATION_CONFIRMATION, false);
      assertFalse(result.isDone(), "the early ACT_TERM did not count");
      h.deliver(reply(execute, Cause.ACTIVATION_TERMINATION, false));
      assertEquals(CommandOutcome.TERMINATED, join(result).outcome());
    }
  }

  @TestFactory
  Stream<DynamicTest> distinguishableActTermIsIgnored() {
    return Stream.of("value", "phase", "address", "time")
        .map(mismatch -> DynamicTest.dynamicTest(mismatch, () -> checkMismatchedActTerm(mismatch)));
  }

  private void checkMismatchedActTerm(String mismatch) {
    try (var h = terminating()) {
      Command command = new Command.SingleCommandRequest(POINT, true, 2, Optional.of(TIME));
      CompletableFuture<CommandResult> result = h.send(command, CommandMode.directExecute());
      Asdu execute = h.lastSent();
      h.reply(Cause.ACTIVATION_CONFIRMATION, false);

      var sent = (SingleCommandWithCp56Time) execute.objects().get(0);
      InformationObject wrong =
          new SingleCommandWithCp56Time(
              mismatch.equals("address") ? OTHER.objectAddress() : sent.address(),
              !mismatch.equals("value"),
              new QualifierOfCommand(2, mismatch.equals("phase")),
              mismatch.equals("time")
                  ? Cp56Time2a.from(TIME.plusSeconds(1), ZoneOffset.UTC)
                  : sent.time());
      h.deliver(reply(execute, Cause.ACTIVATION_TERMINATION, false, List.of(wrong)));
      assertFalse(result.isDone(), mismatch + " mismatch must not terminate the command");

      h.deliver(reply(execute, Cause.ACTIVATION_TERMINATION, false));
      assertEquals(CommandOutcome.TERMINATED, join(result).outcome());
    }
  }

  @Test
  void negativeActTermYieldsTerminatedNegativeWithoutEvent() {
    try (var h = terminating()) {
      CompletableFuture<CommandResult> result =
          h.send(Command.single(POINT, true), CommandMode.directExecute());
      Asdu execute = h.lastSent();
      h.reply(Cause.ACTIVATION_CONFIRMATION, false);
      h.deliver(reply(execute, Cause.ACTIVATION_TERMINATION, true));

      CommandResult r = join(result);
      assertEquals(CommandOutcome.TERMINATED_NEGATIVE, r.outcome());
      assertFalse(r.positive());
      assertEquals(0, h.negativeConfirmationEvents());
    }
  }

  @Test
  void missingActTermYieldsTerminationMissingAfterTheTerminationTimeout() {
    try (var h = terminating()) {
      CompletableFuture<CommandResult> result =
          h.send(Command.single(POINT, true), CommandMode.directExecute());
      Asdu execute = h.lastSent();
      h.clock.advance(40, MILLIS);
      h.reply(Cause.ACTIVATION_CONFIRMATION, false);

      h.clock.advance(COMMAND_TIMEOUT_MILLIS, MILLIS);
      assertFalse(result.isDone(), "the termination timer replaces the command timeout");
      h.clock.advance(TERMINATION_TIMEOUT.toMillis() - COMMAND_TIMEOUT_MILLIS - 1, MILLIS);
      assertFalse(result.isDone(), "measured from the positive ACT_CON");
      h.clock.advance(1, MILLIS);

      CommandResult r = join(result);
      assertEquals(CommandOutcome.TERMINATION_MISSING, r.outcome());
      assertFalse(r.positive());
      assertEquals(Cause.ACTIVATION_CONFIRMATION, r.cause());
      assertTrue(r.confirmation().isPresent());
      assertEquals(0, h.client.pendingRequestCount());

      // A late ACT_TERM no longer belongs to a request and is published as before.
      h.deliver(reply(execute, Cause.ACTIVATION_TERMINATION, true));
      assertEquals(1, h.negativeConfirmationEvents());
    }
  }

  @Test
  void connectionLossAfterActConYieldsTerminationMissing() {
    try (var h = terminating()) {
      CompletableFuture<CommandResult> result =
          h.send(Command.single(POINT, true), CommandMode.directExecute());
      h.reply(Cause.ACTIVATION_CONFIRMATION, false);
      h.session().fireConnectionLost(null);

      assertEquals(CommandOutcome.TERMINATION_MISSING, join(result).outcome());
      assertEquals(0, h.client.pendingRequestCount());
    }
  }

  @Test
  void disconnectAfterActConYieldsTerminationMissing() {
    try (var h = terminating()) {
      CompletableFuture<CommandResult> result =
          h.send(Command.single(POINT, true), CommandMode.directExecute());
      h.reply(Cause.ACTIVATION_CONFIRMATION, false);
      h.client.disconnect();

      assertEquals(CommandOutcome.TERMINATION_MISSING, join(result).outcome());
      assertEquals(0, h.client.pendingRequestCount());
    }
  }

  @Test
  void connectionLossOrTimeoutBeforeActConStillThrows() {
    try (var h = terminating()) {
      CompletableFuture<CommandResult> lost =
          h.send(Command.single(POINT, true), CommandMode.directExecute());
      h.session().fireConnectionLost(null);
      assertFailure(lost, ConnectionClosedException.class);

      h.client.connect();
      CompletableFuture<CommandResult> timedOut =
          h.send(Command.single(POINT, true), CommandMode.directExecute());
      h.clock.advance(COMMAND_TIMEOUT_MILLIS, MILLIS);
      assertFailure(timedOut, ProtocolTimeoutException.class);
      assertEquals(0, h.client.pendingRequestCount());
    }
  }

  @Test
  void negativeActConRejectsAtOnceUnderEitherPolicy() {
    for (CommandCompletion completion : CommandCompletion.values()) {
      checkNegativeActCon(completion);
    }
  }

  private void checkNegativeActCon(CommandCompletion completion) {
    try (var h = new CommandHarness()) {
      CompletableFuture<CommandResult> result =
          h.send(
              Command.single(POINT, true),
              CommandMode.directExecute(),
              CommandOptions.defaults().withCompletion(completion));
      h.reply(Cause.ACTIVATION_CONFIRMATION, true);

      CommandResult r = join(result);
      assertEquals(CommandOutcome.REJECTED, r.outcome());
      assertEquals(Cause.ACTIVATION_CONFIRMATION, r.cause());
      assertEquals(0, h.client.pendingRequestCount());
    }
  }

  @TestFactory
  Stream<DynamicTest> unknownCauseEchoRejectsWithEitherPnBit() {
    List<Cause> causes =
        List.of(
            Cause.UNKNOWN_TYPE_ID,
            Cause.UNKNOWN_CAUSE,
            Cause.UNKNOWN_COMMON_ADDRESS,
            Cause.UNKNOWN_INFORMATION_OBJECT_ADDRESS);
    return causes.stream()
        .flatMap(
            cause ->
                Stream.of(false, true)
                    .flatMap(
                        negative ->
                            Stream.of("direct", "select", "execute")
                                .map(
                                    phase ->
                                        DynamicTest.dynamicTest(
                                            phase + " " + cause + " P/N=" + (negative ? 1 : 0),
                                            () -> checkUnknownCause(phase, cause, negative)))));
  }

  private void checkUnknownCause(String phase, Cause cause, boolean negative) {
    try (var h = terminating()) {
      CompletableFuture<CommandResult> result =
          h.send(
              Command.single(POINT, true),
              phase.equals("direct")
                  ? CommandMode.directExecute()
                  : CommandMode.selectBeforeOperate());
      if (phase.equals("execute")) {
        h.reply(Cause.ACTIVATION_CONFIRMATION, false);
        assertEquals(2, h.sent().size());
      }
      h.reply(cause, negative);

      CommandResult r = join(result);
      assertEquals(CommandOutcome.REJECTED, r.outcome());
      assertEquals(cause, r.cause());
      assertEquals(phase.equals("execute") ? 2 : 1, h.sent().size(), "nothing more is sent");
      assertEquals(0, h.client.pendingRequestCount());
      assertEquals(0, h.negativeConfirmationEvents());
    }
  }

  @Test
  void reservationLastsUntilTermination() {
    try (var h = terminating()) {
      CompletableFuture<CommandResult> first =
          h.send(Command.single(POINT, true), CommandMode.directExecute());
      Asdu execute = h.lastSent();
      h.reply(Cause.ACTIVATION_CONFIRMATION, false);

      assertFailure(
          h.send(Command.single(POINT, false), CommandMode.directExecute()),
          RequestInProgressException.class);
      CompletableFuture<CommandResult> other =
          h.send(Command.single(OTHER, true), CommandMode.directExecute());
      assertFalse(other.isDone(), "other points proceed");

      h.deliver(reply(execute, Cause.ACTIVATION_TERMINATION, false));
      assertEquals(CommandOutcome.TERMINATED, join(first).outcome());
      CompletableFuture<CommandResult> next =
          h.send(Command.single(POINT, false), CommandMode.directExecute());
      assertFalse(next.isDone(), "the reservation ended with the termination");
    }
  }

  @Test
  void reservationEndsWithTheTerminationTimeout() {
    try (var h = terminating()) {
      CompletableFuture<CommandResult> first =
          h.send(Command.single(POINT, true), CommandMode.directExecute());
      h.reply(Cause.ACTIVATION_CONFIRMATION, false);
      h.clock.advance(TERMINATION_TIMEOUT.toMillis() - 1, MILLIS);
      assertFailure(
          h.send(Command.single(POINT, false), CommandMode.directExecute()),
          RequestInProgressException.class);
      h.clock.advance(1, MILLIS);
      assertEquals(CommandOutcome.TERMINATION_MISSING, join(first).outcome());
      assertFalse(h.send(Command.single(POINT, false), CommandMode.directExecute()).isDone());
    }
  }

  @Test
  void selectBeforeOperateAwaitsTerminationOfTheExecute() {
    try (var h = terminating()) {
      CompletableFuture<CommandResult> result =
          h.send(Command.single(POINT, true), CommandMode.selectBeforeOperate());
      Asdu select = h.lastSent();
      h.reply(Cause.ACTIVATION_CONFIRMATION, false);
      h.deliver(reply(select, Cause.ACTIVATION_TERMINATION, false));
      Asdu execute = h.lastSent();
      assertFalse(((SingleCommand) execute.objects().get(0)).qualifier().select());
      h.reply(Cause.ACTIVATION_CONFIRMATION, false);
      assertFalse(result.isDone());
      h.deliver(reply(execute, Cause.ACTIVATION_TERMINATION, false));
      assertEquals(CommandOutcome.TERMINATED, join(result).outcome());
    }
  }

  @Test
  void commandCompletionAppliesToSingleDoubleAndRegulatingStep() {
    try (var h =
        new CommandHarness(
            b ->
                b.commandCompletion(CommandCompletion.TERMINATION)
                    .commandTerminationTimeout(TERMINATION_TIMEOUT))) {
      assertTerminationAwaited(h, Command.single(POINT, true), true);
      assertTerminationAwaited(h, Command.doublePoint(POINT, DoubleCommandState.ON), true);
      assertTerminationAwaited(
          h, Command.regulatingStep(POINT, StepCommandState.NEXT_STEP_HIGHER), true);
      assertTerminationAwaited(h, Command.setpointShortFloat(POINT, 1.5f), false);
      assertTerminationAwaited(h, Command.bitstring(POINT, 7), false);
    }
  }

  @Test
  void setpointCompletionAppliesToSetpointsAndBitstrings() {
    try (var h =
        new CommandHarness(
            b ->
                b.setpointCompletion(CommandCompletion.TERMINATION)
                    .commandTerminationTimeout(TERMINATION_TIMEOUT))) {
      assertTerminationAwaited(h, Command.setpointNormalized(POINT, NormalizedValue.of(0.5)), true);
      assertTerminationAwaited(h, Command.setpointScaled(POINT, (short) 12), true);
      assertTerminationAwaited(h, Command.setpointShortFloat(POINT, 1.5f), true);
      assertTerminationAwaited(h, Command.bitstring(POINT, 7), true);
      assertTerminationAwaited(h, Command.single(POINT, true), false);
    }
  }

  private static void assertTerminationAwaited(CommandHarness h, Command command, boolean awaited) {
    CompletableFuture<CommandResult> result = h.send(command, CommandMode.directExecute());
    Asdu execute = h.lastSent();
    h.reply(Cause.ACTIVATION_CONFIRMATION, false);
    assertEquals(!awaited, result.isDone(), command + " awaits termination: " + awaited);
    h.deliver(reply(execute, Cause.ACTIVATION_TERMINATION, false));
    assertEquals(
        awaited ? CommandOutcome.TERMINATED : CommandOutcome.CONFIRMED, join(result).outcome());
  }

  @Test
  void optionsOverrideThePolicyAndTheTerminationTimeout() {
    try (var h = terminating()) {
      CompletableFuture<CommandResult> confirmed =
          h.send(
              Command.single(POINT, true),
              CommandMode.directExecute(),
              CommandOptions.defaults().withCompletion(CommandCompletion.CONFIRMATION));
      h.reply(Cause.ACTIVATION_CONFIRMATION, false);
      assertEquals(CommandOutcome.CONFIRMED, join(confirmed).outcome());

      CompletableFuture<CommandResult> shortWait =
          h.send(
              Command.single(POINT, true),
              CommandMode.directExecute(),
              CommandOptions.defaults().withTerminationTimeout(Duration.ofMillis(10)));
      h.reply(Cause.ACTIVATION_CONFIRMATION, false);
      h.clock.advance(10, MILLIS);
      assertEquals(CommandOutcome.TERMINATION_MISSING, join(shortWait).outcome());
    }

    try (var h = new CommandHarness()) {
      CompletableFuture<CommandResult> terminated =
          h.send(Command.single(POINT, true), CommandMode.directExecute(), AWAIT_TERMINATION);
      Asdu execute = h.lastSent();
      h.reply(Cause.ACTIVATION_CONFIRMATION, false);
      h.deliver(reply(execute, Cause.ACTIVATION_TERMINATION, false));
      assertEquals(CommandOutcome.TERMINATED, join(terminated).outcome());
    }
  }

  @Test
  void defaultSettingsEncodeTheSameAsdus() {
    try (var h = new CommandHarness()) {
      Command command = new Command.SingleCommandRequest(POINT, true, 1, Optional.of(TIME));
      CompletableFuture<CommandResult> result = h.send(command, CommandMode.selectBeforeOperate());
      h.reply(Cause.ACTIVATION_CONFIRMATION, false);
      h.reply(Cause.ACTIVATION_CONFIRMATION, false);
      assertTrue(join(result).positive());

      var select = (SingleCommandWithCp56Time) h.sent(0).objects().get(0);
      var execute = (SingleCommandWithCp56Time) h.sent(1).objects().get(0);
      Cp56Time2a tag = Cp56Time2a.from(TIME, ZoneOffset.UTC);
      assertEquals(
          new SingleCommandWithCp56Time(
              POINT.objectAddress(), true, new QualifierOfCommand(1, true), tag),
          select);
      assertEquals(
          new SingleCommandWithCp56Time(
              POINT.objectAddress(), true, new QualifierOfCommand(1, false), tag),
          execute);
    }
  }
}
