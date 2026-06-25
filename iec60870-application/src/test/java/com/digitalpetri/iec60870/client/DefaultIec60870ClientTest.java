package com.digitalpetri.iec60870.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.digitalpetri.iec60870.ConnectionClosedException;
import com.digitalpetri.iec60870.NegativeConfirmationException;
import com.digitalpetri.iec60870.ProtocolTimeoutException;
import com.digitalpetri.iec60870.RequestInProgressException;
import com.digitalpetri.iec60870.SequenceNumberException;
import com.digitalpetri.iec60870.address.CommonAddress;
import com.digitalpetri.iec60870.address.InformationObjectAddress;
import com.digitalpetri.iec60870.address.PointAddress;
import com.digitalpetri.iec60870.asdu.Asdu;
import com.digitalpetri.iec60870.asdu.AsduType;
import com.digitalpetri.iec60870.asdu.Cause;
import com.digitalpetri.iec60870.asdu.InformationObject;
import com.digitalpetri.iec60870.asdu.element.Qds;
import com.digitalpetri.iec60870.asdu.element.QualifierOfCommand;
import com.digitalpetri.iec60870.asdu.element.QualifierOfInterrogation;
import com.digitalpetri.iec60870.asdu.object.InterrogationCommand;
import com.digitalpetri.iec60870.asdu.object.MeasuredValueScaled;
import com.digitalpetri.iec60870.asdu.object.SingleCommand;
import com.digitalpetri.iec60870.client.ClientEvent.PointUpdated;
import com.digitalpetri.iec60870.fakes.FakeClientTransport;
import com.digitalpetri.iec60870.fakes.FakeSession;
import com.digitalpetri.iec60870.point.PointType;
import com.digitalpetri.iec60870.session.Session;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link DefaultIec60870Client} driven by a neutral fake {@link Session}. */
class DefaultIec60870ClientTest {

  private static final CommonAddress STATION = CommonAddress.of(1);

  private final FakeClientTransport transport = new FakeClientTransport();
  private final AtomicReference<FakeSession> sessionRef = new AtomicReference<>();
  private final ClientConfig config =
      ClientConfig.builder().callbackExecutor(Runnable::run).build();
  private final DefaultIec60870Client client =
      new DefaultIec60870Client(transport, config, clientSessionFactory(sessionRef));

  private FakeSession session() {
    FakeSession current = sessionRef.get();
    if (current == null) {
      throw new IllegalStateException("session not yet built");
    }
    return current;
  }

  /** A factory that builds a CLIENT-role fake session and records it for the test to drive. */
  private static BiFunction<Session.Events, ScheduledExecutorService, Session> clientSessionFactory(
      AtomicReference<FakeSession> ref) {
    return (events, scheduler) -> {
      FakeSession session = FakeSession.client(events);
      ref.set(session);
      return session;
    };
  }

  @AfterEach
  void tearDown() {
    client.close();
  }

  @Test
  void connectStartsDataTransfer() {
    client.connect();
    assertTrue(client.isConnected());
    assertTrue(session().isDataTransferStarted());
  }

  @Test
  void interrogateCollectsUntilTermination() {
    client.connect();

    CompletionStage<InterrogationResult> stage = client.interrogateAsync(STATION);

    // Positive activation confirmation.
    session().deliverAsdu(control(Cause.ACTIVATION_CONFIRMATION, false));
    // One monitor object reported as an interrogation response.
    session().deliverAsdu(measured(Cause.INTERROGATED_BY_STATION, (short) 42));
    // Activation termination ends the interrogation.
    session().deliverAsdu(control(Cause.ACTIVATION_TERMINATION, false));

    InterrogationResult result = stage.toCompletableFuture().join();
    assertEquals(STATION, result.station());
    assertTrue(result.terminated());
    assertEquals(1, result.objects().size());
    assertEquals(1, result.pointValues().size());
    assertEquals((short) 42, result.pointValues().get(0).value().value());
  }

  @Test
  void interrogateRejectedThrowsNegativeConfirmation() {
    client.connect();
    CompletionStage<InterrogationResult> stage = client.interrogateAsync(STATION);

    session().deliverAsdu(control(Cause.ACTIVATION_CONFIRMATION, true));

    var ex = assertThrows(CompletionException.class, () -> stage.toCompletableFuture().join());
    assertInstanceOf(NegativeConfirmationException.class, ex.getCause());
  }

  @Test
  void concurrentInterrogationOfSameStationIsRejected() {
    client.connect();

    // First interrogation is in flight, awaiting its confirmation and termination.
    CompletionStage<InterrogationResult> first = client.interrogateAsync(STATION);

    // A second interrogation of the same station cannot be told apart from the first on the wire,
    // so it is rejected rather than registered (IEC 60870-5-101 §7.4.5).
    CompletionStage<InterrogationResult> second = client.interrogateAsync(STATION);
    var ex = assertThrows(CompletionException.class, () -> second.toCompletableFuture().join());
    assertInstanceOf(RequestInProgressException.class, ex.getCause());

    // The rejection left the first request untouched: still pending, and no extra ASDU was sent.
    assertEquals(1, client.pendingRequestCount());
    assertEquals(1, session().sentAsdus().size(), "rejected interrogation must not send an ASDU");

    // The first interrogation still completes normally.
    session().deliverAsdu(control(Cause.ACTIVATION_CONFIRMATION, false));
    session().deliverAsdu(control(Cause.ACTIVATION_TERMINATION, false));
    assertTrue(first.toCompletableFuture().join().terminated());
  }

  @Test
  void blockingInterrogateRejectionThrowsTypedException() {
    client.connect();
    // Leave an interrogation in flight, then drive the blocking API against the same station.
    client.interrogateAsync(STATION);

    assertThrows(RequestInProgressException.class, () -> client.interrogate(STATION));
  }

  @Test
  void interrogationsOfDifferentStationsAreAllowed() {
    client.connect();

    // Different common addresses are distinguishable on the wire, so both are accepted in parallel.
    CompletionStage<InterrogationResult> first = client.interrogateAsync(STATION);
    CompletionStage<InterrogationResult> second = client.interrogateAsync(CommonAddress.of(2));

    assertFalse(first.toCompletableFuture().isDone());
    assertFalse(second.toCompletableFuture().isDone());
    assertEquals(2, client.pendingRequestCount());
    assertEquals(2, session().sentAsdus().size());
  }

  @Test
  void interrogationAllowedAgainAfterPreviousTerminates() {
    client.connect();

    CompletionStage<InterrogationResult> first = client.interrogateAsync(STATION);
    session().deliverAsdu(control(Cause.ACTIVATION_CONFIRMATION, false));
    session().deliverAsdu(control(Cause.ACTIVATION_TERMINATION, false));
    assertTrue(first.toCompletableFuture().join().terminated());
    assertEquals(0, client.pendingRequestCount());

    // The previous interrogation terminated, releasing the lock, so a new one is accepted.
    CompletionStage<InterrogationResult> second = client.interrogateAsync(STATION);
    assertFalse(second.toCompletableFuture().isDone());
    assertEquals(1, client.pendingRequestCount());
  }

  @Test
  void concurrentReadOfSamePointIsRejected() {
    client.connect();
    PointAddress point = new PointAddress(STATION, InformationObjectAddress.of(110));

    CompletionStage<List<InformationObject>> first = client.readAsync(point);
    CompletionStage<List<InformationObject>> second = client.readAsync(point);

    var ex = assertThrows(CompletionException.class, () -> second.toCompletableFuture().join());
    assertInstanceOf(RequestInProgressException.class, ex.getCause());
    assertEquals(1, client.pendingRequestCount());
    assertEquals(1, session().sentAsdus().size(), "rejected read must not send an ASDU");

    // The first read still completes normally.
    session().deliverAsdu(measured(Cause.REQUEST, (short) 42));
    assertEquals(1, first.toCompletableFuture().join().size());
  }

  @Test
  void readsOfDifferentPointsAreAllowed() {
    client.connect();

    // Distinct IOAs carry distinct keys, so parallel reads of different points are accepted.
    CompletionStage<List<InformationObject>> first =
        client.readAsync(new PointAddress(STATION, InformationObjectAddress.of(110)));
    CompletionStage<List<InformationObject>> second =
        client.readAsync(new PointAddress(STATION, InformationObjectAddress.of(111)));

    assertFalse(first.toCompletableFuture().isDone());
    assertFalse(second.toCompletableFuture().isDone());
    assertEquals(2, client.pendingRequestCount());
    assertEquals(2, session().sentAsdus().size());
  }

  @Test
  void readReturnsOnlyTheRequestedObject() {
    client.connect();
    PointAddress point = new PointAddress(STATION, InformationObjectAddress.of(110));

    CompletionStage<List<InformationObject>> stage = client.readAsync(point);

    // A response that happens to carry several objects must yield only the requested point.
    session()
        .deliverAsdu(
            new Asdu(
                AsduType.M_ME_NB_1,
                false,
                Cause.REQUEST,
                false,
                false,
                config.originatorAddress(),
                STATION,
                List.of(
                    new MeasuredValueScaled(InformationObjectAddress.of(110), (short) 1, goodQds()),
                    new MeasuredValueScaled(
                        InformationObjectAddress.of(111), (short) 2, goodQds()))));

    List<InformationObject> objects = stage.toCompletableFuture().join();
    assertEquals(1, objects.size());
    assertEquals(InformationObjectAddress.of(110), objects.get(0).address());
  }

  @Test
  void concurrentCommandToSamePointIsRejected() {
    client.connect();
    PointAddress point = new PointAddress(STATION, InformationObjectAddress.of(5000));

    CompletionStage<CommandResult> first =
        client.commands().sendAsync(Command.single(point, true), CommandMode.directExecute());
    CompletionStage<CommandResult> second =
        client.commands().sendAsync(Command.single(point, true), CommandMode.directExecute());

    var ex = assertThrows(CompletionException.class, () -> second.toCompletableFuture().join());
    assertInstanceOf(RequestInProgressException.class, ex.getCause());
    assertEquals(1, client.pendingRequestCount());

    session().deliverAsdu(commandConfirmation(point.objectAddress(), false));
    assertTrue(first.toCompletableFuture().join().positive());
  }

  @Test
  void commandsToDifferentPointsAreAllowed() {
    client.connect();

    client
        .commands()
        .sendAsync(
            Command.single(new PointAddress(STATION, InformationObjectAddress.of(5000)), true),
            CommandMode.directExecute());
    client
        .commands()
        .sendAsync(
            Command.single(new PointAddress(STATION, InformationObjectAddress.of(5001)), true),
            CommandMode.directExecute());

    assertEquals(2, client.pendingRequestCount());
    assertEquals(2, session().sentAsdus().size());
  }

  @Test
  void concurrentClockSyncOfSameStationIsRejected() {
    client.connect();
    Instant time = Instant.parse("2024-06-01T12:00:00Z");

    CompletionStage<Void> first = client.synchronizeClockAsync(STATION, time);
    CompletionStage<Void> second = client.synchronizeClockAsync(STATION, time);

    var ex = assertThrows(CompletionException.class, () -> second.toCompletableFuture().join());
    assertInstanceOf(RequestInProgressException.class, ex.getCause());
    assertEquals(1, client.pendingRequestCount());
    assertEquals(1, session().sentAsdus().size(), "rejected clock sync must not send an ASDU");
  }

  @Test
  void directCommandPositiveConfirmation() {
    client.connect();
    PointAddress point = new PointAddress(STATION, InformationObjectAddress.of(5000));

    CompletionStage<CommandResult> stage =
        client.commands().sendAsync(Command.single(point, true), CommandMode.directExecute());

    session().deliverAsdu(commandConfirmation(point.objectAddress(), false));

    CommandResult result = stage.toCompletableFuture().join();
    assertTrue(result.positive());
    assertEquals(point, result.target());
    assertEquals(Cause.ACTIVATION_CONFIRMATION, result.cause());

    // The client sent exactly one execute activation (S/E = 0).
    List<Asdu> commands = session().sentAsdus();
    assertEquals(1, commands.size());
    SingleCommand sent = (SingleCommand) commands.get(0).objects().get(0);
    assertTrue(sent.on());
    assertFalse(sent.qualifier().select());
  }

  @Test
  void directCommandNegativeConfirmationIsNotPositive() {
    client.connect();
    PointAddress point = new PointAddress(STATION, InformationObjectAddress.of(5000));

    CompletionStage<CommandResult> stage =
        client.commands().sendAsync(Command.single(point, true), CommandMode.directExecute());

    session().deliverAsdu(commandConfirmation(point.objectAddress(), true));

    CommandResult result = stage.toCompletableFuture().join();
    assertFalse(result.positive());
  }

  @Test
  void selectBeforeOperateSendsSelectThenExecute() {
    client.connect();
    PointAddress point = new PointAddress(STATION, InformationObjectAddress.of(5000));

    CompletionStage<CommandResult> stage =
        client.commands().sendAsync(Command.single(point, true), CommandMode.selectBeforeOperate());

    // Confirm the select phase; the execute phase is sent only after this confirmation.
    session().deliverAsdu(commandConfirmation(point.objectAddress(), false));
    // Confirm the execute phase.
    session().deliverAsdu(commandConfirmation(point.objectAddress(), false));

    CommandResult result = stage.toCompletableFuture().join();
    assertTrue(result.positive());

    List<Asdu> commands = session().sentAsdus();
    assertEquals(2, commands.size());
    assertTrue(((SingleCommand) commands.get(0).objects().get(0)).qualifier().select());
    assertFalse(((SingleCommand) commands.get(1).objects().get(0)).qualifier().select());
  }

  @Test
  void monitorAsduPublishesPointUpdatedAndAsduReceived() {
    client.connect();

    List<ClientEvent> events = new CopyOnWriteArrayList<>();
    subscribe(events);

    session().deliverAsdu(measured(Cause.SPONTANEOUS, (short) 7));

    boolean sawAsduReceived = events.stream().anyMatch(e -> e instanceof ClientEvent.AsduReceived);
    PointUpdated update =
        events.stream()
            .filter(e -> e instanceof PointUpdated)
            .map(e -> (PointUpdated) e)
            .findFirst()
            .orElseThrow();
    assertTrue(sawAsduReceived);
    assertEquals((short) 7, update.value().value());
    assertEquals(PointType.SCALED, update.value().type());
    assertEquals(AsduType.M_ME_NB_1, update.asduType());
    assertEquals(Cause.SPONTANEOUS, update.cause());
  }

  @Test
  void disconnectFailsPendingRequest() {
    client.connect();
    CompletionStage<InterrogationResult> stage = client.interrogateAsync(STATION);

    session().fireClosed(null);

    var ex = assertThrows(CompletionException.class, () -> stage.toCompletableFuture().join());
    assertSame(ConnectionClosedException.class, ex.getCause().getClass());
  }

  @Test
  void connectFailureIsTranslatedToTypedException() {
    FakeClientTransport failing = new FakeClientTransport();
    failing.failConnect(new IOException("refused"));
    AtomicReference<FakeSession> failingSession = new AtomicReference<>();
    try (DefaultIec60870Client failingClient =
        new DefaultIec60870Client(
            failing,
            ClientConfig.builder().callbackExecutor(Runnable::run).build(),
            clientSessionFactory(failingSession))) {
      var ex =
          assertThrows(
              CompletionException.class,
              () -> failingClient.connectAsync().toCompletableFuture().join());
      assertInstanceOf(IOException.class, ex.getCause());
      assertFalse(failingClient.isConnected());
    }
  }

  @Test
  void commandTimeoutCleansUpPendingRequest() {
    FakeClientTransport quietTransport = new FakeClientTransport();
    AtomicReference<FakeSession> quietSession = new AtomicReference<>();
    try (DefaultIec60870Client timingClient =
        new DefaultIec60870Client(
            quietTransport,
            ClientConfig.builder()
                .callbackExecutor(Runnable::run)
                .commandTimeout(Duration.ofMillis(50))
                .requestTimeout(Duration.ofMillis(50))
                .build(),
            clientSessionFactory(quietSession))) {
      timingClient.connect();
      PointAddress point = new PointAddress(STATION, InformationObjectAddress.of(5000));

      CompletionStage<CommandResult> stage =
          timingClient
              .commands()
              .sendAsync(Command.single(point, true), CommandMode.directExecute());

      // No confirmation is ever delivered; the command must time out and be removed.
      var ex = assertThrows(CompletionException.class, () -> stage.toCompletableFuture().join());
      assertInstanceOf(ProtocolTimeoutException.class, ex.getCause());

      assertEquals(0, timingClient.pendingRequestCount(), "timed-out request must not leak");

      // A late confirmation for the timed-out command is ignored without error.
      quietSession.get().deliverAsdu(commandConfirmation(point.objectAddress(), false));
      assertEquals(0, timingClient.pendingRequestCount());
    }
  }

  @Test
  void sendFailureFailsTheRequestAndDoesNotLeak() {
    FakeClientTransport failing = new FakeClientTransport();
    // A session whose sendAsdu throws models an immediate send failure.
    try (DefaultIec60870Client failingClient =
        new DefaultIec60870Client(
            failing,
            ClientConfig.builder().callbackExecutor(Runnable::run).build(),
            (events, scheduler) -> new ThrowingSendSession(FakeSession.client(events)))) {
      failingClient.connect();

      CompletionStage<InterrogationResult> stage = failingClient.interrogateAsync(STATION);

      assertThrows(CompletionException.class, () -> stage.toCompletableFuture().join());
      assertEquals(0, failingClient.pendingRequestCount(), "failed send must not leak a request");
    }
  }

  @Test
  void unmatchedResponseIsIgnored() {
    client.connect();

    List<ClientEvent> events = new CopyOnWriteArrayList<>();
    subscribe(events);

    CompletionStage<InterrogationResult> stage = client.interrogateAsync(STATION);

    // A confirmation for a different station does not correlate to the pending interrogation.
    session()
        .deliverAsdu(
            new Asdu(
                AsduType.C_IC_NA_1,
                false,
                Cause.ACTIVATION_CONFIRMATION,
                false,
                false,
                config.originatorAddress(),
                CommonAddress.of(99),
                List.of(
                    new InterrogationCommand(
                        InformationObjectAddress.of(0), QualifierOfInterrogation.STATION))));

    // The interrogation is still pending; the unmatched ASDU was surfaced only as AsduReceived.
    assertFalse(stage.toCompletableFuture().isDone());
    assertEquals(1, client.pendingRequestCount());
    assertTrue(events.stream().anyMatch(e -> e instanceof ClientEvent.AsduReceived));
    assertTrue(events.stream().noneMatch(e -> e instanceof ClientEvent.PointUpdated));

    // Completing it for the correct station still works, proving no corruption.
    session().deliverAsdu(control(Cause.ACTIVATION_CONFIRMATION, false));
    session().deliverAsdu(control(Cause.ACTIVATION_TERMINATION, false));
    assertTrue(stage.toCompletableFuture().join().terminated());
  }

  @Test
  void callbacksAreSerialized() throws Exception {
    // A single-thread executor preserves submission order and never runs two callbacks at once.
    ExecutorService executor = Executors.newSingleThreadExecutor();
    FakeClientTransport serialTransport = new FakeClientTransport();
    AtomicReference<FakeSession> serialSession = new AtomicReference<>();
    try (DefaultIec60870Client serialClient =
        new DefaultIec60870Client(
            serialTransport,
            ClientConfig.builder().callbackExecutor(executor).build(),
            clientSessionFactory(serialSession))) {
      serialClient.connect();

      int count = 200;
      CountDownLatch latch = new CountDownLatch(count);
      AtomicInteger concurrent = new AtomicInteger();
      AtomicInteger maxConcurrent = new AtomicInteger();
      List<ClientEvent> received = new ArrayList<>();

      serialClient
          .events()
          .subscribe(
              new Flow.Subscriber<>() {
                @Override
                public void onSubscribe(Flow.Subscription subscription) {
                  subscription.request(Long.MAX_VALUE);
                }

                @Override
                public void onNext(ClientEvent item) {
                  int now = concurrent.incrementAndGet();
                  maxConcurrent.accumulateAndGet(now, Math::max);
                  received.add(item); // safe only if callbacks never overlap
                  concurrent.decrementAndGet();
                  if (item instanceof ClientEvent.AsduReceived) {
                    latch.countDown();
                  }
                }

                @Override
                public void onError(Throwable throwable) {}

                @Override
                public void onComplete() {}
              });

      for (int i = 0; i < count; i++) {
        serialSession.get().deliverAsdu(measured(Cause.SPONTANEOUS, (short) i));
      }

      assertTrue(latch.await(5, TimeUnit.SECONDS), "all events delivered");
      assertEquals(1, maxConcurrent.get(), "callbacks must never overlap");
      // The unsynchronized list captured every counted event, proving callbacks never raced.
      assertEquals(
          count,
          received.stream().filter(e -> e instanceof ClientEvent.AsduReceived).count(),
          "every delivered ASDU event was recorded without corruption");
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void errorClosePublishesExactlyOneConnectionClosedWithRealCause() {
    client.connect();

    List<ClientEvent> events = new CopyOnWriteArrayList<>();
    subscribe(events);

    // Drive a fatal protocol-error close carrying the real cause, then a follow-on transport-loss
    // close: the facade publishes ConnectionClosed exactly once, with the first (real) cause.
    client.interrogateAsync(STATION);
    SequenceNumberException realCause = new SequenceNumberException("bad acknowledgement");
    session().fireClosed(realCause);
    // A second close is a no-op on an already-closed session.
    session().fireClosed(null);

    List<ClientEvent.ConnectionClosed> closes =
        events.stream()
            .filter(e -> e instanceof ClientEvent.ConnectionClosed)
            .map(e -> (ClientEvent.ConnectionClosed) e)
            .toList();
    assertEquals(
        1, closes.size(), "ConnectionClosed must be published exactly once on an error close");
    assertSame(realCause, closes.get(0).cause());
  }

  @Test
  void plainTransportDropPublishesExactlyOneConnectionClosed() {
    client.connect();

    List<ClientEvent> events = new CopyOnWriteArrayList<>();
    subscribe(events);

    session().fireConnectionLost(null);

    long count = events.stream().filter(e -> e instanceof ClientEvent.ConnectionClosed).count();
    assertEquals(1, count, "a plain transport drop publishes ConnectionClosed exactly once");
  }

  @Test
  void transportLossDoesNotDisconnectButProtocolErrorCloseDoes() {
    // A transport-level connection loss (peer drop, send failure) must NOT call
    // transport.disconnect:
    // doing so would fire Event.Disconnect on the persistent ChannelFsm and stop its
    // auto-reconnect.
    client.connect();
    assertEquals(0, transport.disconnectCount());

    session().fireConnectionLost(null);
    assertEquals(
        0,
        transport.disconnectCount(),
        "a transport-level connection loss must not disconnect the (reconnecting) transport");
    assertTrue(
        transport.isConnected(),
        "the transport is left connected so a persistent transport can auto-reconnect");

    // A self-initiated protocol-error/timeout close, by contrast, must tear the transport down so a
    // persistent transport stops reconnecting.
    FakeClientTransport errorTransport = new FakeClientTransport();
    AtomicReference<FakeSession> errorSession = new AtomicReference<>();
    try (DefaultIec60870Client errorClient =
        new DefaultIec60870Client(errorTransport, config, clientSessionFactory(errorSession))) {
      errorClient.connect();
      assertEquals(0, errorTransport.disconnectCount());
      errorSession.get().fireClosed(new SequenceNumberException("protocol error"));
      assertEquals(
          1,
          errorTransport.disconnectCount(),
          "a protocol-error self-close must disconnect the transport to stop reconnection");
    }
  }

  @Test
  void readIsNotCompletedBySpontaneousUpdateOnSameAddress() {
    client.connect();
    PointAddress point = new PointAddress(STATION, InformationObjectAddress.of(110));

    CompletionStage<List<InformationObject>> stage = client.readAsync(point);

    // A spontaneous update on the same CA+IOA must not complete the read (it carries COT
    // SPONTANEOUS, not REQUEST).
    session().deliverAsdu(measured(Cause.SPONTANEOUS, (short) 7));
    assertFalse(
        stage.toCompletableFuture().isDone(), "a SPONTANEOUS update must not complete a read");
    assertEquals(1, client.pendingRequestCount());

    // The actual read response (COT REQUEST) completes it.
    session().deliverAsdu(measured(Cause.REQUEST, (short) 42));
    List<InformationObject> objects = stage.toCompletableFuture().join();
    assertEquals(1, objects.size());
  }

  private void subscribe(List<ClientEvent> sink) {
    client
        .events()
        .subscribe(
            new Flow.Subscriber<>() {
              @Override
              public void onSubscribe(Flow.Subscription subscription) {
                subscription.request(Long.MAX_VALUE);
              }

              @Override
              public void onNext(ClientEvent item) {
                sink.add(item);
              }

              @Override
              public void onError(Throwable throwable) {}

              @Override
              public void onComplete() {}
            });
  }

  private Asdu control(Cause cause, boolean negative) {
    return new Asdu(
        AsduType.C_IC_NA_1,
        false,
        cause,
        negative,
        false,
        config.originatorAddress(),
        STATION,
        List.of(
            new InterrogationCommand(
                InformationObjectAddress.of(0), QualifierOfInterrogation.STATION)));
  }

  private Asdu measured(Cause cause, short value) {
    return new Asdu(
        AsduType.M_ME_NB_1,
        false,
        cause,
        false,
        false,
        config.originatorAddress(),
        STATION,
        List.of(new MeasuredValueScaled(InformationObjectAddress.of(110), value, goodQds())));
  }

  private Asdu commandConfirmation(InformationObjectAddress ioa, boolean negative) {
    return new Asdu(
        AsduType.C_SC_NA_1,
        false,
        Cause.ACTIVATION_CONFIRMATION,
        negative,
        false,
        config.originatorAddress(),
        STATION,
        List.of(new SingleCommand(ioa, true, new QualifierOfCommand(0, false))));
  }

  private static Qds goodQds() {
    return new Qds(false, false, false, false, false);
  }

  /** A {@link Session} whose {@link #sendAsdu(Asdu)} always throws, modeling a send failure. */
  private static final class ThrowingSendSession implements Session {

    private final FakeSession delegate;

    ThrowingSendSession(FakeSession delegate) {
      this.delegate = delegate;
    }

    @Override
    public void onConnected() {
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
      throw new IllegalStateException("write failed");
    }

    @Override
    public boolean awaitSendCapacity(long timeoutMillis) {
      return delegate.awaitSendCapacity(timeoutMillis);
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
}
