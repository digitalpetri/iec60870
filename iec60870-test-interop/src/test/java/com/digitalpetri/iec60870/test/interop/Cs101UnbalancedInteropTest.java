package com.digitalpetri.iec60870.test.interop;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.digitalpetri.iec60870.ProtocolProfile;
import com.digitalpetri.iec60870.address.CommonAddress;
import com.digitalpetri.iec60870.address.OriginatorAddress;
import com.digitalpetri.iec60870.address.PointAddress;
import com.digitalpetri.iec60870.asdu.Cause;
import com.digitalpetri.iec60870.asdu.element.BinaryCounterReading;
import com.digitalpetri.iec60870.asdu.element.DoublePointState;
import com.digitalpetri.iec60870.asdu.element.NormalizedValue;
import com.digitalpetri.iec60870.asdu.element.QualifierOfInterrogation;
import com.digitalpetri.iec60870.asdu.element.Vti;
import com.digitalpetri.iec60870.asdu.object.SingleCommand;
import com.digitalpetri.iec60870.client.ClientEvent;
import com.digitalpetri.iec60870.client.CommandResult;
import com.digitalpetri.iec60870.client.Iec60870Client;
import com.digitalpetri.iec60870.client.InterrogationResult;
import com.digitalpetri.iec60870.cs101.LinkSettings;
import com.digitalpetri.iec60870.point.PointCapability;
import com.digitalpetri.iec60870.point.PointType;
import com.digitalpetri.iec60870.point.PointValue;
import com.digitalpetri.iec60870.serial.SerialIec101Client;
import com.digitalpetri.iec60870.serial.SerialIec101Server;
import com.digitalpetri.iec60870.server.CommandDecision;
import com.digitalpetri.iec60870.server.CommandRequest;
import com.digitalpetri.iec60870.server.Iec60870Server;
import com.digitalpetri.iec60870.server.PointDefinition;
import com.digitalpetri.iec60870.server.ServerContext;
import com.digitalpetri.iec60870.server.ServerHandler;
import com.digitalpetri.iec60870.server.Station;
import java.io.IOException;
import java.lang.ProcessBuilder.Redirect;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Assumptions;
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
 * IEC 60870-5-101 UNBALANCED interop scenario: OUR serial {@link SerialIec101Client} / {@link
 * SerialIec101Server} talking to the lib60870-C CS101 <b>unbalanced</b> peer ({@code interop_cs101}
 * run with {@code INTEROP_CS101_MODE=unbalanced}) over a socat-bridged virtual serial line.
 *
 * <p>This is the unbalanced sibling of {@link Cs101BalancedInteropTest}. It reuses the same
 * application-layer point image, command accept/reject partitioning, and the CS101 wire sizing
 * ({@code ProtocolProfile(1, 1, 2, 255)} — COT&nbsp;1, CA&nbsp;1, IOA&nbsp;2); only the link layer
 * differs (FT1.2 <b>unbalanced</b> master/secondary polling instead of the balanced point-to-point
 * machine). Every assertion is anchored to {@code
 * iec60870-test-interop/docker/INTEROP-CONTRACT-CS101.md}.
 *
 * <h2>Addressing</h2>
 *
 * <p>A single secondary station with link address {@code 1} (which also equals the common address).
 * Our master polls slave address {@code 1} ({@code
 * LinkSettings.unbalanced().slaveAddresses(List.of( 1))}); commands to common address {@code 1} are
 * routed to that secondary. Our slave answers on link address {@code 1} ({@code
 * LinkSettings.unbalanced().linkAddress(1)}), which the C master polls.
 *
 * <h2>Class-1 / class-2 traffic</h2>
 *
 * <p>The C slave enqueues its periodic update as <b>class-2</b> data (drained by the master's
 * regular class-2 poll) and its interrogation / command responses as <b>class-1</b> data (drained
 * via the access-demand (ACD) escalation to a class-1 poll). Our slave routes {@link
 * Cause#SPONTANEOUS} ASDUs to class-1 and everything else (including the {@link Cause#PERIODIC}
 * publish) to class-2, so the C master sees the periodic update on its class-2 poll.
 *
 * <h2>Why this test can skip</h2>
 *
 * <p>A serial line is point-to-point but Testcontainers exposes TCP ports, so socat bridges the C
 * peer's PTY to {@code TCP-LISTEN:2404} inside the container and a second <b>host</b> {@code socat}
 * bridges a host PTY to {@code TCP:localhost:<mappedPort>}. The host {@code socat} is an external
 * dependency the build host may not have, so each test {@link Assumptions#assumeTrue(boolean,
 * String) assumes} {@code which socat} succeeds and <b>skips cleanly</b> (rather than failing) when
 * it is absent. Install it with {@code brew install socat} (or the platform equivalent) to run the
 * test fully.
 *
 * <p>The lib60870-C peer image is built from {@code iec60870-test-interop/docker/lib60870c} via
 * {@link ImageFromDockerfile} (the Docker layer cache makes reruns fast); it is the same image the
 * CS104 and CS101-balanced interop tests use, now selecting the unbalanced peer via {@code
 * INTEROP_CS101_MODE=unbalanced}.
 */
@Tag("interop")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("IEC 60870-5-101 unbalanced interop vs lib60870-C")
class Cs101UnbalancedInteropTest {

  private static final Logger log = LoggerFactory.getLogger(Cs101UnbalancedInteropTest.class);

  /** Contract: CA = 1. */
  private static final CommonAddress STATION = CommonAddress.of(1);

  /** Contract: the single secondary station's link address (also the poll target / CA). */
  private static final int LINK_ADDRESS = 1;

  /** CS101 wire sizing: COT 1 (no OA), CA 1, IOA 2 — matches the C peer's app-layer params. */
  private static final ProtocolProfile PROFILE = new ProtocolProfile(1, 1, 2, 255);

  /**
   * Our master's unbalanced link: poll the single secondary (link address {@code 1}). A 500&nbsp;ms
   * poll cadence keeps the bridged link snappy without flooding it.
   */
  private static final LinkSettings MASTER_LINK =
      LinkSettings.unbalanced()
          .slaveAddresses(List.of(LINK_ADDRESS))
          .pollInterval(Duration.ofMillis(500))
          .build();

  /**
   * Our slave's unbalanced link: answer on link address {@code 1} (the address the C master polls).
   */
  private static final LinkSettings SLAVE_LINK =
      LinkSettings.unbalanced().linkAddress(LINK_ADDRESS).build();

  /** Contract: monitor IOAs and command accept/reject partitioning (reused from CS104). */
  private static final int IOA_SCALED = 1050; // periodic update IOA (contract section 8)

  private static final int IOA_DOUBLE = 1010;
  private static final int IOA_STEP = 1020;
  private static final int IOA_BITS = 1030;
  private static final int IOA_NORM = 1040;
  private static final int IOA_SHORT = 1060;
  private static final int IOA_SINGLE = 1000;
  private static final int IOA_COUNTER_A = 1070;
  private static final int IOA_COUNTER_B = 1071;
  private static final int IOA_ACCEPT = 2000;
  private static final int IOA_REJECT = 3000;
  private static final short SCALED_VALUE = 12345;
  private static final int COUNTER_VALUE = 1000;

  /** Generous: the first run shallow-clones + builds lib60870-C (minutes). */
  private static final Duration STARTUP_TIMEOUT = Duration.ofMinutes(10);

  /** All blocking waits are bounded; the bridged serial link is slow, so allow generous time. */
  private static final Duration WAIT_TIMEOUT = Duration.ofSeconds(30);

  // --- Our master vs the C unbalanced slave (secondary) ---------------------------------------

  @Test
  @DisplayName("Our SerialIec101Client (unbalanced master) polls the lib60870-C CS101 slave")
  void clientVsCSlave() throws Exception {
    assumeHostSocat();

    try (Bridge bridge = Bridge.open("slave")) {
      Iec60870Client client =
          SerialIec101Client.builder()
              .serialPort(bridge.hostPty().toString())
              .baudRate(9600)
              .profile(PROFILE)
              .linkSettings(MASTER_LINK)
              .originatorAddress(OriginatorAddress.none())
              .startDataTransferOnConnect(true)
              .build();

      RecordingSubscriber events = new RecordingSubscriber();
      client.events().subscribe(events);

      try {
        // connect() opens the host PTY and arms the unbalanced poller, bringing up the secondary.
        client.connect();
        assertTrue(client.isConnected(), "client should be connected after connect()");

        // Station interrogation: the C slave queues the response as class-1 data; the master drains
        // it via the access-demand escalation off its class-2 poll (contract section 3/4).
        InterrogationResult result = client.interrogate(STATION, QualifierOfInterrogation.STATION);
        assertTrue(result.terminated(), "station interrogation must end with ACT_TERM");
        assertFalse(result.pointValues().isEmpty(), "station interrogation must return points");

        // A command to an ACCEPT IOA is positively confirmed; the REJECT IOA is negative.
        CommandResult accept = client.commands().single(PointAddress.of(1, IOA_ACCEPT), true);
        assertTrue(
            accept.positive(), () -> "accept command must be positive; cause=" + accept.cause());

        CommandResult reject = client.commands().single(PointAddress.of(1, IOA_REJECT), true);
        assertFalse(reject.positive(), "command to IOA 3000 must be negatively confirmed");

        // Spontaneous traffic: the C slave enqueues a periodic M_ME_NB_1 at IOA 1050 every 2s as
        // class-2 data, which the master's class-2 poll delivers as Cause.PERIODIC.
        ClientEvent.PointUpdated update =
            events.awaitPointUpdated(
                u ->
                    u.address().objectAddress().value().longValue() == IOA_SCALED
                        && u.cause() == Cause.PERIODIC,
                WAIT_TIMEOUT);
        assertNotNull(update, "expected a periodic PointUpdated for IOA 1050");
      } finally {
        client.close();
      }
    }
  }

  // --- Our slave (secondary) vs the C unbalanced master ---------------------------------------

  @Test
  @DisplayName("The lib60870-C CS101 unbalanced master polls our SerialIec101Server (slave)")
  void serverVsCMaster() throws Exception {
    assumeHostSocat();

    try (Bridge bridge = Bridge.open("master")) {
      Iec60870Server server =
          SerialIec101Server.builder()
              .serialPort(bridge.hostPty().toString())
              .baudRate(9600)
              .profile(PROFILE)
              .linkSettings(SLAVE_LINK)
              .addStation(buildStation())
              .handler(new AcceptRejectHandler())
              .build();

      ScheduledExecutorService periodic =
          new ScheduledThreadPoolExecutor(1, Cs101UnbalancedInteropTest::daemon);

      try {
        server.start();

        // Contract section 8: publish a periodic scaled value at IOA 1050 every 2s. Our slave
        // routes
        // the (non-spontaneous) PERIODIC update to its class-2 queue, so the C master observes it
        // on
        // its regular class-2 poll (its scripted "spontaneous data observed" step).
        AtomicInteger periodicValue = new AtomicInteger(SCALED_VALUE);
        periodic.scheduleAtFixedRate(
            () -> {
              try {
                short v = (short) periodicValue.incrementAndGet();
                server.publish(
                    PointAddress.of(1, IOA_SCALED), PointValue.scaled(v), Cause.PERIODIC);
              } catch (RuntimeException e) {
                log.debug("periodic publish skipped", e);
              }
            },
            2,
            2,
            TimeUnit.SECONDS);

        // The C master brings up + polls the secondary, runs its scripted sequence, and prints
        // PASS:/FAIL: markers plus a final INTEROP-CS101-MASTER RESULT line. Wait for it.
        String logs =
            bridge.awaitContainerLog("INTEROP-CS101-MASTER RESULT", WAIT_TIMEOUT.plusSeconds(60));
        assertNotNull(
            logs,
            () ->
                "C master did not print a RESULT line. Container log:\n" + bridge.containerLogs());

        assertTrue(
            containsResultWithFailZero(logs),
            () -> "C master reported failures (fail!=0). Container log:\n" + logs);
        assertContains(logs, "PASS: link available");
        assertContains(logs, "PASS: station interrogation (ACT_CON + data)");
        assertContains(logs, "PASS: accept command confirmed (P/N=0)");
        assertContains(logs, "PASS: reject command negatively confirmed (P/N=1)");
        assertContains(logs, "PASS: spontaneous data observed");
      } finally {
        periodic.shutdownNow();
        server.close();
      }
    }
  }

  // --- Station / handler (mirrors Cs101BalancedInteropTest) -----------------------------------

  private static Station buildStation() {
    return Station.builder(STATION)
        .point(
            PointDefinition.of(
                PointAddress.of(1, IOA_SINGLE),
                PointType.SINGLE_POINT,
                PointValue.single(true),
                PointCapability.REPORTED,
                PointCapability.READABLE,
                PointCapability.COMMANDABLE))
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
        .point(
            PointDefinition.of(
                PointAddress.of(1, IOA_COUNTER_A),
                PointType.INTEGRATED_TOTALS,
                PointValue.counter(new BinaryCounterReading(COUNTER_VALUE, 0, false, false, false)),
                PointCapability.REPORTED))
        .point(
            PointDefinition.of(
                PointAddress.of(1, IOA_COUNTER_B),
                PointType.INTEGRATED_TOTALS,
                PointValue.counter(new BinaryCounterReading(COUNTER_VALUE, 0, false, false, false)),
                PointCapability.REPORTED))
        .group(1, PointAddress.of(1, IOA_SINGLE))
        .group(1, PointAddress.of(1, IOA_DOUBLE))
        .group(1, PointAddress.of(1, IOA_STEP))
        .group(1, PointAddress.of(1, IOA_BITS))
        .group(1, PointAddress.of(1, IOA_NORM))
        .group(1, PointAddress.of(1, IOA_SCALED))
        .group(1, PointAddress.of(1, IOA_SHORT))
        .build();
  }

  /** Accept IOA 2000..2999, reject IOA 3000 (contract section 7). */
  private static final class AcceptRejectHandler implements ServerHandler {
    @Override
    public CommandDecision onCommand(ServerContext context, CommandRequest request) {
      long ioa = request.target().objectAddress().value().longValue();
      if (ioa == IOA_REJECT) {
        return CommandDecision.reject(Cause.ACTIVATION_CONFIRMATION);
      }
      if (ioa < IOA_ACCEPT || ioa > 2999) {
        return CommandDecision.reject(Cause.UNKNOWN_INFORMATION_OBJECT_ADDRESS);
      }
      if (request.commandObject() instanceof SingleCommand single) {
        return CommandDecision.acceptAndUpdate(PointValue.single(single.on()));
      }
      return CommandDecision.accept();
    }
  }

  // --- socat-bridged serial line management ---------------------------------------------------

  /**
   * Owns the lib60870-C peer container (whose entrypoint bridges the C peer's PTY to {@code
   * TCP-LISTEN:2404}) and the host {@code socat} subprocess that bridges a host PTY to the mapped
   * TCP port. {@link #hostPty()} is the device path the Java serial builders open. Closing tears
   * both down and deletes the temp PTY directory. This mirrors the balanced test's bridge but runs
   * the peer with {@code INTEROP_CS101_MODE=unbalanced}.
   */
  private static final class Bridge implements AutoCloseable {

    private final GenericContainer<?> container;
    private final Process hostSocat;
    private final Path tempDir;
    private final Path hostPty;

    private Bridge(GenericContainer<?> container, Process hostSocat, Path tempDir, Path hostPty) {
      this.container = container;
      this.hostSocat = hostSocat;
      this.tempDir = tempDir;
      this.hostPty = hostPty;
    }

    Path hostPty() {
      return hostPty;
    }

    @SuppressWarnings("resource") // container/process are closed in close()
    static Bridge open(String role) throws IOException, InterruptedException {
      GenericContainer<?> container =
          new GenericContainer<>(
                  new ImageFromDockerfile("iec60870-test-interop/lib60870c-interop", false)
                      .withFileFromPath(".", Path.of("docker/lib60870c")))
              .withExposedPorts(2404)
              .withEnv("INTEROP_CS101_MODE", "unbalanced")
              .withEnv("INTEROP_CS101_ROLE", role)
              .withEnv("INTEROP_CA", "1")
              .withEnv("INTEROP_ACCEPT_IOA", Integer.toString(IOA_ACCEPT))
              .withEnv("INTEROP_REJECT_IOA", Integer.toString(IOA_REJECT))
              .withCommand("stdbuf", "-oL", "-eL", "cs101-entrypoint.sh")
              .withStartupTimeout(STARTUP_TIMEOUT)
              .waitingFor(Wait.forLogMessage(".*INTEROP-CS101-PEER READY.*", 1));
      container.withLogConsumer(new Slf4jLogConsumer(LoggerFactory.getLogger("interop_cs101")));
      container.start();

      String host = container.getHost();
      int mappedPort = container.getMappedPort(2404);
      log.info("CS101 unbalanced peer ({}) bridged at {}:{}", role, host, mappedPort);

      Path tempDir = Files.createTempDirectory("cs101-unbalanced-interop");
      Path hostPty = tempDir.resolve("ttyCS101host");

      // Bridge a host PTY (opened by the Java serial transport) to the container's TCP-LISTEN.
      Process hostSocat =
          new ProcessBuilder(
                  "socat", "PTY,link=" + hostPty + ",raw,echo=0", "TCP:" + host + ":" + mappedPort)
              .redirectErrorStream(true)
              .redirectOutput(Redirect.appendTo(tempDir.resolve("host-socat.log").toFile()))
              .start();

      // Wait for socat to create the PTY symlink before the serial transport opens it.
      long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
      while (!Files.exists(hostPty, LinkOption.NOFOLLOW_LINKS)) {
        if (System.nanoTime() > deadline || !hostSocat.isAlive()) {
          hostSocat.destroyForcibly();
          container.stop();
          deleteQuietly(tempDir);
          throw new IllegalStateException(
              "host socat did not create the PTY "
                  + hostPty
                  + " (alive="
                  + hostSocat.isAlive()
                  + ")");
        }
        //noinspection BusyWait
        Thread.sleep(50);
      }
      log.info("host PTY {} ready", hostPty);

      return new Bridge(container, hostSocat, tempDir, hostPty);
    }

    /** Returns the container's accumulated stdout/stderr. */
    String containerLogs() {
      return container.getLogs();
    }

    /**
     * Polls the container log until it contains {@code marker} or the timeout elapses, returning
     * the full log at that point or {@code null} on timeout.
     */
    @Nullable String awaitContainerLog(String marker, Duration timeout)
        throws InterruptedException {
      long deadline = System.nanoTime() + timeout.toNanos();
      while (System.nanoTime() < deadline) {
        String logs = container.getLogs();
        if (logs.contains(marker)) {
          return logs;
        }
        //noinspection BusyWait
        Thread.sleep(250);
      }
      return null;
    }

    @Override
    public void close() {
      hostSocat.destroyForcibly();
      try {
        container.stop();
      } catch (RuntimeException e) {
        log.debug("container stop failed", e);
      }
      deleteQuietly(tempDir);
    }

    private static void deleteQuietly(Path dir) {
      try (var paths = Files.walk(dir)) {
        paths
            .sorted((a, b) -> b.getNameCount() - a.getNameCount())
            .forEach(
                p -> {
                  try {
                    Files.deleteIfExists(p);
                  } catch (IOException ignored) {
                    // best effort
                  }
                });
      } catch (IOException ignored) {
        // best effort
      }
    }
  }

  // --- Skip guard -----------------------------------------------------------------------------

  /** Skips the test (rather than failing) when the host has no {@code socat} for the PTY bridge. */
  private static void assumeHostSocat() {
    Assumptions.assumeTrue(hostHasSocat(), "host socat required for the serial PTY bridge");
  }

  private static boolean hostHasSocat() {
    try {
      Process which =
          new ProcessBuilder("which", "socat")
              .redirectOutput(Redirect.DISCARD)
              .redirectError(Redirect.DISCARD)
              .start();
      return which.waitFor(10, TimeUnit.SECONDS) && which.exitValue() == 0;
    } catch (IOException | InterruptedException e) {
      Thread.currentThread().interrupt();
      return false;
    }
  }

  // --- Helpers --------------------------------------------------------------------------------

  private static Thread daemon(Runnable runnable) {
    Thread thread = new Thread(runnable, "cs101-unbalanced-interop-periodic");
    thread.setDaemon(true);
    return thread;
  }

  private static void assertContains(String haystack, String needle) {
    assertTrue(
        haystack.contains(needle),
        () -> "expected C master log to contain '" + needle + "'. Full log:\n" + haystack);
  }

  private static boolean containsResultWithFailZero(String output) {
    for (String line : output.split("\\R")) {
      if (line.contains("INTEROP-CS101-MASTER RESULT") && line.contains("fail=0")) {
        return true;
      }
    }
    return false;
  }

  // --- Event recorder (trimmed from ClientVsLib60870ServerInteropTest) ------------------------

  private static final class RecordingSubscriber implements Flow.Subscriber<ClientEvent> {

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
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) {
          return null;
        }
        try {
          CountDownLatch t = tick;
          if (!t.await(remaining, TimeUnit.NANOSECONDS) && System.nanoTime() >= deadline) {
            return null;
          }
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          return null;
        }
      }
    }
  }
}
