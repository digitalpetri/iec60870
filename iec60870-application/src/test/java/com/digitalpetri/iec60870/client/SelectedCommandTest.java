package com.digitalpetri.iec60870.client;

import static com.digitalpetri.iec60870.client.CommandHarness.assertFailure;
import static com.digitalpetri.iec60870.client.CommandHarness.join;
import static com.digitalpetri.iec60870.client.CommandHarness.reply;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.digitalpetri.iec60870.ConnectionClosedException;
import com.digitalpetri.iec60870.NegativeConfirmationException;
import com.digitalpetri.iec60870.ProtocolTimeoutException;
import com.digitalpetri.iec60870.RequestInProgressException;
import com.digitalpetri.iec60870.address.CommonAddress;
import com.digitalpetri.iec60870.address.InformationObjectAddress;
import com.digitalpetri.iec60870.address.PointAddress;
import com.digitalpetri.iec60870.asdu.Asdu;
import com.digitalpetri.iec60870.asdu.Cause;
import com.digitalpetri.iec60870.asdu.object.SingleCommand;
import com.digitalpetri.iec60870.asdu.object.SingleCommandWithCp56Time;
import com.digitalpetri.iec60870.asdu.time.Cp56Time2a;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** Select now, then execute or cancel later through a {@link SelectedCommand} handle. */
class SelectedCommandTest {

  private static final PointAddress POINT =
      new PointAddress(CommonAddress.of(1), InformationObjectAddress.of(100));
  private static final PointAddress OTHER =
      new PointAddress(CommonAddress.of(1), InformationObjectAddress.of(101));
  private static final Instant TIME = Instant.parse("2024-06-01T12:00:00Z");
  private static final Duration SELECTION_TIMEOUT = Duration.ofMillis(500);
  private static final TimeUnit MILLIS = TimeUnit.MILLISECONDS;

  private static CommandHarness harness() {
    return new CommandHarness(b -> b.selectionTimeout(SELECTION_TIMEOUT));
  }

  /** Selects {@code command} and confirms the select positively. */
  private static SelectedCommand selected(CommandHarness h, Command command) {
    return selected(h, command, CommandOptions.defaults());
  }

  private static SelectedCommand selected(
      CommandHarness h, Command command, CommandOptions options) {
    CompletableFuture<SelectedCommand> stage = h.select(command, options);
    h.reply(Cause.ACTIVATION_CONFIRMATION, false);
    return join(stage);
  }

  @Test
  void selectReturnsHandleOnPositiveConfirmation() {
    try (var h = harness()) {
      Command command = new Command.SingleCommandRequest(POINT, true, 3, Optional.empty());
      Instant before = Instant.now();
      CompletableFuture<SelectedCommand> stage = h.select(command);
      Asdu select = h.lastSent();
      assertEquals(Cause.ACTIVATION, select.cause());
      assertTrue(((SingleCommand) select.objects().get(0)).qualifier().select());
      assertFalse(stage.isDone());

      h.reply(Cause.ACTIVATION_CONFIRMATION, false);
      SelectedCommand handle = join(stage);
      assertSame(command, handle.command());
      assertEquals(Cause.ACTIVATION_CONFIRMATION, handle.selectConfirmation().cause());
      assertFalse(handle.expiresAt().isBefore(before.plus(SELECTION_TIMEOUT)));
      assertEquals(1, h.client.pendingRequestCount(), "the handle holds the reservation");
      assertEquals(1, h.sent().size());
    }
  }

  @Test
  void rejectedSelectFailsWithNegativeConfirmationException() {
    for (Cause cause :
        List.of(Cause.ACTIVATION_CONFIRMATION, Cause.UNKNOWN_INFORMATION_OBJECT_ADDRESS)) {
      for (boolean negative : List.of(true, false)) {
        if (cause == Cause.ACTIVATION_CONFIRMATION && !negative) {
          continue;
        }
        try (var h = harness()) {
          CompletableFuture<SelectedCommand> stage = h.select(Command.single(POINT, true));
          h.reply(cause, negative);
          assertFailure(stage, NegativeConfirmationException.class);
          assertEquals(0, h.client.pendingRequestCount());
          assertEquals(1, h.sent().size());
          assertEquals(0, h.negativeConfirmationEvents());
        }
      }
    }
  }

  @Test
  void selectingBitstringThrowsBeforeSending() {
    try (var h = harness()) {
      assertThrows(
          IllegalArgumentException.class,
          () -> h.client.commands().select(Command.bitstring(POINT, 1)));
      assertEquals(0, h.sent().size());
      assertEquals(0, h.client.pendingRequestCount());
    }
  }

  @Test
  void selectTimeoutThrowsAndDeactivatesTheSelect() {
    try (var h = harness()) {
      CompletableFuture<SelectedCommand> stage = h.select(Command.single(POINT, true));
      Asdu select = h.lastSent();
      h.clock.advance(CommandHarness.COMMAND_TIMEOUT.toMillis(), MILLIS);
      assertFailure(stage, ProtocolTimeoutException.class);

      Asdu deactivation = h.lastSent();
      assertEquals(Cause.DEACTIVATION, deactivation.cause());
      assertEquals(select.objects(), deactivation.objects());

      // The late select confirmation sends nothing more; DEACT_CON releases the reservation.
      h.deliver(reply(select, Cause.ACTIVATION_CONFIRMATION, false));
      assertEquals(2, h.sent().size());
      h.deliver(reply(deactivation, Cause.DEACTIVATION_CONFIRMATION, true));
      assertEquals(0, h.client.pendingRequestCount());
      assertEquals(0, h.negativeConfirmationEvents(), "the DEACT_CON was consumed");
    }
  }

  @Test
  void heldSelectionBlocksTheSamePointAndFamilyOnly() {
    try (var h = harness()) {
      SelectedCommand handle = selected(h, Command.single(POINT, true));
      assertFailure(
          h.send(Command.single(POINT, false), CommandMode.directExecute()),
          RequestInProgressException.class);
      assertFailure(h.select(Command.single(POINT, false)), RequestInProgressException.class);
      assertEquals(1, h.sent().size(), "the refused requests sent nothing");

      CompletableFuture<CommandResult> other =
          h.send(Command.single(OTHER, true), CommandMode.directExecute());
      h.reply(Cause.ACTIVATION_CONFIRMATION, false);
      assertTrue(join(other).positive());

      handle.close();
    }
  }

  @Test
  void executeSendsTheSelectedCommandAndReleasesTheReservation() {
    try (var h = harness()) {
      Command command = new Command.SingleCommandRequest(POINT, false, 4, Optional.empty());
      SelectedCommand handle = selected(h, command);

      CompletionStage<CommandResult> result = handle.executeAsync();
      var execute = (SingleCommand) h.lastSent().objects().get(0);
      assertEquals(Cause.ACTIVATION, h.lastSent().cause());
      assertFalse(execute.on());
      assertEquals(4, execute.qualifier().qualifier());
      assertFalse(execute.qualifier().select());

      h.reply(Cause.ACTIVATION_CONFIRMATION, false);
      assertEquals(CommandOutcome.CONFIRMED, join(result).outcome());
      assertEquals(0, h.client.pendingRequestCount());
    }
  }

  @Test
  void executeCompletesPerTheOptionsGivenToSelect() {
    try (var h = harness()) {
      SelectedCommand handle =
          selected(
              h,
              Command.single(POINT, true),
              CommandOptions.defaults().withCompletion(CommandCompletion.TERMINATION));
      CompletionStage<CommandResult> result = handle.executeAsync();
      Asdu execute = h.lastSent();
      h.reply(Cause.ACTIVATION_CONFIRMATION, false);
      assertFalse(result.toCompletableFuture().isDone());
      h.deliver(reply(execute, Cause.ACTIVATION_TERMINATION, false));
      assertEquals(CommandOutcome.TERMINATED, join(result).outcome());
    }
  }

  @Test
  void cancelSendsDeactivationAndCorrelatesItsConfirmation() {
    record Case(Cause cause, boolean negative, CommandOutcome outcome) {}
    for (Case c :
        List.of(
            new Case(Cause.DEACTIVATION_CONFIRMATION, false, CommandOutcome.CONFIRMED),
            new Case(Cause.DEACTIVATION_CONFIRMATION, true, CommandOutcome.REJECTED),
            new Case(Cause.UNKNOWN_CAUSE, true, CommandOutcome.REJECTED),
            new Case(Cause.UNKNOWN_INFORMATION_OBJECT_ADDRESS, false, CommandOutcome.REJECTED))) {
      try (var h = harness()) {
        Command command = new Command.SingleCommandRequest(POINT, true, 2, Optional.empty());
        SelectedCommand handle = selected(h, command);
        Asdu select = h.lastSent();

        CompletionStage<CommandResult> result = handle.cancelAsync();
        Asdu deactivation = h.lastSent();
        assertEquals(Cause.DEACTIVATION, deactivation.cause());
        assertEquals(select.objects(), deactivation.objects(), "same value, qualifier, and S/E=1");
        assertFalse(result.toCompletableFuture().isDone());

        h.reply(c.cause(), c.negative());
        CommandResult r = join(result);
        assertEquals(c.outcome(), r.outcome(), c.toString());
        assertEquals(c.cause(), r.cause());
        assertEquals(0, h.client.pendingRequestCount());
        assertEquals(0, h.negativeConfirmationEvents());
      }
    }
  }

  @Test
  void cancelTimeoutThrowsAndEndsTheSelection() {
    try (var h = harness()) {
      SelectedCommand handle = selected(h, Command.single(POINT, true));
      CompletionStage<CommandResult> result = handle.cancelAsync();
      h.clock.advance(CommandHarness.COMMAND_TIMEOUT.toMillis(), MILLIS);
      assertFailure(result, ProtocolTimeoutException.class);
      assertEquals(0, h.client.pendingRequestCount());
      assertFailure(handle.executeAsync(), IllegalStateException.class);
    }
  }

  @Test
  void executeAndCancelSucceedAtMostOnce() {
    try (var h = harness()) {
      SelectedCommand handle = selected(h, Command.single(POINT, true));
      CompletionStage<CommandResult> executed = handle.executeAsync();
      assertEquals(2, h.sent().size());

      assertFailure(handle.executeAsync(), IllegalStateException.class);
      assertFailure(handle.cancelAsync(), IllegalStateException.class);
      assertEquals(2, h.sent().size(), "the refused calls sent nothing");

      h.reply(Cause.ACTIVATION_CONFIRMATION, false);
      assertTrue(join(executed).positive());
      assertThrows(IllegalStateException.class, handle::execute);
      assertThrows(IllegalStateException.class, handle::cancel);
      handle.close();
      assertEquals(2, h.sent().size(), "close after execute sends nothing");
    }
  }

  @Test
  void concurrentExecuteAndCancelExcludeEachOther() throws Exception {
    ExecutorService workers = Executors.newFixedThreadPool(2);
    try {
      for (int round = 0; round < 50; round++) {
        try (var h = harness()) {
          SelectedCommand handle = selected(h, Command.single(POINT, true));
          var start = new CountDownLatch(1);
          Future<CompletionStage<CommandResult>> execute =
              workers.submit(
                  () -> {
                    start.await();
                    return handle.executeAsync();
                  });
          Future<CompletionStage<CommandResult>> cancel =
              workers.submit(
                  () -> {
                    start.await();
                    return handle.cancelAsync();
                  });
          start.countDown();
          List<CompletableFuture<CommandResult>> stages =
              List.of(
                  execute.get(5, TimeUnit.SECONDS).toCompletableFuture(),
                  cancel.get(5, TimeUnit.SECONDS).toCompletableFuture());

          long refused =
              stages.stream()
                  .filter(
                      s ->
                          s.isCompletedExceptionally()
                              && s.handle((r, e) -> e instanceof IllegalStateException).join())
                  .count();
          assertEquals(1, refused, "exactly one of execute and cancel succeeds");
          assertEquals(2, h.sent().size(), "only the winner sent an ASDU");
        }
      }
    } finally {
      workers.shutdownNow();
      assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
    }
  }

  @Test
  void connectionLossEndsTheSelectionWithoutDeactivation() {
    try (var h = harness()) {
      SelectedCommand handle = selected(h, Command.single(POINT, true));
      h.session().fireConnectionLost(null);
      assertEquals(0, h.client.pendingRequestCount());

      assertFailure(handle.executeAsync(), ConnectionClosedException.class);
      assertFailure(handle.cancelAsync(), ConnectionClosedException.class);
      assertThrows(ConnectionClosedException.class, handle::execute);
      handle.close();
      assertEquals(1, h.sent().size(), "nothing is sent after the loss");

      // The next connection does not inherit the selection.
      h.client.connect();
      SelectedCommand again = selected(h, Command.single(POINT, true));
      assertEquals(1, h.client.pendingRequestCount());
      again.close();
    }
  }

  @Test
  void disconnectEndsTheSelectionWithoutDeactivation() {
    try (var h = harness()) {
      SelectedCommand handle = selected(h, Command.single(POINT, true));
      h.client.disconnect();
      assertEquals(0, h.client.pendingRequestCount());
      assertFailure(handle.executeAsync(), ConnectionClosedException.class);
      assertFailure(handle.cancelAsync(), ConnectionClosedException.class);
      handle.close();
      assertTrue(
          h.sent().stream().noneMatch(a -> a.cause() == Cause.DEACTIVATION), "no DEACT is sent");
    }
  }

  @Test
  void automaticReconnectDoesNotInheritTheSelection() {
    try (var h =
        new CommandHarness(
            b ->
                b.selectionTimeout(SELECTION_TIMEOUT)
                    .reconnectPolicy(
                        ReconnectPolicy.exponentialBackoff(
                            Duration.ofMillis(10), Duration.ofMillis(10))))) {
      SelectedCommand handle = selected(h, Command.single(POINT, true));
      h.session().fireConnectionLost(null);
      h.clock.advance(10, MILLIS);
      assertEquals(ConnectionState.CONNECTED, h.client.connectionState());

      assertFailure(handle.executeAsync(), ConnectionClosedException.class);
      h.clock.advance(SELECTION_TIMEOUT.toMillis(), MILLIS);
      assertTrue(
          h.sent().stream().noneMatch(a -> a.cause() == Cause.DEACTIVATION),
          "the old selection neither executes nor deactivates on the new connection");
      assertEquals(0, h.client.pendingRequestCount());
      selected(h, Command.single(POINT, true)).close();
    }
  }

  @Test
  void selectionTimeoutDeactivatesOnceAndConsumesTheConfirmation() {
    try (var h = harness()) {
      SelectedCommand handle = selected(h, Command.single(POINT, true));
      h.clock.advance(SELECTION_TIMEOUT.toMillis() - 1, MILLIS);
      assertEquals(1, h.sent().size());
      h.clock.advance(1, MILLIS);

      assertEquals(2, h.sent().size());
      Asdu deactivation = h.lastSent();
      assertEquals(Cause.DEACTIVATION, deactivation.cause());
      assertFailure(handle.executeAsync(), IllegalStateException.class);
      assertEquals(1, h.client.pendingRequestCount(), "held until DEACT_CON");

      // The outstation's own timer fired first: its negative DEACT_CON is consumed.
      h.reply(Cause.DEACTIVATION_CONFIRMATION, true);
      assertEquals(0, h.client.pendingRequestCount());
      assertEquals(0, h.negativeConfirmationEvents());

      assertFailure(handle.cancelAsync(), IllegalStateException.class);
      handle.close();
      h.clock.advance(SELECTION_TIMEOUT.toMillis(), MILLIS);
      assertEquals(2, h.sent().size(), "one deactivation only");
    }
  }

  @Test
  void closeDeactivatesAHeldSelection() {
    try (var h = harness()) {
      try (SelectedCommand handle = selected(h, Command.single(POINT, true))) {
        assertEquals(1, h.sent().size());
        assertSame(handle.command().target(), POINT);
      }
      assertEquals(Cause.DEACTIVATION, h.lastSent().cause());
      h.reply(Cause.DEACTIVATION_CONFIRMATION, false);
      assertEquals(0, h.client.pendingRequestCount());
      assertEquals(2, h.sent().size());
    }
  }

  @Test
  void closeAfterCancelSendsNothing() {
    try (var h = harness()) {
      SelectedCommand handle = selected(h, Command.single(POINT, true));
      CompletionStage<CommandResult> result = handle.cancelAsync();
      h.reply(Cause.DEACTIVATION_CONFIRMATION, false);
      assertEquals(CommandOutcome.CONFIRMED, join(result).outcome());
      handle.close();
      assertEquals(2, h.sent().size());
    }
  }

  @Test
  void timeTaggedExecuteAndCancelCarryTheTimeTheyAreSent() {
    Command command = new Command.SingleCommandRequest(POINT, true, 0, Optional.of(TIME));
    Cp56Time2a selectTag = Cp56Time2a.from(TIME, ZoneOffset.UTC);
    try (var h = harness()) {
      SelectedCommand handle = selected(h, command);
      assertEquals(selectTag, tagOf(h.lastSent()));

      Instant before = Instant.now().minusSeconds(1);
      CompletionStage<CommandResult> result = handle.executeAsync();
      Cp56Time2a executeTag = tagOf(h.lastSent());
      assertNotEquals(selectTag, executeTag);
      assertFalse(executeTag.toInstant(ZoneOffset.UTC).isBefore(before));

      // The confirmation must echo the execute's own tag.
      h.reply(Cause.ACTIVATION_CONFIRMATION, false);
      assertTrue(join(result).positive());
    }
    try (var h = harness()) {
      SelectedCommand handle = selected(h, command);
      Instant before = Instant.now().minusSeconds(1);
      CompletionStage<CommandResult> result = handle.cancelAsync();
      Cp56Time2a cancelTag = tagOf(h.lastSent());
      assertFalse(cancelTag.toInstant(ZoneOffset.UTC).isBefore(before));
      h.reply(Cause.DEACTIVATION_CONFIRMATION, false);
      assertEquals(CommandOutcome.CONFIRMED, join(result).outcome());
    }
  }

  @Test
  void cancellingTheSelectStageDeactivatesALateConfirmedSelection() {
    try (var h = harness()) {
      CompletableFuture<SelectedCommand> stage = h.select(Command.single(POINT, true));
      assertTrue(stage.cancel(false));
      h.reply(Cause.ACTIVATION_CONFIRMATION, false);
      assertEquals(Cause.DEACTIVATION, h.lastSent().cause());
      h.reply(Cause.DEACTIVATION_CONFIRMATION, false);
      assertEquals(0, h.client.pendingRequestCount());
    }
  }

  @Test
  void blockingSelectThrowsTheRejection() {
    try (var h = harness()) {
      CompletableFuture<SelectedCommand> stage =
          CompletableFuture.supplyAsync(
              () -> h.client.commands().select(Command.single(POINT, true)));
      while (h.sent().isEmpty()) {
        Thread.onSpinWait();
      }
      h.reply(Cause.ACTIVATION_CONFIRMATION, true);
      CompletionException error = assertThrows(CompletionException.class, stage::join);
      assertTrue(error.getCause() instanceof NegativeConfirmationException);
    }
  }

  private static Cp56Time2a tagOf(Asdu asdu) {
    return ((SingleCommandWithCp56Time) asdu.objects().get(0)).time();
  }
}
