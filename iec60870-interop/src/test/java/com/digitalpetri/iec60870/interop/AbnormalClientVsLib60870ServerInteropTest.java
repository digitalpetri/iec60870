package com.digitalpetri.iec60870.interop;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.digitalpetri.iec60870.ConnectionClosedException;
import com.digitalpetri.iec60870.ProtocolProfile;
import com.digitalpetri.iec60870.address.CommonAddress;
import com.digitalpetri.iec60870.address.OriginatorAddress;
import com.digitalpetri.iec60870.asdu.element.QualifierOfInterrogation;
import com.digitalpetri.iec60870.client.ClientEvent;
import com.digitalpetri.iec60870.client.Iec60870Client;
import com.digitalpetri.iec60870.client.InterrogationResult;
import com.digitalpetri.iec60870.cs104.ApciSettings;
import com.digitalpetri.iec60870.transport.tcp.TcpIec104Client;
import eu.rekawek.toxiproxy.Proxy;
import eu.rekawek.toxiproxy.ToxiproxyClient;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Predicate;
import org.joou.UShort;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.output.Slf4jLogConsumer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.toxiproxy.ToxiproxyContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * ABNORMAL-path interop scenarios: OUR Java {@link Iec60870Client} against the lib60870-C {@code
 * interop_server}, with the network and the peer container deliberately disrupted at runtime.
 *
 * <p>Where {@link ClientVsLib60870ServerInteropTest} exercises the well-behaved request/response
 * surface with a single shared peer, this class exercises what happens when the wire breaks: the
 * peer process is killed mid-session, the peer is restarted underneath the client, and the network
 * is hard-partitioned mid-request. The abnormality always comes from the container/network layer
 * (Docker kill, Toxiproxy disable) — the well-behaved {@code interop_server} C driver is unchanged,
 * so nothing here crosses the EPL/GPL boundary into {@code docker/}.
 *
 * <h2>Topology</h2>
 *
 * <p>A {@link ToxiproxyContainer} sits on a shared {@link Network} between the Java client and the
 * peer. The client always targets a single, <em>stable</em> proxy port; the peer is reachable from
 * the proxy through the fixed network alias {@code peer}. This indirection is what makes a
 * peer-restart test robust: a restarted container gets a brand-new Docker-mapped port, but the
 * proxy's listen port never changes, so the client's persistent transport FSM can transparently
 * reconnect to the same endpoint after the peer comes back.
 *
 * <h2>Non-flaky discipline</h2>
 *
 * <p>Every assertion is on an <em>outcome</em> (a {@link ClientEvent.ConnectionClosed} was
 * published, a request failed with {@link ConnectionClosedException}, a fresh interrogation
 * round-tripped after recovery) and never on a wall-clock duration. All waits are bounded by
 * generous deadlines so a slow CI host does not flake; a fast peer/proxy simply returns sooner. The
 * kill-mid-request assertion is tolerant by construction: the in-flight request may either complete
 * just before the disruption lands or fail cleanly afterward, but a {@code ConnectionClosed} event
 * must always follow.
 */
@Tag("interop")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Java client vs lib60870-C interop_server (abnormal paths)")
class AbnormalClientVsLib60870ServerInteropTest {

  private static final Logger log =
      LoggerFactory.getLogger(AbnormalClientVsLib60870ServerInteropTest.class);

  /** Contract: CA = 1. */
  private static final CommonAddress STATION = CommonAddress.of(1);

  /** lib60870-C listens on 2404 inside the container. */
  private static final int PEER_PORT = 2404;

  /** Stable network alias the proxy uses to reach the peer across restarts. */
  private static final String PEER_ALIAS = "peer";

  /** First Toxiproxy-exposed port (see ToxiproxyContainer: 8666..8697). */
  private static final int PROXY_LISTEN_PORT = 8666;

  /**
   * Pinned via GHCR to dodge Docker Hub rate limits; this is the image the TC ToxiproxyContainer
   * already recognizes (DockerImageName.parse("ghcr.io/shopify/toxiproxy")).
   */
  private static final DockerImageName TOXIPROXY_IMAGE =
      DockerImageName.parse("ghcr.io/shopify/toxiproxy:2.12.0")
          .asCompatibleSubstituteFor("ghcr.io/shopify/toxiproxy");

  /** Generous startup timeout: the first run shallow-clones + builds lib60870-C (minutes). */
  private static final Duration STARTUP_TIMEOUT = Duration.ofMinutes(10);

  /** Generous outcome-wait deadline; nothing here asserts on exact timing. */
  private static final Duration WAIT_TIMEOUT = Duration.ofSeconds(30);

  /** Built once; the Dockerfile/clone/build is cached after the first scenario. */
  private static final ImageFromDockerfile PEER_IMAGE =
      new ImageFromDockerfile("iec60870-interop/lib60870c-interop", false)
          .withFileFromPath(".", Path.of("docker/lib60870c"));

  // Shared across all scenarios in this class; per-test state is the peer container + client.
  private @Nullable Network network;
  private @Nullable ToxiproxyContainer toxiproxy;
  private @Nullable ToxiproxyClient toxiproxyClient;

  // Per-test, torn down in @AfterEach.
  private @Nullable GenericContainer<?> peer;
  private @Nullable Iec60870Client client;
  private @Nullable Proxy proxy;

  @BeforeAll
  void startSharedInfrastructure() {
    network = Network.newNetwork();
    toxiproxy = new ToxiproxyContainer(TOXIPROXY_IMAGE).withNetwork(network);
    toxiproxy.withLogConsumer(new Slf4jLogConsumer(LoggerFactory.getLogger("toxiproxy")));
    toxiproxy.start();
    toxiproxyClient = new ToxiproxyClient(toxiproxy.getHost(), toxiproxy.getControlPort());
    log.info("toxiproxy control endpoint {}:{}", toxiproxy.getHost(), toxiproxy.getControlPort());
  }

  @AfterAll
  void stopSharedInfrastructure() {
    if (toxiproxy != null) {
      toxiproxy.stop();
    }
    if (network != null) {
      network.close();
    }
  }

  @AfterEach
  void tearDownScenario() {
    if (client != null) {
      try {
        client.close();
      } catch (RuntimeException e) {
        log.debug("client close failed during teardown", e);
      }
      client = null;
    }
    if (proxy != null) {
      try {
        proxy.delete();
      } catch (IOException e) {
        log.debug("proxy delete failed during teardown", e);
      }
      proxy = null;
    }
    if (peer != null) {
      peer.stop();
      peer = null;
    }
  }

  // --- #8: hard partition mid-request -----------------------------------------------------------

  @Test
  @DisplayName("#8 Network partition mid-request: in-flight interrogation fails + ConnectionClosed")
  void partitionMidRequestFailsInFlightCall() throws Exception {
    startPeer();
    Proxy proxy = createProxy("partition-mid-request");
    EventRecorder events = new EventRecorder();
    Iec60870Client client = connectThroughProxy(events, ApciSettings.defaults());

    // Fire an interrogation asynchronously, then hard-partition the link before it can complete.
    // Toxiproxy.disable() drops the established TCP connection, so the request must fail cleanly
    // and
    // a ConnectionClosed event must be published. The request *might* race to completion first; we
    // accept either, but a ConnectionClosed is mandatory.
    CompletionStage<InterrogationResult> inFlight =
        client.interrogateAsync(STATION, QualifierOfInterrogation.STATION);

    proxy.disable();
    log.info("toxiproxy partition engaged (proxy disabled)");

    try {
      inFlight.toCompletableFuture().get(WAIT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
      log.info("in-flight interrogation completed before the partition landed (accepted)");
    } catch (ExecutionException e) {
      assertInstanceOf(
          ConnectionClosedException.class,
          e.getCause(),
          () -> "in-flight request must fail with ConnectionClosedException, got " + e.getCause());
    } catch (TimeoutException e) {
      fail("in-flight request neither completed nor failed within " + WAIT_TIMEOUT);
    }

    ClientEvent.ConnectionClosed closed = events.awaitConnectionClosed(WAIT_TIMEOUT);
    assertNotNull(closed, "a ConnectionClosed event must follow the partition");
  }

  // --- #8 variant: hard peer kill mid-request (pure Docker API, no proxy toxics) ----------------

  @Test
  @DisplayName("#8 Peer SIGKILL mid-request: in-flight interrogation fails + ConnectionClosed")
  void killPeerMidRequestFailsInFlightCall() throws Exception {
    GenericContainer<?> peer = startPeer();
    createProxy("kill-mid-request");
    EventRecorder events = new EventRecorder();
    Iec60870Client client = connectThroughProxy(events, ApciSettings.defaults());

    CompletionStage<InterrogationResult> inFlight =
        client.interrogateAsync(STATION, QualifierOfInterrogation.STATION);

    // SIGKILL the peer process; the TCP connection is severed, the proxy upstream goes away.
    String id = peer.getContainerId();
    assertNotNull(id, "peer container id");
    peer.getDockerClient().killContainerCmd(id).exec();
    log.info("peer container SIGKILLed");

    try {
      inFlight.toCompletableFuture().get(WAIT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
      log.info("in-flight interrogation completed before the kill landed (accepted)");
    } catch (ExecutionException e) {
      assertInstanceOf(
          ConnectionClosedException.class,
          e.getCause(),
          () -> "in-flight request must fail with ConnectionClosedException, got " + e.getCause());
    } catch (TimeoutException e) {
      fail("in-flight request neither completed nor failed within " + WAIT_TIMEOUT);
    }

    ClientEvent.ConnectionClosed closed = events.awaitConnectionClosed(WAIT_TIMEOUT);
    assertNotNull(closed, "a ConnectionClosed event must follow the peer kill");
  }

  // --- #7: peer restart -> reconnect & resume ---------------------------------------------------

  @Test
  @DisplayName("#7 Peer restart: client observes close, auto-reconnects through the stable proxy")
  void peerRestartReconnectsAndResumes() throws Exception {
    GenericContainer<?> firstPeer = startPeer();
    createProxy("peer-restart");
    EventRecorder events = new EventRecorder();
    Iec60870Client client = connectThroughProxy(events, ApciSettings.defaults());

    // Baseline: a station interrogation round-trips before any disruption.
    InterrogationResult before = client.interrogate(STATION, QualifierOfInterrogation.STATION);
    assertTrue(before.terminated(), "baseline interrogation must terminate (ACT_TERM)");
    assertFalse(before.pointValues().isEmpty(), "baseline interrogation must return points");

    // Kill the peer; the client must observe the connection close.
    events.clear();
    String firstId = firstPeer.getContainerId();
    assertNotNull(firstId, "first peer container id");
    firstPeer.getDockerClient().killContainerCmd(firstId).exec();
    log.info("first peer SIGKILLed; awaiting ConnectionClosed");

    ClientEvent.ConnectionClosed closed = events.awaitConnectionClosed(WAIT_TIMEOUT);
    assertNotNull(closed, "client must observe ConnectionClosed after the peer dies");

    firstPeer.stop();
    this.peer = null;

    // Bring a fresh peer up on the SAME network alias; the proxy's stable listen port now reaches
    // it. The persistent transport FSM auto-reconnects TCP, but STARTDT is only re-driven by an
    // explicit connect(), so we re-connect and assert a fresh interrogation round-trips.
    GenericContainer<?> secondPeer = startPeer();
    log.info("second peer started on alias {}", PEER_ALIAS);

    // Re-run connect() (re-establishing data transfer) against the same proxy endpoint. Retry to
    // absorb the brief window where the proxy upstream is still re-resolving to the new container.
    awaitReconnected(client, WAIT_TIMEOUT);

    InterrogationResult after = client.interrogate(STATION, QualifierOfInterrogation.STATION);
    assertTrue(after.terminated(), "post-restart interrogation must terminate (ACT_TERM)");
    assertFalse(after.pointValues().isEmpty(), "post-restart interrogation must return points");
    log.info("client resumed against the restarted peer");

    // keep secondPeer referenced for teardown
    assertNotNull(secondPeer.getContainerId());
  }

  // --- #4: half-open stall via Toxiproxy timeout toxic ------------------------------------------

  @Test
  @DisplayName("#4 Half-open stall: a frozen link trips t1 and the client closes the connection")
  void halfOpenStallTripsT1() throws Exception {
    startPeer();
    Proxy proxy = createProxy("half-open-stall");
    EventRecorder events = new EventRecorder();

    // Short t1 so the I-frame acknowledgement timeout fires well inside the outcome deadline, while
    // still asserting an OUTCOME (the connection closes) rather than a duration. t2 < t1 per spec.
    ApciSettings shortTimers =
        new ApciSettings(
            UShort.valueOf(12),
            UShort.valueOf(8),
            Duration.ofSeconds(10), // t0
            Duration.ofSeconds(2), // t1: ack timeout
            Duration.ofSeconds(1), // t2
            Duration.ofSeconds(20)); // t3
    Iec60870Client client = connectThroughProxy(events, shortTimers);

    // A timeout toxic stops forwarding data without sending a FIN: the link is half-open. We then
    // issue a request whose ACK can never arrive, so t1 must expire and the client must close.
    proxy.toxics().timeout("freeze-up", eu.rekawek.toxiproxy.model.ToxicDirection.UPSTREAM, 0);
    proxy.toxics().timeout("freeze-down", eu.rekawek.toxiproxy.model.ToxicDirection.DOWNSTREAM, 0);
    log.info("toxiproxy timeout toxics engaged (half-open)");

    events.clear();
    CompletionStage<InterrogationResult> stalled =
        client.interrogateAsync(STATION, QualifierOfInterrogation.STATION);

    ClientEvent.ConnectionClosed closed = events.awaitConnectionClosed(WAIT_TIMEOUT);
    assertNotNull(closed, "t1 expiry on a half-open link must close the connection");

    // The stalled request must also fail cleanly (not hang).
    try {
      stalled.toCompletableFuture().get(WAIT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
      fail("the request on a half-open link must not complete successfully");
    } catch (ExecutionException e) {
      assertInstanceOf(
          ConnectionClosedException.class,
          e.getCause(),
          () -> "stalled request must fail with ConnectionClosedException, got " + e.getCause());
    } catch (TimeoutException e) {
      fail("stalled request neither completed nor failed within " + WAIT_TIMEOUT);
    }
  }

  // --- helpers ----------------------------------------------------------------------------------

  /** Starts a fresh peer container on the shared network under the stable {@link #PEER_ALIAS}. */
  private GenericContainer<?> startPeer() {
    GenericContainer<?> c =
        new GenericContainer<>(PEER_IMAGE)
            .withNetwork(requireNetwork())
            .withNetworkAliases(PEER_ALIAS)
            .withExposedPorts(PEER_PORT)
            .withCommand("stdbuf", "-oL", "-eL", "interop_server")
            .withStartupTimeout(STARTUP_TIMEOUT)
            .waitingFor(Wait.forLogMessage(".*INTEROP-SERVER READY.*", 1))
            .withLogConsumer(new Slf4jLogConsumer(LoggerFactory.getLogger("interop_server")));
    c.start();
    this.peer = c;
    return c;
  }

  /**
   * Creates a Toxiproxy proxy listening on the stable {@link #PROXY_LISTEN_PORT} and forwarding to
   * {@code peer:2404}. Recreated per test so toxics never leak between scenarios.
   */
  private Proxy createProxy(String name) throws IOException {
    Proxy p =
        requireToxiproxyClient()
            .createProxy(name, "0.0.0.0:" + PROXY_LISTEN_PORT, PEER_ALIAS + ":" + PEER_PORT);
    this.proxy = p;
    return p;
  }

  /** Builds a client targeting the stable proxy endpoint and connects (running STARTDT). */
  private Iec60870Client connectThroughProxy(EventRecorder events, ApciSettings apci) {
    int mappedProxyPort = requireToxiproxy().getMappedPort(PROXY_LISTEN_PORT);
    Iec60870Client c =
        TcpIec104Client.builder()
            .host(requireToxiproxy().getHost())
            .port(mappedProxyPort)
            .profile(ProtocolProfile.iec104Default())
            .apci(apci)
            .originatorAddress(OriginatorAddress.of(3))
            .startDataTransferOnConnect(true)
            .build();
    c.events().subscribe(events);
    c.connect();
    assertTrue(c.isConnected(), "client should be connected after connect()");
    this.client = c;
    return c;
  }

  /**
   * Re-drives {@link Iec60870Client#connect()} until it succeeds (the persistent transport may need
   * a few attempts while the proxy upstream re-resolves to the restarted peer), bounded by {@code
   * timeout}.
   */
  private static void awaitReconnected(Iec60870Client client, Duration timeout) {
    long deadline = System.nanoTime() + timeout.toNanos();
    RuntimeException last = null;
    while (System.nanoTime() < deadline) {
      try {
        client.connect();
        if (client.isConnected()) {
          return;
        }
      } catch (RuntimeException e) {
        last = e;
      }
      try {
        TimeUnit.MILLISECONDS.sleep(500);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        break;
      }
    }
    throw new AssertionError("client did not reconnect within " + timeout, last);
  }

  private Network requireNetwork() {
    assertNotNull(network, "network must be initialized by @BeforeAll");
    return network;
  }

  private ToxiproxyContainer requireToxiproxy() {
    assertNotNull(toxiproxy, "toxiproxy must be initialized by @BeforeAll");
    return toxiproxy;
  }

  private ToxiproxyClient requireToxiproxyClient() {
    assertNotNull(toxiproxyClient, "toxiproxy client must be initialized by @BeforeAll");
    return toxiproxyClient;
  }

  // --- Event recorder ---------------------------------------------------------------------------

  /**
   * A {@link Flow.Subscriber} that records every {@link ClientEvent} and lets a test await a {@link
   * ClientEvent.ConnectionClosed} with a bounded timeout. Modeled on the recorder in {@link
   * ClientVsLib60870ServerInteropTest}; trimmed to the close-path needs of this class.
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

    /** Awaits a {@link ClientEvent.ConnectionClosed}, or {@code null} if the timeout elapses. */
    ClientEvent.@Nullable ConnectionClosed awaitConnectionClosed(Duration timeout) {
      return await(ClientEvent.ConnectionClosed.class, c -> true, timeout);
    }

    private <T extends ClientEvent> @Nullable T await(
        Class<T> type, Predicate<T> predicate, Duration timeout) {
      long deadline = System.nanoTime() + timeout.toNanos();
      while (true) {
        for (ClientEvent event : queue) {
          if (type.isInstance(event)) {
            T t = type.cast(event);
            if (predicate.test(t)) {
              queue.remove(event);
              return t;
            }
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
