package com.digitalpetri.iec60870.test.interop;

import com.digitalpetri.iec60870.asdu.Asdu;
import com.digitalpetri.iec60870.client.ClientEvent;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.Flow;
import java.util.function.Predicate;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Records client events from a live interop peer and lets tests await matching ASDUs or point
 * updates with bounded timeouts.
 */
final class InteropEventRecorder implements Flow.Subscriber<ClientEvent> {

  private static final Logger logger = LoggerFactory.getLogger(InteropEventRecorder.class);

  private final Object monitor = new Object();
  private final List<ClientEvent> events = new ArrayList<>();

  @Override
  public void onSubscribe(Flow.Subscription subscription) {
    subscription.request(Long.MAX_VALUE);
  }

  @Override
  public void onNext(ClientEvent event) {
    synchronized (monitor) {
      events.add(event);
      monitor.notifyAll();
    }
  }

  @Override
  public void onError(Throwable throwable) {
    logger.warn("event stream error", throwable);
  }

  @Override
  public void onComplete() {}

  /** Drops queued events so a later wait observes only fresh traffic. */
  void clear() {
    synchronized (monitor) {
      events.clear();
    }
  }

  @Nullable Asdu awaitAsdu(Predicate<Asdu> predicate, Duration timeout) {
    return await(
        event ->
            event instanceof ClientEvent.AsduReceived received && predicate.test(received.asdu())
                ? received.asdu()
                : null,
        timeout);
  }

  ClientEvent.@Nullable PointUpdated awaitPointUpdated(
      Predicate<ClientEvent.PointUpdated> predicate, Duration timeout) {
    return await(
        event ->
            event instanceof ClientEvent.PointUpdated updated && predicate.test(updated)
                ? updated
                : null,
        timeout);
  }

  private <T> @Nullable T await(EventMatcher<T> matcher, Duration timeout) {
    long deadline = System.nanoTime() + timeout.toNanos();
    synchronized (monitor) {
      while (true) {
        Iterator<ClientEvent> iterator = events.iterator();
        while (iterator.hasNext()) {
          ClientEvent event = iterator.next();
          T result = matcher.match(event);
          if (result != null) {
            iterator.remove();
            return result;
          }
        }

        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) {
          return null;
        }

        try {
          long millis = remaining / 1_000_000L;
          int nanos = (int) (remaining % 1_000_000L);
          monitor.wait(millis, nanos);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          return null;
        }
      }
    }
  }

  @FunctionalInterface
  private interface EventMatcher<T> {

    @Nullable T match(ClientEvent event);
  }
}
