package com.digitalpetri.iec60870.client;

import static java.util.Objects.requireNonNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.digitalpetri.iec60870.ProtocolTimeoutException;
import com.digitalpetri.iec60870.address.CommonAddress;
import com.digitalpetri.iec60870.address.InformationObjectAddress;
import com.digitalpetri.iec60870.address.OriginatorAddress;
import com.digitalpetri.iec60870.address.PointAddress;
import com.digitalpetri.iec60870.asdu.Asdu;
import com.digitalpetri.iec60870.asdu.AsduType;
import com.digitalpetri.iec60870.asdu.Cause;
import com.digitalpetri.iec60870.asdu.InformationObject;
import com.digitalpetri.iec60870.asdu.element.DoubleCommandState;
import com.digitalpetri.iec60870.asdu.element.NormalizedValue;
import com.digitalpetri.iec60870.asdu.element.Qds;
import com.digitalpetri.iec60870.asdu.element.QualifierOfCommand;
import com.digitalpetri.iec60870.asdu.element.StepCommandState;
import com.digitalpetri.iec60870.asdu.object.Bitstring32CommandWithCp56Time;
import com.digitalpetri.iec60870.asdu.object.ClockSynchronizationCommand;
import com.digitalpetri.iec60870.asdu.object.DoubleCommandWithCp56Time;
import com.digitalpetri.iec60870.asdu.object.RegulatingStepCommandWithCp56Time;
import com.digitalpetri.iec60870.asdu.object.SetpointNormalizedWithCp56Time;
import com.digitalpetri.iec60870.asdu.object.SetpointScaledWithCp56Time;
import com.digitalpetri.iec60870.asdu.object.SetpointShortFloatWithCp56Time;
import com.digitalpetri.iec60870.asdu.object.SingleCommandWithCp56Time;
import com.digitalpetri.iec60870.asdu.object.SinglePointWithCp24Time;
import com.digitalpetri.iec60870.asdu.object.SinglePointWithCp56Time;
import com.digitalpetri.iec60870.asdu.time.Cp24Time2a;
import com.digitalpetri.iec60870.asdu.time.Cp56Time2a;
import com.digitalpetri.iec60870.asdu.time.Cp56TimeTagged;
import com.digitalpetri.iec60870.fakes.FakeClientTransport;
import com.digitalpetri.iec60870.fakes.FakeSession;
import com.digitalpetri.iec60870.point.TimePolicy;
import com.digitalpetri.iec60870.test.common.ManualScheduler;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

/**
 * Time-tag handling in the client: resolution of received monitor tags, encoding of command and
 * clock-sync tags, and time-tag matching of command echoes.
 */
class ClientTimeTagTest {

  private static final PointAddress POINT =
      new PointAddress(CommonAddress.of(1), InformationObjectAddress.of(100));
  private static final Instant TIME = Instant.parse("2026-06-01T12:00:00Z");
  private static final Qds GOOD = new Qds(false, false, false, false, false);
  private static final TimePolicy BERLIN = TimePolicy.of(ZoneId.of("Europe/Berlin"));

  /** Echo time-tag changes that carry no identity. */
  private static final Map<String, UnaryOperator<Cp56Time2a>> IRRELEVANT_CHANGES =
      Map.of(
          "dayOfWeek", t -> copy(t, 0, t.invalid(), t.summerTime(), t.genuine()),
          "invalid", t -> copy(t, t.dayOfWeek(), !t.invalid(), t.summerTime(), t.genuine()),
          "summerTime", t -> copy(t, t.dayOfWeek(), t.invalid(), !t.summerTime(), t.genuine()),
          "genuine", t -> copy(t, t.dayOfWeek(), t.invalid(), t.summerTime(), !t.genuine()));

  @Test
  void pointUpdatedCarriesTimeTagResolvedByPolicy() {
    try (Harness h = new Harness(b -> b.timePolicy(BERLIN))) {
      List<ClientEvent> events = new CopyOnWriteArrayList<>();
      h.subscribe(events);
      var local = new Cp56Time2a(0, 0, 14, 1, 1, 6, 26, false, true, true);
      var invalid = new Cp56Time2a(0, 0, 14, 1, 1, 6, 26, true, true, true);

      h.session()
          .deliverAsdu(
              new Asdu(
                  AsduType.M_SP_TB_1,
                  false,
                  Cause.SPONTANEOUS,
                  false,
                  false,
                  OriginatorAddress.none(),
                  POINT.commonAddress(),
                  List.of(
                      new SinglePointWithCp56Time(POINT.objectAddress(), true, GOOD, local),
                      new SinglePointWithCp56Time(POINT.objectAddress(), true, GOOD, invalid))));

      List<ClientEvent.PointUpdated> updates =
          events.stream()
              .filter(e -> e instanceof ClientEvent.PointUpdated)
              .map(e -> (ClientEvent.PointUpdated) e)
              .toList();
      assertEquals(2, updates.size());

      ClientEvent.PointUpdated good = updates.get(0);
      assertEquals(Optional.of(TIME), good.timestamp());
      assertEquals(Optional.of(TIME), good.value().timestamp());
      assertEquals(local, good.timeTag().orElseThrow().cp56());
      assertTrue(good.timeTag().orElseThrow().summerTime());

      ClientEvent.PointUpdated flagged = updates.get(1);
      assertEquals(Optional.of(TIME), flagged.timeTag().orElseThrow().instant());
      assertTrue(flagged.timeTag().orElseThrow().invalid());
      assertEquals(Optional.empty(), flagged.timestamp());
      assertEquals(Optional.empty(), flagged.value().timestamp());
    }
  }

  @Test
  void interrogationPointValuesUsePolicyWithoutCp24Completion() {
    var local = new Cp56Time2a(0, 0, 14, 1, 1, 6, 26, false, true, true);
    var result =
        new InterrogationResult(
            POINT.commonAddress(),
            List.of(
                new SinglePointWithCp56Time(POINT.objectAddress(), true, GOOD, local),
                new SinglePointWithCp24Time(
                    POINT.objectAddress(), true, GOOD, new Cp24Time2a(0, 3, false, true))),
            true);

    List<InterrogationResult.PointEntry> entries =
        result.pointValues(BERLIN.withCompleteCp24(true));

    assertEquals(Optional.of(TIME), entries.get(0).value().timestamp());
    assertEquals(Optional.empty(), entries.get(1).value().timestamp());
    assertEquals(
        Optional.of(Instant.parse("2026-06-01T14:00:00Z")),
        result.pointValues().get(0).value().timestamp());
  }

  @Test
  void commandTimeTagUsesPolicyZone() {
    try (Harness h = new Harness(b -> b.timePolicy(BERLIN))) {
      h.send(new Command.SingleCommandRequest(POINT, true, 0, Optional.of(TIME)), false);

      Cp56Time2a time = timeOf(h.sent(0));
      assertEquals(new Cp56Time2a(0, 0, 14, 1, 1, 6, 26, false, true, true), time);
    }
  }

  @Test
  void commandTimeTagOmitsDayOfWeekWhenDisabled() {
    try (Harness h = new Harness(b -> b.timePolicy(BERLIN.withSendDayOfWeek(false)))) {
      h.send(new Command.SingleCommandRequest(POINT, true, 0, Optional.of(TIME)), false);

      assertEquals(0, timeOf(h.sent(0)).dayOfWeek());
    }
  }

  @TestFactory
  Stream<DynamicTest> defaultCommandTimeTagIsUtc() {
    return commands()
        .map(
            command ->
                DynamicTest.dynamicTest(
                    command.getClass().getSimpleName(),
                    () -> {
                      try (Harness h = new Harness(b -> {})) {
                        h.send(command, false);
                        assertEquals(Cp56Time2a.from(TIME, ZoneOffset.UTC), timeOf(h.sent(0)));
                      }
                    }));
  }

  @TestFactory
  Stream<DynamicTest> berlinPolicyEncodesEveryCommandType() {
    return commands()
        .map(
            command ->
                DynamicTest.dynamicTest(
                    command.getClass().getSimpleName(),
                    () -> {
                      try (Harness h = new Harness(b -> b.timePolicy(BERLIN))) {
                        h.send(command, false);
                        Cp56Time2a time = timeOf(h.sent(0));
                        assertEquals(14, time.hour());
                        assertTrue(time.summerTime());
                      }
                    }));
  }

  @TestFactory
  Stream<DynamicTest> instantMatchConfirmsEchoWithIrrelevantDifferences() {
    return commands()
        .flatMap(
            command ->
                IRRELEVANT_CHANGES.entrySet().stream()
                    .map(
                        change ->
                            DynamicTest.dynamicTest(
                                command.getClass().getSimpleName() + " " + change.getKey(),
                                () -> {
                                  try (Harness h = new Harness(b -> {})) {
                                    CompletableFuture<CommandResult> result =
                                        h.send(command, false);
                                    h.session()
                                        .deliverAsdu(retimedEcho(h.sent(0), change.getValue()));
                                    assertTrue(result.isDone(), "echo must confirm");
                                    assertTrue(result.join().positive());
                                  }
                                })));
  }

  @Test
  void instantMatchConfirmsSelectAndExecuteWithDayOfWeekZeroEcho() {
    try (Harness h = new Harness(b -> {})) {
      CompletableFuture<CommandResult> result = h.send(singleTimed(), true);
      UnaryOperator<Cp56Time2a> dayOfWeekZero = IRRELEVANT_CHANGES.get("dayOfWeek");

      h.session().deliverAsdu(retimedEcho(h.sent(0), dayOfWeekZero));
      assertEquals(2, h.session().sentAsdus().size(), "select echo must send EXECUTE");
      h.session().deliverAsdu(retimedEcho(h.sent(1), dayOfWeekZero));
      assertTrue(result.join().positive());
    }
  }

  @Test
  void instantMatchRejectsEchoOneSecondOff() {
    try (Harness h = new Harness(b -> {})) {
      CompletableFuture<CommandResult> result = h.send(singleTimed(), false);

      h.session()
          .deliverAsdu(
              retimedEcho(h.sent(0), t -> Cp56Time2a.from(TIME.plusSeconds(1), ZoneOffset.UTC)));

      assertTimesOut(h, result);
    }
  }

  @Test
  void exactMatchRejectsEchoWithDayOfWeekZero() {
    try (Harness h = new Harness(b -> b.commandTimeTagMatch(TimeTagMatch.EXACT))) {
      CompletableFuture<CommandResult> result = h.send(singleTimed(), false);

      h.session().deliverAsdu(retimedEcho(h.sent(0), IRRELEVANT_CHANGES.get("dayOfWeek")));

      assertTimesOut(h, result);
    }
  }

  @Test
  void exactMatchConfirmsIdenticalEcho() {
    try (Harness h = new Harness(b -> b.commandTimeTagMatch(TimeTagMatch.EXACT))) {
      CompletableFuture<CommandResult> result = h.send(singleTimed(), false);

      h.session().deliverAsdu(retimedEcho(h.sent(0), UnaryOperator.identity()));

      assertTrue(result.join().positive());
    }
  }

  @Test
  void ignoreMatchConfirmsEchoWithDifferentTime() {
    try (Harness h = new Harness(b -> b.commandTimeTagMatch(TimeTagMatch.IGNORE))) {
      CompletableFuture<CommandResult> result = h.send(singleTimed(), false);

      h.session()
          .deliverAsdu(
              retimedEcho(
                  h.sent(0), t -> Cp56Time2a.from(TIME.minusSeconds(3600), ZoneOffset.UTC)));

      assertTrue(result.join().positive());
    }
  }

  @TestFactory
  Stream<DynamicTest> ignoreMatchStillComparesCommandFields() {
    Map<String, UnaryOperator<SingleCommandWithCp56Time>> mismatches =
        Map.of(
            "value",
            o -> new SingleCommandWithCp56Time(o.address(), !o.on(), o.qualifier(), o.time()),
            "qualifier",
            o ->
                new SingleCommandWithCp56Time(
                    o.address(),
                    o.on(),
                    new QualifierOfCommand(o.qualifier().qualifier() + 1, o.qualifier().select()),
                    o.time()),
            "phase",
            o ->
                new SingleCommandWithCp56Time(
                    o.address(),
                    o.on(),
                    new QualifierOfCommand(o.qualifier().qualifier(), !o.qualifier().select()),
                    o.time()));
    return mismatches.entrySet().stream()
        .map(
            mismatch ->
                DynamicTest.dynamicTest(
                    mismatch.getKey(),
                    () -> {
                      try (Harness h =
                          new Harness(b -> b.commandTimeTagMatch(TimeTagMatch.IGNORE))) {
                        CompletableFuture<CommandResult> result = h.send(singleTimed(), true);
                        Asdu request = h.sent(0);
                        var sent = (SingleCommandWithCp56Time) request.objects().get(0);
                        h.session().deliverAsdu(echo(request, mismatch.getValue().apply(sent)));
                        assertEquals(1, h.session().sentAsdus().size(), "must not send EXECUTE");
                        assertTimesOut(h, result);
                      }
                    }));
  }

  @Test
  void clockSyncUsesPolicyZone() {
    try (Harness h = new Harness(b -> b.timePolicy(BERLIN))) {
      h.client.synchronizeClockAsync(POINT.commonAddress(), TIME);

      Asdu request = h.sent(0);
      assertEquals(AsduType.C_CS_NA_1, request.type());
      var command = (ClockSynchronizationCommand) request.objects().get(0);
      assertEquals(new Cp56Time2a(0, 0, 14, 1, 1, 6, 26, false, true, true), command.time());
    }
  }

  @Test
  void clockSyncWithoutDayOfWeekOrSummerTime() {
    TimePolicy policy = BERLIN.withSendDayOfWeek(false).withSetSummerTime(false);
    try (Harness h = new Harness(b -> b.timePolicy(policy))) {
      h.client.synchronizeClockAsync(POINT.commonAddress(), TIME);

      var command = (ClockSynchronizationCommand) h.sent(0).objects().get(0);
      assertEquals(new Cp56Time2a(0, 0, 14, 1, 0, 6, 26, false, false, true), command.time());
    }
  }

  @Test
  void defaultClockSyncIsUtc() {
    try (Harness h = new Harness(b -> {})) {
      h.client.synchronizeClockAsync(POINT.commonAddress(), TIME);

      var command = (ClockSynchronizationCommand) h.sent(0).objects().get(0);
      assertEquals(Cp56Time2a.from(TIME, ZoneOffset.UTC), command.time());
    }
  }

  private static Command singleTimed() {
    return new Command.SingleCommandRequest(POINT, true, 2, Optional.of(TIME));
  }

  /** One time-tagged command of every type, all for {@link #TIME}. */
  private static Stream<Command> commands() {
    Optional<Instant> time = Optional.of(TIME);
    return Stream.of(
        new Command.SingleCommandRequest(POINT, true, 1, time),
        new Command.DoubleCommandRequest(POINT, DoubleCommandState.ON, 1, time),
        new Command.RegulatingStepCommandRequest(POINT, StepCommandState.NEXT_STEP_HIGHER, 1, time),
        new Command.SetpointNormalizedRequest(POINT, NormalizedValue.of(0.5), 1, time),
        new Command.SetpointScaledRequest(POINT, (short) 42, 1, time),
        new Command.SetpointShortFloatRequest(POINT, 12.5f, 1, time),
        new Command.BitstringCommandRequest(POINT, 0x5A5A, time));
  }

  private static Cp56Time2a timeOf(Asdu request) {
    return assertInstanceOf(Cp56TimeTagged.class, request.objects().get(0)).time();
  }

  private static Cp56Time2a copy(
      Cp56Time2a t, int dayOfWeek, boolean invalid, boolean summerTime, boolean genuine) {
    return new Cp56Time2a(
        t.milliseconds(),
        t.minute(),
        t.hour(),
        t.dayOfMonth(),
        dayOfWeek,
        t.month(),
        t.year(),
        invalid,
        summerTime,
        genuine);
  }

  /** Builds a positive confirmation that echoes the request with its time tag changed. */
  private static Asdu retimedEcho(Asdu request, UnaryOperator<Cp56Time2a> change) {
    InformationObject sent = request.objects().get(0);
    Cp56Time2a time = change.apply(((Cp56TimeTagged) sent).time());
    InformationObject object;
    if (sent instanceof SingleCommandWithCp56Time o) {
      object = new SingleCommandWithCp56Time(o.address(), o.on(), o.qualifier(), time);
    } else if (sent instanceof DoubleCommandWithCp56Time o) {
      object = new DoubleCommandWithCp56Time(o.address(), o.state(), o.qualifier(), time);
    } else if (sent instanceof RegulatingStepCommandWithCp56Time o) {
      object = new RegulatingStepCommandWithCp56Time(o.address(), o.state(), o.qualifier(), time);
    } else if (sent instanceof SetpointNormalizedWithCp56Time o) {
      object = new SetpointNormalizedWithCp56Time(o.address(), o.value(), o.qualifier(), time);
    } else if (sent instanceof SetpointScaledWithCp56Time o) {
      object = new SetpointScaledWithCp56Time(o.address(), o.value(), o.qualifier(), time);
    } else if (sent instanceof SetpointShortFloatWithCp56Time o) {
      object = new SetpointShortFloatWithCp56Time(o.address(), o.value(), o.qualifier(), time);
    } else if (sent instanceof Bitstring32CommandWithCp56Time o) {
      object = new Bitstring32CommandWithCp56Time(o.address(), o.bits(), time);
    } else {
      throw new IllegalArgumentException("not a time-tagged command: " + sent);
    }
    return echo(request, object);
  }

  private static Asdu echo(Asdu request, InformationObject object) {
    return new Asdu(
        request.type(),
        request.sequence(),
        Cause.ACTIVATION_CONFIRMATION,
        false,
        request.test(),
        request.originatorAddress(),
        request.commonAddress(),
        List.of(object));
  }

  private static void assertTimesOut(Harness h, CompletableFuture<CommandResult> result) {
    assertFalse(result.isDone(), "echo must not confirm");
    h.clock.advance(50, TimeUnit.MILLISECONDS);
    CompletionException error = assertThrows(CompletionException.class, result::join);
    assertInstanceOf(ProtocolTimeoutException.class, error.getCause());
  }

  private static final class Harness implements AutoCloseable {
    final ManualScheduler clock = new ManualScheduler();
    final AtomicReference<@Nullable FakeSession> session = new AtomicReference<>();
    final DefaultIec60870Client client;

    Harness(Consumer<ClientConfig.Builder> configure) {
      ClientConfig.Builder builder =
          ClientConfig.builder()
              .callbackExecutor(Runnable::run)
              .commandTimeout(Duration.ofMillis(50));
      configure.accept(builder);
      client =
          new DefaultIec60870Client(
              new FakeClientTransport(),
              builder.build(),
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

    void subscribe(List<ClientEvent> sink) {
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
