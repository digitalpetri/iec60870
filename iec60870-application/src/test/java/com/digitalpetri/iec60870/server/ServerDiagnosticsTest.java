package com.digitalpetri.iec60870.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.digitalpetri.iec60870.AsduDecodeException;
import com.digitalpetri.iec60870.OutboundQueuePolicy;
import com.digitalpetri.iec60870.address.CommonAddress;
import com.digitalpetri.iec60870.address.InformationObjectAddress;
import com.digitalpetri.iec60870.address.OriginatorAddress;
import com.digitalpetri.iec60870.asdu.Asdu;
import com.digitalpetri.iec60870.asdu.AsduType;
import com.digitalpetri.iec60870.asdu.Cause;
import com.digitalpetri.iec60870.asdu.element.Qds;
import com.digitalpetri.iec60870.asdu.object.MeasuredValueScaled;
import com.digitalpetri.iec60870.asdu.object.ReadCommand;
import com.digitalpetri.iec60870.diagnostics.ConnectionDiagnostics;
import com.digitalpetri.iec60870.diagnostics.Direction;
import com.digitalpetri.iec60870.fakes.FakeServerTransport;
import com.digitalpetri.iec60870.fakes.FakeSession;
import com.digitalpetri.iec60870.session.Session;
import com.digitalpetri.iec60870.transport.ServerTransportConnection;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Flow;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/** Tests the per-connection diagnostics the server facade reports. */
class ServerDiagnosticsTest {

  private final FakeServerTransport transport = new FakeServerTransport();
  private final List<String> log = Collections.synchronizedList(new ArrayList<>());
  private final AtomicInteger resolved = new AtomicInteger();
  private final List<ConnectionDiagnostics> passedToFactory = new ArrayList<>();

  private final ConnectionDiagnostics diagnostics =
      new ConnectionDiagnostics() {
        @Override
        public void onConnectionAccepted(SocketAddress remote) {
          log.add("accepted");
        }

        @Override
        public void onConnectFailed(SocketAddress remote, Throwable cause) {
          log.add("rejected");
        }

        @Override
        public void onConnectionClosed(@Nullable Throwable cause) {
          log.add("closed");
        }

        @Override
        public void onEventDropped(Class<?> eventType) {
          log.add("eventDropped " + eventType.getSimpleName());
        }

        @Override
        public void onAsduDropped(Direction direction, String reason) {
          log.add("asduDropped " + direction + " " + reason);
        }
      };

  private DefaultIec60870Server server(ServerConfig.Builder builder) {
    ServerConfig config =
        builder
            .station(Station.builder(CommonAddress.of(1)).build())
            .diagnostics(
                remote -> {
                  resolved.incrementAndGet();
                  return diagnostics;
                })
            .build();
    DefaultIec60870Server server =
        new DefaultIec60870Server(
            transport,
            config,
            new DefaultIec60870Server.SessionFactory() {
              @Override
              public Session create(
                  ServerTransportConnection connection,
                  Session.Events events,
                  ScheduledExecutorService scheduler) {
                throw new AssertionError("the server calls the four-argument create");
              }

              @Override
              public Session create(
                  ServerTransportConnection connection,
                  Session.Events events,
                  ScheduledExecutorService scheduler,
                  ConnectionDiagnostics connectionDiagnostics) {
                passedToFactory.add(connectionDiagnostics);
                FakeSession session =
                    FakeSession.server(
                        events, config.maxOutboundQueue(), config.eventQueuePolicy());
                ((FakeServerTransport.FakeConnection) connection).attachSession(session);
                return session;
              }
            });
    server.start();
    return server;
  }

  @Test
  void acceptedAndClosedConnectionsAreReportedToTheirListener() {
    server(ServerConfig.builder().callbackExecutor(Runnable::run));

    FakeServerTransport.FakeConnection connection = transport.accept("client");
    connection.loseConnection();

    assertEquals(List.of("accepted", "closed"), log);
    assertEquals(1, resolved.get());
    assertEquals(List.of(diagnostics), passedToFactory, "the factory gets the configured listener");
  }

  @Test
  void connectionsOverTheLimitAreReportedAsRejected() {
    server(ServerConfig.builder().callbackExecutor(Runnable::run).maxConnections(1));

    transport.accept("first");
    transport.accept("second");

    assertEquals(List.of("accepted", "rejected"), log);
  }

  @Test
  void inboundAsdusDroppedAtTheDispatchBoundAreReported() {
    // Nothing runs on this executor, so the first ASDU stays on the dispatch chain.
    List<Runnable> parked = new ArrayList<>();
    Executor executor = parked::add;
    server(ServerConfig.builder().callbackExecutor(executor).maxInboundQueue(1));
    FakeServerTransport.FakeConnection connection = transport.accept("client");
    connection.startDataTransfer();

    connection.deliverAsdu(read());
    connection.deliverAsdu(read());

    assertEquals(List.of("accepted", "asduDropped IN dispatch backlog full"), log);
  }

  @Test
  void serverEventsDroppedForASlowSubscriberAreReported() {
    DefaultIec60870Server server = server(ServerConfig.builder().callbackExecutor(Runnable::run));
    server
        .events()
        .subscribe(
            new Flow.Subscriber<>() {
              @Override
              public void onSubscribe(Flow.Subscription subscription) {}

              @Override
              public void onNext(ServerEvent item) {}

              @Override
              public void onError(Throwable throwable) {}

              @Override
              public void onComplete() {}
            });
    FakeServerTransport.FakeConnection connection = transport.accept("client");
    connection.startDataTransfer();

    byte[] malformed = HexFormat.of().parseHex("0d0103000100640000000000");
    for (int i = 0; i < Flow.defaultBufferSize() + 10; i++) {
      connection.deliverUndecodableAsdu(malformed, new AsduDecodeException("truncated"));
    }

    assertTrue(log.contains("eventDropped UndecodableAsdu"), log.toString());
  }

  @Test
  void monitorAsdusDiscardedByTheQueuePolicyAreReported() {
    for (OutboundQueuePolicy policy :
        List.of(OutboundQueuePolicy.DROP_NEWEST, OutboundQueuePolicy.DROP_OLDEST)) {
      List<String> reasons = new ArrayList<>();
      var queue =
          new MonitorPublicationQueue(
              ServerConfig.builder().maxOutboundQueue(1).eventQueuePolicy(policy).build(),
              ignored -> {},
              asdu -> CompletableFuture.completedFuture(null),
              Runnable::run,
              error -> fail(error),
              reasons::add);
      try {
        // An interrogation in progress defers publications into the bounded buffer.
        queue.hold();
        queue.submit(measured());
        queue.submit(measured());
      } finally {
        queue.close();
      }
      String expected =
          policy == OutboundQueuePolicy.DROP_NEWEST
              ? "event queue full (DROP_NEWEST)"
              : "event queue full (evicted)";
      assertEquals(List.of(expected), reasons, policy.toString());
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
        CommonAddress.of(1),
        List.of(new ReadCommand(InformationObjectAddress.of(1))));
  }

  private static Asdu measured() {
    return new Asdu(
        AsduType.M_ME_NB_1,
        false,
        Cause.SPONTANEOUS,
        false,
        false,
        OriginatorAddress.none(),
        CommonAddress.of(1),
        List.of(
            new MeasuredValueScaled(
                InformationObjectAddress.of(110),
                (short) 1,
                new Qds(false, false, false, false, false))));
  }
}
