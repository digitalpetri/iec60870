package com.digitalpetri.iec60870.test.interop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.digitalpetri.iec60870.NegativeConfirmationException;
import com.digitalpetri.iec60870.ProtocolProfile;
import com.digitalpetri.iec60870.address.CommonAddress;
import com.digitalpetri.iec60870.address.OriginatorAddress;
import com.digitalpetri.iec60870.address.PointAddress;
import com.digitalpetri.iec60870.asdu.Asdu;
import com.digitalpetri.iec60870.asdu.AsduType;
import com.digitalpetri.iec60870.asdu.Cause;
import com.digitalpetri.iec60870.asdu.InformationObject;
import com.digitalpetri.iec60870.asdu.element.BinaryCounterReading;
import com.digitalpetri.iec60870.asdu.element.DoubleCommandState;
import com.digitalpetri.iec60870.asdu.element.DoublePointState;
import com.digitalpetri.iec60870.asdu.element.FreezeMode;
import com.digitalpetri.iec60870.asdu.element.NormalizedValue;
import com.digitalpetri.iec60870.asdu.element.QualifierOfCounterInterrogation;
import com.digitalpetri.iec60870.asdu.element.QualifierOfInterrogation;
import com.digitalpetri.iec60870.asdu.element.QualifierOfResetProcess;
import com.digitalpetri.iec60870.asdu.element.StepCommandState;
import com.digitalpetri.iec60870.asdu.element.Vti;
import com.digitalpetri.iec60870.asdu.object.IntegratedTotals;
import com.digitalpetri.iec60870.asdu.object.TestCommandWithCp56Time;
import com.digitalpetri.iec60870.client.ClientEvent;
import com.digitalpetri.iec60870.client.Command;
import com.digitalpetri.iec60870.client.CommandCompletion;
import com.digitalpetri.iec60870.client.CommandMode;
import com.digitalpetri.iec60870.client.CommandOptions;
import com.digitalpetri.iec60870.client.CommandOutcome;
import com.digitalpetri.iec60870.client.CommandResult;
import com.digitalpetri.iec60870.client.CounterInterrogationResult;
import com.digitalpetri.iec60870.client.Iec60870Client;
import com.digitalpetri.iec60870.client.InterrogationResult;
import com.digitalpetri.iec60870.client.SelectedCommand;
import com.digitalpetri.iec60870.client.TestResult;
import com.digitalpetri.iec60870.point.MonitorMapping;
import com.digitalpetri.iec60870.point.PointType;
import com.digitalpetri.iec60870.point.PointValue;
import com.digitalpetri.iec60870.point.PointValueExtraction;
import com.digitalpetri.iec60870.tcp.TcpIec104Client;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.output.Slf4jLogConsumer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;

/**
 * BROAD interop scenario: OUR Java {@link Iec60870Client} (controlling station) driving the
 * lib60870-C {@code interop_server} (controlled station) over plaintext IEC 60870-5-104.
 *
 * <p>Every assertion is anchored to {@code iec60870-test-interop/docker/INTEROP-CONTRACT.md}. The
 * single lib60870-C peer image is built from {@code iec60870-test-interop/docker/lib60870c} via
 * {@link ImageFromDockerfile} so the test is self-contained (the Docker layer cache makes reruns
 * fast); the container command is {@code stdbuf -oL -eL interop_server} and startup is gated on the
 * {@code INTEROP-SERVER READY} log marker. One container is shared by all {@code @Test} methods via
 * {@code @TestInstance(PER_CLASS)}.
 *
 * <p>Every request goes through the high-level {@link Iec60870Client}: interrogation, group
 * interrogation, counter interrogation, read, the full command surface, clock sync, the test
 * command (C_TS_TA_1), and the reset process command.
 *
 * <p><b>Every monitor type round-trips against the live peer.</b> {@code interop_server} emits each
 * interrogation point as its own non-sequence {@code CS101_ASDU} (one {@code
 * CS101_ASDU_addInformationObject} per ASDU). This is required because lib60870-C v2.3.5's {@code
 * CS101_ASDU_addInformationObject} silently <em>drops</em> any object whose TypeID differs from the
 * ASDU's first object (one ASDU carries a single TypeID for all of its objects), so packing a
 * mixed-type interrogation image into one ASDU would collapse on the wire to just the first point.
 * With the per-point fix, station / group&nbsp;1 interrogation delivers all seven non-time monitor
 * types and group&nbsp;2 delivers all seven time-tagged points (reported, per CS101, with their
 * non-time TypeIDs at the time-tagged IOAs). The CP56Time2a-tagged TypeIDs themselves are
 * round-tripped through the <em>read</em> command, which returns each IOA at its native (time or
 * non-time) TypeID. The interrogation and read tests below decode and assert the value and logical
 * {@link PointType} (and, for reads, the wire TypeID) of every documented monitor point; the
 * periodic-update test additionally asserts the {@link
 * com.digitalpetri.iec60870.client.ClientEvent.PointUpdated#asduType() wire TypeID} reported on the
 * point-update event.
 */
@Tag("interop")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Java client vs lib60870-C interop_server")
// The single Iec60870Client is owned by the @BeforeAll/@AfterAll lifecycle and shared by all tests;
// closing it per call site (try-with-resources) would tear down the shared client mid-suite.
@SuppressWarnings("resource")
class ClientVsLib60870ServerInteropTest {

  private static final Logger log =
      LoggerFactory.getLogger(ClientVsLib60870ServerInteropTest.class);

  /** Contract: CA = 1. */
  private static final CommonAddress STATION = CommonAddress.of(1);

  /** Contract: monitor IOAs live at 1000..1071; accept range 2000..2999; reject IOA 3000. */
  private static final int IOA_SINGLE = 1000;

  private static final int IOA_SINGLE_T = 1001;
  private static final int IOA_DOUBLE = 1010;
  private static final int IOA_DOUBLE_T = 1011;
  private static final int IOA_STEP = 1020;
  private static final int IOA_STEP_T = 1021;
  private static final int IOA_BITS = 1030;
  private static final int IOA_BITS_T = 1031;
  private static final int IOA_NORM = 1040;
  private static final int IOA_NORM_T = 1041;
  private static final int IOA_SCALED = 1050;
  private static final int IOA_SCALED_T = 1051;
  private static final int IOA_SHORT = 1060;
  private static final int IOA_SHORT_T = 1061;
  private static final int IOA_COUNTER = 1070;
  private static final int IOA_COUNTER_T = 1071;

  private static final int IOA_ACCEPT = 2000;
  private static final int IOA_REJECT = 3000;
  private static final int IOA_NO_ACT_TERM = 2998;
  private static final int IOA_NEGATIVE_ACT_TERM = 2999;
  private static final int IOA_UNKNOWN = 4000;

  private static final CommandOptions AWAIT_TERMINATION =
      CommandOptions.defaults().withCompletion(CommandCompletion.TERMINATION);

  /** Fixed monitor values (contract section 2). */
  private static final boolean VAL_SINGLE = true;

  private static final DoublePointState VAL_DOUBLE = DoublePointState.ON;
  private static final int VAL_STEP = 7;
  private static final int VAL_BITS = 0x12345678;
  private static final double VAL_NORM = 0.5;
  private static final short VAL_SCALED = 12345;
  private static final float VAL_SHORT = 3.14159f;

  /** Generous startup timeout: the first run shallow-clones + builds lib60870-C (minutes). */
  private static final Duration STARTUP_TIMEOUT = Duration.ofMinutes(10);

  /** All blocking waits in this test are bounded by this timeout; nothing hangs. */
  private static final Duration WAIT_TIMEOUT = Duration.ofSeconds(15);

  @SuppressWarnings("resource")
  private static final GenericContainer<?> SERVER =
      new GenericContainer<>(
              new ImageFromDockerfile("iec60870-test-interop/lib60870c-interop", false)
                  .withFileFromPath(".", Path.of("docker/lib60870c")))
          .withExposedPorts(2404)
          .withCommand("stdbuf", "-oL", "-eL", "interop_server")
          .withStartupTimeout(STARTUP_TIMEOUT)
          .waitingFor(Wait.forLogMessage(".*INTEROP-SERVER READY.*", 1));

  private @Nullable Iec60870Client client;
  private @Nullable EventRecorder events;

  @BeforeAll
  void startServerAndClient() {
    SERVER.withLogConsumer(new Slf4jLogConsumer(LoggerFactory.getLogger("interop_server")));
    SERVER.start();

    String host = SERVER.getHost();
    int port = SERVER.getMappedPort(2404);
    log.info("interop_server reachable at {}:{}", host, port);

    client =
        TcpIec104Client.builder()
            .host(host)
            .port(port)
            .profile(ProtocolProfile.iec104Default())
            .originatorAddress(OriginatorAddress.of(3))
            .startDataTransferOnConnect(true)
            .build();

    events = new EventRecorder();
    client.events().subscribe(events);

    // connect() establishes the TCP connection and performs the STARTDT handshake.
    client.connect();
    assertTrue(client.isConnected(), "client should be connected after connect()");
  }

  @AfterAll
  void stopClientAndServer() {
    if (client != null) {
      client.close();
    }
    SERVER.stop();
  }

  /** Returns the client established in {@link #startServerAndClient()}, asserting it is present. */
  private Iec60870Client client() {
    assertNotNull(client, "client must be initialized by @BeforeAll");
    return client;
  }

  /**
   * Returns the recorder established in {@link #startServerAndClient()}, asserting it is present.
   */
  private EventRecorder events() {
    assertNotNull(events, "events must be initialized by @BeforeAll");
    return events;
  }

  // --- Interrogation --------------------------------------------------------------------------

  @Test
  @DisplayName("STARTDT succeeds and station interrogation returns every non-counter point value")
  void stationInterrogation() {
    events().clear();
    InterrogationResult result = client().interrogate(STATION, QualifierOfInterrogation.STATION);
    assertTrue(result.terminated(), "station interrogation must end with ACT_TERM");

    Map<Long, PointValue<?>> values = byIoa(result);

    // Contract section 3: station interrogation returns all seven non-time monitor types (counters
    // excluded). interop_server emits each as its own non-sequence ASDU (one TypeID per ASDU), so
    // every distinct type reaches the client at its correct TypeID and decodes to its fixed value.
    assertEquals(
        7,
        values.size(),
        "station IC must return all 7 non-time monitor points (one ASDU per point)");
    assertAllNonTimePoints(values);

    // The carrying data ASDU uses the documented station-response COT (contract section 3).
    Asdu data =
        events()
            .awaitAsdu(
                a ->
                    a.cause() == Cause.INTERROGATED_BY_STATION
                        && a.objects().stream()
                            .anyMatch(o -> o.address().value().longValue() == IOA_SINGLE),
                WAIT_TIMEOUT);
    assertNotNull(data, "station IC data ASDU must carry COT INTERROGATED_BY_STATION");

    // The positive ACT_CON and the ACT_TERM for the interrogation were both observed.
    Asdu actCon =
        events()
            .awaitAsdu(
                a -> a.type() == AsduType.C_IC_NA_1 && a.cause() == Cause.ACTIVATION_CONFIRMATION,
                WAIT_TIMEOUT);
    assertNotNull(actCon, "station IC must be positively confirmed (ACT_CON)");
    assertFalse(actCon.negative(), "station IC ACT_CON must be positive");

    // Integrated totals are NEVER delivered via station interrogation (contract section 3).
    assertFalse(values.containsKey((long) IOA_COUNTER), "counter must not appear in station IC");
    assertFalse(values.containsKey((long) IOA_COUNTER_T), "counter must not appear in station IC");
  }

  @Test
  @DisplayName("Group 1 interrogation reports all seven non-time points with COT GROUP_1")
  void group1Interrogation() {
    events().clear();
    InterrogationResult result = client().interrogate(STATION, QualifierOfInterrogation.GROUP_1);
    assertTrue(result.terminated(), "group 1 interrogation must end with ACT_TERM");

    Map<Long, PointValue<?>> values = byIoa(result);

    // Contract section 3: group 1 = all seven non-time monitor points, one ASDU per point.
    assertEquals(7, values.size(), "group 1 IC must return all 7 non-time monitor points");
    assertAllNonTimePoints(values);

    Asdu data =
        events()
            .awaitAsdu(
                a ->
                    a.cause() == Cause.INTERROGATED_BY_GROUP_1
                        && a.objects().stream()
                            .anyMatch(o -> o.address().value().longValue() == IOA_SINGLE),
                WAIT_TIMEOUT);
    assertNotNull(data, "group 1 data ASDU must carry COT INTERROGATED_BY_GROUP_1");

    // No time-tagged IOAs in group 1.
    assertFalse(values.containsKey((long) IOA_SINGLE_T), "1001 must not be in group 1");
  }

  @Test
  @DisplayName("Group 2 reports all seven time-tagged points as non-time types with COT GROUP_2")
  void group2Interrogation() {
    events().clear();
    InterrogationResult result = client().interrogate(STATION, QualifierOfInterrogation.GROUP_2);
    assertTrue(result.terminated(), "group 2 interrogation must end with ACT_TERM");

    Map<Long, PointValue<?>> values = byIoa(result);

    // Contract section 3: group 2 = the seven time-tagged points, reported (per CS101) with their
    // non-time TypeIDs but at the *time-tagged* IOAs (1001,1011,...,1061), one ASDU per point. The
    // IOA identifies the logical (time-tagged) point; assert IOA + decoded value for all seven.
    assertEquals(7, values.size(), "group 2 IC must return all 7 time-tagged monitor points");
    assertAllTimeTaggedPointsByValue(values);

    Asdu data =
        events()
            .awaitAsdu(
                a ->
                    a.cause() == Cause.INTERROGATED_BY_GROUP_2
                        && a.objects().stream()
                            .anyMatch(o -> o.address().value().longValue() == IOA_SINGLE_T),
                WAIT_TIMEOUT);
    assertNotNull(data, "group 2 data ASDU must carry COT INTERROGATED_BY_GROUP_2");
    assertEquals(
        AsduType.M_SP_NA_1,
        data.type(),
        "group 2 reports the time-tagged single point as the non-time TypeID M_SP_NA_1");

    assertFalse(values.containsKey((long) IOA_SINGLE), "1000 must not be in group 2");
  }

  @Test
  @DisplayName("Station and group interrogations collect OA0 data for the requesting originator")
  void interrogationCollectsDefaultOriginatorData() {
    for (QualifierOfInterrogation qoi :
        List.of(
            QualifierOfInterrogation.STATION,
            QualifierOfInterrogation.GROUP_1,
            QualifierOfInterrogation.GROUP_2)) {
      events().clear();
      InterrogationResult result = client().interrogate(STATION, qoi);
      assertTrue(result.terminated());
      assertEquals(7, result.objects().size(), "all OA0 points must be collected for " + qoi);
      for (Cause cause :
          List.of(
              Cause.ACTIVATION_CONFIRMATION,
              Cause.fromValue(qoi.value().intValue()),
              Cause.ACTIVATION_TERMINATION)) {
        Asdu response = events().awaitAsdu(asdu -> asdu.cause() == cause, WAIT_TIMEOUT);
        assertNotNull(response, "interrogation response must carry " + cause);
        assertFalse(response.test());
        assertEquals(
            cause == Cause.fromValue(qoi.value().intValue())
                ? OriginatorAddress.none()
                : OriginatorAddress.of(3),
            response.originatorAddress(),
            "OA3 request receives OA3 controls and OA0 data: " + response);
      }
    }
  }

  // --- Counter interrogation ------------------------------------------------------------------

  @Test
  @DisplayName("General counter read returns integrated totals (1070=1000, 1071=1000)")
  void counterInterrogation() {
    events().clear();

    CounterInterrogationResult result = client().counterInterrogate(STATION);

    Map<Long, BinaryCounterReading> counters = countersByIoa(result.objects());
    assertEquals(2, counters.size(), "general counter read must return both totals");
    assertEquals(1000, counters.get((long) IOA_COUNTER).value(), "1070 counter value == 1000");
    assertEquals(1000, counters.get((long) IOA_COUNTER_T).value(), "1071 counter value == 1000");

    // Integrated totals decode to a PointValue whose logical type is INTEGRATED_TOTALS.
    assertEquals(PointType.INTEGRATED_TOTALS, result.pointValues().get(0).value().type());

    // The data carried the general counter cause and is also published as PointUpdated.
    ClientEvent.PointUpdated update =
        events()
            .awaitPointUpdated(
                u ->
                    u.address().objectAddress().value().longValue() == IOA_COUNTER
                        && u.cause() == Cause.REQUESTED_BY_GENERAL_COUNTER,
                WAIT_TIMEOUT);
    assertNotNull(update, "counter data must be published with COT REQUESTED_BY_GENERAL_COUNTER");
  }

  @Test
  @DisplayName("Group 1 counter read returns integrated totals with COT 38")
  void counterGroup1Interrogation() {
    events().clear();

    CounterInterrogationResult result =
        client()
            .counterInterrogate(STATION, QualifierOfCounterInterrogation.group(1, FreezeMode.READ));

    Map<Long, BinaryCounterReading> counters = countersByIoa(result.objects());
    assertEquals(2, counters.size(), "group 1 counter read must return both totals");
    assertEquals(1000, counters.get((long) IOA_COUNTER).value(), "1070 counter value == 1000");

    ClientEvent.PointUpdated update =
        events()
            .awaitPointUpdated(
                u ->
                    u.address().objectAddress().value().longValue() == IOA_COUNTER
                        && u.cause() == Cause.REQUESTED_BY_GROUP_1_COUNTER,
                WAIT_TIMEOUT);
    assertNotNull(update, "group 1 counter data must carry COT REQUESTED_BY_GROUP_1_COUNTER");
  }

  @Test
  @DisplayName("Counter freeze completes on ACT_CON and a read follows")
  void counterFreezeThenRead() {
    CounterInterrogationResult freeze =
        client()
            .counterInterrogate(
                STATION, QualifierOfCounterInterrogation.general(FreezeMode.FREEZE_NO_RESET));
    assertTrue(freeze.objects().isEmpty(), "a freeze transmits no counters");

    assertEquals(2, client().counterInterrogate(STATION).objects().size());
  }

  @Test
  @DisplayName("Counter group 2 is rejected with a negative ACT_CON")
  void counterGroup2Rejected() {
    NegativeConfirmationException e =
        assertThrows(
            NegativeConfirmationException.class,
            () ->
                client()
                    .counterInterrogate(
                        STATION, QualifierOfCounterInterrogation.group(2, FreezeMode.READ)));
    assertTrue(e.asdu().orElseThrow().negative(), "contract section 4: ACT_CON with P/N = 1");
  }

  @Test
  @DisplayName("Counter read of an unknown CA fails fast on COT 46")
  void counterInterrogationOfUnknownStationFailsFast() {
    // The fixture answers an unknown CA with COT 46 and P/N left at 0.
    NegativeConfirmationException e =
        assertThrows(
            NegativeConfirmationException.class,
            () -> client().counterInterrogate(CommonAddress.of(2)));
    assertEquals(Cause.UNKNOWN_COMMON_ADDRESS, e.cause());
  }

  // --- Read -----------------------------------------------------------------------------------

  @Test
  @DisplayName("Read of IOA 1000 returns the single point with COT REQUEST")
  void readSinglePoint() {
    PointAddress point = PointAddress.of(1, IOA_SINGLE);
    List<InformationObject> objects = client().read(point);
    assertFalse(objects.isEmpty(), "read must return at least one information object");

    InformationObject single =
        objects.stream()
            .filter(o -> o.address().value().longValue() == IOA_SINGLE)
            .findFirst()
            .orElseThrow(() -> new AssertionError("read response did not contain IOA 1000"));

    PointValueExtraction extraction =
        MonitorMapping.extract(single)
            .orElseThrow(() -> new AssertionError("could not extract value from read response"));
    assertSinglePoint(extraction.value(), true);

    // The carrying ASDU's COT REQUEST is published as an AsduReceived; confirm it.
    Asdu readAsdu =
        events()
            .awaitAsdu(
                a ->
                    a.cause() == Cause.REQUEST
                        && a.objects().stream()
                            .anyMatch(o -> o.address().value().longValue() == IOA_SINGLE),
                WAIT_TIMEOUT);
    assertNotNull(readAsdu, "read response ASDU should carry COT REQUEST");
  }

  @Test
  @DisplayName(
      "Reading every monitor IOA round-trips each type at its native (time/non-time) TypeID")
  void readEveryMonitorType() {
    // Contract section 5: a read returns the requested IOA at its documented value and native
    // (time or non-time) TypeID as one ASDU with COT REQUEST. Read each of the 14 monitor IOAs and
    // assert the carrying ASDU's TypeID, the decoded value, and (for CP56 IOAs) a resolved
    // timestamp. This round-trips every MONITOR type, including the CP56Time2a-tagged TypeIDs that
    // an interrogation response (non-time TypeIDs per CS101) cannot carry.
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
            new ReadCase(IOA_SHORT_T, AsduType.M_ME_TF_1, true));

    for (ReadCase c : cases) {
      events().clear();
      PointAddress point = PointAddress.of(1, c.ioa());
      List<InformationObject> objects = client().read(point);
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

      // CP56Time2a-tagged reads resolve to an Instant; non-time reads carry no timestamp.
      assertEquals(
          c.timeTagged(),
          extraction.timestamp().isPresent(),
          () -> "timestamp presence for read of IOA " + c.ioa());

      // The carrying ASDU (COT REQUEST) must use the IOA's native TypeID.
      Asdu readAsdu =
          events()
              .awaitAsdu(
                  a ->
                      a.cause() == Cause.REQUEST
                          && a.objects().stream()
                              .anyMatch(o -> o.address().value().longValue() == c.ioa()),
                  WAIT_TIMEOUT);
      assertNotNull(readAsdu, () -> "read of IOA " + c.ioa() + " must publish a COT REQUEST ASDU");
      assertEquals(
          c.expectedType(),
          readAsdu.type(),
          () -> "read of IOA " + c.ioa() + " must return its native TypeID");
    }
  }

  // --- Commands -------------------------------------------------------------------------------

  @Test
  @DisplayName("Every command type to an ACCEPT IOA is confirmed and terminated")
  void acceptedCommands() {
    PointAddress accept = PointAddress.of(1, IOA_ACCEPT);

    // Each entry: a builder for the command under test, keyed by a human label for diagnostics.
    record Case(String label, Command command, boolean supportsSelectBeforeOperate) {}

    List<Case> cases =
        List.of(
            new Case("single(45)", Command.single(accept, true), true),
            new Case("double(46)", Command.doublePoint(accept, DoubleCommandState.ON), true),
            new Case(
                "regulating-step(47)",
                Command.regulatingStep(accept, StepCommandState.NEXT_STEP_HIGHER),
                true),
            new Case(
                "setpoint-norm(48)",
                Command.setpointNormalized(accept, NormalizedValue.of(0.25)),
                true),
            new Case("setpoint-scaled(49)", Command.setpointScaled(accept, (short) 4321), true),
            new Case("setpoint-short(50)", Command.setpointShortFloat(accept, 2.71828f), true),
            new Case("bitstring(51)", Command.bitstring(accept, 0x0F0F0F0F), false));

    for (Case c : cases) {
      CommandResult direct =
          client().commands().send(c.command(), CommandMode.directExecute(), AWAIT_TERMINATION);
      assertEquals(
          CommandOutcome.TERMINATED,
          direct.outcome(),
          () ->
              c.label()
                  + " direct-execute must be confirmed and terminated; cause="
                  + direct.cause());

      if (c.supportsSelectBeforeOperate()) {
        CommandResult sbo =
            client()
                .commands()
                .send(c.command(), CommandMode.selectBeforeOperate(), AWAIT_TERMINATION);
        assertEquals(
            CommandOutcome.TERMINATED,
            sbo.outcome(),
            () ->
                c.label()
                    + " select-before-operate must be confirmed and terminated; cause="
                    + sbo.cause());
      }
    }
  }

  @Test
  @DisplayName("A selection at an ACCEPT IOA is cancelled with DEACT_CON")
  void selectThenCancel() {
    PointAddress accept = PointAddress.of(1, IOA_ACCEPT);
    for (Command command :
        List.of(
            Command.single(accept, true),
            Command.doublePoint(accept, DoubleCommandState.ON),
            Command.setpointShortFloat(accept, 2.71828f))) {
      SelectedCommand selected = client().commands().select(command);
      CommandResult cancelled = selected.cancel();
      assertEquals(CommandOutcome.CONFIRMED, cancelled.outcome(), () -> command + " cancel");
      assertEquals(Cause.DEACTIVATION_CONFIRMATION, cancelled.cause());

      // The reservation was released, so the point can be selected and executed again.
      try (SelectedCommand again = client().commands().select(command, AWAIT_TERMINATION)) {
        assertEquals(CommandOutcome.TERMINATED, again.execute().outcome());
      }
    }
  }

  @Test
  @DisplayName("An execute at IOA 2998 is confirmed but never terminated")
  void missingTermination() {
    CommandResult result =
        client()
            .commands()
            .send(
                Command.single(PointAddress.of(1, IOA_NO_ACT_TERM), true),
                CommandMode.directExecute(),
                AWAIT_TERMINATION.withTerminationTimeout(Duration.ofSeconds(1)));
    assertEquals(CommandOutcome.TERMINATION_MISSING, result.outcome());
  }

  @Test
  @DisplayName("An execute at IOA 2999 is terminated negatively")
  void negativeTermination() {
    CommandResult result =
        client()
            .commands()
            .send(
                Command.single(PointAddress.of(1, IOA_NEGATIVE_ACT_TERM), true),
                CommandMode.selectBeforeOperate(),
                AWAIT_TERMINATION);
    assertEquals(CommandOutcome.TERMINATED_NEGATIVE, result.outcome());
    assertFalse(result.positive());
  }

  @Test
  @DisplayName("A command to an unknown IOA is rejected by its COT 47 reply without P/N")
  void unknownAddressCommand() {
    CommandResult result = client().commands().single(PointAddress.of(1, IOA_UNKNOWN), true);
    assertEquals(CommandOutcome.REJECTED, result.outcome());
    assertEquals(Cause.UNKNOWN_INFORMATION_OBJECT_ADDRESS, result.cause());
    assertFalse(result.confirmation().orElseThrow().negative(), "the fixture leaves P/N clear");
  }

  @Test
  @DisplayName("A command to the REJECT IOA 3000 is negatively confirmed")
  void rejectedCommand() {
    PointAddress reject = PointAddress.of(1, IOA_REJECT);
    CommandResult result = client().commands().single(reject, true);
    assertFalse(result.positive(), "command to IOA 3000 must be negatively confirmed (P/N=1)");
  }

  // --- Clock sync, test command, reset process ------------------------------------------------

  @Test
  @DisplayName("Clock synchronization is positively confirmed")
  void clockSync() {
    // synchronizeClock throws on a negative confirmation; reaching the next line means a positive
    // ACT_CON arrived (contract section 6).
    client().synchronizeClock(STATION, Instant.now());
  }

  @Test
  @DisplayName("Test command (C_TS_TA_1) is positively confirmed with the same TSC")
  void testCommand() {
    // IEC 60870-5-104 clause 6 selects only C_TS_TA_1; the lib60870-C core echoes it with COT 7.
    TestResult first = client().test(STATION);
    TestResult second = client().test(STATION);

    assertEquals(AsduType.C_TS_TA_1, first.confirmation().type());
    assertFalse(first.roundTripTime().isNegative(), "round-trip time must not be negative");
    int firstTsc = testSequenceCounter(first);
    assertEquals((firstTsc + 1) & 0xFFFF, testSequenceCounter(second), "the TSC increments");
  }

  @Test
  @DisplayName("Reset process is positively confirmed and re-issues End-of-Initialization")
  void resetProcess() {
    events().clear();

    // resetProcess throws on a negative confirmation; returning means a positive ACT_CON arrived.
    client().resetProcess(STATION, QualifierOfResetProcess.GENERAL);

    // Contract section 6: a reset process re-issues End-of-Initialization (M_EI_NA_1).
    Asdu endOfInit = events().awaitAsdu(a -> a.type() == AsduType.M_EI_NA_1, WAIT_TIMEOUT);
    assertNotNull(endOfInit, "reset process should re-issue End-of-Initialization (M_EI_NA_1)");
  }

  // --- Passive / periodic ---------------------------------------------------------------------

  @Test
  @DisplayName("A periodic M_ME_NB_1 update for IOA 1050 (COT PERIODIC) arrives within ~5s")
  void periodicUpdate() {
    // Contract section 8: the server enqueues a periodic scaled measured value (M_ME_NB_1) at IOA
    // 1050 every 2s with COT PERIODIC. Subscribe-and-wait for a matching PointUpdated.
    ClientEvent.PointUpdated update =
        events()
            .awaitPointUpdated(
                u ->
                    u.address().objectAddress().value().longValue() == IOA_SCALED
                        && u.cause() == Cause.PERIODIC,
                Duration.ofSeconds(8));
    assertNotNull(update, "expected a periodic PointUpdated for IOA 1050");
    assertInstanceOf(
        Short.class, update.value().value(), "periodic 1050 value must be a scaled Short");
    assertEquals(PointType.SCALED, update.value().type(), "periodic 1050 logical point type");
    assertEquals(AsduType.M_ME_NB_1, update.asduType(), "periodic 1050 carrying wire type");
  }

  // --- Coverage notes -------------------------------------------------------------------------
  //
  // Deliberately NOT covered here, with reasons:
  //  * TLS transport: lib60870-C interop image is plaintext only (TLS is not compiled in), so the
  //    secure-transport path cannot be exercised against this peer.
  //  * Time-tagged command TypeIDs (C_SC_TA_1 .. C_BO_TA_1, ids 58..64): the contract partitions
  //    accept/reject purely by IOA and the interop_client accept test uses the non-time command
  //    forms; the non-time command TypeIDs (45..51) are fully covered above. Time-tagged command
  //    encode/decode is covered by core unit tests.
  //  * Group interrogations 3..16 and counter groups 3..4: the contract documents these as
  //    negative (ACT_CON P/N=1) but the server only assigns real points to station/group 1/group 2
  //    and general/group-1 counter; counter group 2 stands in for the rejection path above, and the
  //    rest is left to the LIMITED (interop_client-driven) scenario and core unit tests.
  static {
    log.info(
        "Coverage note: TLS, time-tagged command TypeIDs (58..64), and group 3..16 / counter "
            + "group 3..4 rejection paths are intentionally not exercised in this BROAD scenario "
            + "(see class comment).");
  }

  // --- Value assertions -----------------------------------------------------------------------

  /**
   * Asserts that {@code values} (keyed by IOA) carries all seven non-time monitor points at their
   * non-time IOAs (1000,1010,...,1060) with the fixed values from contract section 2.
   */
  private static void assertAllNonTimePoints(Map<Long, PointValue<?>> values) {
    assertSinglePoint(values.get((long) IOA_SINGLE), VAL_SINGLE);
    assertDoublePoint(values.get((long) IOA_DOUBLE), VAL_DOUBLE);
    assertStep(values.get((long) IOA_STEP), VAL_STEP);
    assertBits(values.get((long) IOA_BITS), VAL_BITS);
    assertNormalized(values.get((long) IOA_NORM), VAL_NORM);
    assertScaled(values.get((long) IOA_SCALED), VAL_SCALED);
    assertShortFloat(values.get((long) IOA_SHORT), VAL_SHORT);
  }

  /**
   * Asserts that {@code values} (keyed by IOA) carries all seven time-tagged monitor points at
   * their time-tagged IOAs (1001,1011,...,1061) with the fixed values from contract section 2.
   * Group 2 reports these via their non-time TypeIDs (per CS101), so only the value is asserted
   * here.
   */
  private static void assertAllTimeTaggedPointsByValue(Map<Long, PointValue<?>> values) {
    assertSinglePoint(values.get((long) IOA_SINGLE_T), VAL_SINGLE);
    assertDoublePoint(values.get((long) IOA_DOUBLE_T), VAL_DOUBLE);
    assertStep(values.get((long) IOA_STEP_T), VAL_STEP);
    assertBits(values.get((long) IOA_BITS_T), VAL_BITS);
    assertNormalized(values.get((long) IOA_NORM_T), VAL_NORM);
    assertScaled(values.get((long) IOA_SCALED_T), VAL_SCALED);
    assertShortFloat(values.get((long) IOA_SHORT_T), VAL_SHORT);
  }

  /**
   * Asserts the decoded value for a single monitor IOA (time or non-time variant) matches its fixed
   * contract value, dispatching on the IOA's type block.
   */
  private static void assertMonitorValue(int ioa, PointValue<?> value) {
    switch (ioa) {
      case IOA_SINGLE, IOA_SINGLE_T -> assertSinglePoint(value, VAL_SINGLE);
      case IOA_DOUBLE, IOA_DOUBLE_T -> assertDoublePoint(value, VAL_DOUBLE);
      case IOA_STEP, IOA_STEP_T -> assertStep(value, VAL_STEP);
      case IOA_BITS, IOA_BITS_T -> assertBits(value, VAL_BITS);
      case IOA_NORM, IOA_NORM_T -> assertNormalized(value, VAL_NORM);
      case IOA_SCALED, IOA_SCALED_T -> assertScaled(value, VAL_SCALED);
      case IOA_SHORT, IOA_SHORT_T -> assertShortFloat(value, VAL_SHORT);
      default -> throw new AssertionError("no fixed value mapping for IOA " + ioa);
    }
  }

  private static void assertSinglePoint(PointValue<?> value, boolean expected) {
    assertNotNull(value, "missing single-point value");
    assertEquals(PointType.SINGLE_POINT, value.type(), "single-point type");
    assertEquals(expected, value.value(), "single-point state");
  }

  // expected is fixed per contract today but kept to make each typed helper self-documenting.
  @SuppressWarnings("SameParameterValue")
  private static void assertDoublePoint(PointValue<?> value, DoublePointState expected) {
    assertNotNull(value, "missing double-point value");
    assertEquals(PointType.DOUBLE_POINT, value.type(), "double-point type");
    assertEquals(expected, value.value(), "double-point state");
  }

  @SuppressWarnings("SameParameterValue")
  private static void assertStep(PointValue<?> value, int expected) {
    assertNotNull(value, "missing step-position value");
    assertEquals(PointType.STEP_POSITION, value.type(), "step-position type");
    Vti vti = assertInstanceOf(Vti.class, value.value(), "step value should be a Vti");
    assertEquals(expected, vti.value(), "step position value");
    assertFalse(vti.transientState(), "step transient flag should be false");
  }

  @SuppressWarnings("SameParameterValue")
  private static void assertBits(PointValue<?> value, int expected) {
    assertNotNull(value, "missing bitstring value");
    assertEquals(PointType.BITSTRING32, value.type(), "bitstring type");
    assertEquals(expected, value.value(), "bitstring 32 value");
  }

  @SuppressWarnings("SameParameterValue")
  private static void assertNormalized(PointValue<?> value, double expected) {
    assertNotNull(value, "missing normalized value");
    assertEquals(PointType.NORMALIZED, value.type(), "normalized type");
    NormalizedValue normalized =
        assertInstanceOf(NormalizedValue.class, value.value(), "normalized value type");
    double actual = normalized.doubleValue();
    // Within one LSB (~3.05e-5) of the requested 0.5 (contract section 2).
    assertTrue(
        Math.abs(actual - expected) <= 3.06e-5,
        "normalized value " + actual + " not within 1 LSB of " + expected);
  }

  @SuppressWarnings("SameParameterValue")
  private static void assertScaled(PointValue<?> value, short expected) {
    assertNotNull(value, "missing scaled value");
    assertEquals(PointType.SCALED, value.type(), "scaled type");
    assertEquals(expected, value.value(), "scaled value");
  }

  @SuppressWarnings("SameParameterValue")
  private static void assertShortFloat(PointValue<?> value, float expected) {
    assertNotNull(value, "missing short-float value");
    assertEquals(PointType.SHORT_FLOAT, value.type(), "short-float type");
    Float actual = assertInstanceOf(Float.class, value.value(), "short-float value type");
    assertEquals(expected, actual, 1e-4f, "short-float value");
  }

  // --- Projection helpers ---------------------------------------------------------------------

  /** Projects an interrogation result onto {@code IOA -> PointValue}. */
  private static Map<Long, PointValue<?>> byIoa(InterrogationResult result) {
    return result.pointValues().stream()
        .collect(
            Collectors.toMap(
                e -> e.address().objectAddress().value().longValue(),
                InterrogationResult.PointEntry::value,
                (a, b) -> b,
                LinkedHashMap::new));
  }

  /** Projects integrated-totals objects onto {@code IOA -> BinaryCounterReading}. */
  private static Map<Long, BinaryCounterReading> countersByIoa(List<InformationObject> objects) {
    Map<Long, BinaryCounterReading> out = new LinkedHashMap<>();
    for (InformationObject o : objects) {
      if (o instanceof IntegratedTotals it) {
        out.put(it.address().value().longValue(), it.counter());
      }
    }
    return out;
  }

  private static int testSequenceCounter(TestResult result) {
    return ((TestCommandWithCp56Time) result.confirmation().objects().get(0))
        .testSequenceCounter()
        .intValue();
  }

  // --- Event recorder -------------------------------------------------------------------------

  /**
   * A {@link Flow.Subscriber} that records every {@link ClientEvent} into an unbounded queue and
   * lets a test await a matching {@link ClientEvent.AsduReceived} or {@link
   * ClientEvent.PointUpdated} with a bounded timeout. The client delivers events serially on its
   * callback executor, so the queue is consumed by the waiting test thread.
   */
  private static final class EventRecorder implements Flow.Subscriber<ClientEvent> {

    private final ConcurrentLinkedQueue<ClientEvent> queue = new ConcurrentLinkedQueue<>();
    private volatile CountDownLatch tick = new CountDownLatch(1);

    @Override
    public void onSubscribe(Flow.Subscription s) {
      s.request(Long.MAX_VALUE);
    }

    @Override
    public void onNext(ClientEvent event) {
      queue.add(event);
      CountDownLatch t = tick;
      tick = new CountDownLatch(1);
      t.countDown();
    }

    @Override
    public void onError(Throwable throwable) {
      log.warn("event stream error", throwable);
    }

    @Override
    public void onComplete() {}

    /** Drops all currently-queued events so a subsequent await sees only fresh traffic. */
    void clear() {
      queue.clear();
    }

    /**
     * Awaits an {@link ClientEvent.AsduReceived} whose ASDU matches {@code predicate}, returning
     * the matching ASDU or {@code null} if the timeout elapses first.
     */
    @SuppressWarnings("SameParameterValue") // timeout kept symmetric with awaitPointUpdated
    @Nullable Asdu awaitAsdu(Predicate<Asdu> predicate, Duration timeout) {
      long deadline = System.nanoTime() + timeout.toNanos();
      while (true) {
        for (ClientEvent event : queue) {
          if (event instanceof ClientEvent.AsduReceived received
              && predicate.test(received.asdu())) {
            queue.remove(event);
            return received.asdu();
          }
        }
        if (awaitTickTimedOut(deadline)) {
          return null;
        }
      }
    }

    /**
     * Awaits a {@link ClientEvent.PointUpdated} matching {@code predicate}, returning it or {@code
     * null} if the timeout elapses first.
     */
    ClientEvent.@Nullable PointUpdated awaitPointUpdated(
        Predicate<ClientEvent.PointUpdated> predicate, Duration timeout) {
      long deadline = System.nanoTime() + timeout.toNanos();
      while (true) {
        for (ClientEvent event : queue) {
          if (event instanceof ClientEvent.PointUpdated updated && predicate.test(updated)) {
            queue.remove(event);
            return updated;
          }
        }
        if (awaitTickTimedOut(deadline)) {
          return null;
        }
      }
    }

    private boolean awaitTickTimedOut(long deadlineNanos) {
      long remaining = deadlineNanos - System.nanoTime();
      if (remaining <= 0) {
        return true;
      }
      try {
        return !(tick.await(remaining, TimeUnit.NANOSECONDS) || System.nanoTime() < deadlineNanos);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return true;
      }
    }
  }
}
