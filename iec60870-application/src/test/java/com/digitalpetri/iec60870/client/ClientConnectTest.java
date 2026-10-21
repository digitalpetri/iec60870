package com.digitalpetri.iec60870.client;

import static java.util.Objects.requireNonNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.digitalpetri.iec60870.ConnectionClosedException;
import com.digitalpetri.iec60870.address.CommonAddress;
import com.digitalpetri.iec60870.address.InformationObjectAddress;
import com.digitalpetri.iec60870.address.PointAddress;
import com.digitalpetri.iec60870.asdu.Asdu;
import com.digitalpetri.iec60870.asdu.InformationObject;
import com.digitalpetri.iec60870.session.Session;
import com.digitalpetri.iec60870.transport.ClientTransport;
import com.digitalpetri.iec60870.transport.TransportListener;
import io.netty.buffer.ByteBuf;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Connect lifecycle tests with explicitly completed transport and STARTDT stages. */
class ClientConnectTest {

  private final PendingTransport transport = new PendingTransport();
  private final AtomicReference<@Nullable PendingSession> sessionRef = new AtomicReference<>();
  private final DefaultIec60870Client client =
      new DefaultIec60870Client(
          transport,
          ClientConfig.builder().callbackExecutor(Runnable::run).build(),
          (events, scheduler) -> {
            var session = new PendingSession(events);
            sessionRef.set(session);
            return session;
          });

  private PendingSession session() {
    return requireNonNull(sessionRef.get());
  }

  @AfterEach
  void tearDown() {
    client.close();
  }

  @Test
  void repeatedConnectPreservesTheActiveSession() {
    CompletionStage<Void> first = client.connectAsync();
    transport.result.complete(null);
    session().startResult.complete(null);
    first.toCompletableFuture().join();

    client.connectAsync().toCompletableFuture().join();

    assertEquals(1, transport.connectCount);
    assertEquals(1, session().initializations);
    assertEquals(1, session().starts);
  }

  @Test
  void overlappingConnectsShareTransportAndDataTransferInitialization() {
    CompletionStage<Void> first = client.connectAsync();
    CompletionStage<Void> second = client.connectAsync();
    assertEquals(1, transport.connectCount);
    assertEquals(0, session().initializations);
    assertFalse(first.toCompletableFuture().isDone());
    assertFalse(second.toCompletableFuture().isDone());

    transport.result.complete(null);
    CompletionStage<Void> duringStart = client.connectAsync();
    assertEquals(1, session().initializations);
    assertEquals(1, session().starts);
    assertFalse(first.toCompletableFuture().isDone());
    assertFalse(second.toCompletableFuture().isDone());
    assertFalse(duringStart.toCompletableFuture().isDone());

    session().startResult.complete(null);
    first.toCompletableFuture().join();
    second.toCompletableFuture().join();
    duringStart.toCompletableFuture().join();
  }

  @Test
  void failedTransportConnectCanBeRetried() {
    CompletionStage<Void> first = client.connectAsync();
    transport.result.completeExceptionally(new IOException("connection refused"));
    assertThrows(CompletionException.class, () -> first.toCompletableFuture().join());

    transport.result = new CompletableFuture<>();
    CompletionStage<Void> retry = client.connectAsync();
    transport.result.complete(null);
    session().startResult.complete(null);
    retry.toCompletableFuture().join();
    assertEquals(2, transport.connectCount);
    assertEquals(1, session().initializations);
  }

  @Test
  void connectAfterConnectionLossInitializesTheReplacementSession() {
    CompletionStage<Void> first = client.connectAsync();
    transport.result.complete(null);
    session().startResult.complete(null);
    first.toCompletableFuture().join();
    session().events.onConnectionLost(null);

    transport.result = new CompletableFuture<>();
    session().startResult = new CompletableFuture<>();
    CompletionStage<Void> reconnect = client.connectAsync();
    transport.result.complete(null);
    session().startResult.complete(null);
    reconnect.toCompletableFuture().join();
    assertEquals(2, transport.connectCount);
    assertEquals(2, session().initializations);
    assertEquals(2, session().starts);
  }

  @Test
  void closedClientDoesNotInitializeFromALateTransportCompletion() {
    CompletionStage<Void> pending = client.connectAsync();
    client.close();
    transport.result.complete(null);

    assertEquals(0, session().initializations);
    assertEquals(0, session().starts);
    var ex = assertThrows(CompletionException.class, () -> pending.toCompletableFuture().join());
    assertInstanceOf(ConnectionClosedException.class, ex.getCause());
    assertThrows(
        CompletionException.class, () -> client.connectAsync().toCompletableFuture().join());
    assertEquals(1, transport.connectCount);
  }

  @Test
  void disconnectDiscardsALateCompletionOfTheOldConnectAttempt() {
    CompletionStage<Void> oldAttempt = client.connectAsync();
    CompletableFuture<Void> oldTransportResult = transport.result;
    client.disconnectAsync();
    transport.result = new CompletableFuture<>();
    CompletionStage<Void> replacement = client.connectAsync();

    oldTransportResult.complete(null);
    assertEquals(0, session().initializations);
    var ex = assertThrows(CompletionException.class, () -> oldAttempt.toCompletableFuture().join());
    assertInstanceOf(ConnectionClosedException.class, ex.getCause());
    assertFalse(replacement.toCompletableFuture().isDone());

    transport.result.complete(null);
    session().startResult.complete(null);
    replacement.toCompletableFuture().join();
    assertEquals(1, session().initializations);
    assertEquals(1, session().starts);
  }

  @Test
  void connectionLostBeforeTheSessionIsArmedFailsTheAttempt() {
    var noDataTransfer =
        new DefaultIec60870Client(
            transport,
            ClientConfig.builder()
                .callbackExecutor(Runnable::run)
                .startDataTransferOnConnect(false)
                .reconnectPolicy(ReconnectPolicy.disabled())
                .build(),
            (events, scheduler) -> {
              var session = new PendingSession(events);
              sessionRef.set(session);
              return session;
            });
    try {
      CompletionStage<Void> attempt = noDataTransfer.connectAsync();
      // The socket connects and is lost again before the facade can attribute the loss to it.
      session().events.onConnectionLost(null);
      transport.dropped = true;
      transport.result.complete(null);

      var error =
          assertThrows(CompletionException.class, () -> attempt.toCompletableFuture().join());
      assertInstanceOf(ConnectionClosedException.class, error.getCause());
      assertEquals(ConnectionState.DISCONNECTED, noDataTransfer.connectionState());
    } finally {
      noDataTransfer.close();
    }
  }

  @Test
  void lossReportedBeforeTheTransportConnectsIsIgnored() {
    CompletionStage<Void> attempt = client.connectAsync();
    // Only an older connection can report a loss while this attempt is still dialing.
    session().events.onConnectionLost(null);
    assertEquals(ConnectionState.CONNECTING, client.connectionState());

    transport.result.complete(null);
    session().startResult.complete(null);
    attempt.toCompletableFuture().join();
    assertEquals(ConnectionState.CONNECTED, client.connectionState());
  }

  @Test
  void closeFailsConnectWhileDataTransferIsPending() {
    CompletionStage<Void> pending = client.connectAsync();
    transport.result.complete(null);
    client.close();

    assertTrue(pending.toCompletableFuture().isCompletedExceptionally());
    session().startResult.complete(null);
    var ex = assertThrows(CompletionException.class, () -> pending.toCompletableFuture().join());
    assertInstanceOf(ConnectionClosedException.class, ex.getCause());
  }

  @Test
  void lossDuringInitializationQueuesReplacementBeforeStartingDataTransfer() {
    AtomicReference<@Nullable CompletionStage<Void>> replacement = new AtomicReference<>();
    session().afterInitialize =
        () -> {
          session().afterInitialize = () -> {};
          session().events.onConnectionLost(null);
          replacement.set(client.connectAsync());
        };
    CompletionStage<Void> oldAttempt = client.connectAsync();
    transport.result.complete(null);

    assertTrue(oldAttempt.toCompletableFuture().isCompletedExceptionally());
    assertEquals(2, session().initializations);
    assertEquals(1, session().starts);
    session().startResult.complete(null);
    requireNonNull(replacement.get()).toCompletableFuture().join();
  }

  @Test
  void cancellingOneConnectCallerDoesNotCancelOtherCallers() {
    CompletionStage<Void> cancelled = client.connectAsync();
    CompletionStage<Void> surviving = client.connectAsync();
    assertTrue(cancelled.toCompletableFuture().cancel(false));
    transport.result.complete(null);
    session().startResult.complete(null);

    surviving.toCompletableFuture().join();
    assertEquals(1, session().initializations);
    client.connectAsync().toCompletableFuture().join();
    assertEquals(1, session().initializations);
  }

  @Test
  void connectionLossDetachesRequestsBeforeAdmittingReconnect() throws Exception {
    var lossLock = new PausingLossLock();
    // Pause precisely after the loss handler releases its first client-lock critical section.
    // A transport/session hook cannot expose the gap between detaching and failing the requests.
    Field clientLock = DefaultIec60870Client.class.getDeclaredField("lock");
    clientLock.setAccessible(true);
    clientLock.set(client, lossLock);
    CompletionStage<Void> connected = client.connectAsync();
    transport.result.complete(null);
    session().startResult.complete(null);
    connected.toCompletableFuture().join();
    PointAddress point = new PointAddress(CommonAddress.of(1), InformationObjectAddress.of(100));
    CompletionStage<List<InformationObject>> oldRead = client.readAsync(point);
    assertEquals(1, client.pendingRequestCount());

    ExecutorService worker = Executors.newSingleThreadExecutor();
    try {
      Future<?> loss =
          worker.submit(
              () -> {
                lossLock.lossThread = Thread.currentThread();
                session().events.onConnectionLost(null);
              });
      assertTrue(lossLock.detached.await(5, TimeUnit.SECONDS));
      CompletionStage<Void> reconnect = client.connectAsync();
      assertEquals(
          0, client.pendingRequestCount(), "old requests must be detached before reconnect");
      CompletionStage<List<InformationObject>> newRead = client.readAsync(point);
      assertFalse(
          newRead.toCompletableFuture().isDone(), "the replacement can use the same target");
      assertFalse(
          reconnect.toCompletableFuture().isDone(),
          "the replacement dials only after the old connection's teardown");
      lossLock.proceed.countDown();
      loss.get(5, TimeUnit.SECONDS);
      reconnect.toCompletableFuture().get(5, TimeUnit.SECONDS);
      assertEquals(2, session().initializations);
      CompletionException error =
          assertThrows(CompletionException.class, () -> oldRead.toCompletableFuture().join());
      assertInstanceOf(ConnectionClosedException.class, error.getCause());
      assertFalse(
          newRead.toCompletableFuture().isDone(), "old cleanup must not fail the new request");
      assertEquals(1, client.pendingRequestCount());
    } finally {
      lossLock.proceed.countDown();
      worker.shutdownNow();
      assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS));
    }
  }

  @Test
  void connectAfterADisconnectWaitsForItsTeardown() {
    // Nothing is connected, but the disconnect's transport close is still in progress.
    var disconnecting = new CompletableFuture<Void>();
    transport.disconnectResult = disconnecting;
    CompletionStage<Void> disconnected = client.disconnectAsync();
    transport.disconnectResult = CompletableFuture.completedFuture(null);

    CompletionStage<Void> connecting = client.connectAsync();
    assertEquals(0, transport.connectCount, "the new connection must not dial before the teardown");

    disconnecting.complete(null);
    assertTrue(disconnected.toCompletableFuture().isDone());
    assertEquals(1, transport.connectCount);
    transport.result.complete(null);
    session().startResult.complete(null);
    connecting.toCompletableFuture().join();
    assertEquals(List.of("disconnect", "connect"), transport.calls);
  }

  @Test
  void disconnectRacingAnAdmittedDialAbortsTheAttempt() throws Exception {
    var lossLock = new PausingLossLock();
    Field clientLock = DefaultIec60870Client.class.getDeclaredField("lock");
    clientLock.setAccessible(true);
    clientLock.set(client, lossLock);
    CompletionStage<Void> connected = client.connectAsync();
    transport.result.complete(null);
    session().startResult.complete(null);
    connected.toCompletableFuture().join();

    // The lost connection's transport close completes later, on another thread, which then dials
    // the replacement. Pause that thread right after the dial is admitted.
    var closing = new CompletableFuture<Void>();
    transport.disconnectResult = closing;
    session().events.onConnectionLost(null);
    transport.disconnectResult = CompletableFuture.completedFuture(null);
    transport.result = new CompletableFuture<>();
    CompletionStage<Void> replacement = client.connectAsync();

    ExecutorService worker = Executors.newSingleThreadExecutor();
    try {
      Future<?> dialing =
          worker.submit(
              () -> {
                lossLock.lossThread = Thread.currentThread();
                closing.complete(null);
              });
      assertTrue(lossLock.detached.await(5, TimeUnit.SECONDS));
      CompletionStage<Void> disconnected = client.disconnectAsync();
      lossLock.proceed.countDown();
      dialing.get(5, TimeUnit.SECONDS);
      disconnected.toCompletableFuture().get(5, TimeUnit.SECONDS);

      assertEquals(
          "disconnect",
          transport.calls.get(transport.calls.size() - 1),
          "an admitted dial must be aborted by the disconnect, not follow it: " + transport.calls);
      var error =
          assertThrows(CompletionException.class, () -> replacement.toCompletableFuture().join());
      assertInstanceOf(ConnectionClosedException.class, error.getCause());
      assertEquals(ConnectionState.DISCONNECTED, client.connectionState());
    } finally {
      lossLock.proceed.countDown();
      worker.shutdownNow();
      assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS));
    }
  }

  @Test
  void cancellingAConnectionDoesNotReleaseAnEarlierDisconnectBarrier() throws Exception {
    var lossLock = new PausingLossLock();
    Field clientLock = DefaultIec60870Client.class.getDeclaredField("lock");
    clientLock.setAccessible(true);
    clientLock.set(client, lossLock);

    ExecutorService worker = Executors.newSingleThreadExecutor();
    try {
      // D1 installs its barrier, then pauses before queueing its teardown.
      Future<CompletionStage<Void>> first =
          worker.submit(
              () -> {
                lossLock.lossThread = Thread.currentThread();
                return client.disconnectAsync();
              });
      assertTrue(lossLock.detached.await(5, TimeUnit.SECONDS));

      // Connection A waits for D1; D2 cancels A; connection B must still wait for D1.
      CompletionStage<Void> cancelled = client.connectAsync();
      client.disconnectAsync();
      CompletionStage<Void> later = client.connectAsync();
      assertTrue(cancelled.toCompletableFuture().isCompletedExceptionally());
      assertEquals(0, transport.connectCount, "B must not dial before D1's teardown");

      lossLock.proceed.countDown();
      first.get(5, TimeUnit.SECONDS).toCompletableFuture().get(5, TimeUnit.SECONDS);
      assertEquals(1, transport.connectCount);
      transport.result.complete(null);
      session().startResult.complete(null);
      later.toCompletableFuture().get(5, TimeUnit.SECONDS);
      assertEquals(ConnectionState.CONNECTED, client.connectionState());
      assertEquals("connect", transport.calls.get(transport.calls.size() - 1));
    } finally {
      lossLock.proceed.countDown();
      worker.shutdownNow();
      assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS));
    }
  }

  @Test
  void closeCompletesTheEventStreamOnlyAfterAnEarlierDisconnectPublishes() throws Exception {
    List<Object> events = new CopyOnWriteArrayList<>();
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
                events.add("complete");
              }
            });
    CompletionStage<Void> connected = client.connectAsync();
    transport.result.complete(null);
    session().startResult.complete(null);
    connected.toCompletableFuture().join();
    var lossLock = new PausingLossLock();
    Field clientLock = DefaultIec60870Client.class.getDeclaredField("lock");
    clientLock.setAccessible(true);
    clientLock.set(client, lossLock);

    ExecutorService worker = Executors.newSingleThreadExecutor();
    try {
      // The disconnect detaches the connection, then pauses before queueing its teardown.
      Future<CompletionStage<Void>> disconnecting =
          worker.submit(
              () -> {
                lossLock.lossThread = Thread.currentThread();
                return client.disconnectAsync();
              });
      assertTrue(lossLock.detached.await(5, TimeUnit.SECONDS));

      client.close();
      assertFalse(events.contains("complete"), "the stream must wait for the disconnect");

      lossLock.proceed.countDown();
      disconnecting.get(5, TimeUnit.SECONDS).toCompletableFuture().get(5, TimeUnit.SECONDS);
      int closedAt =
          events.indexOf(
              events.stream()
                  .filter(e -> e instanceof ClientEvent.ConnectionClosed)
                  .findFirst()
                  .orElseThrow());
      assertEquals(events.size() - 1, events.indexOf("complete"));
      assertTrue(closedAt < events.size() - 1);
    } finally {
      lossLock.proceed.countDown();
      worker.shutdownNow();
      assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS));
    }
  }

  private static final class PausingLossLock extends ReentrantLock {
    private volatile @Nullable Thread lossThread;
    private final AtomicBoolean paused = new AtomicBoolean();
    private final CountDownLatch detached = new CountDownLatch(1);
    private final CountDownLatch proceed = new CountDownLatch(1);

    @Override
    public void unlock() {
      super.unlock();
      if (Thread.currentThread() == lossThread && paused.compareAndSet(false, true)) {
        detached.countDown();
        try {
          assertTrue(proceed.await(5, TimeUnit.SECONDS));
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new AssertionError(e);
        }
      }
    }
  }

  private static final class PendingTransport implements ClientTransport {
    private CompletableFuture<Void> result = new CompletableFuture<>();
    private CompletableFuture<Void> disconnectResult = CompletableFuture.completedFuture(null);
    private final List<String> calls = new CopyOnWriteArrayList<>();
    private volatile boolean dropped;
    private int connectCount;

    @Override
    public CompletionStage<Void> connect() {
      connectCount++;
      calls.add("connect");
      return result;
    }

    @Override
    public CompletionStage<Void> disconnect() {
      calls.add("disconnect");
      return disconnectResult;
    }

    @Override
    public void closeConnection() {}

    @Override
    public boolean isConnected() {
      return !dropped && result.isDone() && !result.isCompletedExceptionally();
    }

    @Override
    public CompletionStage<Void> send(ByteBuf frame) {
      frame.release();
      return CompletableFuture.completedFuture(null);
    }

    @Override
    public void setListener(TransportListener listener) {}
  }

  private static final class PendingSession implements Session {
    private final Events events;
    private CompletableFuture<Void> startResult = new CompletableFuture<>();
    private int initializations;
    private int starts;
    private Runnable afterInitialize = () -> {};

    private PendingSession(Events events) {
      this.events = events;
    }

    @Override
    public void onConnected() {
      initializations++;
      afterInitialize.run();
    }

    @Override
    public CompletionStage<Void> startDataTransfer() {
      starts++;
      return startResult;
    }

    @Override
    public CompletionStage<Void> stopDataTransfer() {
      return CompletableFuture.completedFuture(null);
    }

    @Override
    public boolean isDataTransferStarted() {
      return startResult.isDone() && !startResult.isCompletedExceptionally();
    }

    @Override
    public void sendAsdu(Asdu asdu) {}

    @Override
    public CompletionStage<Void> sendAsduAsync(Asdu asdu) {
      sendAsdu(asdu);
      return CompletableFuture.completedFuture(null);
    }

    @Override
    public boolean awaitSendCapacity(long timeoutMillis) {
      return true;
    }

    @Override
    public int pendingSendCount() {
      return 0;
    }

    @Override
    public void close() {}
  }
}
