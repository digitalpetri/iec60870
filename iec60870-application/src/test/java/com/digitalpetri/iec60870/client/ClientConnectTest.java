package com.digitalpetri.iec60870.client;

import static java.util.Objects.requireNonNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.digitalpetri.iec60870.ConnectionClosedException;
import com.digitalpetri.iec60870.asdu.Asdu;
import com.digitalpetri.iec60870.session.Session;
import com.digitalpetri.iec60870.transport.ClientTransport;
import com.digitalpetri.iec60870.transport.TransportListener;
import io.netty.buffer.ByteBuf;
import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicReference;
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
  void connectionLossDiscardsALateCompletionOfTheOldConnectAttempt() {
    CompletionStage<Void> oldAttempt = client.connectAsync();
    CompletableFuture<Void> oldTransportResult = transport.result;
    session().events.onConnectionLost(null);
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

  private static final class PendingTransport implements ClientTransport {
    private CompletableFuture<Void> result = new CompletableFuture<>();
    private int connectCount;

    @Override
    public CompletionStage<Void> connect() {
      connectCount++;
      return result;
    }

    @Override
    public CompletionStage<Void> disconnect() {
      return CompletableFuture.completedFuture(null);
    }

    @Override
    public void closeConnection() {}

    @Override
    public boolean isConnected() {
      return result.isDone() && !result.isCompletedExceptionally();
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
