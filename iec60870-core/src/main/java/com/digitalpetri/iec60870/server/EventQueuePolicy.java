package com.digitalpetri.iec60870.server;

/**
 * The policy applied when a started connection's outbound send window is full and a {@linkplain
 * Iec60870Server#publish published} monitor ASDU cannot be transmitted immediately.
 *
 * <p>Each started connection holds outbound monitor ASDUs in the session's send queue, which is
 * bounded by {@link ServerConfig#maxOutboundQueue()} (the APCI {@code k} window transmits from the
 * head of that queue). When the queue reaches its bound, this policy decides what happens to a
 * newly published value rather than letting the queue grow without bound.
 */
public enum EventQueuePolicy {

  /**
   * Discard the oldest queued value to make room for the newly published one. Favors freshness: a
   * slow consumer always sees the most recent values, at the cost of losing some intermediate ones.
   */
  DROP_OLDEST,

  /**
   * Discard the newly published value, keeping the already queued ones. Favors completeness of the
   * already-accepted history, at the cost of dropping the latest update.
   */
  DROP_NEWEST,

  /**
   * Block the publishing thread until the connection's buffer drains enough to accept the value.
   * Applies backpressure to the publisher; never drops a value.
   */
  BLOCK;

  /** The default policy used when a {@link ServerConfig} does not specify one. */
  public static final EventQueuePolicy DEFAULT = DROP_OLDEST;
}
