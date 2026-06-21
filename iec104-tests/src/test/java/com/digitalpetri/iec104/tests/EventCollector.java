package com.digitalpetri.iec104.tests;

import com.digitalpetri.iec104.client.ClientEvent;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Flow;
import java.util.function.Predicate;

/**
 * A {@link Flow.Subscriber} that records every {@link ClientEvent} a client publishes, so a test
 * can assert that a particular event (for example a {@link ClientEvent.PointUpdated}) eventually
 * arrived.
 *
 * <p>Subscribe an instance to {@link com.digitalpetri.iec104.client.Iec104Client#events()} before
 * connecting to avoid missing early lifecycle events. Events are recorded in arrival order; the
 * collector requests unbounded demand so it never applies back-pressure to the client's callback
 * executor.
 */
final class EventCollector implements Flow.Subscriber<ClientEvent> {

  private final ConcurrentLinkedQueue<ClientEvent> events = new ConcurrentLinkedQueue<>();

  @Override
  public void onSubscribe(Flow.Subscription subscription) {
    subscription.request(Long.MAX_VALUE);
  }

  @Override
  public void onNext(ClientEvent item) {
    events.add(item);
  }

  @Override
  public void onError(Throwable throwable) {
    // The publisher closes on client shutdown; nothing to record beyond the events already seen.
  }

  @Override
  public void onComplete() {
    // No action: the test inspects the events collected up to completion.
  }

  /**
   * Returns a snapshot of every event recorded so far, in arrival order.
   *
   * @return the recorded events.
   */
  List<ClientEvent> events() {
    return List.copyOf(events);
  }

  /**
   * Reports whether any recorded event of the given type matches the predicate.
   *
   * @param type the concrete event type to look for.
   * @param predicate the additional condition the matching event must satisfy.
   * @param <T> the event subtype.
   * @return {@code true} if at least one recorded event matches.
   */
  <T extends ClientEvent> boolean hasMatch(Class<T> type, Predicate<T> predicate) {
    for (ClientEvent event : events) {
      if (type.isInstance(event) && predicate.test(type.cast(event))) {
        return true;
      }
    }
    return false;
  }

  /**
   * Reports whether any recorded event is of the given type.
   *
   * @param type the concrete event type to look for.
   * @return {@code true} if at least one recorded event is an instance of {@code type}.
   */
  boolean hasAny(Class<? extends ClientEvent> type) {
    return hasMatch(type, e -> true);
  }
}
