package com.digitalpetri.iec60870.client;

import static java.util.Objects.requireNonNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.digitalpetri.iec60870.ConnectionClosedException;
import com.digitalpetri.iec60870.ProtocolTimeoutException;
import com.digitalpetri.iec60870.RequestInProgressException;
import com.digitalpetri.iec60870.address.CommonAddress;
import com.digitalpetri.iec60870.address.InformationObjectAddress;
import com.digitalpetri.iec60870.address.OriginatorAddress;
import com.digitalpetri.iec60870.address.PointAddress;
import com.digitalpetri.iec60870.asdu.Asdu;
import com.digitalpetri.iec60870.asdu.AsduType;
import com.digitalpetri.iec60870.asdu.Cause;
import com.digitalpetri.iec60870.asdu.InformationObject;
import com.digitalpetri.iec60870.asdu.element.QualifierOfCommand;
import com.digitalpetri.iec60870.asdu.object.SingleCommand;
import com.digitalpetri.iec60870.asdu.object.SingleCommandWithCp56Time;
import com.digitalpetri.iec60870.asdu.time.Cp56Time2a;
import com.digitalpetri.iec60870.fakes.FakeClientTransport;
import com.digitalpetri.iec60870.fakes.FakeSession;
import com.digitalpetri.iec60870.test.common.ManualScheduler;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

/** Command identity and phase transitions with deliberately delayed application callbacks. */
class CommandTransactionTest {

  private static final PointAddress POINT =
      new PointAddress(CommonAddress.of(1), InformationObjectAddress.of(100));
  private static final Instant TIME = Instant.parse("2024-06-01T12:00:00Z");

  @Test
  void lateOffConfirmationDoesNotConfirmNewOnCommand() {
    try (Harness h = new Harness(Runnable::run)) {
      CompletableFuture<CommandResult> off = h.send(Command.single(POINT, false), false);
      Asdu oldRequest = h.sent(0);
      h.clock.advance(50, TimeUnit.MILLISECONDS);
      assertFailure(off, ProtocolTimeoutException.class);

      CompletableFuture<CommandResult> on = h.send(Command.single(POINT, true), false);
      h.session().deliverAsdu(reply(oldRequest, Cause.ACTIVATION_CONFIRMATION, false));
      assertFalse(on.isDone(), "a late OFF reply cannot confirm ON");
      h.session().deliverAsdu(reply(h.sent(1), Cause.ACTIVATION_CONFIRMATION, false));
      assertTrue(on.join().positive());
    }
  }

  @TestFactory
  Stream<DynamicTest> distinguishableReplyDoesNotAdvanceSelect() {
    return Stream.of(
            "type",
            "station",
            "address",
            "phase",
            "value",
            "qualifier",
            "time",
            "originator",
            "test",
            "sequence",
            "count",
            "deactivation",
            "negativeDeactivation",
            "negativeSpontaneous")
        .map(mismatch -> DynamicTest.dynamicTest(mismatch, () -> checkMismatchedReply(mismatch)));
  }

  private void checkMismatchedReply(String mismatch) {
    try (Harness h = new Harness(Runnable::run)) {
      Command command = new Command.SingleCommandRequest(POINT, true, 2, Optional.of(TIME));
      CompletableFuture<CommandResult> result = h.send(command, true);
      Asdu request = h.sent(0);
      h.session().deliverAsdu(mismatchedReply(request, mismatch));
      assertEquals(1, h.session().sentAsdus().size(), "mismatched reply must not send EXECUTE");
      assertFalse(result.isDone());
      h.session().deliverAsdu(reply(request, Cause.ACTIVATION_CONFIRMATION, false));
      assertEquals(2, h.session().sentAsdus().size());
      h.session().deliverAsdu(reply(h.sent(1), Cause.ACTIVATION_CONFIRMATION, false));
      assertTrue(result.join().positive());
    }
  }

  @Test
  void duplicateSelectConfirmationDoesNotConfirmExecute() {
    try (Harness h = new Harness(Runnable::run)) {
      CompletableFuture<CommandResult> result = h.send(Command.single(POINT, true), true);
      Asdu selectReply = reply(h.sent(0), Cause.ACTIVATION_CONFIRMATION, false);
      h.session().deliverAsdu(selectReply);
      h.session().deliverAsdu(selectReply);
      assertFalse(result.isDone());
      h.session().deliverAsdu(reply(h.sent(1), Cause.ACTIVATION_CONFIRMATION, false));
      assertTrue(result.join().positive());
    }
  }

  @Test
  void negativeErrorConfirmationRejectsSelectWithoutExecuting() {
    try (Harness h = new Harness(Runnable::run)) {
      CompletableFuture<CommandResult> result = h.send(Command.single(POINT, true), true);
      h.session().deliverAsdu(reply(h.sent(0), Cause.UNKNOWN_INFORMATION_OBJECT_ADDRESS, true));
      assertFalse(result.join().positive());
      assertEquals(Cause.UNKNOWN_INFORMATION_OBJECT_ADDRESS, result.join().cause());
      assertEquals(1, h.session().sentAsdus().size());
      assertEquals(0, h.client.pendingRequestCount());
    }
  }

  @Test
  void queuedSelectContinuationDoesNotExecuteAfterReconnect() {
    var callbacks = new QueuedExecutor();
    try (Harness h = new Harness(callbacks)) {
      CompletableFuture<CommandResult> result = h.send(Command.single(POINT, true), true);
      h.session().deliverAsdu(reply(h.sent(0), Cause.ACTIVATION_CONFIRMATION, false));
      h.session().fireConnectionLost(null);
      h.client.connect();
      callbacks.drain();
      assertEquals(1, h.session().sentAsdus().size(), "old SELECT cannot authorize new connection");
      assertFailure(result, ConnectionClosedException.class);
      assertEquals(0, h.client.pendingRequestCount());
    }
  }

  @Test
  void commandReservationSurvivesQueuedSelectContinuation() {
    var callbacks = new QueuedExecutor();
    try (Harness h = new Harness(callbacks)) {
      CompletableFuture<CommandResult> result = h.send(Command.single(POINT, true), true);
      Asdu selectReply = reply(h.sent(0), Cause.ACTIVATION_CONFIRMATION, false);
      h.session().deliverAsdu(selectReply);
      h.session().deliverAsdu(selectReply);
      assertEquals(1, h.client.pendingRequestCount(), "SELECT and EXECUTE share one reservation");
      CompletableFuture<CommandResult> competing = h.send(Command.single(POINT, false), false);
      assertFailure(competing, RequestInProgressException.class);
      callbacks.drain();
      assertEquals(2, h.session().sentAsdus().size(), "duplicate SELECT queues only one EXECUTE");
      h.session().deliverAsdu(reply(h.sent(1), Cause.ACTIVATION_CONFIRMATION, false));
      callbacks.drain();
      assertTrue(result.join().positive());
      assertEquals(0, h.client.pendingRequestCount());
    }
  }

  @Test
  void closeWhileSelectContinuationIsQueuedFailsCommand() {
    var callbacks = new QueuedExecutor();
    try (Harness h = new Harness(callbacks)) {
      CompletableFuture<CommandResult> result = h.send(Command.single(POINT, true), true);
      h.session().deliverAsdu(reply(h.sent(0), Cause.ACTIVATION_CONFIRMATION, false));
      h.client.close();
      callbacks.drain();
      assertFailure(result, ConnectionClosedException.class);
      assertEquals(1, h.session().sentAsdus().size());
    }
  }

  @Test
  void cancellingQueuedSelectContinuationSuppressesExecute() {
    var callbacks = new QueuedExecutor();
    try (Harness h = new Harness(callbacks)) {
      CompletableFuture<CommandResult> result = h.send(Command.single(POINT, true), true);
      h.session().deliverAsdu(reply(h.sent(0), Cause.ACTIVATION_CONFIRMATION, false));
      assertTrue(result.cancel(false));
      callbacks.drain();
      assertEquals(1, h.session().sentAsdus().size());
      assertEquals(0, h.client.pendingRequestCount());
    }
  }

  @Test
  void executeTimeoutStartsWhenQueuedContinuationSendsExecute() {
    var callbacks = new QueuedExecutor();
    try (Harness h = new Harness(callbacks)) {
      CompletableFuture<CommandResult> result = h.send(Command.single(POINT, true), true);
      h.clock.advance(49, TimeUnit.MILLISECONDS);
      h.session().deliverAsdu(reply(h.sent(0), Cause.ACTIVATION_CONFIRMATION, false));
      h.clock.advance(100, TimeUnit.MILLISECONDS);
      callbacks.drain();
      assertFalse(result.isDone(), "SELECT timeout was cancelled");
      assertEquals(2, h.session().sentAsdus().size());
      h.clock.advance(49, TimeUnit.MILLISECONDS);
      callbacks.drain();
      assertFalse(result.isDone(), "EXECUTE has its own deadline");
      h.clock.advance(1, TimeUnit.MILLISECONDS);
      callbacks.drain();
      assertFailure(result, ProtocolTimeoutException.class);
      assertEquals(0, h.client.pendingRequestCount());
    }
  }

  private static Asdu mismatchedReply(Asdu request, String mismatch) {
    var original = (SingleCommandWithCp56Time) request.objects().get(0);
    InformationObject object = original;
    if (List.of("address", "phase", "value", "qualifier", "time").contains(mismatch)) {
      object =
          new SingleCommandWithCp56Time(
              mismatch.equals("address") ? InformationObjectAddress.of(101) : original.address(),
              !mismatch.equals("value"),
              new QualifierOfCommand(
                  mismatch.equals("qualifier") ? 3 : 2, !mismatch.equals("phase")),
              mismatch.equals("time")
                  ? Cp56Time2a.from(TIME.plusSeconds(1), ZoneOffset.UTC)
                  : original.time());
    }
    if (mismatch.equals("type")) {
      object = new SingleCommand(original.address(), original.on(), original.qualifier());
    }
    Cause cause =
        switch (mismatch) {
          case "deactivation", "negativeDeactivation" -> Cause.DEACTIVATION_CONFIRMATION;
          case "negativeSpontaneous" -> Cause.SPONTANEOUS;
          default -> Cause.ACTIVATION_CONFIRMATION;
        };
    return new Asdu(
        mismatch.equals("type") ? AsduType.C_SC_NA_1 : request.type(),
        mismatch.equals("sequence"),
        cause,
        mismatch.startsWith("negative"),
        mismatch.equals("test"),
        mismatch.equals("originator") ? OriginatorAddress.of(28) : request.originatorAddress(),
        mismatch.equals("station") ? CommonAddress.of(2) : request.commonAddress(),
        mismatch.equals("count") ? List.of(object, object) : List.of(object));
  }

  private static Asdu reply(Asdu request, Cause cause, boolean negative) {
    return new Asdu(
        request.type(),
        request.sequence(),
        cause,
        negative,
        request.test(),
        request.originatorAddress(),
        request.commonAddress(),
        request.objects());
  }

  private static void assertFailure(CompletableFuture<?> future, Class<? extends Throwable> type) {
    assertTrue(future.isDone(), "operation must have completed");
    CompletionException error = assertThrows(CompletionException.class, future::join);
    assertInstanceOf(type, error.getCause());
  }

  private static final class QueuedExecutor implements Executor {
    private final Deque<Runnable> tasks = new ArrayDeque<>();

    @Override
    public void execute(Runnable task) {
      tasks.addLast(task);
    }

    void drain() {
      while (!tasks.isEmpty()) {
        tasks.removeFirst().run();
      }
    }
  }

  private static final class Harness implements AutoCloseable {
    final ManualScheduler clock = new ManualScheduler();
    final AtomicReference<@Nullable FakeSession> session = new AtomicReference<>();
    final DefaultIec60870Client client;

    Harness(Executor callbacks) {
      client =
          new DefaultIec60870Client(
              new FakeClientTransport(),
              ClientConfig.builder()
                  .callbackExecutor(callbacks)
                  .originatorAddress(OriginatorAddress.of(27))
                  .commandTimeout(Duration.ofMillis(50))
                  .build(),
              (events, scheduler) -> {
                FakeSession fake = FakeSession.client(events);
                session.set(fake);
                return fake;
              },
              clock);
      client.connect();
    }

    FakeSession session() {
      return requireNonNull(session.get());
    }

    Asdu sent(int index) {
      return session().sentAsdus().get(index);
    }

    CompletableFuture<CommandResult> send(Command command, boolean select) {
      return client
          .commands()
          .sendAsync(
              command, select ? CommandMode.selectBeforeOperate() : CommandMode.directExecute())
          .toCompletableFuture();
    }

    @Override
    public void close() {
      client.close();
    }
  }
}
