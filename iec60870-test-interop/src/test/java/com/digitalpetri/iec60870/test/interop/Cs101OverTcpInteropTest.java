package com.digitalpetri.iec60870.test.interop;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.digitalpetri.iec60870.ProtocolProfile;
import com.digitalpetri.iec60870.address.OriginatorAddress;
import com.digitalpetri.iec60870.address.PointAddress;
import com.digitalpetri.iec60870.asdu.Cause;
import com.digitalpetri.iec60870.client.Iec60870Client;
import com.digitalpetri.iec60870.cs101.LinkSettings;
import com.digitalpetri.iec60870.point.PointValue;
import com.digitalpetri.iec60870.server.Iec60870Server;
import com.digitalpetri.iec60870.tcp.TcpIec101Client;
import com.digitalpetri.iec60870.tcp.TcpIec101Server;
import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.Testcontainers;
import org.testcontainers.containers.ContainerLaunchException;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.output.Slf4jLogConsumer;
import org.testcontainers.containers.output.ToStringConsumer;
import org.testcontainers.containers.startupcheck.OneShotStartupCheckStrategy;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;

/** Balanced IEC 60870-5-101-over-TCP interop against lib60870-C's serial CS101 peer. */
@Tag("interop")
@DisplayName("IEC 60870-5-101-over-TCP interop vs lib60870-C")
class Cs101OverTcpInteropTest {

  private static final Logger log = LoggerFactory.getLogger(Cs101OverTcpInteropTest.class);

  private static final ProtocolProfile PROFILE = new ProtocolProfile(1, 1, 2, 255);
  private static final LinkSettings LINK = LinkSettings.balanced().build();
  private static final Duration STARTUP_TIMEOUT = Duration.ofMinutes(10);
  private static final Duration WAIT_TIMEOUT = Duration.ofSeconds(25);

  @Test
  @DisplayName("Java TcpIec101Client drives the lib60870-C CS101 slave over TCP")
  void javaTcp101ClientDrivesCSlave() {
    try (GenericContainer<?> slave =
        new GenericContainer<>(image())
            .withExposedPorts(2404)
            .withEnv("INTEROP_CS101_ROLE", "slave")
            .withEnv("INTEROP_CA", "1")
            .withEnv("INTEROP_ACCEPT_IOA", Integer.toString(InteropClientContract.IOA_ACCEPT))
            .withEnv("INTEROP_REJECT_IOA", Integer.toString(InteropClientContract.IOA_REJECT))
            .withCommand("stdbuf", "-oL", "-eL", "cs101-entrypoint.sh")
            .withStartupTimeout(STARTUP_TIMEOUT)
            .waitingFor(Wait.forLogMessage(".*INTEROP-CS101-PEER READY.*", 1))) {

      slave.withLogConsumer(new Slf4jLogConsumer(LoggerFactory.getLogger("interop_cs101_tcp")));
      slave.start();

      Iec60870Client client =
          TcpIec101Client.builder()
              .host(slave.getHost())
              .port(slave.getMappedPort(2404))
              .profile(PROFILE)
              .linkSettings(LINK)
              .originatorAddress(OriginatorAddress.none())
              .startDataTransferOnConnect(true)
              .build();
      InteropEventRecorder events = new InteropEventRecorder();
      client.events().subscribe(events);

      try {
        client.connect();
        assertTrue(client.isConnected(), "client should be connected after balanced link reset");
        InteropClientContract.assertBroadClientContract(
            client, events, OriginatorAddress.none(), WAIT_TIMEOUT, Duration.ofSeconds(10));
      } finally {
        client.close();
      }
    }
  }

  @Test
  @DisplayName("lib60870-C CS101 master drives Java TcpIec101Server through the connect bridge")
  void cMasterDrivesJavaTcp101Server() throws Exception {
    int serverPort = freeEphemeralPort();

    Iec60870Server server =
        TcpIec101Server.builder()
            .bindAddress("127.0.0.1")
            .port(serverPort)
            .profile(PROFILE)
            .linkSettings(LINK)
            .addStation(InteropServerFixture.buildStation())
            .handler(InteropServerFixture.acceptRejectHandler())
            .build();
    ScheduledExecutorService periodic =
        new ScheduledThreadPoolExecutor(1, InteropServerFixture::daemon);

    try {
      server.start();
      AtomicInteger periodicValue = new AtomicInteger(12345);
      periodic.scheduleAtFixedRate(
          () -> {
            try {
              short value = (short) periodicValue.incrementAndGet();
              server.publish(
                  PointAddress.of(1, InteropClientContract.IOA_SCALED),
                  PointValue.scaled(value),
                  Cause.PERIODIC);
            } catch (RuntimeException e) {
              log.debug("periodic publish skipped", e);
            }
          },
          2,
          2,
          TimeUnit.SECONDS);

      Testcontainers.exposeHostPorts(serverPort);
      ToStringConsumer logs = new ToStringConsumer();

      try (GenericContainer<?> master =
          new GenericContainer<>(image())
              .withStartupTimeout(STARTUP_TIMEOUT)
              .withStartupCheckStrategy(new OneShotStartupCheckStrategy())
              .withEnv("INTEROP_CS101_ROLE", "master")
              .withEnv("INTEROP_CS101_BRIDGE", "connect")
              .withEnv("INTEROP_CS101_TCP_HOST", "host.testcontainers.internal")
              .withEnv("INTEROP_CS101_TCP_PORT", Integer.toString(serverPort))
              .withEnv("INTEROP_CA", "1")
              .withEnv("INTEROP_ACCEPT_IOA", Integer.toString(InteropClientContract.IOA_ACCEPT))
              .withEnv("INTEROP_REJECT_IOA", Integer.toString(InteropClientContract.IOA_REJECT))
              .withCommand("stdbuf", "-oL", "-eL", "cs101-entrypoint.sh")) {

        master.withLogConsumer(logs);
        master.withLogConsumer(
            new Slf4jLogConsumer(LoggerFactory.getLogger("interop_cs101_tcp_master")));

        ContainerLaunchException launchFailure = null;
        try {
          master.start();
        } catch (ContainerLaunchException e) {
          launchFailure = e;
        }

        String output = logs.toUtf8String();
        ContainerLaunchException launchFailureForMessage = launchFailure;
        assertNotNull(output, "container log should be captured");
        assertTrue(
            containsResultWithFailZero(output),
            () ->
                "C master reported failures"
                    + (launchFailureForMessage != null ? " and exited non-zero" : "")
                    + ". Container log:\n"
                    + output);
        assertContains(output, "PASS: link available");
        assertContains(output, "PASS: station interrogation (ACT_CON + data)");
        assertContains(output, "PASS: counter interrogation (ACT_CON + data)");
        assertContains(output, "PASS: read command returned data");
        assertContains(output, "PASS: accept command confirmed (P/N=0)");
        assertContains(output, "PASS: reject command negatively confirmed (P/N=1)");
        assertContains(output, "PASS: spontaneous data observed");
      }
    } finally {
      periodic.shutdownNow();
      server.close();
    }
  }

  private static ImageFromDockerfile image() {
    return new ImageFromDockerfile("iec60870-test-interop/lib60870c-interop", false)
        .withFileFromPath(".", Path.of("docker/lib60870c"));
  }

  private static int freeEphemeralPort() throws IOException {
    try (ServerSocket socket = new ServerSocket(0)) {
      socket.setReuseAddress(true);
      return socket.getLocalPort();
    }
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
}
