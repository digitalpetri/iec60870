package com.digitalpetri.iec60870.client;

import static java.util.Objects.requireNonNull;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.digitalpetri.iec60870.address.OriginatorAddress;
import com.digitalpetri.iec60870.asdu.Asdu;
import com.digitalpetri.iec60870.asdu.Cause;
import com.digitalpetri.iec60870.asdu.InformationObject;
import com.digitalpetri.iec60870.fakes.FakeClientTransport;
import com.digitalpetri.iec60870.fakes.FakeSession;
import com.digitalpetri.iec60870.test.common.ManualScheduler;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.UnaryOperator;
import org.jspecify.annotations.Nullable;

/**
 * A client over a {@link FakeSession} and a {@link ManualScheduler}, with a 50 ms command timeout,
 * originator address 27, and a recorded event stream.
 */
final class CommandHarness implements AutoCloseable {

  static final Duration COMMAND_TIMEOUT = Duration.ofMillis(50);

  final ManualScheduler clock = new ManualScheduler();
  final List<ClientEvent> events = new CopyOnWriteArrayList<>();
  final DefaultIec60870Client client;
  private final AtomicReference<@Nullable FakeSession> session = new AtomicReference<>();

  CommandHarness() {
    this(Runnable::run, UnaryOperator.identity());
  }

  CommandHarness(UnaryOperator<ClientConfig.Builder> settings) {
    this(Runnable::run, settings);
  }

  CommandHarness(Executor callbacks, UnaryOperator<ClientConfig.Builder> settings) {
    ClientConfig config =
        settings
            .apply(
                ClientConfig.builder()
                    .callbackExecutor(callbacks)
                    .originatorAddress(OriginatorAddress.of(27))
                    .commandTimeout(COMMAND_TIMEOUT))
            .build();
    client =
        new DefaultIec60870Client(
            new FakeClientTransport(),
            config,
            (events, scheduler) -> {
              FakeSession fake = FakeSession.client(events);
              session.set(fake);
              return fake;
            },
            clock);
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
    client.connect();
  }

  FakeSession session() {
    return requireNonNull(session.get());
  }

  List<Asdu> sent() {
    return session().sentAsdus();
  }

  Asdu sent(int index) {
    return sent().get(index);
  }

  Asdu lastSent() {
    List<Asdu> sent = sent();
    return sent.get(sent.size() - 1);
  }

  void deliver(Asdu asdu) {
    session().deliverAsdu(asdu);
  }

  /** Answers the last sent ASDU with the given cause and P/N bit, echoing its objects. */
  void reply(Cause cause, boolean negative) {
    deliver(reply(lastSent(), cause, negative));
  }

  CompletableFuture<CommandResult> send(Command command, CommandMode mode) {
    return client.commands().sendAsync(command, mode).toCompletableFuture();
  }

  CompletableFuture<CommandResult> send(Command command, CommandMode mode, CommandOptions options) {
    return client.commands().sendAsync(command, mode, options).toCompletableFuture();
  }

  CompletableFuture<SelectedCommand> select(Command command) {
    return client.commands().selectAsync(command).toCompletableFuture();
  }

  CompletableFuture<SelectedCommand> select(Command command, CommandOptions options) {
    return client.commands().selectAsync(command, options).toCompletableFuture();
  }

  long negativeConfirmationEvents() {
    return events.stream().filter(e -> e instanceof ClientEvent.NegativeConfirmation).count();
  }

  @Override
  public void close() {
    client.close();
  }

  static Asdu reply(Asdu request, Cause cause, boolean negative) {
    return reply(request, cause, negative, request.objects());
  }

  static Asdu reply(Asdu request, Cause cause, boolean negative, List<InformationObject> objects) {
    return new Asdu(
        request.type(),
        request.sequence(),
        cause,
        negative,
        request.test(),
        request.originatorAddress(),
        request.commonAddress(),
        objects);
  }

  static <T> T join(CompletionStage<T> stage) {
    return stage.toCompletableFuture().join();
  }

  static void assertFailure(CompletionStage<?> stage, Class<? extends Throwable> type) {
    CompletableFuture<?> future = stage.toCompletableFuture();
    assertTrue(future.isDone(), "operation must have completed");
    CompletionException error = assertThrows(CompletionException.class, future::join);
    assertInstanceOf(type, error.getCause());
  }

  /** Runs callback tasks only when drained, to model a delayed application executor. */
  static final class QueuedExecutor implements Executor {
    private final Deque<Runnable> tasks = new ArrayDeque<>();

    @Override
    public synchronized void execute(Runnable task) {
      tasks.addLast(task);
    }

    void drain() {
      while (true) {
        Runnable next;
        synchronized (this) {
          next = tasks.pollFirst();
        }
        if (next == null) {
          return;
        }
        next.run();
      }
    }
  }
}
