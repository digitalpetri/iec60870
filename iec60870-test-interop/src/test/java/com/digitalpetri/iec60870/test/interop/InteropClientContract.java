package com.digitalpetri.iec60870.test.interop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
import com.digitalpetri.iec60870.asdu.element.DoubleCommandState;
import com.digitalpetri.iec60870.asdu.element.DoublePointState;
import com.digitalpetri.iec60870.asdu.element.FixedTestBitPattern;
import com.digitalpetri.iec60870.asdu.element.FreezeMode;
import com.digitalpetri.iec60870.asdu.element.NormalizedValue;
import com.digitalpetri.iec60870.asdu.element.QualifierOfCounterInterrogation;
import com.digitalpetri.iec60870.asdu.element.QualifierOfInterrogation;
import com.digitalpetri.iec60870.asdu.element.QualifierOfResetProcess;
import com.digitalpetri.iec60870.asdu.element.StepCommandState;
import com.digitalpetri.iec60870.asdu.element.Vti;
import com.digitalpetri.iec60870.asdu.object.CounterInterrogationCommand;
import com.digitalpetri.iec60870.asdu.object.IntegratedTotals;
import com.digitalpetri.iec60870.asdu.object.ResetProcessCommand;
import com.digitalpetri.iec60870.asdu.object.TestCommand;
import com.digitalpetri.iec60870.client.ClientEvent;
import com.digitalpetri.iec60870.client.Command;
import com.digitalpetri.iec60870.client.CommandMode;
import com.digitalpetri.iec60870.client.CommandResult;
import com.digitalpetri.iec60870.client.Iec60870Client;
import com.digitalpetri.iec60870.client.InterrogationResult;
import com.digitalpetri.iec60870.point.MonitorMapping;
import com.digitalpetri.iec60870.point.PointType;
import com.digitalpetri.iec60870.point.PointValue;
import com.digitalpetri.iec60870.point.PointValueExtraction;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Shared assertions for Java-client-vs-lib60870-C slave interop scenarios. */
final class InteropClientContract {

  static final CommonAddress STATION = CommonAddress.of(1);

  static final int IOA_SINGLE = 1000;
  static final int IOA_SINGLE_T = 1001;
  static final int IOA_DOUBLE = 1010;
  static final int IOA_DOUBLE_T = 1011;
  static final int IOA_STEP = 1020;
  static final int IOA_STEP_T = 1021;
  static final int IOA_BITS = 1030;
  static final int IOA_BITS_T = 1031;
  static final int IOA_NORM = 1040;
  static final int IOA_NORM_T = 1041;
  static final int IOA_SCALED = 1050;
  static final int IOA_SCALED_T = 1051;
  static final int IOA_SHORT = 1060;
  static final int IOA_SHORT_T = 1061;
  static final int IOA_COUNTER = 1070;
  static final int IOA_COUNTER_T = 1071;
  static final int IOA_ACCEPT = 2000;
  static final int IOA_REJECT = 3000;

  private static final boolean VAL_SINGLE = true;
  private static final DoublePointState VAL_DOUBLE = DoublePointState.ON;
  private static final int VAL_STEP = 7;
  private static final int VAL_BITS = 0x12345678;
  private static final double VAL_NORM = 0.5;
  private static final short VAL_SCALED = 12345;
  private static final float VAL_SHORT = 3.14159f;
  private static final int VAL_COUNTER = 1000;

  private InteropClientContract() {}

  static void assertBroadClientContract(
      Iec60870Client client,
      InteropEventRecorder events,
      OriginatorAddress originator,
      Duration waitTimeout,
      Duration periodicTimeout) {

    assertStationInterrogation(client, events, waitTimeout);
    assertGroup1Interrogation(client, events, waitTimeout);
    assertGroup2Interrogation(client, events, waitTimeout);
    assertCounterInterrogation(client, events, originator, waitTimeout);
    assertReadEveryMonitorType(client, events, waitTimeout);
    assertAcceptedCommands(client);
    assertRejectedCommand(client);
    assertClockSync(client);
    assertTestCommand(client, events, originator, waitTimeout);
    assertResetProcess(client, events, originator, waitTimeout);
    assertPeriodicUpdate(events, periodicTimeout);
  }

  static void assertFocusedClientContract(
      Iec60870Client client, InteropEventRecorder events, Duration waitTimeout) {
    assertStationInterrogation(client, events, waitTimeout);

    CommandResult accept = client.commands().single(PointAddress.of(1, IOA_ACCEPT), true);
    assertTrue(accept.positive(), () -> "accept command must be positive; cause=" + accept.cause());

    CommandResult reject = client.commands().single(PointAddress.of(1, IOA_REJECT), true);
    assertFalse(reject.positive(), "command to IOA 3000 must be negatively confirmed");
  }

  static void assertStationInterrogation(
      Iec60870Client client, InteropEventRecorder events, Duration waitTimeout) {
    events.clear();
    InterrogationResult result = client.interrogate(STATION, QualifierOfInterrogation.STATION);
    assertTrue(result.terminated(), "station interrogation must end with ACT_TERM");

    Map<Long, PointValue<?>> values = byIoa(result);
    assertEquals(7, values.size(), "station IC must return all 7 non-time monitor points");
    assertAllNonTimePoints(values);

    Asdu data =
        events.awaitAsdu(
            a ->
                a.cause() == Cause.INTERROGATED_BY_STATION
                    && a.objects().stream()
                        .anyMatch(o -> o.address().value().longValue() == IOA_SINGLE),
            waitTimeout);
    assertNotNull(data, "station IC data ASDU must carry COT INTERROGATED_BY_STATION");

    Asdu actCon =
        events.awaitAsdu(
            a -> a.type() == AsduType.C_IC_NA_1 && a.cause() == Cause.ACTIVATION_CONFIRMATION,
            waitTimeout);
    assertNotNull(actCon, "station IC must be positively confirmed (ACT_CON)");
    assertFalse(actCon.negative(), "station IC ACT_CON must be positive");
  }

  private static void assertGroup1Interrogation(
      Iec60870Client client, InteropEventRecorder events, Duration waitTimeout) {
    events.clear();
    InterrogationResult result = client.interrogate(STATION, QualifierOfInterrogation.GROUP_1);
    assertTrue(result.terminated(), "group 1 interrogation must end with ACT_TERM");

    Map<Long, PointValue<?>> values = byIoa(result);
    assertEquals(7, values.size(), "group 1 IC must return all 7 non-time monitor points");
    assertAllNonTimePoints(values);

    Asdu data =
        events.awaitAsdu(
            a ->
                a.cause() == Cause.INTERROGATED_BY_GROUP_1
                    && a.objects().stream()
                        .anyMatch(o -> o.address().value().longValue() == IOA_SINGLE),
            waitTimeout);
    assertNotNull(data, "group 1 data ASDU must carry COT INTERROGATED_BY_GROUP_1");
  }

  private static void assertGroup2Interrogation(
      Iec60870Client client, InteropEventRecorder events, Duration waitTimeout) {
    events.clear();
    InterrogationResult result = client.interrogate(STATION, QualifierOfInterrogation.GROUP_2);
    assertTrue(result.terminated(), "group 2 interrogation must end with ACT_TERM");

    Map<Long, PointValue<?>> values = byIoa(result);
    assertEquals(7, values.size(), "group 2 IC must return all 7 time-tagged monitor points");
    assertAllTimeTaggedPointsByValue(values);

    Asdu data =
        events.awaitAsdu(
            a ->
                a.cause() == Cause.INTERROGATED_BY_GROUP_2
                    && a.objects().stream()
                        .anyMatch(o -> o.address().value().longValue() == IOA_SINGLE_T),
            waitTimeout);
    assertNotNull(data, "group 2 data ASDU must carry COT INTERROGATED_BY_GROUP_2");
    assertEquals(AsduType.M_SP_NA_1, data.type(), "group 2 uses non-time TypeIDs");
  }

  private static void assertCounterInterrogation(
      Iec60870Client client,
      InteropEventRecorder events,
      OriginatorAddress originator,
      Duration waitTimeout) {
    events.clear();

    Asdu request =
        new Asdu(
            AsduType.C_CI_NA_1,
            false,
            Cause.ACTIVATION,
            false,
            false,
            originator,
            STATION,
            List.of(
                new CounterInterrogationCommand(
                    InformationObjectAddress.of(0),
                    new QualifierOfCounterInterrogation(5, FreezeMode.READ))));
    client.send(request);

    Asdu data =
        events.awaitAsdu(
            a ->
                a.type() == AsduType.M_IT_NA_1
                    && a.cause() == Cause.REQUESTED_BY_GENERAL_COUNTER
                    && !a.objects().isEmpty(),
            waitTimeout);
    assertNotNull(data, "expected M_IT_NA_1 counter data");

    Asdu actCon =
        events.awaitAsdu(
            a -> a.type() == AsduType.C_CI_NA_1 && a.cause() == Cause.ACTIVATION_CONFIRMATION,
            waitTimeout);
    assertNotNull(actCon, "expected ACT_CON for counter interrogation");
    assertFalse(actCon.negative(), "counter interrogation ACT_CON must be positive");

    Map<Long, BinaryCounterReading> counters = countersByIoa(data);
    if (!counters.containsKey((long) IOA_COUNTER_T)) {
      Asdu more =
          events.awaitAsdu(
              a ->
                  a.type() == AsduType.M_IT_NA_1
                      && a.cause() == Cause.REQUESTED_BY_GENERAL_COUNTER
                      && countersByIoa(a).containsKey((long) IOA_COUNTER_T),
              waitTimeout);
      if (more != null) {
        counters.putAll(countersByIoa(more));
      }
    }

    assertTrue(counters.containsKey((long) IOA_COUNTER), "counter at 1070 must be reported");
    assertEquals(VAL_COUNTER, counters.get((long) IOA_COUNTER).value(), "1070 counter value");
    assertTrue(counters.containsKey((long) IOA_COUNTER_T), "counter at 1071 must be reported");
    assertEquals(VAL_COUNTER, counters.get((long) IOA_COUNTER_T).value(), "1071 counter value");
  }

  private static void assertReadEveryMonitorType(
      Iec60870Client client, InteropEventRecorder events, Duration waitTimeout) {

    record ReadCase(int ioa, AsduType expectedType, boolean timeTagged) {}

    List<ReadCase> cases =
        List.of(
            new ReadCase(IOA_SINGLE, AsduType.M_SP_NA_1, false),
            new ReadCase(IOA_SINGLE_T, AsduType.M_SP_TB_1, true),
            new ReadCase(IOA_DOUBLE, AsduType.M_DP_NA_1, false),
            new ReadCase(IOA_DOUBLE_T, AsduType.M_DP_TB_1, true),
            new ReadCase(IOA_STEP, AsduType.M_ST_NA_1, false),
            new ReadCase(IOA_STEP_T, AsduType.M_ST_TB_1, true),
            new ReadCase(IOA_BITS, AsduType.M_BO_NA_1, false),
            new ReadCase(IOA_BITS_T, AsduType.M_BO_TB_1, true),
            new ReadCase(IOA_NORM, AsduType.M_ME_NA_1, false),
            new ReadCase(IOA_NORM_T, AsduType.M_ME_TD_1, true),
            new ReadCase(IOA_SCALED, AsduType.M_ME_NB_1, false),
            new ReadCase(IOA_SCALED_T, AsduType.M_ME_TE_1, true),
            new ReadCase(IOA_SHORT, AsduType.M_ME_NC_1, false),
            new ReadCase(IOA_SHORT_T, AsduType.M_ME_TF_1, true),
            new ReadCase(IOA_COUNTER, AsduType.M_IT_NA_1, false),
            new ReadCase(IOA_COUNTER_T, AsduType.M_IT_TB_1, true));

    for (ReadCase c : cases) {
      events.clear();
      List<InformationObject> objects = client.read(PointAddress.of(1, c.ioa()));
      assertFalse(objects.isEmpty(), () -> "read of IOA " + c.ioa() + " returned no objects");

      InformationObject object =
          objects.stream()
              .filter(o -> o.address().value().longValue() == c.ioa())
              .findFirst()
              .orElseThrow(
                  () -> new AssertionError("read response did not contain IOA " + c.ioa()));

      PointValueExtraction extraction =
          MonitorMapping.extract(object)
              .orElseThrow(
                  () -> new AssertionError("could not extract value from read of IOA " + c.ioa()));
      assertMonitorValue(c.ioa(), extraction.value());
      assertEquals(
          c.timeTagged(),
          extraction.timestamp().isPresent(),
          () -> "timestamp presence for read of IOA " + c.ioa());

      Asdu readAsdu =
          events.awaitAsdu(
              a ->
                  a.cause() == Cause.REQUEST
                      && a.objects().stream()
                          .anyMatch(o -> o.address().value().longValue() == c.ioa()),
              waitTimeout);
      assertNotNull(readAsdu, () -> "read of IOA " + c.ioa() + " must publish a REQUEST ASDU");
      assertEquals(c.expectedType(), readAsdu.type(), () -> "read TypeID for IOA " + c.ioa());
    }
  }

  private static void assertAcceptedCommands(Iec60870Client client) {
    PointAddress accept = PointAddress.of(1, IOA_ACCEPT);

    record Case(String label, Command command, boolean supportsSelectBeforeOperate) {}

    List<Case> cases =
        List.of(
            new Case("single", Command.single(accept, true), true),
            new Case("double", Command.doublePoint(accept, DoubleCommandState.ON), true),
            new Case(
                "regulating-step",
                Command.regulatingStep(accept, StepCommandState.NEXT_STEP_HIGHER),
                true),
            new Case(
                "setpoint-normalized",
                Command.setpointNormalized(accept, NormalizedValue.of(0.25)),
                true),
            new Case("setpoint-scaled", Command.setpointScaled(accept, (short) 4321), true),
            new Case("setpoint-short", Command.setpointShortFloat(accept, 2.71828f), true),
            new Case("bitstring", Command.bitstring(accept, 0x0F0F0F0F), false));

    for (Case c : cases) {
      CommandResult direct = client.commands().send(c.command(), CommandMode.directExecute());
      assertTrue(
          direct.positive(),
          () -> c.label() + " direct execute must be positive; cause=" + direct.cause());

      if (c.supportsSelectBeforeOperate()) {
        CommandResult sbo = client.commands().send(c.command(), CommandMode.selectBeforeOperate());
        assertTrue(
            sbo.positive(),
            () -> c.label() + " select-before-operate must be positive; cause=" + sbo.cause());
      }
    }
  }

  private static void assertRejectedCommand(Iec60870Client client) {
    CommandResult result = client.commands().single(PointAddress.of(1, IOA_REJECT), true);
    assertFalse(result.positive(), "command to IOA 3000 must be negatively confirmed");
  }

  private static void assertClockSync(Iec60870Client client) {
    client.synchronizeClock(STATION, Instant.now());
  }

  private static void assertTestCommand(
      Iec60870Client client,
      InteropEventRecorder events,
      OriginatorAddress originator,
      Duration waitTimeout) {
    events.clear();
    client.send(
        new Asdu(
            AsduType.C_TS_NA_1,
            false,
            Cause.ACTIVATION,
            false,
            false,
            originator,
            STATION,
            List.of(new TestCommand(InformationObjectAddress.of(0), FixedTestBitPattern.DEFAULT))));

    Asdu actCon =
        events.awaitAsdu(
            a -> a.type() == AsduType.C_TS_NA_1 && a.cause() == Cause.ACTIVATION_CONFIRMATION,
            waitTimeout);
    assertNotNull(actCon, "expected ACT_CON for test command");
    assertFalse(actCon.negative(), "test command ACT_CON must be positive");
  }

  private static void assertResetProcess(
      Iec60870Client client,
      InteropEventRecorder events,
      OriginatorAddress originator,
      Duration waitTimeout) {
    events.clear();
    client.send(
        new Asdu(
            AsduType.C_RP_NA_1,
            false,
            Cause.ACTIVATION,
            false,
            false,
            originator,
            STATION,
            List.of(
                new ResetProcessCommand(
                    InformationObjectAddress.of(0), QualifierOfResetProcess.GENERAL))));

    Asdu actCon =
        events.awaitAsdu(
            a -> a.type() == AsduType.C_RP_NA_1 && a.cause() == Cause.ACTIVATION_CONFIRMATION,
            waitTimeout);
    assertNotNull(actCon, "expected ACT_CON for reset process");
    assertFalse(actCon.negative(), "reset process ACT_CON must be positive");

    Asdu endOfInit = events.awaitAsdu(a -> a.type() == AsduType.M_EI_NA_1, waitTimeout);
    assertNotNull(endOfInit, "reset process should re-issue End-of-Initialization");
  }

  static void assertPeriodicUpdate(InteropEventRecorder events, Duration timeout) {
    ClientEvent.PointUpdated update =
        events.awaitPointUpdated(
            u ->
                u.address().objectAddress().value().longValue() == IOA_SCALED
                    && u.cause() == Cause.PERIODIC,
            timeout);
    assertNotNull(update, "expected a periodic PointUpdated for IOA 1050");
    assertEquals(PointType.SCALED, update.value().type(), "periodic logical point type");
    assertEquals(AsduType.M_ME_NB_1, update.asduType(), "periodic carrying wire type");
  }

  private static void assertAllNonTimePoints(Map<Long, PointValue<?>> values) {
    assertSinglePoint(values.get((long) IOA_SINGLE), VAL_SINGLE);
    assertDoublePoint(values.get((long) IOA_DOUBLE), VAL_DOUBLE);
    assertStep(values.get((long) IOA_STEP), VAL_STEP);
    assertBits(values.get((long) IOA_BITS), VAL_BITS);
    assertNormalized(values.get((long) IOA_NORM), VAL_NORM);
    assertScaled(values.get((long) IOA_SCALED), VAL_SCALED);
    assertShortFloat(values.get((long) IOA_SHORT), VAL_SHORT);
  }

  private static void assertAllTimeTaggedPointsByValue(Map<Long, PointValue<?>> values) {
    assertSinglePoint(values.get((long) IOA_SINGLE_T), VAL_SINGLE);
    assertDoublePoint(values.get((long) IOA_DOUBLE_T), VAL_DOUBLE);
    assertStep(values.get((long) IOA_STEP_T), VAL_STEP);
    assertBits(values.get((long) IOA_BITS_T), VAL_BITS);
    assertNormalized(values.get((long) IOA_NORM_T), VAL_NORM);
    assertScaled(values.get((long) IOA_SCALED_T), VAL_SCALED);
    assertShortFloat(values.get((long) IOA_SHORT_T), VAL_SHORT);
  }

  private static void assertMonitorValue(int ioa, PointValue<?> value) {
    switch (ioa) {
      case IOA_SINGLE, IOA_SINGLE_T -> assertSinglePoint(value, VAL_SINGLE);
      case IOA_DOUBLE, IOA_DOUBLE_T -> assertDoublePoint(value, VAL_DOUBLE);
      case IOA_STEP, IOA_STEP_T -> assertStep(value, VAL_STEP);
      case IOA_BITS, IOA_BITS_T -> assertBits(value, VAL_BITS);
      case IOA_NORM, IOA_NORM_T -> assertNormalized(value, VAL_NORM);
      case IOA_SCALED, IOA_SCALED_T -> assertScaled(value, VAL_SCALED);
      case IOA_SHORT, IOA_SHORT_T -> assertShortFloat(value, VAL_SHORT);
      case IOA_COUNTER, IOA_COUNTER_T -> assertCounter(value, VAL_COUNTER);
      default -> throw new AssertionError("no fixed value mapping for IOA " + ioa);
    }
  }

  private static void assertSinglePoint(PointValue<?> value, boolean expected) {
    assertNotNull(value, "missing single-point value");
    assertEquals(PointType.SINGLE_POINT, value.type(), "single-point type");
    assertEquals(expected, value.value(), "single-point state");
  }

  private static void assertDoublePoint(PointValue<?> value, DoublePointState expected) {
    assertNotNull(value, "missing double-point value");
    assertEquals(PointType.DOUBLE_POINT, value.type(), "double-point type");
    assertEquals(expected, value.value(), "double-point state");
  }

  private static void assertStep(PointValue<?> value, int expected) {
    assertNotNull(value, "missing step-position value");
    assertEquals(PointType.STEP_POSITION, value.type(), "step-position type");
    Vti vti = assertInstanceOf(Vti.class, value.value(), "step value should be a Vti");
    assertEquals(expected, vti.value(), "step position value");
    assertFalse(vti.transientState(), "step transient flag should be false");
  }

  private static void assertBits(PointValue<?> value, int expected) {
    assertNotNull(value, "missing bitstring value");
    assertEquals(PointType.BITSTRING32, value.type(), "bitstring type");
    assertEquals(expected, value.value(), "bitstring 32 value");
  }

  private static void assertNormalized(PointValue<?> value, double expected) {
    assertNotNull(value, "missing normalized value");
    assertEquals(PointType.NORMALIZED, value.type(), "normalized type");
    NormalizedValue normalized =
        assertInstanceOf(NormalizedValue.class, value.value(), "normalized value type");
    double actual = normalized.doubleValue();
    assertTrue(
        Math.abs(actual - expected) <= 3.06e-5,
        "normalized value " + actual + " not within 1 LSB of " + expected);
  }

  private static void assertScaled(PointValue<?> value, short expected) {
    assertNotNull(value, "missing scaled value");
    assertEquals(PointType.SCALED, value.type(), "scaled type");
    assertEquals(expected, value.value(), "scaled value");
  }

  private static void assertShortFloat(PointValue<?> value, float expected) {
    assertNotNull(value, "missing short-float value");
    assertEquals(PointType.SHORT_FLOAT, value.type(), "short-float type");
    Float actual = assertInstanceOf(Float.class, value.value(), "short-float value type");
    assertEquals(expected, actual, 1e-4f, "short-float value");
  }

  private static void assertCounter(PointValue<?> value, int expected) {
    assertNotNull(value, "missing counter value");
    assertEquals(PointType.INTEGRATED_TOTALS, value.type(), "counter type");
    BinaryCounterReading actual =
        assertInstanceOf(BinaryCounterReading.class, value.value(), "counter value type");
    assertEquals(expected, actual.value(), "counter reading");
  }

  private static Map<Long, PointValue<?>> byIoa(InterrogationResult result) {
    return result.pointValues().stream()
        .collect(
            Collectors.toMap(
                e -> e.address().objectAddress().value().longValue(),
                InterrogationResult.PointEntry::value,
                (a, b) -> b,
                LinkedHashMap::new));
  }

  private static Map<Long, BinaryCounterReading> countersByIoa(Asdu asdu) {
    Map<Long, BinaryCounterReading> out = new LinkedHashMap<>();
    for (InformationObject object : asdu.objects()) {
      if (object instanceof IntegratedTotals integratedTotals) {
        out.put(integratedTotals.address().value().longValue(), integratedTotals.counter());
      }
    }
    return out;
  }
}
