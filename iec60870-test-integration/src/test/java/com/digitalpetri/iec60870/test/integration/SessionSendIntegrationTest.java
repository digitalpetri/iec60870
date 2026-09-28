package com.digitalpetri.iec60870.test.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import com.digitalpetri.iec60870.asdu.element.QualifierOfCounterInterrogation;
import com.digitalpetri.iec60870.asdu.element.QualifierOfInterrogation;
import com.digitalpetri.iec60870.asdu.object.CounterInterrogationCommand;
import com.digitalpetri.iec60870.asdu.object.InterrogationCommand;
import com.digitalpetri.iec60870.asdu.object.ReadCommand;
import com.digitalpetri.iec60870.client.ClientConfig;
import com.digitalpetri.iec60870.client.DefaultIec60870Client;
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
import com.digitalpetri.iec60870.server.PointDefinition;
import com.digitalpetri.iec60870.server.ServerConfig;
import com.digitalpetri.iec60870.server.Station;
import com.digitalpetri.iec60870.test.common.ManualScheduler;
import com.digitalpetri.iec60870.test.common.RecordingClientTransport;
import com.digitalpetri.iec60870.test.common.RecordingServerConnection;
import com.digitalpetri.iec60870.transport.ServerTransport;
import com.digitalpetri.iec60870.transport.ServerTransportConnection;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.joou.UShort;
import org.junit.jupiter.api.Test;

class SessionSendIntegrationTest {
  private static final CommonAddress CA = CommonAddress.of(1);

  @Test
  void rawSendWaitsForWindowAndFailsOnClose() {
    var clock = new ManualScheduler();
    var ref = new AtomicReference<ApciSession>();
    var sent = new ArrayList<Apdu>();
    var config =
        ClientConfig.builder()
            .startDataTransferOnConnect(false)
            .callbackExecutor(Runnable::run)
            .build();
    try (var client =
        new DefaultIec60870Client(
            new RecordingClientTransport(),
            config,
            (events, scheduler) -> {
              var session =
                  new ApciSession(
                      ApciSession.Role.CLIENT,
                      ApciSettings.defaults(),
                      scheduler,
                      sent::add,
                      events);
              ref.set(session);
              return session;
            },
            clock)) {
      client.connect();
      for (int i = 0; i < 12; i++) {
        client.sendAsync(read());
      }
      var pending = client.sendAsync(read()).toCompletableFuture();
      assertFalse(pending.isDone(), "a full APCI window has not written the ASDU");
      assertEquals(1, ref.get().pendingSendCount());
      ref.get().onApdu(new Apdu(new ControlField.TypeS(1), null));
      assertTrue(pending.isDone());
      assertFalse(pending.isCompletedExceptionally());
      var abandoned = client.sendAsync(read()).toCompletableFuture();
      client.close();
      assertTrue(abandoned.isCompletedExceptionally());
    }
  }

  @Test
  void rawSendAfterCloseFails() {
    var config =
        ClientConfig.builder()
            .startDataTransferOnConnect(false)
            .callbackExecutor(Runnable::run)
            .build();
    try (var client =
        new DefaultIec60870Client(
            new RecordingClientTransport(),
            config,
            (events, scheduler) ->
                new ApciSession(
                    ApciSession.Role.CLIENT,
                    ApciSettings.defaults(),
                    scheduler,
                    ignored -> {},
                    events),
            new ManualScheduler())) {
      client.connect();
      client.close();
      assertTrue(client.sendAsync(read()).toCompletableFuture().isCompletedExceptionally());
    }
  }

  @Test
  void interrogationLargerThanQueueWritesEveryPointBeforeTermination() {
    verifyInterrogation(false);
  }

  @Test
  void counterInterrogationLargerThanQueueWritesEveryPointBeforeTermination() {
    verifyInterrogation(true);
  }

  @Test
  void directExecutorStreamsThousandsOfImmediateWritesWithoutRecursion() {
    verifyInterrogation(false, true, false, false);
  }

  @Test
  void failedInterrogationWriteClosesWithoutPositiveTermination() {
    verifyInterrogation(false, false, true, false);
  }

  @Test
  void saturatedEventQueueRetainsSolicitedResponse() {
    verifyInterrogation(false, false, false, true);
  }

  @Test
  void saturatedProtectedQueueClosesWithoutPositiveTermination() {
    verifyInterrogation(false, false, false, true, true);
  }

  private void verifyInterrogation(boolean counters) {
    verifyInterrogation(counters, false, false, false);
  }

  private void verifyInterrogation(
      boolean counters, boolean direct, boolean fail, boolean saturate) {
    verifyInterrogation(counters, direct, fail, saturate, false);
  }

  private void verifyInterrogation(
      boolean counters, boolean direct, boolean fail, boolean saturate, boolean protectedQueue) {
    var callbacks = new ArrayDeque<Runnable>();
    var transport = new AcceptTransport();
    var ref = new AtomicReference<ApciSession>();
    var sent = new ArrayList<Apdu>();
    var station = Station.builder(CA);
    for (int i = 1; i <= 2000; i++) {
      if (counters) {
        station.point(
            PointDefinition.of(
                PointAddress.of(1, i),
                PointType.INTEGRATED_TOTALS,
                PointValue.counter(new BinaryCounterReading(i, 0, false, false, false)),
                PointCapability.REPORTED));
      } else {
        station.point(
            PointDefinition.of(
                PointAddress.of(1, i),
                PointType.SINGLE_POINT,
                PointValue.single(true),
                PointCapability.REPORTED));
      }
    }
    var config =
        ServerConfig.builder()
            .station(station.build())
            .timeTagStyle(TimeTagStyle.NONE)
            .callbackExecutor(direct ? Runnable::run : callbacks::add)
            .build();
    ApciSettings defaults = ApciSettings.defaults();
    ApciSettings settings =
        direct
            ? new ApciSettings(
                UShort.valueOf(4096),
                defaults.w(),
                defaults.t0(),
                defaults.t1(),
                defaults.t2(),
                defaults.t3())
            : defaults;
    var failedWrite = new CompletableFuture<Void>();
    var output =
        new ApciSession.Output() {
          @Override
          public void send(Apdu apdu) {
            sent.add(apdu);
          }

          @Override
          public CompletionStage<Void> sendAsync(Apdu apdu) {
            if (fail
                && apdu.asdu() != null
                && apdu.asdu().cause() == Cause.INTERROGATED_BY_STATION) {
              return failedWrite;
            }
            send(apdu);
            return CompletableFuture.completedFuture(null);
          }
        };
    try (var server =
        new DefaultIec60870Server(
            transport,
            config,
            (connection, events, scheduler) -> {
              var session =
                  new ApciSession(
                      ApciSession.Role.SERVER,
                      settings,
                      scheduler,
                      output,
                      events,
                      config.maxOutboundQueue(),
                      config.eventQueuePolicy());
              ref.set(session);
              return session;
            },
            new ManualScheduler())) {
      server.start();
      transport.accept();
      var session = ref.get();
      session.onApdu(new Apdu(new ControlField.TypeU(UFunction.STARTDT_ACT), null));
      if (saturate) {
        for (int i = 0; i < 1012; i++) {
          if (protectedQueue) {
            session.sendAsduAsync(read());
          } else {
            session.sendAsdu(read());
          }
        }
      }
      InformationObject request =
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
                  CA,
                  List.of(request))));
      if (fail) {
        while (!callbacks.isEmpty()) {
          callbacks.remove().run();
        }
        server.publish(PointAddress.of(1, 2000), PointValue.single(false), Cause.SPONTANEOUS);
        failedWrite.completeExceptionally(new IllegalStateException("write failed"));
      }
      int acknowledged = 0;
      for (int turn = 0; turn < 3000; turn++) {
        while (!callbacks.isEmpty()) {
          callbacks.remove().run();
        }
        int written = (int) sent.stream().filter(a -> a.asdu() != null).count();
        if (written == acknowledged) {
          break;
        }
        acknowledged = written;
        session.onApdu(new Apdu(new ControlField.TypeS(written), null));
      }
      List<Asdu> replies = sent.stream().map(Apdu::asdu).filter(Objects::nonNull).toList();
      if (fail || protectedQueue) {
        assertFalse(replies.stream().anyMatch(a -> a.cause() == Cause.SPONTANEOUS));
        assertFalse(replies.stream().anyMatch(a -> a.cause() == Cause.ACTIVATION_TERMINATION));
        assertTrue(session.sendAsduAsync(read()).toCompletableFuture().isCompletedExceptionally());
        assertEquals(0, session.pendingSendCount());
        return;
      }
      replies = replies.stream().filter(a -> a.type() != AsduType.C_RD_NA_1).toList();
      assertEquals(Cause.ACTIVATION_CONFIRMATION, replies.get(0).cause());
      assertEquals(Cause.ACTIVATION_TERMINATION, replies.get(replies.size() - 1).cause());
      assertEquals(2002, replies.size(), "positive termination requires every requested point");
      assertEquals(
          2000,
          replies.subList(1, 2001).stream()
              .map(a -> a.objects().get(0).address())
              .distinct()
              .count());
    }
  }

  private static Asdu read() {
    return new Asdu(
        AsduType.C_RD_NA_1,
        false,
        Cause.REQUEST,
        false,
        false,
        OriginatorAddress.none(),
        CA,
        List.of(new ReadCommand(InformationObjectAddress.of(10))));
  }

  private static final class AcceptTransport implements ServerTransport {
    private Consumer<ServerTransportConnection> onAccept = ignored -> {};

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
      onAccept = handler;
    }

    void accept() {
      onAccept.accept(new RecordingServerConnection());
    }
  }
}
