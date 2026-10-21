package com.digitalpetri.iec60870.client;

import static java.util.Objects.requireNonNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.digitalpetri.iec60870.address.CommonAddress;
import com.digitalpetri.iec60870.address.InformationObjectAddress;
import com.digitalpetri.iec60870.address.OriginatorAddress;
import com.digitalpetri.iec60870.asdu.Asdu;
import com.digitalpetri.iec60870.asdu.AsduType;
import com.digitalpetri.iec60870.asdu.Cause;
import com.digitalpetri.iec60870.asdu.element.Qds;
import com.digitalpetri.iec60870.asdu.object.MeasuredValueScaled;
import com.digitalpetri.iec60870.diagnostics.ConnectionDiagnostics;
import com.digitalpetri.iec60870.diagnostics.Direction;
import com.digitalpetri.iec60870.fakes.FakeClientTransport;
import com.digitalpetri.iec60870.fakes.FakeSession;
import com.digitalpetri.iec60870.test.common.ManualScheduler;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Tests the connection-level diagnostics the client facade reports. */
class ClientDiagnosticsTest {

  private final ManualScheduler scheduler = new ManualScheduler();
  private final FakeClientTransport transport = new FakeClientTransport();
  private final AtomicReference<@Nullable FakeSession> sessionRef = new AtomicReference<>();
  private final List<String> log = Collections.synchronizedList(new ArrayList<>());
  private final List<ClientEvent> events = new CopyOnWriteArrayList<>();
  private @Nullable DefaultIec60870Client client;

  private final ConnectionDiagnostics diagnostics =
      new ConnectionDiagnostics() {
        @Override
        public void onConnectionClosed(@Nullable Throwable cause) {
          log.add("closed " + (cause == null ? "null" : cause.getMessage()));
        }

        @Override
        public void onReconnectScheduled(int attempt, Duration delay, Throwable cause) {
          log.add("reconnect " + attempt + " " + delay.toMillis());
        }

        @Override
        public void onInboundPaused(boolean paused) {
          log.add("paused " + paused);
        }

        @Override
        public void onEventDropped(Class<?> eventType) {
          log.add("eventDropped " + eventType.getSimpleName());
        }

        @Override
        public void onAsduDropped(Direction direction, String reason) {
          log.add("asduDropped " + direction);
        }
      };

  @AfterEach
  void tearDown() {
    if (client != null) {
      client.close();
    }
  }

  private DefaultIec60870Client newClient(
      ReconnectPolicy policy, int backlogLimit, boolean subscribe) {
    var client =
        new DefaultIec60870Client(
            transport,
            ClientConfig.builder()
                .callbackExecutor(Runnable::run)
                .reconnectPolicy(policy)
                .eventBacklogLimit(backlogLimit)
                .diagnostics(diagnostics)
                .build(),
            (events, scheduler) -> {
              FakeSession session = FakeSession.client(events);
              sessionRef.set(session);
              return session;
            },
            scheduler);
    this.client = client;
    if (subscribe) {
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
                  events.add(item);
                }

                @Override
                public void onError(Throwable throwable) {}

                @Override
                public void onComplete() {}
              });
    }
    return client;
  }

  private FakeSession session() {
    return requireNonNull(sessionRef.get());
  }

  @Test
  void lossReportsTheCloseAndTheScheduledReconnect() {
    newClient(ReconnectPolicy.defaults(), ClientConfig.DEFAULT_EVENT_BACKLOG_LIMIT, true).connect();

    session().fireConnectionLost(new IOException("reset"));

    assertEquals(List.of("closed reset", "reconnect 1 1000"), log);
  }

  @Test
  void applicationDisconnectReportsAnOrderlyClose() {
    DefaultIec60870Client client =
        newClient(ReconnectPolicy.disabled(), ClientConfig.DEFAULT_EVENT_BACKLOG_LIMIT, true);
    client.connect();

    client.disconnect();

    assertEquals(List.of("closed null"), log);
  }

  @Test
  void disconnectBeforeTheConnectStageCompletesStillReportsOneClose() {
    DefaultIec60870Client client =
        newClient(ReconnectPolicy.disabled(), ClientConfig.DEFAULT_EVENT_BACKLOG_LIMIT, true);
    transport.holdNextConnect();
    CompletableFuture<Void> connecting = client.connectAsync().toCompletableFuture();

    // The transport has reported the connection; the disconnect lands before its stage completes.
    client.disconnectAsync().toCompletableFuture().join();
    assertEquals(List.of(), log, "the teardown cannot know the transport connected yet");
    transport.heldConnect().complete(null);

    assertEquals(List.of("closed null"), log);
    assertTrue(connecting.isCompletedExceptionally());
    client.close();
    assertEquals(List.of("closed null"), log, "reported exactly once");
  }

  @Test
  void disconnectAfterTheConnectStageCompletesReportsOneClose() {
    DefaultIec60870Client client =
        newClient(ReconnectPolicy.disabled(), ClientConfig.DEFAULT_EVENT_BACKLOG_LIMIT, true);
    transport.holdNextConnect();
    client.connectAsync();
    transport.heldConnect().complete(null);

    client.disconnectAsync().toCompletableFuture().join();
    client.close();

    assertEquals(List.of("closed null"), log);
  }

  @Test
  void failedAttemptIsNotReportedAsAClose() {
    DefaultIec60870Client client =
        newClient(ReconnectPolicy.disabled(), ClientConfig.DEFAULT_EVENT_BACKLOG_LIMIT, true);
    transport.failConnect(new IOException("refused"));

    try {
      client.connect();
    } catch (RuntimeException expected) {
      // The transport reports the failed attempt.
    }

    assertEquals(List.of(), log);
  }

  @Test
  void protocolWarningsArePublished() {
    newClient(ReconnectPolicy.disabled(), ClientConfig.DEFAULT_EVENT_BACKLOG_LIMIT, true).connect();

    session().deliverProtocolWarning("ignored unsolicited TESTFR con");

    List<ClientEvent> warnings =
        events.stream().filter(ClientEvent.ProtocolWarning.class::isInstance).toList();
    assertEquals(
        List.of(new ClientEvent.ProtocolWarning("ignored unsolicited TESTFR con")), warnings);
  }

  @Test
  void inboundPauseAndBacklogDropsAreReported() {
    // No subscriber requests anything, so every published event stays in its backlog.
    DefaultIec60870Client client = newClient(ReconnectPolicy.disabled(), 1, false);
    client
        .events()
        .subscribe(
            new Flow.Subscriber<>() {
              @Override
              public void onSubscribe(Flow.Subscription subscription) {}

              @Override
              public void onNext(ClientEvent item) {}

              @Override
              public void onError(Throwable throwable) {}

              @Override
              public void onComplete() {}
            });
    client.connect();

    session().deliverAsdu(measured());
    assertEquals(List.of("paused true"), log);
    assertEquals(List.of(true), session().inboundPauses());

    // Past the hard capacity the client discards inbound data and closes the connection.
    for (int i = 0; i < 20_000 && !log.contains("asduDropped IN"); i++) {
      session().deliverAsdu(measured());
    }
    assertTrue(log.contains("asduDropped IN"), log.toString());
    session().deliverProtocolWarning("late");
    assertTrue(log.contains("eventDropped ProtocolWarning"), log.toString());
  }

  @Test
  void gaugesArePublic() {
    Iec60870Client client =
        newClient(ReconnectPolicy.disabled(), ClientConfig.DEFAULT_EVENT_BACKLOG_LIMIT, true);
    client.connect();

    client.interrogateAsync(CommonAddress.of(1));

    assertEquals(1, client.pendingRequestCount());
    assertEquals(session().pendingSendCount(), client.pendingSendCount());
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
