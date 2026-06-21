package com.digitalpetri.iec104.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.digitalpetri.iec104.ConnectionClosedException;
import com.digitalpetri.iec104.NegativeConfirmationException;
import com.digitalpetri.iec104.ProtocolTimeoutException;
import com.digitalpetri.iec104.address.CommonAddress;
import com.digitalpetri.iec104.address.InformationObjectAddress;
import com.digitalpetri.iec104.address.PointAddress;
import com.digitalpetri.iec104.asdu.Asdu;
import com.digitalpetri.iec104.asdu.AsduType;
import com.digitalpetri.iec104.asdu.Cause;
import com.digitalpetri.iec104.asdu.element.Qds;
import com.digitalpetri.iec104.asdu.element.QualifierOfCommand;
import com.digitalpetri.iec104.asdu.element.QualifierOfInterrogation;
import com.digitalpetri.iec104.asdu.object.InterrogationCommand;
import com.digitalpetri.iec104.asdu.object.MeasuredValueScaled;
import com.digitalpetri.iec104.asdu.object.SingleCommand;
import com.digitalpetri.iec104.client.ClientEvent.PointUpdated;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link DefaultIec104Client} driven by a {@link FakeClientTransport}. */
class DefaultIec104ClientTest {

  private static final CommonAddress STATION = CommonAddress.of(1);

  private final FakeClientTransport transport = new FakeClientTransport();
  private final ClientConfig config =
      ClientConfig.builder().callbackExecutor(Runnable::run).build();
  private final DefaultIec104Client client = new DefaultIec104Client(transport, config);

  @AfterEach
  void tearDown() {
    client.close();
  }

  @Test
  void connectStartsDataTransfer() {
    client.connect();
    assertTrue(client.isConnected());
  }

  @Test
  void interrogateCollectsUntilTermination() {
    client.connect();

    CompletionStage<InterrogationResult> stage = client.interrogateAsync(STATION);

    // Positive activation confirmation.
    transport.deliverAsdu(control(Cause.ACTIVATION_CONFIRMATION, false));
    // One monitor object reported as an interrogation response.
    transport.deliverAsdu(measured(Cause.INTERROGATED_BY_STATION, (short) 42));
    // Activation termination ends the interrogation.
    transport.deliverAsdu(control(Cause.ACTIVATION_TERMINATION, false));

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

    transport.deliverAsdu(control(Cause.ACTIVATION_CONFIRMATION, true));

    var ex = assertThrows(CompletionException.class, () -> stage.toCompletableFuture().join());
    assertInstanceOf(NegativeConfirmationException.class, ex.getCause());
  }

  @Test
  void directCommandPositiveConfirmation() {
    client.connect();
    PointAddress point = new PointAddress(STATION, InformationObjectAddress.of(5000));

    CompletionStage<CommandResult> stage =
        client.commands().sendAsync(Command.single(point, true), CommandMode.directExecute());

    transport.deliverAsdu(commandConfirmation(point.objectAddress(), false));

    CommandResult result = stage.toCompletableFuture().join();
    assertTrue(result.positive());
    assertEquals(point, result.target());
    assertEquals(Cause.ACTIVATION_CONFIRMATION, result.cause());

    // The client sent exactly one execute activation (S/E = 0).
    List<Asdu> commands = transport.sentAsdus();
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

    transport.deliverAsdu(commandConfirmation(point.objectAddress(), true));

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
    transport.deliverAsdu(commandConfirmation(point.objectAddress(), false));
    // Confirm the execute phase.
    transport.deliverAsdu(commandConfirmation(point.objectAddress(), false));

    CommandResult result = stage.toCompletableFuture().join();
    assertTrue(result.positive());

    List<Asdu> commands = transport.sentAsdus();
    assertEquals(2, commands.size());
    assertTrue(((SingleCommand) commands.get(0).objects().get(0)).qualifier().select());
    assertFalse(((SingleCommand) commands.get(1).objects().get(0)).qualifier().select());
  }

  @Test
  void monitorAsduPublishesPointUpdatedAndAsduReceived() {
    client.connect();

    List<ClientEvent> events = new CopyOnWriteArrayList<>();
    subscribe(events);

    transport.deliverAsdu(measured(Cause.SPONTANEOUS, (short) 7));

    boolean sawAsduReceived = events.stream().anyMatch(e -> e instanceof ClientEvent.AsduReceived);
    PointUpdated update =
        events.stream()
            .filter(e -> e instanceof PointUpdated)
            .map(e -> (PointUpdated) e)
            .findFirst()
            .orElseThrow();
    assertTrue(sawAsduReceived);
    assertEquals((short) 7, update.value().value());
    assertEquals(Cause.SPONTANEOUS, update.cause());
  }

  @Test
  void disconnectFailsPendingRequest() {
    client.connect();
    CompletionStage<InterrogationResult> stage = client.interrogateAsync(STATION);

    transport.loseConnection();

    var ex = assertThrows(CompletionException.class, () -> stage.toCompletableFuture().join());
    assertSame(ConnectionClosedException.class, ex.getCause().getClass());
  }

  @Test
  void connectFailureIsTranslatedToTypedException() {
    FakeClientTransport failing = new FakeClientTransport();
    failing.failConnect(new IOException("refused"));
    try (DefaultIec104Client failingClient =
        new DefaultIec104Client(
            failing, ClientConfig.builder().callbackExecutor(Runnable::run).build())) {
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
    try (DefaultIec104Client timingClient =
        new DefaultIec104Client(
            quietTransport,
            ClientConfig.builder()
                .callbackExecutor(Runnable::run)
                .commandTimeout(Duration.ofMillis(50))
                .requestTimeout(Duration.ofMillis(50))
                .build())) {
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
      quietTransport.deliverAsdu(commandConfirmation(point.objectAddress(), false));
      assertEquals(0, timingClient.pendingRequestCount());
    }
  }

  @Test
  void sendFailureFailsTheRequestAndDoesNotLeak() {
    FakeClientTransport failing = new FakeClientTransport();
    try (DefaultIec104Client failingClient =
        new DefaultIec104Client(
            failing, ClientConfig.builder().callbackExecutor(Runnable::run).build())) {
      failingClient.connect();
      // From now on every send fails; the in-flight interrogation must fail too.
      failing.failSend(new IOException("write failed"));

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
    transport.deliverAsdu(
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
    transport.deliverAsdu(control(Cause.ACTIVATION_CONFIRMATION, false));
    transport.deliverAsdu(control(Cause.ACTIVATION_TERMINATION, false));
    assertTrue(stage.toCompletableFuture().join().terminated());
  }

  @Test
  void callbacksAreSerialized() throws Exception {
    // A single-thread executor preserves submission order and never runs two callbacks at once.
    ExecutorService executor = Executors.newSingleThreadExecutor();
    FakeClientTransport serialTransport = new FakeClientTransport();
    try (DefaultIec104Client serialClient =
        new DefaultIec104Client(
            serialTransport, ClientConfig.builder().callbackExecutor(executor).build())) {
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
        serialTransport.deliverAsdu(measured(Cause.SPONTANEOUS, (short) i));
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
}
