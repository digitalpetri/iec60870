package com.digitalpetri.iec60870.client;

import static java.util.Objects.requireNonNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.Executor;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

class EventStreamTest {

  /** Runs delivery tasks only when the test drains it, so backlogs build deterministically. */
  private final Queue<Runnable> tasks = new ArrayDeque<>();

  private final Executor executor = tasks::add;
  private final List<Boolean> pauses = new ArrayList<>();

  private void drain() {
    Runnable task;
    while ((task = tasks.poll()) != null) {
      task.run();
    }
  }

  @Test
  void hardCapacityHoldsTwiceTheLimitPlusHeadroom() {
    assertEquals(16384, EventStream.hardCapacity(1));
    assertEquals(16384, EventStream.hardCapacity(4096));
    assertEquals(32768, EventStream.hardCapacity(4097));
    assertEquals(1 << 30, EventStream.hardCapacity(EventStream.MAX_BACKLOG_LIMIT));
  }

  @Test
  void rejectsOutOfRangeLimit() {
    assertThrows(IllegalArgumentException.class, () -> new EventStream<>(executor, 0, p -> {}));
    assertThrows(
        IllegalArgumentException.class,
        () -> new EventStream<>(executor, EventStream.MAX_BACKLOG_LIMIT + 1, p -> {}));
  }

  @Test
  void deliversEveryEventInOrderToASubscriberFarBehind() {
    var stream = new EventStream<Integer>(executor, 4096, pauses::add);
    var subscriber = new TestSubscriber(Long.MAX_VALUE);
    stream.subscribe(subscriber);

    for (int i = 0; i < 10_000; i++) {
      stream.publish(i);
    }
    assertEquals(10_000, stream.maxBacklog());
    drain();

    assertEquals(10_000, subscriber.received.size());
    for (int i = 0; i < 10_000; i++) {
      assertEquals(i, subscriber.received.get(i));
    }
    assertEquals(0, stream.maxBacklog());
  }

  @Test
  void pausesAtLimitAndResumesAtHalf() {
    var stream = new EventStream<Integer>(executor, 10, pauses::add);
    var subscriber = new TestSubscriber(0);
    stream.subscribe(subscriber);
    drain();

    publish(stream, 9);
    stream.afterInbound();
    assertEquals(List.of(), pauses);

    publish(stream, 1);
    stream.afterInbound();
    stream.afterInbound();
    assertEquals(List.of(true), pauses);
    assertTrue(stream.isPaused());

    subscriber.request(4);
    drain();
    assertEquals(6, stream.maxBacklog());
    assertEquals(List.of(true), pauses);

    subscriber.request(1);
    drain();
    assertEquals(List.of(true, false), pauses);
    assertFalse(stream.isPaused());
  }

  @Test
  void backlogCountsOnlyEventsPublishedAfterSubscribing() {
    var stream = new EventStream<Integer>(executor, 10, pauses::add);
    publish(stream, 100);

    stream.subscribe(new TestSubscriber(0));
    drain();
    assertEquals(0, stream.maxBacklog());

    publish(stream, 3);
    assertEquals(3, stream.maxBacklog());
  }

  @Test
  void reportsNoRoomForDataAtCapacityButStillAcceptsLifecycleEvents() {
    var stream = new EventStream<Integer>(executor, 1, pauses::add);
    var subscriber = new TestSubscriber(Long.MAX_VALUE);
    stream.subscribe(subscriber);

    int dataCapacity = EventStream.hardCapacity(1) - EventStream.LIFECYCLE_RESERVE;
    publish(stream, dataCapacity - 1);
    assertTrue(stream.hasRoomForData());
    publish(stream, 1);
    assertFalse(stream.hasRoomForData());

    publish(stream, EventStream.LIFECYCLE_RESERVE);
    drain();
    assertEquals(EventStream.hardCapacity(1), subscriber.received.size());
  }

  @Test
  void cancellingTheSlowestSubscriberResumes() {
    var stream = new EventStream<Integer>(executor, 10, pauses::add);
    var subscriber = new TestSubscriber(0);
    stream.subscribe(subscriber);
    drain();
    publish(stream, 10);
    stream.afterInbound();

    subscriber.cancel();
    assertEquals(List.of(true, false), pauses);
    assertEquals(0, stream.maxBacklog());
  }

  @Test
  void subscriberThatThrowsStopsCountingTowardTheBacklog() {
    var stream = new EventStream<Integer>(executor, 10, pauses::add);
    var subscriber = new TestSubscriber(Long.MAX_VALUE);
    subscriber.failOnNext = true;
    stream.subscribe(subscriber);
    publish(stream, 10);
    stream.afterInbound();

    drain();
    assertEquals(List.of(true, false), pauses);
    assertInstanceOf(IllegalStateException.class, subscriber.error.get());
    assertEquals(0, stream.maxBacklog());
  }

  @Test
  void subscriberBlockedInAnExclusionDoesNotHoldThePause() {
    var stream = new EventStream<Integer>(executor, 10, pauses::add);
    var subscriber = new TestSubscriber(Long.MAX_VALUE);
    List<Integer> backlogWhileExcluded = new ArrayList<>();
    subscriber.onItem =
        item -> {
          if (item == -1) {
            // Inbound data keeps arriving while this subscriber waits for a response.
            try (EventStream.Exclusion ignored = stream.excludeCurrentSubscriber()) {
              publish(stream, 20);
              stream.afterInbound();
              backlogWhileExcluded.add(stream.maxBacklog());
            }
          }
        };
    stream.subscribe(subscriber);
    stream.publish(-1);

    drain();
    // The event being delivered counts until its onNext returns.
    assertEquals(List.of(21), backlogWhileExcluded, "events still accumulate for it");
    assertEquals(List.of(), pauses, "but it does not pause inbound data");
    assertEquals(21, subscriber.received.size());
  }

  @Test
  void exclusionResumesInputPausedByTheExcludedSubscriber() {
    var stream = new EventStream<Integer>(executor, 10, pauses::add);
    var subscriber = new TestSubscriber(Long.MAX_VALUE);
    subscriber.onItem =
        item -> {
          if (item == -1) {
            publish(stream, 10);
            stream.afterInbound();
            assertEquals(List.of(true), pauses);
            try (EventStream.Exclusion ignored = stream.excludeCurrentSubscriber()) {
              assertEquals(List.of(true, false), pauses);
            }
          }
        };
    stream.subscribe(subscriber);
    stream.publish(-1);

    drain();
    assertEquals(11, subscriber.received.size());
  }

  @Test
  void exclusionOutsideDeliveryDoesNothing() {
    var stream = new EventStream<Integer>(executor, 10, pauses::add);
    stream.subscribe(new TestSubscriber(0));
    drain();
    publish(stream, 10);

    try (EventStream.Exclusion ignored = stream.excludeCurrentSubscriber()) {
      stream.afterInbound();
    }
    assertEquals(List.of(true), pauses);
  }

  @Test
  void duplicateSubscribeFailsTheExistingSubscriptionThroughItsDelivery() {
    var stream = new EventStream<Integer>(executor, 10, pauses::add);
    var subscriber = new TestSubscriber(Long.MAX_VALUE);
    stream.subscribe(subscriber);
    drain();
    publish(stream, 3);

    stream.subscribe(subscriber);
    // The error is delivered by the publisher's delivery task, never inline from subscribe().
    assertNull(subscriber.error.get());

    drain();
    assertInstanceOf(IllegalStateException.class, subscriber.error.get());
    publish(stream, 3);
    drain();
    assertEquals(List.of(), subscriber.received, "the failed subscription receives nothing more");
    assertEquals(0, stream.maxBacklog(), "neither subscription is still tracked");
  }

  @Test
  void lateCancelOfAnOldSubscriptionKeepsTheNewOneTracked() {
    var stream = new EventStream<Integer>(executor, 10, pauses::add);
    var subscriber = new TestSubscriber(0);
    stream.subscribe(subscriber);
    drain();
    Flow.Subscription old = requireNonNull(subscriber.subscription);
    old.cancel();

    stream.subscribe(subscriber);
    drain();
    old.cancel();

    publish(stream, 10);
    assertEquals(10, stream.maxBacklog(), "the new subscription still counts");
    stream.afterInbound();
    assertEquals(List.of(true), pauses);
  }

  @Test
  void closeCompletesAfterBufferedEvents() {
    var stream = new EventStream<Integer>(executor, 10, pauses::add);
    var subscriber = new TestSubscriber(Long.MAX_VALUE);
    stream.subscribe(subscriber);
    publish(stream, 3);
    stream.close();
    stream.publish(99);

    drain();
    assertEquals(List.of(0, 1, 2), subscriber.received);
    assertTrue(subscriber.completed);
  }

  private static void publish(EventStream<Integer> stream, int count) {
    for (int i = 0; i < count; i++) {
      stream.publish(i);
    }
  }

  private static final class TestSubscriber implements Flow.Subscriber<Integer> {

    private final long initialDemand;
    private final List<Integer> received = new ArrayList<>();
    private final AtomicReference<@Nullable Throwable> error = new AtomicReference<>();
    private Flow.@Nullable Subscription subscription;
    private boolean failOnNext;
    private Consumer<Integer> onItem = item -> {};
    private boolean completed;

    private TestSubscriber(long initialDemand) {
      this.initialDemand = initialDemand;
    }

    private void request(long n) {
      requireNonNull(subscription).request(n);
    }

    private void cancel() {
      requireNonNull(subscription).cancel();
    }

    @Override
    public void onSubscribe(Flow.Subscription subscription) {
      this.subscription = subscription;
      if (initialDemand > 0) {
        subscription.request(initialDemand);
      }
    }

    @Override
    public void onNext(Integer item) {
      if (failOnNext) {
        throw new IllegalStateException("subscriber failure");
      }
      received.add(item);
      onItem.accept(item);
    }

    @Override
    public void onError(Throwable throwable) {
      error.set(throwable);
    }

    @Override
    public void onComplete() {
      completed = true;
    }
  }
}
