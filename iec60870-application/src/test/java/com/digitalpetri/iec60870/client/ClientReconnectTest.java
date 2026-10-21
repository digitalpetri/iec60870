package com.digitalpetri.iec60870.client;

import static java.util.Objects.requireNonNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.digitalpetri.iec60870.ConnectionClosedException;
import com.digitalpetri.iec60870.ProtocolTimeoutException;
import com.digitalpetri.iec60870.address.PointAddress;
import com.digitalpetri.iec60870.fakes.FakeClientTransport;
import com.digitalpetri.iec60870.fakes.FakeSession;
import com.digitalpetri.iec60870.test.common.ManualScheduler;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Tests the client-owned reconnect model on a virtual clock. */
class ClientReconnectTest {

  private final ManualScheduler scheduler = new ManualScheduler();
  private final FakeClientTransport transport = new FakeClientTransport();
  private final AtomicReference<@Nullable FakeSession> sessionRef = new AtomicReference<>();
  private final List<ClientEvent> events = new ArrayList<>();
  private boolean completed;
  private @Nullable DefaultIec60870Client client;

  @AfterEach
  void tearDown() {
    if (client != null) {
      client.close();
    }
  }

  private DefaultIec60870Client newClient(ReconnectPolicy policy) {
    var client =
        new DefaultIec60870Client(
            transport,
            ClientConfig.builder().callbackExecutor(Runnable::run).reconnectPolicy(policy).build(),
            (events, scheduler) -> {
              FakeSession session = FakeSession.client(events);
              sessionRef.set(session);
              return session;
            },
            scheduler);
    this.client = client;
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
              public void onComplete() {
                completed = true;
              }
            });
    return client;
  }

  private FakeSession session() {
    return requireNonNull(sessionRef.get());
  }

  private List<Class<?>> eventTypes() {
    return events.stream().<Class<?>>map(Object::getClass).toList();
  }

  @Test
  void lostConnectionReconnectsAfterTheInitialDelayWithNoApplicationCall() {
    DefaultIec60870Client client = newClient(ReconnectPolicy.defaults());
    client.connect();
    events.clear();

    session().fireConnectionLost(null);

    assertEquals(
        List.of(
            ClientEvent.DataTransferStopped.class,
            ClientEvent.ConnectionClosed.class,
            ClientEvent.ReconnectScheduled.class),
        eventTypes());
    var closed = (ClientEvent.ConnectionClosed) events.get(1);
    assertInstanceOf(
        ConnectionClosedException.class,
        closed.cause(),
        "a peer close is reported with a cause, never as an application close");
    var scheduled = (ClientEvent.ReconnectScheduled) events.get(2);
    assertEquals(1, scheduled.attempt());
    assertEquals(Duration.ofSeconds(1), scheduled.delay());
    assertSame(closed.cause(), scheduled.cause());
    assertEquals(ConnectionState.RECONNECT_WAIT, client.connectionState());
    assertFalse(client.isConnected());
    assertEquals(1, transport.disconnectCount(), "the lost connection is closed");

    scheduler.advance(999, TimeUnit.MILLISECONDS);
    assertEquals(1, transport.connectCount());

    events.clear();
    scheduler.advance(1, TimeUnit.MILLISECONDS);
    assertEquals(2, transport.connectCount());
    assertEquals(
        List.of(ClientEvent.ConnectionOpened.class, ClientEvent.DataTransferStarted.class),
        eventTypes());
    assertTrue(session().isDataTransferStarted());
    assertEquals(ConnectionState.CONNECTED, client.connectionState());
    assertTrue(client.isConnected());
  }

  @Test
  void protocolSelfCloseFollowsThePolicyLikeALoss() {
    DefaultIec60870Client client = newClient(ReconnectPolicy.defaults());
    client.connect();
    var cause = new ProtocolTimeoutException("t1");

    session().fireClosed(cause);

    assertEquals(ConnectionState.RECONNECT_WAIT, client.connectionState());
    scheduler.advance(1, TimeUnit.SECONDS);
    assertEquals(ConnectionState.CONNECTED, client.connectionState());
    assertEquals(2, transport.connectCount());
  }

  @Test
  void failedAttemptsBackOffUpToTheMaximumAndAnEstablishedConnectionResetsTheBackoff() {
    DefaultIec60870Client client =
        newClient(
            ReconnectPolicy.exponentialBackoff(Duration.ofMillis(100), Duration.ofMillis(350)));
    var refused = new IOException("connection refused");
    transport.failConnect(refused);

    var failure = assertThrows(ConnectionClosedException.class, client::connect);
    assertSame(refused, failure.getCause(), "connect() carries the transport cause");

    scheduler.advance(100, TimeUnit.MILLISECONDS);
    scheduler.advance(200, TimeUnit.MILLISECONDS);
    scheduler.advance(350, TimeUnit.MILLISECONDS);
    List<ClientEvent.ReconnectScheduled> scheduled = scheduledReconnects();
    assertEquals(4, scheduled.size());
    assertEquals(List.of(1, 2, 3, 4), scheduled.stream().map(e -> e.attempt()).toList());
    assertEquals(
        List.of(
            Duration.ofMillis(100),
            Duration.ofMillis(200),
            Duration.ofMillis(350),
            Duration.ofMillis(350)),
        scheduled.stream().map(e -> e.delay()).toList());
    assertTrue(scheduled.stream().allMatch(e -> e.cause() instanceof ConnectionClosedException));
    assertSame(refused, scheduled.get(0).cause().getCause());
    assertEquals(4, transport.connectCount());

    transport.failConnect(null);
    scheduler.advance(350, TimeUnit.MILLISECONDS);
    assertEquals(ConnectionState.CONNECTED, client.connectionState());

    events.clear();
    session().fireConnectionLost(new IOException("reset"));
    List<ClientEvent.ReconnectScheduled> afterLoss = scheduledReconnects();
    assertEquals(1, afterLoss.size());
    assertEquals(1, afterLoss.get(0).attempt());
    assertEquals(Duration.ofMillis(100), afterLoss.get(0).delay());
  }

  @Test
  void disabledPolicyStaysDisconnectedUntilConnectIsCalled() {
    DefaultIec60870Client client = newClient(ReconnectPolicy.disabled());
    client.connect();

    session().fireConnectionLost(new IOException("reset"));

    assertEquals(ConnectionState.DISCONNECTED, client.connectionState());
    assertTrue(scheduledReconnects().isEmpty());
    scheduler.advance(1, TimeUnit.HOURS);
    assertEquals(1, transport.connectCount(), "nothing dials without a policy");

    client.connect();
    assertEquals(2, transport.connectCount());
    assertEquals(ConnectionState.CONNECTED, client.connectionState());
  }

  @Test
  void disabledPolicyLeavesAFailedConnectDisconnected() {
    DefaultIec60870Client client = newClient(ReconnectPolicy.disabled());
    transport.failConnect(new IOException("connection refused"));

    assertThrows(ConnectionClosedException.class, client::connect);

    assertEquals(ConnectionState.DISCONNECTED, client.connectionState());
    scheduler.advance(1, TimeUnit.HOURS);
    assertEquals(1, transport.connectCount());
  }

  @Test
  void connectWhileWaitingDialsAtOnce() {
    DefaultIec60870Client client = newClient(ReconnectPolicy.defaults());
    client.connect();
    session().fireConnectionLost(null);

    client.connect();

    assertEquals(2, transport.connectCount());
    assertEquals(ConnectionState.CONNECTED, client.connectionState());
    scheduler.advance(1, TimeUnit.MINUTES);
    assertEquals(2, transport.connectCount(), "the cancelled reconnect does not fire");
  }

  @Test
  void disconnectCancelsTheScheduledReconnect() {
    DefaultIec60870Client client = newClient(ReconnectPolicy.defaults());
    client.connect();
    session().fireConnectionLost(null);

    client.disconnect();

    assertEquals(ConnectionState.DISCONNECTED, client.connectionState());
    scheduler.advance(1, TimeUnit.MINUTES);
    assertEquals(1, transport.connectCount());
  }

  @Test
  void disconnectPublishesAnApplicationCloseAndAllowsConnectingAgain() {
    DefaultIec60870Client client = newClient(ReconnectPolicy.defaults());
    client.connect();
    PointAddress point = PointAddress.of(1, 100);
    CompletionStage<?> read = client.readAsync(point);
    events.clear();

    client.disconnect();

    assertEquals(
        List.of(ClientEvent.DataTransferStopped.class, ClientEvent.ConnectionClosed.class),
        eventTypes());
    assertNull(((ClientEvent.ConnectionClosed) events.get(1)).cause());
    var error = assertThrows(CompletionException.class, () -> read.toCompletableFuture().join());
    assertInstanceOf(ConnectionClosedException.class, error.getCause());
    assertEquals(ConnectionState.DISCONNECTED, client.connectionState());
    assertTrue(session().isClosed());
    scheduler.advance(1, TimeUnit.MINUTES);
    assertEquals(1, transport.connectCount());

    client.connect();
    assertEquals(ConnectionState.CONNECTED, client.connectionState());
    assertEquals(2, transport.connectCount());
  }

  @Test
  void closePublishesConnectionClosedBeforeTheStreamCompletes() {
    DefaultIec60870Client client = newClient(ReconnectPolicy.defaults());
    client.connect();
    events.clear();

    client.close();

    assertEquals(
        List.of(ClientEvent.DataTransferStopped.class, ClientEvent.ConnectionClosed.class),
        eventTypes());
    assertNull(((ClientEvent.ConnectionClosed) events.get(1)).cause());
    assertTrue(completed);
    assertEquals(1, transport.closeCount(), "close() releases the transport");
    assertEquals(ConnectionState.CLOSED, client.connectionState());
    var error =
        assertThrows(
            CompletionException.class, () -> client.connectAsync().toCompletableFuture().join());
    assertInstanceOf(ConnectionClosedException.class, error.getCause());
  }

  @Test
  void closeWhileWaitingCancelsTheReconnect() {
    DefaultIec60870Client client = newClient(ReconnectPolicy.defaults());
    client.connect();
    session().fireConnectionLost(null);

    client.close();

    assertEquals(ConnectionState.CLOSED, client.connectionState());
    scheduler.advance(1, TimeUnit.MINUTES);
    assertEquals(1, transport.connectCount());
    assertTrue(completed);
  }

  @Test
  void connectionStartedWithoutDataTransferIsNotFollowedByDataTransferStopped() {
    var client =
        new DefaultIec60870Client(
            transport,
            ClientConfig.builder()
                .callbackExecutor(Runnable::run)
                .startDataTransferOnConnect(false)
                .build(),
            (events, scheduler) -> {
              FakeSession session = FakeSession.client(events);
              sessionRef.set(session);
              return session;
            },
            scheduler);
    this.client = client;
    List<ClientEvent> received = new ArrayList<>();
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
                received.add(item);
              }

              @Override
              public void onError(Throwable throwable) {}

              @Override
              public void onComplete() {}
            });
    client.connect();

    session().fireConnectionLost(null);

    assertEquals(
        List.of(
            ClientEvent.ConnectionOpened.class,
            ClientEvent.ConnectionClosed.class,
            ClientEvent.ReconnectScheduled.class),
        received.stream().<Class<?>>map(Object::getClass).toList());
    scheduler.advance(1, TimeUnit.SECONDS);
    assertFalse(session().isDataTransferStarted(), "automatic attempts honor the setting");
  }

  private List<ClientEvent.ReconnectScheduled> scheduledReconnects() {
    List<ClientEvent.ReconnectScheduled> scheduled = new ArrayList<>();
    for (ClientEvent event : events) {
      if (event instanceof ClientEvent.ReconnectScheduled reconnect) {
        scheduled.add(reconnect);
      }
    }
    return scheduled;
  }
}
