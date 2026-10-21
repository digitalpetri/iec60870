package com.digitalpetri.iec60870.test.integration;

import static java.util.Objects.requireNonNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.digitalpetri.iec60870.AsduDecodeException;
import com.digitalpetri.iec60870.ProtocolProfile;
import com.digitalpetri.iec60870.ProtocolTimeoutException;
import com.digitalpetri.iec60870.client.ClientConfig;
import com.digitalpetri.iec60870.client.ClientEvent;
import com.digitalpetri.iec60870.client.ConnectionState;
import com.digitalpetri.iec60870.client.DefaultIec60870Client;
import com.digitalpetri.iec60870.client.ReconnectPolicy;
import com.digitalpetri.iec60870.cs104.ApciSettings;
import com.digitalpetri.iec60870.cs104.Cs104Binding;
import com.digitalpetri.iec60870.test.common.ManualScheduler;
import com.digitalpetri.iec60870.transport.ClientTransport;
import com.digitalpetri.iec60870.transport.TransportListener;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * An application that reconnects synchronously from {@link ClientEvent.ConnectionClosed}, on a
 * direct callback executor over a transport whose lifecycle stages complete at once, gets a
 * replacement that the old connection's cleanup cannot abort or end, whichever way the old
 * connection was lost while its STARTDT was pending.
 *
 * <p>Each case wires a real {@link Cs104Binding} and client facade, so the order in which the
 * binding and the session report a loss and complete their pending stages is what is under test.
 */
class SynchronousReconnectIntegrationTest {

  @Test
  void framingError() {
    var cause =
        lose(
            harness -> {
              ByteBuf garbage = Unpooled.wrappedBuffer(new byte[] {0x00});
              try {
                requireNonNull(harness.transport.listener).onFrame(garbage);
              } finally {
                garbage.release();
              }
              return null;
            });
    assertSame(AsduDecodeException.class, cause.getClass());
  }

  @Test
  void transportLoss() {
    var reset = new IOException("connection reset");
    var cause =
        lose(
            harness -> {
              requireNonNull(harness.transport.listener).onConnectionLost(reset);
              return null;
            });
    assertSame(reset, cause);
  }

  @Test
  void failedWrite() {
    var failure = new IOException("write failed");
    // STARTDT act is the first write; it fails while its confirmation is still pending.
    var cause = lose(harness -> harness.transport.failNextSend = failure, harness -> null);
    assertSame(failure, cause);
  }

  @Test
  void startDtTimeout() {
    var cause =
        lose(
            harness -> {
              harness.scheduler.advance(
                  ApciSettings.defaults().t1().toMillis(), TimeUnit.MILLISECONDS);
              return null;
            });
    assertSame(ProtocolTimeoutException.class, cause.getClass());
  }

  /**
   * Connects with STARTDT left pending, loses the connection through {@code loss}, and checks that
   * the replacement opened from {@code ConnectionClosed} survives.
   *
   * @return the cause reported for the lost connection.
   */
  private static Throwable lose(Function<Harness, @Nullable Void> loss) {
    return lose(harness -> {}, loss);
  }

  private static Throwable lose(
      Consumer<Harness> beforeConnect, Function<Harness, @Nullable Void> loss) {
    var harness = new Harness();
    try {
      beforeConnect.accept(harness);
      CompletionStage<Void> first = harness.client.connectAsync();

      loss.apply(harness);

      var failure =
          assertThrows(CompletionException.class, () -> first.toCompletableFuture().join());
      ClientEvent.ConnectionClosed closed =
          harness.events.stream()
              .filter(ClientEvent.ConnectionClosed.class::isInstance)
              .map(ClientEvent.ConnectionClosed.class::cast)
              .findFirst()
              .orElseThrow();
      Throwable cause = requireNonNull(closed.cause());
      Throwable attemptFailure = requireNonNull(failure.getCause());
      // A cause that is not an Iec60870Exception reaches the caller wrapped.
      assertTrue(
          attemptFailure == cause || attemptFailure.getCause() == cause,
          "the attempt fails with the reported cause: " + attemptFailure);

      CompletionStage<Void> second = requireNonNull(harness.replacement.get());
      assertEquals(2, harness.transport.connects, "the subscriber's replacement dialed");
      assertEquals(
          "connect",
          harness.transport.calls.get(harness.transport.calls.size() - 1),
          "nothing closed the replacement after it dialed: " + harness.transport.calls);
      assertFalse(second.toCompletableFuture().isDone(), "the replacement awaits STARTDT con");
      assertEquals(ConnectionState.CONNECTING, harness.client.connectionState());
      assertEquals(
          1,
          harness.events.stream().filter(ClientEvent.ConnectionClosed.class::isInstance).count(),
          "the replacement was not ended by a late report");
      return cause;
    } finally {
      harness.client.close();
    }
  }

  private static final class Harness {

    private final ManualScheduler scheduler = new ManualScheduler();
    private final SynchronousTransport transport = new SynchronousTransport();
    private final List<ClientEvent> events = new CopyOnWriteArrayList<>();
    private final AtomicReference<@Nullable CompletionStage<Void>> replacement =
        new AtomicReference<>();
    private final DefaultIec60870Client client;

    private Harness() {
      client =
          new DefaultIec60870Client(
              transport,
              ClientConfig.builder()
                  .callbackExecutor(Runnable::run)
                  .reconnectPolicy(ReconnectPolicy.disabled())
                  .build(),
              (events, scheduler) ->
                  new Cs104Binding(ApciSettings.defaults(), ProtocolProfile.iec104Default())
                      .bindClient(transport, events, scheduler),
              scheduler);
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
                  if (item instanceof ClientEvent.ConnectionClosed && replacement.get() == null) {
                    replacement.set(client.connectAsync());
                  }
                }

                @Override
                public void onError(Throwable throwable) {}

                @Override
                public void onComplete() {}
              });
    }
  }

  /** Completes every lifecycle stage at once and records the order of the calls. */
  private static final class SynchronousTransport implements ClientTransport {

    private final List<String> calls = new CopyOnWriteArrayList<>();
    private volatile @Nullable TransportListener listener;
    private volatile @Nullable Throwable failNextSend;
    private int connects;

    @Override
    public CompletionStage<Void> connect() {
      connects++;
      calls.add("connect");
      return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletionStage<Void> disconnect() {
      calls.add("disconnect");
      return CompletableFuture.completedFuture(null);
    }

    @Override
    public void closeConnection() {
      calls.add("closeConnection");
    }

    @Override
    public boolean isConnected() {
      return true;
    }

    @Override
    public CompletionStage<Void> send(ByteBuf frame) {
      frame.release();
      Throwable failure = failNextSend;
      if (failure != null) {
        failNextSend = null;
        return CompletableFuture.failedFuture(failure);
      }
      return CompletableFuture.completedFuture(null);
    }

    @Override
    public void setListener(TransportListener listener) {
      this.listener = listener;
    }
  }
}
