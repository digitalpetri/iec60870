package com.digitalpetri.iec60870.test.interop;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.digitalpetri.iec60870.ProtocolProfile;
import com.digitalpetri.iec60870.address.CommonAddress;
import com.digitalpetri.iec60870.address.PointAddress;
import com.digitalpetri.iec60870.asdu.Cause;
import com.digitalpetri.iec60870.asdu.element.BinaryCounterReading;
import com.digitalpetri.iec60870.asdu.element.DoublePointState;
import com.digitalpetri.iec60870.asdu.element.NormalizedValue;
import com.digitalpetri.iec60870.asdu.element.Vti;
import com.digitalpetri.iec60870.asdu.object.SingleCommand;
import com.digitalpetri.iec60870.point.PointCapability;
import com.digitalpetri.iec60870.point.PointType;
import com.digitalpetri.iec60870.point.PointValue;
import com.digitalpetri.iec60870.server.CommandDecision;
import com.digitalpetri.iec60870.server.CommandRequest;
import com.digitalpetri.iec60870.server.Iec60870Server;
import com.digitalpetri.iec60870.server.PointDefinition;
import com.digitalpetri.iec60870.server.ServerContext;
import com.digitalpetri.iec60870.server.ServerHandler;
import com.digitalpetri.iec60870.server.Station;
import com.digitalpetri.iec60870.tcp.TcpIec104Server;
import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.Testcontainers;
import org.testcontainers.containers.ContainerLaunchException;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.output.Slf4jLogConsumer;
import org.testcontainers.containers.output.ToStringConsumer;
import org.testcontainers.containers.startupcheck.OneShotStartupCheckStrategy;
import org.testcontainers.images.builder.ImageFromDockerfile;

/**
 * LIMITED interop scenario: the lib60870-C {@code interop_client} (controlling station) driving OUR
 * Java {@link Iec60870Server} (controlled station) over plaintext IEC 60870-5-104.
 *
 * <p>This is the mirror of {@link ClientVsLib60870ServerInteropTest}. The roles are reversed: the
 * external lib60870-C peer is the <em>client</em> and our library is the <em>server</em>. The C
 * peer runs the scripted sequence documented in {@code
 * iec60870-test-interop/docker/INTEROP-CONTRACT.md} section 11 and prints {@code PASS:}/{@code
 * FAIL:} markers plus a final {@code INTEROP-CLIENT RESULT pass=<n> fail=<n>} line; this test
 * asserts on those markers.
 *
 * <h2>Wiring</h2>
 *
 * <p>The Java server binds {@code 127.0.0.1} on an ephemeral port (chosen with a throw-away {@link
 * ServerSocket} so the concrete number is known up front, since {@link Iec60870Server} exposes no
 * bound-port accessor). The port is published to containers with {@link
 * Testcontainers#exposeHostPorts(int...)}, and the {@code interop_client} container reaches it at
 * {@code host.testcontainers.internal:<port>}. The peer image is built from {@code
 * iec60870-test-interop/docker/lib60870c} via {@link ImageFromDockerfile} so the test is
 * self-contained (the Docker layer cache makes reruns fast; the first run shallow-clones and builds
 * lib60870-C, which can take minutes). The client container is one-shot: it runs the script and
 * exits, so its startup uses {@link OneShotStartupCheckStrategy} (which blocks {@code start()}
 * until the process exits, bounded by the startup timeout) and its full stdout is captured with a
 * {@link ToStringConsumer}. A non-zero client exit (returned when {@code fail != 0}) surfaces as a
 * {@link ContainerLaunchException}, which is caught so the captured log drives a readable
 * assertion.
 *
 * <h2>Server capability level (contract section 11)</h2>
 *
 * <p>Our server implements the accept/reject IOA convention (section 7), station and counter
 * interrogation (sections 3-4), read (section 5), clock sync and the test command (section 6), so
 * every mandatory step of the client script is supported and {@code fail=0} is asserted. The
 * contract notes the test may instead assert on the supported subset when a step is unsupported;
 * here the full subset is supported, so each individual {@code PASS:} marker is asserted as well.
 * APCI lifecycle (STARTDT/STOPDT, and the TESTFR keepalive exercised by the client's idle step) is
 * covered implicitly by the client script driving the connection through to {@code STOPDT_CON}.
 */
@Tag("interop")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("lib60870-C interop_client vs Java server")
class ServerVsLib60870ClientInteropTest {

  private static final Logger log =
      LoggerFactory.getLogger(ServerVsLib60870ClientInteropTest.class);

  /** Contract section 1: CA = 1. */
  private static final CommonAddress STATION = CommonAddress.of(1);

  /** Contract section 2: monitor IOAs. */
  private static final int IOA_SINGLE = 1000; // read target + single-command return-info mirror

  private static final int IOA_DOUBLE = 1010;
  private static final int IOA_STEP = 1020;
  private static final int IOA_BITS = 1030;
  private static final int IOA_NORM = 1040;
  private static final int IOA_SCALED = 1050; // periodic update IOA (section 8)
  private static final int IOA_SHORT = 1060; // setpoint-short return-info mirror
  private static final int IOA_COUNTER_A = 1070; // integrated totals (counter interrogation)
  private static final int IOA_COUNTER_B = 1071;

  /** Contract section 7: accept range 2000..2999, reject IOA 3000. */
  private static final int IOA_ACCEPT = 2000;

  private static final int IOA_REJECT = 3000;

  /** Fixed monitor values per contract section 2. */
  private static final int COUNTER_VALUE = 1000;

  private static final short SCALED_VALUE = 12345;

  /** Generous: the first run shallow-clones + builds lib60870-C (minutes). */
  private static final Duration IMAGE_BUILD_TIMEOUT = Duration.ofMinutes(10);

  private @Nullable ScheduledExecutorService periodic;
  private @Nullable Iec60870Server server;
  private int serverPort;

  @BeforeAll
  void startServer() throws IOException {
    serverPort = freeEphemeralPort();

    Station station =
        Station.builder(STATION)
            // Single point @1000: REPORTED (station/group IC) + READABLE (read command, step 5) +
            // COMMANDABLE (single-command return-info mirror, section 7). Fixed value ON.
            .point(
                PointDefinition.of(
                    PointAddress.of(1, IOA_SINGLE),
                    PointType.SINGLE_POINT,
                    PointValue.single(true),
                    PointCapability.REPORTED,
                    PointCapability.READABLE,
                    PointCapability.COMMANDABLE))
            // A few more monitored points so station/group interrogation returns a real image.
            .point(
                PointDefinition.of(
                    PointAddress.of(1, IOA_DOUBLE),
                    PointType.DOUBLE_POINT,
                    PointValue.doublePoint(DoublePointState.ON),
                    PointCapability.REPORTED,
                    PointCapability.READABLE))
            .point(
                PointDefinition.of(
                    PointAddress.of(1, IOA_STEP),
                    PointType.STEP_POSITION,
                    PointValue.stepPosition(new Vti(7, false)),
                    PointCapability.REPORTED,
                    PointCapability.READABLE))
            .point(
                PointDefinition.of(
                    PointAddress.of(1, IOA_BITS),
                    PointType.BITSTRING32,
                    PointValue.bitstring(0x12345678),
                    PointCapability.REPORTED,
                    PointCapability.READABLE))
            .point(
                PointDefinition.of(
                    PointAddress.of(1, IOA_NORM),
                    PointType.NORMALIZED,
                    PointValue.normalized(NormalizedValue.of(0.5)),
                    PointCapability.REPORTED,
                    PointCapability.READABLE))
            .point(
                PointDefinition.of(
                    PointAddress.of(1, IOA_SCALED),
                    PointType.SCALED,
                    PointValue.scaled(SCALED_VALUE),
                    PointCapability.REPORTED,
                    PointCapability.READABLE))
            .point(
                PointDefinition.of(
                    PointAddress.of(1, IOA_SHORT),
                    PointType.SHORT_FLOAT,
                    PointValue.shortFloat(3.14159f),
                    PointCapability.REPORTED,
                    PointCapability.READABLE,
                    PointCapability.COMMANDABLE))
            // Integrated totals @1070/1071: counter interrogation (section 4). NOT in station IC
            // (the server's counter handling selects only INTEGRATED_TOTALS points), value 1000.
            .point(
                PointDefinition.of(
                    PointAddress.of(1, IOA_COUNTER_A),
                    PointType.INTEGRATED_TOTALS,
                    PointValue.counter(
                        new BinaryCounterReading(COUNTER_VALUE, 0, false, false, false)),
                    PointCapability.REPORTED))
            .point(
                PointDefinition.of(
                    PointAddress.of(1, IOA_COUNTER_B),
                    PointType.INTEGRATED_TOTALS,
                    PointValue.counter(
                        new BinaryCounterReading(COUNTER_VALUE, 0, false, false, false)),
                    PointCapability.REPORTED))
            // Group assignments (contract section 3): group 1 = non-time points, group 2 is unused
            // here. The client script only issues a STATION interrogation, so any group mapping
            // suffices.
            .group(1, PointAddress.of(1, IOA_SINGLE))
            .group(1, PointAddress.of(1, IOA_DOUBLE))
            .group(1, PointAddress.of(1, IOA_STEP))
            .group(1, PointAddress.of(1, IOA_BITS))
            .group(1, PointAddress.of(1, IOA_NORM))
            .group(1, PointAddress.of(1, IOA_SCALED))
            .group(1, PointAddress.of(1, IOA_SHORT))
            .build();

    server =
        TcpIec104Server.builder()
            .bindAddress("127.0.0.1")
            .port(serverPort)
            .profile(ProtocolProfile.iec104Default())
            .addStation(station)
            .handler(new AcceptRejectHandler())
            .build();

    server.start();
    log.info("Java server listening on 127.0.0.1:{}", serverPort);

    // Contract section 8: enqueue a periodic scaled measured value at IOA 1050 every 2s with COT
    // PERIODIC so passive observers (and the client's keepalive idle window) see live traffic.
    AtomicInteger periodicValue = new AtomicInteger(SCALED_VALUE);
    periodic = new ScheduledThreadPoolExecutor(1, ServerVsLib60870ClientInteropTest::daemon);
    periodic.scheduleAtFixedRate(
        () -> {
          try {
            short v = (short) periodicValue.incrementAndGet();
            server.publish(PointAddress.of(1, IOA_SCALED), PointValue.scaled(v), Cause.PERIODIC);
          } catch (RuntimeException e) {
            log.debug("periodic publish skipped", e);
          }
        },
        2,
        2,
        TimeUnit.SECONDS);

    // Make the host server port reachable from the client container.
    Testcontainers.exposeHostPorts(serverPort);
  }

  @AfterAll
  void stopServer() {
    if (periodic != null) {
      periodic.shutdownNow();
    }
    if (server != null) {
      // close() unbinds the transport and shuts down its (owned) Netty event loop groups, so the
      // JVM does not hang on lingering non-daemon I/O threads.
      server.close();
    }
  }

  @Test
  @DisplayName("interop_client completes its scripted sequence with fail=0")
  void clientScriptedSequencePasses() {
    ToStringConsumer logs = new ToStringConsumer();

    // The container IS managed by this try-with-resources; the inspection misfires because the
    // fluent withX() builder methods return SELF, so it can't prove that the chained value is the
    // same instance that gets closed.
    //noinspection resource
    try (GenericContainer<?> client =
        new GenericContainer<>(
                new ImageFromDockerfile("iec60870-test-interop/lib60870c-interop", false)
                    .withFileFromPath(".", Path.of("docker/lib60870c")))
            .withStartupTimeout(IMAGE_BUILD_TIMEOUT)
            // One-shot: the client runs the script and exits; do not wait for a listening port.
            .withStartupCheckStrategy(new OneShotStartupCheckStrategy())
            .withEnv("INTEROP_HOST", "host.testcontainers.internal")
            .withEnv("INTEROP_PORT", Integer.toString(serverPort))
            .withEnv("INTEROP_CA", "1")
            .withEnv("INTEROP_ACCEPT_IOA", Integer.toString(IOA_ACCEPT))
            .withEnv("INTEROP_REJECT_IOA", Integer.toString(IOA_REJECT))
            .withCommand(
                "stdbuf",
                "-oL",
                "-eL",
                "interop_client",
                "host.testcontainers.internal",
                Integer.toString(serverPort))) {

      client.withLogConsumer(logs);
      client.withLogConsumer(new Slf4jLogConsumer(LoggerFactory.getLogger("interop_client")));

      // One-shot under OneShotStartupCheckStrategy: start() blocks until the client process exits
      // and the startup check runs, bounded by the startup timeout, so it never hangs. A non-zero
      // exit surfaces as a ContainerLaunchException when the client returns 1 for fail != 0; catch
      // it so the captured client log drives a readable assertion instead of an opaque stack trace.
      ContainerLaunchException launchFailure = null;
      try {
        client.start();
      } catch (ContainerLaunchException e) {
        launchFailure = e;
      }

      String output = logs.toUtf8String();
      log.info("interop_client output:\n{}", output);

      // The contract guarantees a final result line; its presence proves the script ran to the end
      // (and that start() did not abort for an unrelated reason such as a connectivity failure).
      assertContains(output, "INTEROP-CLIENT RESULT pass=", output);

      // Assert the script reported no failures. On a non-zero exit the captured log (including the
      // FAIL: line and the per-ASDU CLIENT RECVD trace) is included in the failure message.
      final ContainerLaunchException launchFailureForMessage = launchFailure;
      assertTrue(
          containsResultWithFailZero(output),
          () ->
              "interop_client reported failures (fail!=0)"
                  + (launchFailureForMessage != null ? " and exited non-zero" : "")
                  + ". Full client log:\n"
                  + output);

      // Assert the individual PASS markers for the steps our server supports (contract section 11).
      assertContains(output, "PASS: connect", output);
      assertContains(output, "PASS: STARTDT_CON received", output);
      assertContains(output, "PASS: station interrogation (ACT_CON + data)", output);
      assertContains(output, "PASS: counter interrogation (ACT_CON + data)", output);
      assertContains(output, "PASS: clock sync confirmed", output);
      assertContains(output, "PASS: read command returned data", output);
      assertContains(output, "PASS: accept command confirmed (P/N=0)", output);
      assertContains(output, "PASS: reject command negatively confirmed (P/N=1)", output);
      assertContains(output, "PASS: STOPDT_CON received", output);
    }
  }

  // --- Server handler -------------------------------------------------------------------------

  /**
   * Implements the accept/reject IOA convention (contract section 7): commands to IOA 2000..2999
   * are accepted, IOA 3000 is rejected, and any other IOA is declined with {@code
   * UNKNOWN_INFORMATION_OBJECT_ADDRESS}. A single command accepted at an accept IOA mirrors its
   * commanded state onto monitor IOA 1000 as return information (section 7); other accepted
   * commands are confirmed positively without mutating a monitor point. Clock sync, read,
   * interrogation, counter interrogation, and the test command keep their default behaviors.
   */
  private static final class AcceptRejectHandler implements ServerHandler {

    @Override
    public CommandDecision onCommand(ServerContext context, CommandRequest request) {
      long ioa = request.target().objectAddress().value().longValue();

      if (ioa == IOA_REJECT) {
        // Contract section 7: the reject IOA is answered with a NEGATIVE activation confirmation
        // (COT = ACTIVATION_CONFIRMATION (7), P/N = 1). The server mirrors this decision's cause as
        // the COT with the P/N bit set, so the cause must be ACTIVATION_CONFIRMATION rather than a
        // distinct error cause; the interop_client only recognizes COT 7 as an ACT_CON.
        return CommandDecision.reject(Cause.ACTIVATION_CONFIRMATION);
      }
      if (ioa < IOA_ACCEPT || ioa > 2999) {
        // IOAs outside the documented accept range and not the reject IOA: decline as an unknown
        // information object address (contract section 7). Not exercised by the client script.
        return CommandDecision.reject(Cause.UNKNOWN_INFORMATION_OBJECT_ADDRESS);
      }

      // Accept. Mirror a single command's state onto single-point IOA 1000 (section 7); other
      // command types are confirmed positively without a return-information update.
      if (request.commandObject() instanceof SingleCommand single) {
        return CommandDecision.acceptAndUpdate(PointValue.single(single.on()));
      }
      return CommandDecision.accept();
    }
  }

  // --- Helpers --------------------------------------------------------------------------------

  /** Reserves and immediately releases an ephemeral local port, returning its number. */
  private static int freeEphemeralPort() throws IOException {
    try (ServerSocket socket = new ServerSocket(0)) {
      socket.setReuseAddress(true);
      return socket.getLocalPort();
    }
  }

  private static Thread daemon(Runnable runnable) {
    Thread thread = new Thread(runnable, "interop-periodic");
    thread.setDaemon(true);
    return thread;
  }

  private static void assertContains(String haystack, String needle, String fullLog) {
    assertTrue(
        haystack.contains(needle),
        () -> "expected client log to contain '" + needle + "'. Full client log:\n" + fullLog);
  }

  /** True if any {@code INTEROP-CLIENT RESULT} line in the output reports {@code fail=0}. */
  private static boolean containsResultWithFailZero(String output) {
    for (String line : output.split("\\R")) {
      if (line.contains("INTEROP-CLIENT RESULT") && line.contains("fail=0")) {
        return true;
      }
    }
    return false;
  }
}
