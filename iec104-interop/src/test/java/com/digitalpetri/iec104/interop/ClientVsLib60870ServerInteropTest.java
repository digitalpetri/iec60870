package com.digitalpetri.iec104.interop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.digitalpetri.iec104.ProtocolProfile;
import com.digitalpetri.iec104.address.CommonAddress;
import com.digitalpetri.iec104.address.InformationObjectAddress;
import com.digitalpetri.iec104.address.OriginatorAddress;
import com.digitalpetri.iec104.address.PointAddress;
import com.digitalpetri.iec104.asdu.Asdu;
import com.digitalpetri.iec104.asdu.AsduType;
import com.digitalpetri.iec104.asdu.Cause;
import com.digitalpetri.iec104.asdu.InformationObject;
import com.digitalpetri.iec104.asdu.element.BinaryCounterReading;
import com.digitalpetri.iec104.asdu.element.DoubleCommandState;
import com.digitalpetri.iec104.asdu.element.DoublePointState;
import com.digitalpetri.iec104.asdu.element.FixedTestBitPattern;
import com.digitalpetri.iec104.asdu.element.FreezeMode;
import com.digitalpetri.iec104.asdu.element.NormalizedValue;
import com.digitalpetri.iec104.asdu.element.QualifierOfCounterInterrogation;
import com.digitalpetri.iec104.asdu.element.QualifierOfInterrogation;
import com.digitalpetri.iec104.asdu.element.QualifierOfResetProcess;
import com.digitalpetri.iec104.asdu.element.StepCommandState;
import com.digitalpetri.iec104.asdu.element.Vti;
import com.digitalpetri.iec104.asdu.object.CounterInterrogationCommand;
import com.digitalpetri.iec104.asdu.object.IntegratedTotals;
import com.digitalpetri.iec104.asdu.object.ResetProcessCommand;
import com.digitalpetri.iec104.asdu.object.TestCommand;
import com.digitalpetri.iec104.client.ClientEvent;
import com.digitalpetri.iec104.client.Command;
import com.digitalpetri.iec104.client.CommandMode;
import com.digitalpetri.iec104.client.CommandResult;
import com.digitalpetri.iec104.client.Iec104Client;
import com.digitalpetri.iec104.client.InterrogationResult;
import com.digitalpetri.iec104.point.MonitorMapping;
import com.digitalpetri.iec104.point.PointValue;
import com.digitalpetri.iec104.point.PointValueExtraction;
import com.digitalpetri.iec104.transport.tcp.TcpIec104Client;
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
 * BROAD interop scenario: OUR Java {@link Iec104Client} (controlling station) driving the
 * lib60870-C {@code interop_server} (controlled station) over plaintext IEC 60870-5-104.
 *
 * <p>Every assertion is anchored to {@code iec104-interop/docker/INTEROP-CONTRACT.md}. The single
 * lib60870-C peer image is built from {@code iec104-interop/docker/lib60870c} via {@link
 * ImageFromDockerfile} so the test is self-contained (the Docker layer cache makes reruns fast);
 * the container command is {@code stdbuf -oL -eL interop_server} and startup is gated on the {@code
 * INTEROP-SERVER READY} log marker. One container is shared by all {@code @Test} methods via
 * {@code @TestInstance(PER_CLASS)}.
 *
 * <p>The high-level {@link Iec104Client} exposes interrogation, group interrogation, read, the full
 * command surface, and clock sync directly. Counter interrogation, the test command, and the reset
 * process command have no dedicated high-level method on this client, so they are exercised with
 * raw {@link Iec104Client#send(Asdu)} calls and their confirmations are observed through {@link
 * Iec104Client#events()}.
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
 * non-time) TypeID. The interrogation and read tests below decode and assert the value (and, for
 * reads, the TypeID) of every documented monitor point.
 */
@Tag("interop")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Java client vs lib60870-C interop_server")
// The single Iec104Client is owned by the @BeforeAll/@AfterAll lifecycle and shared by all tests;
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
              new ImageFromDockerfile("iec104-interop/lib60870c-interop", false)
                  .withFileFromPath(".", Path.of("docker/lib60870c")))
          .withExposedPorts(2404)
          .withCommand("stdbuf", "-oL", "-eL", "interop_server")
          .withStartupTimeout(STARTUP_TIMEOUT)
          .waitingFor(Wait.forLogMessage(".*INTEROP-SERVER READY.*", 1));

  private @Nullable Iec104Client client;
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
  private Iec104Client client() {
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

  // --- Counter interrogation ------------------------------------------------------------------

  @Test
  @DisplayName("Counter interrogation returns integrated totals (1070=1000, 1071=1000)")
  void counterInterrogation() {
    // No high-level counter-interrogation method on Iec104Client: send a raw C_CI_NA_1 (general
    // counter request) and collect the M_IT_NA_1 data ASDUs from events().
    events().clear();

    Asdu request =
        new Asdu(
            AsduType.C_CI_NA_1,
            false,
            Cause.ACTIVATION,
            false,
            false,
            OriginatorAddress.of(3),
            STATION,
            List.of(
                new CounterInterrogationCommand(
                    InformationObjectAddress.of(0),
                    // RQT_GENERAL = 5; freeze mode is accepted but ignored by the fixed image.
                    new QualifierOfCounterInterrogation(5, FreezeMode.READ))));

    client().send(request);

    // Expect: ACT_CON (positive), the counter data ASDU(s) with COT REQUESTED_BY_GENERAL_COUNTER,
    // then ACT_TERM. Wait for the data ASDU carrying the two integrated totals.
    Asdu data =
        events()
            .awaitAsdu(
                a ->
                    a.type() == AsduType.M_IT_NA_1
                        && a.cause() == Cause.REQUESTED_BY_GENERAL_COUNTER
                        && !a.objects().isEmpty(),
                WAIT_TIMEOUT);
    assertNotNull(data, "expected M_IT_NA_1 counter data with COT REQUESTED_BY_GENERAL_COUNTER");

    // Also confirm the positive ACT_CON for the counter interrogation was received.
    Asdu actCon =
        events()
            .awaitAsdu(
                a -> a.type() == AsduType.C_CI_NA_1 && a.cause() == Cause.ACTIVATION_CONFIRMATION,
                WAIT_TIMEOUT);
    assertNotNull(actCon, "expected ACT_CON for counter interrogation");
    assertFalse(actCon.negative(), "counter interrogation ACT_CON must be positive");

    Map<Long, BinaryCounterReading> counters = countersByIoa(data);
    assertTrue(counters.containsKey((long) IOA_COUNTER), "counter at 1070 must be reported");
    assertEquals(1000, counters.get((long) IOA_COUNTER).value(), "1070 counter value == 1000");

    // 1071 may arrive in the same or a subsequent data ASDU; merge.
    if (!counters.containsKey((long) IOA_COUNTER_T)) {
      Asdu more =
          events()
              .awaitAsdu(
                  a ->
                      a.type() == AsduType.M_IT_NA_1
                          && a.cause() == Cause.REQUESTED_BY_GENERAL_COUNTER
                          && countersByIoa(a).containsKey((long) IOA_COUNTER_T),
                  WAIT_TIMEOUT);
      if (more != null) {
        counters.putAll(countersByIoa(more));
      }
    }
    assertTrue(counters.containsKey((long) IOA_COUNTER_T), "counter at 1071 must be reported");
    assertEquals(1000, counters.get((long) IOA_COUNTER_T).value(), "1071 counter value == 1000");
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
  @DisplayName("Every command type to an ACCEPT IOA is positively confirmed (direct + SBO)")
  void acceptedCommands() {
    PointAddress accept = PointAddress.of(1, IOA_ACCEPT);

    // Each entry: a builder for the command under test, keyed by a human label for diagnostics.
    record Case(String label, Command command) {}
    List<Case> cases =
        List.of(
            new Case("single(45)", Command.single(accept, true)),
            new Case("double(46)", Command.doublePoint(accept, DoubleCommandState.ON)),
            new Case(
                "regulating-step(47)",
                Command.regulatingStep(accept, StepCommandState.NEXT_STEP_HIGHER)),
            new Case(
                "setpoint-norm(48)", Command.setpointNormalized(accept, NormalizedValue.of(0.25))),
            new Case("setpoint-scaled(49)", Command.setpointScaled(accept, (short) 4321)),
            new Case("setpoint-short(50)", Command.setpointShortFloat(accept, 2.71828f)),
            new Case("bitstring(51)", Command.bitstring(accept, 0x0F0F0F0F)));

    for (Case c : cases) {
      CommandResult direct = client().commands().send(c.command(), CommandMode.directExecute());
      assertTrue(
          direct.positive(),
          () ->
              c.label() + " direct-execute must be positively confirmed; cause=" + direct.cause());

      CommandResult sbo = client().commands().send(c.command(), CommandMode.selectBeforeOperate());
      assertTrue(
          sbo.positive(),
          () ->
              c.label()
                  + " select-before-operate must be positively confirmed; cause="
                  + sbo.cause());
    }
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
    // synchronizeClock throws on a negative confirmation; reaching the next line means ACT_CON
    // positive (contract section 6).
    client().synchronizeClock(STATION, Instant.now());
  }

  @Test
  @DisplayName("Test command is positively confirmed (ACT_CON)")
  void testCommand() {
    // No high-level test-command method: send a raw C_TS_NA_1 and observe the positive ACT_CON.
    events().clear();
    Asdu request =
        new Asdu(
            AsduType.C_TS_NA_1,
            false,
            Cause.ACTIVATION,
            false,
            false,
            OriginatorAddress.of(3),
            STATION,
            List.of(new TestCommand(InformationObjectAddress.of(0), FixedTestBitPattern.DEFAULT)));
    client().send(request);

    Asdu actCon =
        events()
            .awaitAsdu(
                a -> a.type() == AsduType.C_TS_NA_1 && a.cause() == Cause.ACTIVATION_CONFIRMATION,
                WAIT_TIMEOUT);
    assertNotNull(actCon, "expected ACT_CON for test command");
    assertFalse(actCon.negative(), "test command ACT_CON must be positive");
  }

  @Test
  @DisplayName("Reset process is positively confirmed and re-issues End-of-Initialization")
  void resetProcess() {
    // No high-level reset method: send a raw C_RP_NA_1 (general) and observe the positive ACT_CON.
    events().clear();
    Asdu request =
        new Asdu(
            AsduType.C_RP_NA_1,
            false,
            Cause.ACTIVATION,
            false,
            false,
            OriginatorAddress.of(3),
            STATION,
            List.of(
                new ResetProcessCommand(
                    InformationObjectAddress.of(0), QualifierOfResetProcess.GENERAL)));
    client().send(request);

    Asdu actCon =
        events()
            .awaitAsdu(
                a -> a.type() == AsduType.C_RP_NA_1 && a.cause() == Cause.ACTIVATION_CONFIRMATION,
                WAIT_TIMEOUT);
    assertNotNull(actCon, "expected ACT_CON for reset process");
    assertFalse(actCon.negative(), "reset process ACT_CON must be positive");

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
  //  * Group interrogations 3..16 and counter groups 2..4: the contract documents these as
  //    negative (ACT_CON P/N=1) but the server only assigns real points to station/group 1/group 2
  //    and general/group-1 counter; the negative-rejection path for the unused groups is left to
  //    the LIMITED (interop_client-driven) scenario and core unit tests.
  static {
    log.info(
        "Coverage note: TLS, time-tagged command TypeIDs (58..64), and group 3..16 / counter "
            + "group 2..4 rejection paths are intentionally not exercised in this BROAD scenario "
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
    assertEquals(expected, value.value(), "single-point state");
  }

  // expected is fixed per contract today but kept to make each typed helper self-documenting.
  @SuppressWarnings("SameParameterValue")
  private static void assertDoublePoint(PointValue<?> value, DoublePointState expected) {
    assertNotNull(value, "missing double-point value");
    assertEquals(expected, value.value(), "double-point state");
  }

  @SuppressWarnings("SameParameterValue")
  private static void assertStep(PointValue<?> value, int expected) {
    assertNotNull(value, "missing step-position value");
    Vti vti = assertInstanceOf(Vti.class, value.value(), "step value should be a Vti");
    assertEquals(expected, vti.value(), "step position value");
    assertFalse(vti.transientState(), "step transient flag should be false");
  }

  @SuppressWarnings("SameParameterValue")
  private static void assertBits(PointValue<?> value, int expected) {
    assertNotNull(value, "missing bitstring value");
    assertEquals(expected, value.value(), "bitstring 32 value");
  }

  @SuppressWarnings("SameParameterValue")
  private static void assertNormalized(PointValue<?> value, double expected) {
    assertNotNull(value, "missing normalized value");
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
    assertEquals(expected, value.value(), "scaled value");
  }

  @SuppressWarnings("SameParameterValue")
  private static void assertShortFloat(PointValue<?> value, float expected) {
    assertNotNull(value, "missing short-float value");
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

  /** Projects an integrated-totals ASDU onto {@code IOA -> BinaryCounterReading}. */
  private static Map<Long, BinaryCounterReading> countersByIoa(Asdu asdu) {
    Map<Long, BinaryCounterReading> out = new LinkedHashMap<>();
    for (InformationObject o : asdu.objects()) {
      if (o instanceof IntegratedTotals it) {
        out.put(it.address().value().longValue(), it.counter());
      }
    }
    return out;
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
