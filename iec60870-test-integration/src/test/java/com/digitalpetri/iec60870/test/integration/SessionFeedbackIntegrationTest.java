package com.digitalpetri.iec60870.test.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.digitalpetri.iec60870.NegativeConfirmationException;
import com.digitalpetri.iec60870.address.CommonAddress;
import com.digitalpetri.iec60870.address.InformationObjectAddress;
import com.digitalpetri.iec60870.address.OriginatorAddress;
import com.digitalpetri.iec60870.address.PointAddress;
import com.digitalpetri.iec60870.asdu.Asdu;
import com.digitalpetri.iec60870.asdu.AsduType;
import com.digitalpetri.iec60870.asdu.Cause;
import com.digitalpetri.iec60870.asdu.InformationObject;
import com.digitalpetri.iec60870.asdu.element.BinaryCounterReading;
import com.digitalpetri.iec60870.asdu.element.FreezeMode;
import com.digitalpetri.iec60870.asdu.element.Qds;
import com.digitalpetri.iec60870.asdu.element.QualifierOfCounterInterrogation;
import com.digitalpetri.iec60870.asdu.element.QualifierOfInterrogation;
import com.digitalpetri.iec60870.asdu.object.CounterInterrogationCommand;
import com.digitalpetri.iec60870.asdu.object.IntegratedTotals;
import com.digitalpetri.iec60870.asdu.object.InterrogationCommand;
import com.digitalpetri.iec60870.asdu.object.ReadCommand;
import com.digitalpetri.iec60870.asdu.object.SinglePointInformation;
import com.digitalpetri.iec60870.client.ClientConfig;
import com.digitalpetri.iec60870.client.DefaultIec60870Client;
import com.digitalpetri.iec60870.cs101.Ft12Frame;
import com.digitalpetri.iec60870.cs101.Ft12LinkLayer;
import com.digitalpetri.iec60870.cs101.LinkControlField;
import com.digitalpetri.iec60870.cs101.LinkSettings;
import com.digitalpetri.iec60870.cs104.ApciSession;
import com.digitalpetri.iec60870.cs104.ApciSettings;
import com.digitalpetri.iec60870.cs104.Apdu;
import com.digitalpetri.iec60870.cs104.ControlField;
import com.digitalpetri.iec60870.cs104.UFunction;
import com.digitalpetri.iec60870.point.PointCapability;
import com.digitalpetri.iec60870.point.PointType;
import com.digitalpetri.iec60870.point.PointValue;
import com.digitalpetri.iec60870.point.TimeTagStyle;
import com.digitalpetri.iec60870.server.DefaultIec60870Server;
import com.digitalpetri.iec60870.server.InterrogationRequest;
import com.digitalpetri.iec60870.server.InterrogationResponse;
import com.digitalpetri.iec60870.server.PointDefinition;
import com.digitalpetri.iec60870.server.ServerConfig;
import com.digitalpetri.iec60870.server.ServerContext;
import com.digitalpetri.iec60870.server.ServerHandler;
import com.digitalpetri.iec60870.server.Station;
import com.digitalpetri.iec60870.test.common.ManualScheduler;
import com.digitalpetri.iec60870.test.common.RecordingClientTransport;
import com.digitalpetri.iec60870.test.common.RecordingServerConnection;
import com.digitalpetri.iec60870.transport.ServerTransport;
import com.digitalpetri.iec60870.transport.ServerTransportConnection;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class SessionFeedbackIntegrationTest {
  @Test
  void publicationCannotBeOvertakenByAnUnsentSnapshotPoint() {
    verifyPublicationOrder(false);
    verifyPublicationOrder(true);
  }

  private void verifyPublicationOrder(boolean counters) {
    try (var harness = new ServerHarness(counters, new ServerHandler() {})) {
      harness.request(counters);
      harness.callbacks.runAll();
      harness.server.publish(
          PointAddress.of(1, 20),
          counters ? counter(99) : PointValue.single(false),
          Cause.SPONTANEOUS);
      harness.drain();
      List<Asdu> point =
          harness.asdus().stream()
              .filter(a -> a.objects().get(0).address().value().intValue() == 20)
              .toList();
      assertEquals(2, point.size());
      assertEquals(
          Cause.SPONTANEOUS, point.get(1).cause(), "the newest event must follow the old snapshot");
      if (counters) {
        assertEquals(99, ((IntegratedTotals) point.get(1).objects().get(0)).counter().value());
      } else {
        assertFalse(((SinglePointInformation) point.get(1).objects().get(0)).on());
      }
    }
  }

  @Test
  void customAsyncInterrogationKeepsItsObjectsAndReleasesLaterPublications() {
    var response = new CompletableFuture<InterrogationResponse>();
    ServerHandler handler =
        new ServerHandler() {
          @Override
          public CompletionStage<InterrogationResponse> onInterrogationAsync(
              ServerContext context, InterrogationRequest request) {
            return response;
          }
        };
    try (var harness = new ServerHarness(false, handler)) {
      harness.request(false);
      harness.callbacks.runAll();
      harness.server.publish(PointAddress.of(1, 20), PointValue.single(false), Cause.SPONTANEOUS);
      response.complete(
          InterrogationResponse.of(
              List.of(
                  new SinglePointInformation(
                      InformationObjectAddress.of(20),
                      true,
                      new Qds(false, false, false, false, false)))));
      harness.drain();
      List<Asdu> point =
          harness.asdus().stream()
              .filter(a -> a.objects().get(0).address().value().intValue() == 20)
              .toList();
      assertEquals(2, point.size());
      assertEquals(Cause.INTERROGATED_BY_STATION, point.get(0).cause());
      assertTrue(((SinglePointInformation) point.get(0).objects().get(0)).on());
      assertEquals(Cause.SPONTANEOUS, point.get(1).cause());
      assertFalse(((SinglePointInformation) point.get(1).objects().get(0)).on());
    }
  }

  @Test
  void rejectedOrFailedAsyncInterrogationReleasesPublications() {
    for (boolean failed : List.of(false, true)) {
      var response = new CompletableFuture<InterrogationResponse>();
      ServerHandler handler =
          new ServerHandler() {
            @Override
            public CompletionStage<InterrogationResponse> onInterrogationAsync(
                ServerContext context, InterrogationRequest request) {
              return response;
            }
          };
      try (var harness = new ServerHarness(false, handler)) {
        harness.request(false);
        harness.callbacks.runAll();
        harness.server.publish(PointAddress.of(1, 20), PointValue.single(false), Cause.SPONTANEOUS);
        assertTrue(harness.asdus().isEmpty(), "publication must wait for the pending handler");
        if (failed) response.completeExceptionally(new IllegalStateException("handler failed"));
        else response.complete(InterrogationResponse.reject(Cause.UNKNOWN_CAUSE));
        harness.drain();
        List<Asdu> events =
            harness.asdus().stream().filter(a -> a.cause() == Cause.SPONTANEOUS).toList();
        assertEquals(1, events.size());
        assertFalse(((SinglePointInformation) events.get(0).objects().get(0)).on());
      }
    }
  }

  @Test
  void readReplyReplacesAnEventInASaturatedDefaultQueue() {
    try (var harness = new ServerHarness(false, new ServerHandler() {})) {
      for (int i = 0; i < 1012; i++)
        harness.server.publish(PointAddress.of(1, 20), PointValue.single(false), Cause.SPONTANEOUS);
      assertEquals(1000, harness.session.pendingSendCount());
      harness.session.onApdu(new Apdu(new ControlField.TypeI(0, 0), read(1)));
      harness.callbacks.runAll();
      assertTrue(harness.session.isDataTransferStarted());
      assertEquals(1000, harness.session.pendingSendCount());
      harness.drain();
      assertEquals(1, harness.asdus().stream().filter(a -> a.cause() == Cause.REQUEST).count());
    }
  }

  @Test
  void rawSendCompletesOnCallbackExecutor() throws Exception {
    verifyCompletionThread(false);
    verifyCompletionThread(true);
  }

  private void verifyCompletionThread(boolean fail) throws Exception {
    ExecutorService callbacks =
        Executors.newSingleThreadExecutor(r -> new Thread(r, "callback-executor"));
    var ref = new AtomicReference<ApciSession>();
    try (var client =
        new DefaultIec60870Client(
            new RecordingClientTransport(),
            ClientConfig.builder()
                .callbackExecutor(callbacks)
                .startDataTransferOnConnect(false)
                .build(),
            (events, scheduler) -> {
              var session =
                  new ApciSession(
                      ApciSession.Role.CLIENT,
                      ApciSettings.defaults(),
                      scheduler,
                      ignored -> {},
                      events);
              ref.set(session);
              return session;
            },
            new ManualScheduler())) {
      client.connect();
      for (int i = 0; i < 12; i++) client.sendAsync(read(1));
      CompletionStage<String> completionThread =
          client
              .sendAsync(read(1))
              .handle(
                  (ignored, error) -> {
                    assertEquals(fail, error != null);
                    return Thread.currentThread().getName();
                  });
      var io =
          new Thread(
              () -> {
                if (fail) ref.get().close();
                else ref.get().onApdu(new Apdu(new ControlField.TypeS(1), null));
              },
              "fake-io-thread");
      io.start();
      io.join(5000);
      assertFalse(io.isAlive());
      assertEquals(
          "callback-executor", completionThread.toCompletableFuture().get(5, TimeUnit.SECONDS));
    } finally {
      callbacks.shutdownNow();
    }
  }

  @Test
  void queuedUnknownSlaveReadKeepsItsNegativeConfirmation() {
    verifyUnknownSlave(false);
    verifyUnknownSlave(true);
  }

  private void verifyUnknownSlave(boolean reverseCallbacks) {
    var callbacks = new ArrayDeque<Runnable>();
    var ref = new AtomicReference<Ft12LinkLayer>();
    try (var client =
        new DefaultIec60870Client(
            new RecordingClientTransport(),
            ClientConfig.builder()
                .callbackExecutor(reverseCallbacks ? callbacks::addLast : Runnable::run)
                .startDataTransferOnConnect(false)
                .build(),
            (events, scheduler) -> {
              var session =
                  new Ft12LinkLayer(
                      Ft12LinkLayer.Role.CLIENT,
                      LinkSettings.unbalanced().slaveAddresses(List.of(1, 2)).build(),
                      scheduler,
                      ignored -> {},
                      events);
              ref.set(session);
              return session;
            },
            new ManualScheduler())) {
      client.connect();
      client.startDataTransferAsync();
      Ft12LinkLayer session = ref.get();
      for (int address : List.of(1, 2)) {
        session.onFrame(secondary(11, address));
        session.onFrame(secondary(0, address));
      }
      session.sendAsdu(read(2));
      CompletionStage<List<InformationObject>> result = client.readAsync(PointAddress.of(99, 10));
      assertFalse(result.toCompletableFuture().isDone());
      session.onFrame(secondary(0, 2));
      while (!callbacks.isEmpty()) {
        callbacks.removeLast().run();
      }
      CompletionException error =
          assertThrows(CompletionException.class, () -> result.toCompletableFuture().join());
      NegativeConfirmationException negative =
          assertInstanceOf(NegativeConfirmationException.class, error.getCause());
      assertEquals(Cause.UNKNOWN_COMMON_ADDRESS, negative.cause());
    }
  }

  private static Ft12Frame secondary(int function, int address) {
    return new Ft12Frame.FixedLength(
        LinkControlField.secondary(false, false, false, function), address);
  }

  private static Asdu read(int station) {
    return new Asdu(
        AsduType.C_RD_NA_1,
        false,
        Cause.REQUEST,
        false,
        false,
        OriginatorAddress.none(),
        CommonAddress.of(station),
        List.of(new ReadCommand(InformationObjectAddress.of(10))));
  }

  private static PointValue<BinaryCounterReading> counter(int value) {
    return PointValue.counter(new BinaryCounterReading(value, 0, false, false, false));
  }

  private static final class Callbacks implements Executor {
    private final Deque<Runnable> tasks = new ArrayDeque<>();

    @Override
    public void execute(Runnable task) {
      tasks.addLast(task);
    }

    void runAll() {
      while (!tasks.isEmpty()) tasks.removeFirst().run();
    }
  }

  private static final class ServerHarness implements AutoCloseable {
    final Callbacks callbacks = new Callbacks();
    final List<Apdu> sent = new ArrayList<>();
    final DefaultIec60870Server server;
    final ApciSession session;

    ServerHarness(boolean counters, ServerHandler handler) {
      var station = Station.builder(CommonAddress.of(1));
      for (int i = 1; i <= 20; i++) {
        if (counters)
          station.point(
              PointDefinition.of(
                  PointAddress.of(1, i),
                  PointType.INTEGRATED_TOTALS,
                  counter(i),
                  PointCapability.REPORTED,
                  PointCapability.READABLE));
        else
          station.point(
              PointDefinition.of(
                  PointAddress.of(1, i),
                  PointType.SINGLE_POINT,
                  PointValue.single(true),
                  PointCapability.REPORTED,
                  PointCapability.READABLE));
      }
      var transport = new AcceptTransport();
      var ref = new AtomicReference<ApciSession>();
      var config =
          ServerConfig.builder()
              .station(station.build())
              .handler(handler)
              .timeTagStyle(TimeTagStyle.NONE)
              .callbackExecutor(callbacks)
              .build();
      server =
          new DefaultIec60870Server(
              transport,
              config,
              (connection, events, scheduler) -> {
                var session =
                    new ApciSession(
                        ApciSession.Role.SERVER,
                        ApciSettings.defaults(),
                        scheduler,
                        sent::add,
                        events,
                        config.maxOutboundQueue(),
                        config.eventQueuePolicy());
                ref.set(session);
                return session;
              },
              new ManualScheduler());
      server.start();
      transport.accept();
      session = ref.get();
      session.onApdu(new Apdu(new ControlField.TypeU(UFunction.STARTDT_ACT), null));
    }

    void request(boolean counters) {
      InformationObject object =
          counters
              ? new CounterInterrogationCommand(
                  InformationObjectAddress.of(0),
                  new QualifierOfCounterInterrogation(5, FreezeMode.READ))
              : new InterrogationCommand(
                  InformationObjectAddress.of(0), QualifierOfInterrogation.STATION);
      session.onApdu(
          new Apdu(
              new ControlField.TypeI(0, 0),
              new Asdu(
                  counters ? AsduType.C_CI_NA_1 : AsduType.C_IC_NA_1,
                  false,
                  Cause.ACTIVATION,
                  false,
                  false,
                  OriginatorAddress.none(),
                  CommonAddress.of(1),
                  List.of(object))));
    }

    List<Asdu> asdus() {
      return sent.stream().map(Apdu::asdu).filter(Objects::nonNull).toList();
    }

    void drain() {
      int ack = 0;
      for (int turn = 0; turn < 300; turn++) {
        callbacks.runAll();
        int written = asdus().size();
        if (written == ack) return;
        ack = written;
        session.onApdu(new Apdu(new ControlField.TypeS(ack), null));
      }
      fail("response did not drain");
    }

    @Override
    public void close() {
      server.close();
    }
  }

  private static final class AcceptTransport implements ServerTransport {
    private Consumer<ServerTransportConnection> handler = ignored -> {};

    @Override
    public CompletionStage<Void> bind() {
      return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletionStage<Void> unbind() {
      return CompletableFuture.completedFuture(null);
    }

    @Override
    public void setConnectionHandler(Consumer<ServerTransportConnection> handler) {
      this.handler = handler;
    }

    void accept() {
      handler.accept(new RecordingServerConnection());
    }
  }
}
