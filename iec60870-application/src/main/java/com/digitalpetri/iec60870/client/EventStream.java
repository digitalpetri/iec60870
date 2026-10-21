package com.digitalpetri.iec60870.client;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.Flow;
import java.util.concurrent.SubmissionPublisher;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A {@link Flow.Publisher} that never drops an event for a slow subscriber and asks its producer to
 * pause inbound data while the slowest subscriber lags.
 *
 * <p>Each subscriber's backlog is the number of events published since it subscribed that its
 * {@code onNext} has not yet returned from. The producer calls {@link #afterInbound()} after
 * publishing the events for one inbound unit; once the largest backlog reaches the limit, the
 * stream invokes the pause control with {@code true}. It invokes it with {@code false} from a
 * subscriber's callback once the largest backlog falls to half the limit.
 *
 * <p>The buffer behind each subscriber grows on demand up to a hard capacity well above the limit.
 * {@link #hasRoomForData()} reports whether the producer may still publish data events; the top
 * {@link #LIFECYCLE_RESERVE} slots stay free for lifecycle events, so those fit even when a
 * subscriber has stopped consuming. A producer that finds no room must stop publishing data rather
 * than continue with a gap.
 *
 * <p>A subscriber that blocks inside {@code onNext} until inbound data arrives would otherwise hold
 * the pause that prevents that data from being read. The producer wraps such waits in {@link
 * #excludeCurrentSubscriber()}, which leaves the calling subscriber out of the pause decision for
 * the duration. Its events still accumulate, up to the hard capacity.
 *
 * <p>Thread-safe. Events are delivered serially per subscriber on the supplied executor.
 *
 * @param <T> the event type.
 */
final class EventStream<T> implements Flow.Publisher<T> {

  private static final Logger LOGGER = LoggerFactory.getLogger(EventStream.class);

  /** Slots above the data limit kept free for lifecycle events. */
  static final int LIFECYCLE_RESERVE = 1024;

  /** Headroom between the pause limit and the hard capacity, beyond twice the limit. */
  private static final int OVERFLOW_HEADROOM = 8192;

  /** The largest backlog limit; keeps the hard capacity within the publisher's maximum. */
  static final int MAX_BACKLOG_LIMIT = 1 << 28;

  private final SubmissionPublisher<T> publisher;
  private final int limit;
  private final int resumeAt;
  private final int dataCapacity;
  private final Consumer<Boolean> pauseControl;

  private final List<TrackedSubscriber> subscribers = new CopyOnWriteArrayList<>();

  /** Serializes publication with subscription so each subscriber's start count is exact. */
  private final Object publishLock = new Object();

  /** The number of events published; written under {@link #publishLock}. */
  private volatile long published;

  /** The subscriber whose {@code onNext} is running on the current thread, if any. */
  private final ThreadLocal<@Nullable TrackedSubscriber> delivering = new ThreadLocal<>();

  /** Serializes pause and resume transitions and the calls to the pause control. */
  private final Object flowLock = new Object();

  /** Whether inbound data is paused; written under {@link #flowLock}. */
  private volatile boolean paused;

  /**
   * Creates a stream.
   *
   * @param executor the executor that delivers events to subscribers.
   * @param limit the backlog at which inbound data is paused; resumes at half of it.
   * @param pauseControl invoked with {@code true} to pause and {@code false} to resume inbound
   *     data; must not block and may run on a producer or subscriber thread.
   */
  EventStream(Executor executor, int limit, Consumer<Boolean> pauseControl) {
    if (limit < 1 || limit > MAX_BACKLOG_LIMIT) {
      throw new IllegalArgumentException("limit must be in 1.." + MAX_BACKLOG_LIMIT + ": " + limit);
    }
    this.limit = limit;
    this.resumeAt = limit / 2;
    this.pauseControl = Objects.requireNonNull(pauseControl, "pauseControl");
    int capacity = hardCapacity(limit);
    this.dataCapacity = capacity - LIFECYCLE_RESERVE;
    this.publisher = new SubmissionPublisher<>(executor, capacity);
  }

  /**
   * Returns the per-subscriber buffer capacity for a backlog limit.
   *
   * @param limit the backlog limit.
   * @return the smallest power of two that holds twice the limit plus the overflow headroom.
   */
  static int hardCapacity(int limit) {
    int required = 2 * limit + OVERFLOW_HEADROOM;
    return Integer.highestOneBit(required - 1) << 1;
  }

  @Override
  public void subscribe(Flow.Subscriber<? super T> subscriber) {
    Objects.requireNonNull(subscriber, "subscriber");
    synchronized (publishLock) {
      var tracked = new TrackedSubscriber(subscriber, published);
      // Wrappers are equal when their delegates are. The publisher then rejects a duplicate itself
      // by failing the existing subscription through its serial delivery, so only a new
      // subscriber is tracked.
      if (!subscribers.contains(tracked)) {
        subscribers.add(tracked);
      }
      publisher.subscribe(tracked);
    }
  }

  /**
   * Publishes an event to every current subscriber.
   *
   * <p>An event is dropped only if a subscriber's buffer is completely full, which the {@link
   * #hasRoomForData()} contract and the lifecycle reserve prevent in practice; a drop is logged.
   *
   * @param event the event to publish.
   */
  void publish(T event) {
    synchronized (publishLock) {
      if (publisher.isClosed()) {
        return;
      }
      // Count before offering so a concurrent backlog read can only overestimate.
      published++;
      try {
        publisher.offer(event, this::onDrop);
      } catch (RuntimeException e) {
        // The executor rejected a delivery task; the publisher has closed that subscription.
        LOGGER.warn("event delivery failed: {}", e.toString());
      }
    }
  }

  private boolean onDrop(Flow.Subscriber<? super T> subscriber, T event) {
    LOGGER.warn("dropped an event for a subscriber whose buffer is full: {}", event);
    if (subscriber instanceof EventStream<?>.TrackedSubscriber tracked) {
      tracked.consumed.incrementAndGet();
    }
    return false;
  }

  /**
   * Reports whether every subscriber has room for another inbound unit's data events.
   *
   * @return {@code false} if the slowest subscriber's backlog has reached the data capacity.
   */
  boolean hasRoomForData() {
    return maxBacklog() < dataCapacity;
  }

  /**
   * Pauses inbound data if the slowest subscriber's backlog has reached the limit.
   *
   * <p>Call after publishing the events for one inbound unit.
   */
  void afterInbound() {
    if (paused || maxBacklog(true) < limit) {
      return;
    }
    synchronized (flowLock) {
      if (!paused) {
        paused = true;
        pauseControl.accept(true);
      }
    }
    // A subscriber may have drained between the check and the pause without seeing it.
    maybeResume();
  }

  /**
   * Forgets the pause state for a new connection, without invoking the pause control.
   *
   * <p>A new connection starts unpaused; the next {@link #afterInbound()} pauses it again if the
   * backlog is still above the limit.
   */
  void resetPause() {
    synchronized (flowLock) {
      paused = false;
    }
  }

  /**
   * Returns the largest backlog among the current subscribers.
   *
   * @return the slowest subscriber's backlog, or {@code 0} with no subscribers.
   */
  int maxBacklog() {
    return maxBacklog(false);
  }

  /**
   * Returns the largest backlog, optionally leaving out subscribers that are excluded from the
   * pause decision.
   */
  private int maxBacklog(boolean pauseDecision) {
    long current = published;
    long max = 0;
    for (TrackedSubscriber subscriber : subscribers) {
      if (pauseDecision && subscriber.exclusions.get() > 0) {
        continue;
      }
      max = Math.max(max, current - subscriber.start - subscriber.consumed.get());
    }
    return (int) Math.min(max, Integer.MAX_VALUE);
  }

  /**
   * Leaves the subscriber whose {@code onNext} is running on the calling thread out of the pause
   * decision until the returned handle is closed, and resumes inbound data if that subscriber was
   * the one holding it paused.
   *
   * <p>Does nothing when the calling thread is not delivering an event.
   *
   * @return a handle that ends the exclusion when closed.
   */
  Exclusion excludeCurrentSubscriber() {
    TrackedSubscriber subscriber = delivering.get();
    if (subscriber == null) {
      return () -> {};
    }
    subscriber.exclusions.incrementAndGet();
    maybeResume();
    return () -> subscriber.exclusions.decrementAndGet();
  }

  /** Ends an exclusion from the pause decision. */
  @FunctionalInterface
  interface Exclusion extends AutoCloseable {
    @Override
    void close();
  }

  /**
   * Reports whether inbound data is currently paused.
   *
   * @return {@code true} while paused.
   */
  boolean isPaused() {
    return paused;
  }

  /** Completes every subscriber once it has consumed the events already published. */
  void close() {
    synchronized (publishLock) {
      try {
        publisher.close();
      } catch (RuntimeException e) {
        // The executor rejected a completion task; that subscriber is never completed.
        LOGGER.debug("event stream completion failed: {}", e.toString());
      }
    }
  }

  private void maybeResume() {
    if (!paused) {
      return;
    }
    synchronized (flowLock) {
      if (paused && maxBacklog(true) <= resumeAt) {
        paused = false;
        pauseControl.accept(false);
      }
    }
  }

  private void remove(TrackedSubscriber subscriber) {
    if (subscribers.remove(subscriber)) {
      maybeResume();
    }
  }

  /** Wraps a subscriber to count the events it has consumed. */
  private final class TrackedSubscriber implements Flow.Subscriber<T> {

    private final Flow.Subscriber<? super T> delegate;

    /** The number of events published before this subscriber subscribed. */
    private final long start;

    private final AtomicLong consumed = new AtomicLong();

    /** Open {@link Exclusion}s; the subscriber is left out of the pause decision while positive. */
    private final AtomicInteger exclusions = new AtomicInteger();

    private TrackedSubscriber(Flow.Subscriber<? super T> delegate, long start) {
      this.delegate = delegate;
      this.start = start;
    }

    @Override
    public void onSubscribe(Flow.Subscription subscription) {
      delegate.onSubscribe(
          new Flow.Subscription() {
            @Override
            public void request(long n) {
              subscription.request(n);
            }

            @Override
            public void cancel() {
              subscription.cancel();
              remove(TrackedSubscriber.this);
            }
          });
    }

    @Override
    public void onNext(T item) {
      // A same-thread executor can nest deliveries; restore the outer subscriber afterward.
      TrackedSubscriber outer = delivering.get();
      delivering.set(this);
      try {
        delegate.onNext(item);
      } finally {
        delivering.set(outer);
        consumed.incrementAndGet();
        maybeResume();
      }
    }

    @Override
    public void onError(Throwable throwable) {
      remove(this);
      delegate.onError(throwable);
    }

    @Override
    public void onComplete() {
      remove(this);
      delegate.onComplete();
    }

    @Override
    public boolean equals(@Nullable Object other) {
      return other instanceof EventStream<?>.TrackedSubscriber tracked
          && delegate.equals(tracked.delegate);
    }

    @Override
    public int hashCode() {
      return delegate.hashCode();
    }
  }
}
