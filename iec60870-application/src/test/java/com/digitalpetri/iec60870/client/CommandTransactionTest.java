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
import com.digitalpetri.iec60870.session.Session;
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
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
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
      Runnable selectTimeout = h.clock.lastRunnableWithDelay(50);
      h.clock.advance(49, TimeUnit.MILLISECONDS);
      h.session().deliverAsdu(reply(h.sent(0), Cause.ACTIVATION_CONFIRMATION, false));
      // A timer already dispatched before cancellation can still run after SELECT was confirmed.
      selectTimeout.run();
      h.clock.advance(100, TimeUnit.MILLISECONDS);
      callbacks.drain();
      assertFalse(result.isDone(), "SELECT timeout was cancelled");
      assertEquals(2, h.session().sentAsdus().size());
      selectTimeout.run();
      callbacks.drain();
      assertFalse(result.isDone(), "a stale SELECT timeout cannot fail EXECUTE");
      h.clock.advance(49, TimeUnit.MILLISECONDS);
      callbacks.drain();
      assertFalse(result.isDone(), "EXECUTE has its own deadline");
      h.clock.advance(1, TimeUnit.MILLISECONDS);
      callbacks.drain();
      assertFailure(result, ProtocolTimeoutException.class);
      assertEquals(0, h.client.pendingRequestCount());
    }
  }

  @Test
  void inlineSelectContinuationDoesNotDeadlockCompetingSend() throws Exception {
    AtomicReference<@Nullable LockingSession> sessionRef = new AtomicReference<>();
    var config = ClientConfig.builder().callbackExecutor(Runnable::run).build();
    try (var client =
        new DefaultIec60870Client(
            new FakeClientTransport(),
            config,
            (events, scheduler) -> {
              var session = new LockingSession(events);
              sessionRef.set(session);
              return session;
            })) {
      client.connect();
      LockingSession session = requireNonNull(sessionRef.get());
      CompletableFuture<CommandResult> selected =
          client
              .commands()
              .sendAsync(Command.single(POINT, true), CommandMode.selectBeforeOperate())
              .toCompletableFuture();
      Asdu selectReply =
          reply(session.delegate.sentAsdus().get(0), Cause.ACTIVATION_CONFIRMATION, false);
      var inboundLocked = new CountDownLatch(1);
      var deliverConfirmation = new CountDownLatch(1);
      ExecutorService workers = Executors.newFixedThreadPool(2);
      try {
        Future<?> receive =
            workers.submit(
                () -> {
                  session.lock.lock();
                  try {
                    inboundLocked.countDown();
                    awaitLatch(deliverConfirmation);
                    session.delegate.deliverAsdu(selectReply);
                  } finally {
                    session.lock.unlock();
                  }
                });
        assertTrue(inboundLocked.await(5, TimeUnit.SECONDS));
        Future<?> competing =
            workers.submit(
                () ->
                    client
                        .commands()
                        .sendAsync(
                            Command.single(
                                new PointAddress(
                                    POINT.commonAddress(), InformationObjectAddress.of(101)),
                                true),
                            CommandMode.directExecute()));
        assertTrue(session.competingSendEntered.await(5, TimeUnit.SECONDS));
        // The competing sender is now inside Session.sendAsdu, waiting for the inbound lock.
        // An inline SELECT continuation must enqueue EXECUTE and return, not wait for that sender.
        deliverConfirmation.countDown();
        receive.get(5, TimeUnit.SECONDS);
        competing.get(5, TimeUnit.SECONDS);
        assertEquals(3, session.delegate.sentAsdus().size());
        session.delegate.deliverAsdu(
            reply(session.delegate.sentAsdus().get(2), Cause.ACTIVATION_CONFIRMATION, false));
        assertTrue(selected.join().positive());
      } finally {
        deliverConfirmation.countDown();
        workers.shutdownNow();
        assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
      }
    }
  }

  @Test
  void failedSubmissionDoesNotPreventLaterCommands() {
    AtomicReference<@Nullable LockingSession> sessionRef = new AtomicReference<>();
    var config = ClientConfig.builder().callbackExecutor(Runnable::run).build();
    try (var client =
        new DefaultIec60870Client(
            new FakeClientTransport(),
            config,
            (events, scheduler) -> {
              var session = new LockingSession(events);
              sessionRef.set(session);
              return session;
            })) {
      client.connect();
      LockingSession session = requireNonNull(sessionRef.get());
      session.failNextSend = true;
      CompletableFuture<CommandResult> failed =
          client
              .commands()
              .sendAsync(Command.single(POINT, true), CommandMode.directExecute())
              .toCompletableFuture();
      assertFailure(failed, IllegalStateException.class);
      CompletableFuture<CommandResult> later =
          client
              .commands()
              .sendAsync(Command.single(POINT, false), CommandMode.directExecute())
              .toCompletableFuture();
      session.delegate.deliverAsdu(
          reply(session.delegate.sentAsdus().get(0), Cause.ACTIVATION_CONFIRMATION, false));
      assertTrue(later.join().positive());
    }
  }

  @Test
  void sessionResetWaitsForPriorCommandSubmissionWithoutBlockingCaller() throws Exception {
    AtomicReference<@Nullable LockingSession> sessionRef = new AtomicReference<>();
    var config = ClientConfig.builder().callbackExecutor(Runnable::run).build();
    try (var client =
        new DefaultIec60870Client(
            new FakeClientTransport(),
            config,
            (events, scheduler) -> {
              var session = new LockingSession(events);
              sessionRef.set(session);
              return session;
            })) {
      client.connect();
      LockingSession session = requireNonNull(sessionRef.get());
      CompletableFuture<CommandResult> selected =
          client
              .commands()
              .sendAsync(Command.single(POINT, true), CommandMode.selectBeforeOperate())
              .toCompletableFuture();
      Asdu selectReply =
          reply(session.delegate.sentAsdus().get(0), Cause.ACTIVATION_CONFIRMATION, false);
      session.pauseExecute = true;
      ExecutorService workers = Executors.newFixedThreadPool(2);
      try {
        Future<?> receive = workers.submit(() -> session.delegate.deliverAsdu(selectReply));
        assertTrue(session.executeSendEntered.await(5, TimeUnit.SECONDS));
        session.delegate.fireConnectionLost(null);
        assertFailure(selected, ConnectionClosedException.class);
        Future<CompletionStage<Void>> submission = workers.submit(client::connectAsync);
        CompletionStage<Void> reconnect = submission.get(5, TimeUnit.SECONDS);
        assertFalse(
            reconnect.toCompletableFuture().isDone(), "reset must await the old submission");
        session.releaseExecuteSend.countDown();
        receive.get(5, TimeUnit.SECONDS);
        reconnect.toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertEquals(
            1, session.delegate.sentAsdus().size(), "EXECUTE cannot reach the new connection");
      } finally {
        session.releaseExecuteSend.countDown();
        workers.shutdownNow();
        assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
      }
    }
  }

  @Test
  void failedSessionResetDoesNotPreventLaterConnection() {
    AtomicReference<@Nullable LockingSession> sessionRef = new AtomicReference<>();
    var config = ClientConfig.builder().callbackExecutor(Runnable::run).build();
    try (var client =
        new DefaultIec60870Client(
            new FakeClientTransport(),
            config,
            (events, scheduler) -> {
              var session = new LockingSession(events);
              sessionRef.set(session);
              return session;
            })) {
      LockingSession session = requireNonNull(sessionRef.get());
      session.failNextConnect = true;
      assertFailure(client.connectAsync().toCompletableFuture(), IllegalStateException.class);
      client.connect();
      CompletableFuture<CommandResult> result =
          client
              .commands()
              .sendAsync(Command.single(POINT, true), CommandMode.directExecute())
              .toCompletableFuture();
      session.delegate.deliverAsdu(
          reply(session.delegate.sentAsdus().get(0), Cause.ACTIVATION_CONFIRMATION, false));
      assertTrue(result.join().positive());
    }
  }

  private static void awaitLatch(CountDownLatch latch) {
    try {
      assertTrue(latch.await(5, TimeUnit.SECONDS));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new AssertionError(e);
    }
  }

  /** Models a real session's lock held while delivering callbacks. */
  private static final class LockingSession implements Session {
    final FakeSession delegate;
    final ReentrantLock lock = new ReentrantLock();
    final CountDownLatch competingSendEntered = new CountDownLatch(1);
    final CountDownLatch executeSendEntered = new CountDownLatch(1);
    final CountDownLatch releaseExecuteSend = new CountDownLatch(1);
    boolean pauseExecute;
    boolean failNextSend;
    boolean failNextConnect;

    LockingSession(Session.Events events) {
      delegate = FakeSession.client(events);
    }

    @Override
    public void onConnected() {
      if (failNextConnect) {
        failNextConnect = false;
        throw new IllegalStateException("injected reset failure");
      }
      delegate.onConnected();
    }

    @Override
    public CompletionStage<Void> startDataTransfer() {
      return delegate.startDataTransfer();
    }

    @Override
    public CompletionStage<Void> stopDataTransfer() {
      return delegate.stopDataTransfer();
    }

    @Override
    public boolean isDataTransferStarted() {
      return delegate.isDataTransferStarted();
    }

    @Override
    public void sendAsdu(Asdu asdu) {
      if (failNextSend) {
        failNextSend = false;
        throw new IllegalStateException("injected send failure");
      }
      if (pauseExecute
          && asdu.objects().get(0) instanceof SingleCommand command
          && !command.qualifier().select()) {
        executeSendEntered.countDown();
        awaitLatch(releaseExecuteSend);
      }
      if (asdu.objects().get(0).address().equals(InformationObjectAddress.of(101))) {
        competingSendEntered.countDown();
      }
      try {
        // Interruptible only so a failing regression can unwind the blocked sender and release
        // its facade-side lock instead of leaving deadlocked test threads behind.
        lock.lockInterruptibly();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException(e);
      }
      try {
        delegate.sendAsdu(asdu);
      } finally {
        lock.unlock();
      }
    }

    @Override
    public boolean awaitSendCapacity(long timeoutMillis) {
      return true;
    }

    @Override
    public int pendingSendCount() {
      return delegate.pendingSendCount();
    }

    @Override
    public void close() {
      delegate.close();
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
