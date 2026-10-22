package com.digitalpetri.iec60870.test.integration;

import static java.util.Objects.requireNonNull;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.digitalpetri.iec60870.Iec60870Exception;
import com.digitalpetri.iec60870.TlsOptions;
import com.digitalpetri.iec60870.address.CommonAddress;
import com.digitalpetri.iec60870.address.PointAddress;
import com.digitalpetri.iec60870.client.ClientEvent;
import com.digitalpetri.iec60870.client.ConnectionState;
import com.digitalpetri.iec60870.client.Iec60870Client;
import com.digitalpetri.iec60870.client.InterrogationResult;
import com.digitalpetri.iec60870.client.ReconnectPolicy;
import com.digitalpetri.iec60870.cs104.ApciCounters;
import com.digitalpetri.iec60870.cs104.ApciDiagnostics;
import com.digitalpetri.iec60870.cs104.ApciSettings;
import com.digitalpetri.iec60870.cs104.ApciTimer;
import com.digitalpetri.iec60870.cs104.ControlField;
import com.digitalpetri.iec60870.cs104.FrameTrace;
import com.digitalpetri.iec60870.cs104.UFunction;
import com.digitalpetri.iec60870.diagnostics.Direction;
import com.digitalpetri.iec60870.diagnostics.LoggingContext;
import com.digitalpetri.iec60870.point.PointCapability;
import com.digitalpetri.iec60870.point.PointType;
import com.digitalpetri.iec60870.point.PointValue;
import com.digitalpetri.iec60870.server.Iec60870Server;
import com.digitalpetri.iec60870.server.PointDefinition;
import com.digitalpetri.iec60870.server.Station;
import com.digitalpetri.iec60870.tcp.TcpIec104Client;
import com.digitalpetri.iec60870.tcp.TcpIec104Server;
import com.digitalpetri.iec60870.test.common.RecordingSlf4jProvider;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.SSLException;
import org.joou.UShort;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * End-to-end tests of the diagnostics listener, counters, frame trace, and logging context over a
 * real loopback TCP connection between a {@link TcpIec104Client} and a {@link TcpIec104Server}.
 */
class DiagnosticsIntegrationTest {

  private static final CommonAddress STATION = CommonAddress.of(1);
  private static final PointAddress POINT = PointAddress.of(1, 100);

  /** A trace logger under the recorded prefix, so its lines are captured with their MDC. */
  private static final String TRACE_LOGGER = "com.digitalpetri.iec60870.test.trace";

  /** Short t2 and t3 so the acknowledgement and the idle probe happen within a test. */
  private static final ApciSettings PROBE_APCI =
      new ApciSettings(
          UShort.valueOf(12),
          UShort.valueOf(8),
          Duration.ofSeconds(5),
          Duration.ofSeconds(5),
          Duration.ofMillis(300),
          Duration.ofMillis(1200));

  private final List<AutoCloseable> resources = new ArrayList<>();

  @AfterEach
  void tearDown() throws Exception {
    Collections.reverse(resources);
    for (AutoCloseable resource : resources) {
      resource.close();
    }
  }

  @Test
  void everyFrameTimerAndTestExchangeIsReportedInBothDirections() throws Exception {
    var serverDiagnostics = new RecordingApciDiagnostics();
    AtomicInteger diagnosticsCalls = new AtomicInteger();
    int port =
        startServer(
            TcpIec104Server.builder()
                .diagnostics(
                    remote -> {
                      diagnosticsCalls.incrementAndGet();
                      return serverDiagnostics;
                    }));
    var diagnostics = new RecordingApciDiagnostics();
    Iec60870Client client =
        client(TcpIec104Client.builder().port(port).apci(PROBE_APCI).diagnostics(diagnostics));

    client.connect();
    InterrogationResult result = client.interrogate(STATION);
    assertTrue(result.terminated());
    Await.until("the t3 TESTFR exchange", () -> diagnostics.count("received U(TESTFR_CON)") > 0);

    // The interrogation: confirmation, one single point, termination; t2 acknowledges all three.
    List<String> expectedInOrder =
        List.of(
            "connectAttempt",
            "connected",
            "sent U(STARTDT_ACT) 6",
            "received U(STARTDT_CON) 6",
            "sent I(N(S)=0 N(R)=0) 16",
            "received I(N(S)=0 N(R)=1) 16",
            "received I(N(S)=1 N(R)=1) 16",
            "received I(N(S)=2 N(R)=1) 16",
            "timer T2",
            "sent S(N(R)=3) 6",
            "timer T3",
            "sent U(TESTFR_ACT) 6",
            "received U(TESTFR_CON) 6");
    assertEquals(expectedInOrder, diagnostics.log.subList(0, expectedInOrder.size()));
    SocketAddress[] addresses = diagnostics.connected.get(0);
    assertEquals(port, ((InetSocketAddress) addresses[1]).getPort());
    assertTrue(((InetSocketAddress) addresses[0]).getPort() > 0);

    // The server reports the mirror image of the same exchange.
    Await.until("the server's TESTFR con", () -> serverDiagnostics.count("sent U(TESTFR_CON)") > 0);
    List<String> serverExpected =
        List.of(
            "accepted",
            "received U(STARTDT_ACT) 6",
            "sent U(STARTDT_CON) 6",
            "received I(N(S)=0 N(R)=0) 16",
            "sent I(N(S)=0 N(R)=1) 16",
            "sent I(N(S)=1 N(R)=1) 16",
            "sent I(N(S)=2 N(R)=1) 16",
            "received S(N(R)=3) 6",
            "received U(TESTFR_ACT) 6",
            "sent U(TESTFR_CON) 6");
    assertEquals(serverExpected, serverDiagnostics.log.subList(0, serverExpected.size()));
    assertEquals(1, diagnosticsCalls.get());
  }

  @Test
  void oneCounterSetKeepsTotalsAcrossServerConnections() throws Exception {
    var counters = new ApciCounters();
    int port = startServer(TcpIec104Server.builder().diagnostics(remote -> counters));
    Iec60870Client first = client(TcpIec104Client.builder().port(port));
    Iec60870Client second = client(TcpIec104Client.builder().port(port));

    first.connect();
    second.connect();
    first.interrogate(STATION);
    second.interrogate(STATION);
    first.disconnect();
    second.disconnect();

    Await.until("both closes", () -> counters.snapshot().connectionsClosed() == 2);
    ApciCounters.Snapshot snapshot = counters.snapshot();
    assertEquals(2, snapshot.connectionsOpened());
    assertEquals(2, snapshot.iFramesReceived(), "one interrogation command per connection");
    assertEquals(6, snapshot.iFramesSent(), "three interrogation responses per connection");
    assertTrue(snapshot.uFramesReceived() >= 2, "a STARTDT act per connection");
  }

  @Test
  void throwingListenersDoNotPreventConnectAndInterrogation() throws Exception {
    int port = startServer(TcpIec104Server.builder().diagnostics(remote -> new Throwing()));
    Iec60870Client client =
        client(TcpIec104Client.builder().port(port).apci(PROBE_APCI).diagnostics(new Throwing()));

    client.connect();
    InterrogationResult result = client.interrogate(STATION);

    assertTrue(result.terminated());
    assertEquals(1, result.pointValues().size());
    assertTrue(client.isConnected());
  }

  @Test
  void frameTraceLendsReadOnlyViewsOfEveryFrame() throws Exception {
    int port = startServer(TcpIec104Server.builder());
    var diagnostics = new RecordingApciDiagnostics();
    diagnostics.trace = true;
    Iec60870Client client = client(TcpIec104Client.builder().port(port).diagnostics(diagnostics));

    client.connect();
    client.interrogate(STATION);
    diagnostics.trace = false;
    int traced = diagnostics.traced.size();
    client.interrogate(STATION);

    // STARTDT act and con, the interrogation, and its three responses.
    assertEquals(6, traced);
    assertEquals(traced, diagnostics.traced.size(), "no trace once traceFrames() is false");
    assertArrayEquals(HexFormat.of().parseHex("680407000000"), diagnostics.traced.get(0));
    assertArrayEquals(HexFormat.of().parseHex("68040b000000"), diagnostics.traced.get(1));
    assertTrue(diagnostics.tracedReadOnly.stream().allMatch(readOnly -> readOnly));
  }

  @Test
  void backgroundReconnectAttemptsReportTheirCauseUntilTheServerAppears() throws Exception {
    int port = reserveEphemeralPort();
    var diagnostics = new RecordingApciDiagnostics();
    Iec60870Client client =
        client(
            TcpIec104Client.builder()
                .port(port)
                .reconnectPolicy(
                    ReconnectPolicy.exponentialBackoff(
                        Duration.ofMillis(50), Duration.ofMillis(100)))
                .diagnostics(diagnostics));

    assertThrows(Iec60870Exception.class, client::connect);
    Await.until("three failed attempts", () -> diagnostics.count("connectFailed") >= 3);

    assertTrue(diagnostics.count("connectAttempt") >= 3);
    assertTrue(diagnostics.count("reconnectScheduled") >= 2);
    for (Throwable cause : List.copyOf(diagnostics.connectFailures).subList(0, 3)) {
      assertInstanceOf(ConnectException.class, rootCause(cause), cause.toString());
    }

    buildServer(TcpIec104Server.builder().port(port)).start();
    Await.until("the reconnect", () -> diagnostics.count("connected") == 1);
    Await.until("data transfer", client::isConnected);
  }

  @Test
  void tlsHandshakeFailureIsReportedAsAFailedAttempt() throws Exception {
    SelfSignedTls identity = SelfSignedTls.generate();
    int port =
        startServer(
            TcpIec104Server.builder().tls(TlsOptions.builder(identity.serverContext()).build()));
    var diagnostics = new RecordingApciDiagnostics();
    Iec60870Client client =
        client(
            TcpIec104Client.builder()
                .port(port)
                .tls(TlsOptions.builder(identity.untrustingClientContext()).build())
                .reconnectPolicy(ReconnectPolicy.disabled())
                .diagnostics(diagnostics));

    assertThrows(Iec60870Exception.class, client::connect);

    assertEquals(List.of("connectAttempt", "connectFailed"), diagnostics.log);
    assertInstanceOf(SSLException.class, firstSslException(diagnostics.connectFailures.get(0)));
  }

  @Test
  void t1ExpiryIsReportedBeforeConnectionClosedIsPublished() throws Exception {
    // A peer that accepts the connection and never answers, so STARTDT act waits out t1.
    ServerSocket silent = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
    resources.add(silent);
    Thread acceptor =
        new Thread(
            () -> {
              try (Socket socket = silent.accept();
                  InputStream in = socket.getInputStream()) {
                while (in.read() >= 0) {
                  // Discard everything.
                }
              } catch (IOException ignored) {
                // Closed by the test.
              }
            });
    acceptor.setDaemon(true);
    acceptor.start();

    List<String> log = Collections.synchronizedList(new ArrayList<>());
    var diagnostics = new RecordingApciDiagnostics(log);
    ApciSettings shortT1 =
        new ApciSettings(
            UShort.valueOf(12),
            UShort.valueOf(8),
            Duration.ofSeconds(5),
            Duration.ofMillis(300),
            Duration.ofMillis(200),
            Duration.ofSeconds(20));
    Iec60870Client client =
        client(
            TcpIec104Client.builder()
                .port(silent.getLocalPort())
                .apci(shortT1)
                .reconnectPolicy(ReconnectPolicy.disabled())
                .diagnostics(diagnostics));
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
                if (item instanceof ClientEvent.ConnectionClosed) {
                  log.add("event ConnectionClosed");
                }
              }

              @Override
              public void onError(Throwable throwable) {}

              @Override
              public void onComplete() {}
            });

    assertThrows(Iec60870Exception.class, client::connect);
    Await.until("ConnectionClosed", () -> log.contains("event ConnectionClosed"));

    List<String> relevant =
        log.stream()
            .filter(
                line ->
                    line.startsWith("timer")
                        || line.startsWith("protocolError")
                        || line.startsWith("connectionClosed")
                        || line.startsWith("event"))
            .toList();
    assertEquals(
        List.of(
            "timer T1",
            "protocolError ProtocolTimeoutException",
            "connectionClosed",
            "event ConnectionClosed"),
        relevant);
  }

  @Test
  void unframeableOctetsFromThePeerAreCountedAndReported() throws Exception {
    // A peer that answers the connection with octets that do not start with 0x68. Two octets are
    // the least the decoder needs to reject the stream, so a split read reports the same octets.
    byte[] garbage = HexFormat.of().parseHex("0004");
    ServerSocket peer = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
    resources.add(peer);
    Thread writer =
        new Thread(
            () -> {
              try (Socket socket = peer.accept()) {
                socket.getOutputStream().write(garbage);
                socket.getOutputStream().flush();
                while (socket.getInputStream().read() >= 0) {
                  // Wait for the client to close.
                }
              } catch (IOException ignored) {
                // Closed by the test.
              }
            });
    writer.setDaemon(true);
    writer.start();

    var counters = new ApciCounters();
    List<byte[]> reported = Collections.synchronizedList(new ArrayList<>());
    ApciDiagnostics capture =
        new ApciDiagnostics() {
          @Override
          public void onDecodeError(ByteBuf frame, RuntimeException cause) {
            reported.add(ByteBufUtil.getBytes(frame));
          }
        };
    Iec60870Client client =
        client(
            TcpIec104Client.builder()
                .port(peer.getLocalPort())
                .startDataTransferOnConnect(false)
                .reconnectPolicy(ReconnectPolicy.disabled())
                .diagnostics(ApciDiagnostics.of(counters, capture)));

    // The decoder can reject the octets and drop the connection before the session is armed, which
    // fails the connect. The decode error and the close are reported either way.
    client.connectAsync();
    Await.until("the decode error", () -> counters.snapshot().decodeErrors() == 1);
    Await.until("the close", () -> counters.snapshot().connectionsClosed() == 1);

    assertArrayEquals(garbage, reported.get(0));
  }

  @Test
  void connectionLogLinesCarryTheLoggingContextAndLeaveNothingOnTheIoThread() throws Exception {
    Assumptions.assumeTrue(RecordingSlf4jProvider.isActive(), "needs the recording SLF4J provider");
    EventLoopGroup group = new NioEventLoopGroup(1);
    resources.add(() -> group.shutdownGracefully(0, 0, TimeUnit.SECONDS).sync());
    RecordingSlf4jProvider.startRecording("com.digitalpetri.iec60870");
    List<RecordingSlf4jProvider.Entry> entries;
    int port;
    try {
      port = reserveEphemeralPort();
      Iec60870Server server =
          buildServer(TcpIec104Server.builder().port(port).loggingContext(Map.of("site", "north")));
      server.start();
      Iec60870Client client =
          client(
              TcpIec104Client.builder()
                  .port(port)
                  .eventLoopGroup(group)
                  .reconnectPolicy(
                      ReconnectPolicy.exponentialBackoff(
                          Duration.ofMillis(50), Duration.ofMillis(50)))
                  .diagnostics(FrameTrace.logger(LoggerFactory.getLogger(TRACE_LOGGER)))
                  .loggingContext(Map.of("device", "rtu-5")));
      client.connect();
      client.interrogate(STATION);
      // A lost connection makes the client log its failed reconnects.
      server.close();
      Await.until("a reconnect", () -> client.connectionState() == ConnectionState.RECONNECT_WAIT);
      Thread.sleep(200);
    } finally {
      entries = RecordingSlf4jProvider.stopRecording();
    }

    Set<String> clientLoggers =
        Set.of(
            "com.digitalpetri.iec60870.client.DefaultIec60870Client",
            "com.digitalpetri.iec60870.transport.tcp.NettyClientTransport");
    String remote = "127.0.0.1:" + port;
    List<RecordingSlf4jProvider.Entry> clientEntries =
        entries.stream().filter(e -> clientLoggers.contains(e.logger())).toList();
    assertFalse(clientEntries.isEmpty(), entries.toString());
    for (RecordingSlf4jProvider.Entry entry : clientEntries) {
      assertEquals("rtu-5", entry.mdc().get("device"), entry.toString());
      assertEquals(remote, entry.mdc().get(LoggingContext.REMOTE_KEY), entry.toString());
    }
    // Frame trace lines carry the connection's context too.
    List<RecordingSlf4jProvider.Entry> traceEntries =
        entries.stream().filter(e -> e.logger().equals(TRACE_LOGGER)).toList();
    assertFalse(traceEntries.isEmpty(), "the trace logged frames");
    for (RecordingSlf4jProvider.Entry entry : traceEntries) {
      assertEquals("rtu-5", entry.mdc().get("device"), entry.toString());
      assertEquals(remote, entry.mdc().get(LoggingContext.REMOTE_KEY), entry.toString());
    }
    // Every connection-scoped library line names its device or site.
    for (RecordingSlf4jProvider.Entry entry : entries) {
      if (entry.logger().endsWith("Diagnostics")) {
        continue;
      }
      assertTrue(
          entry.mdc().containsKey("device") || entry.mdc().containsKey("site"), entry.toString());
    }
    assertEquals(
        Map.of(),
        requireNonNullElse(group.submit(MDC::getCopyOfContextMap).get(5, TimeUnit.SECONDS)));
  }

  // --- Helpers -----------------------------------------------------------------------------------

  private static Map<String, String> requireNonNullElse(@Nullable Map<String, String> map) {
    return map != null ? map : Map.of();
  }

  private int startServer(TcpIec104Server.Builder builder) throws Exception {
    int port = reserveEphemeralPort();
    Iec60870Server server = buildServer(builder.port(port));
    server.start();
    return port;
  }

  private Iec60870Server buildServer(TcpIec104Server.Builder builder) {
    Station station =
        Station.builder(STATION)
            .point(
                PointDefinition.of(
                    POINT,
                    PointType.SINGLE_POINT,
                    PointValue.single(true),
                    PointCapability.REPORTED))
            .build();
    Iec60870Server server = builder.bindAddress("127.0.0.1").addStation(station).build();
    resources.add(server);
    return server;
  }

  private Iec60870Client client(TcpIec104Client.Builder builder) {
    Iec60870Client client = builder.host("127.0.0.1").build();
    resources.add(client);
    return client;
  }

  private static int reserveEphemeralPort() throws IOException {
    try (ServerSocket socket = new ServerSocket(0)) {
      socket.setReuseAddress(true);
      return socket.getLocalPort();
    }
  }

  private static Throwable rootCause(Throwable error) {
    Throwable cause = error;
    while (cause.getCause() != null && cause.getCause() != cause) {
      cause = requireNonNull(cause.getCause());
    }
    return cause;
  }

  private static Throwable firstSslException(Throwable error) {
    Throwable cause = error;
    while (!(cause instanceof SSLException) && cause.getCause() != null) {
      cause = requireNonNull(cause.getCause());
    }
    return cause;
  }

  /** A listener that throws from every callback, including the trace. */
  private static final class Throwing implements ApciDiagnostics {
    @Override
    public void onApduSent(ControlField control, int frameLength) {
      throw new IllegalStateException();
    }

    @Override
    public void onApduReceived(ControlField control, int frameLength) {
      throw new IllegalStateException();
    }

    @Override
    public void onTimerExpired(ApciTimer timer) {
      throw new IllegalStateException();
    }

    @Override
    public void onFrameIgnored(UFunction function, String reason) {
      throw new IllegalStateException();
    }

    @Override
    public boolean traceFrames() {
      return true;
    }

    @Override
    public void onFrame(Direction direction, ByteBuf frame) {
      throw new IllegalStateException();
    }

    @Override
    public void onConnectAttempt(SocketAddress remote) {
      throw new IllegalStateException();
    }

    @Override
    public void onConnected(SocketAddress local, SocketAddress remote) {
      throw new IllegalStateException();
    }

    @Override
    public void onConnectionAccepted(SocketAddress remote) {
      throw new IllegalStateException();
    }

    @Override
    public void onConnectionClosed(@Nullable Throwable cause) {
      throw new IllegalStateException();
    }
  }
}
