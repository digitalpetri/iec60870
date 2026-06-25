package com.digitalpetri.iec60870;

/**
 * What to do when the outbound session queue is full and another ASDU is offered.
 *
 * <p>Each session holds outbound ASDUs in a bounded send queue from which the link-layer window
 * transmits. For a 104 session this is the APCI {@code k} window draining the head of the queue;
 * for a future 101 stop-and-wait session it is a window of one. When the queue reaches its
 * configured bound, this policy decides the fate of a newly offered value rather than letting the
 * queue grow without bound. {@code BLOCK} is not applied inside the session lock; the session
 * exposes its pending count and a drain signal so a publishing thread can await capacity off-lock
 * before offering more.
 */
public enum OutboundQueuePolicy {

  /**
   * Discard the oldest queued value to make room for the newly offered one. Favors freshness: a
   * slow consumer always sees the most recent values, at the cost of losing some intermediate ones.
   */
  DROP_OLDEST,

  /**
   * Discard the newly offered value, keeping the already queued ones. Favors completeness of the
   * already-accepted history, at the cost of dropping the latest update.
   */
  DROP_NEWEST,

  /**
   * Apply backpressure to the publishing thread until the session's queue drains enough to accept
   * the value; the session never drops a value on overflow.
   */
  BLOCK;

  /** The default policy used when a configuration does not specify one. */
  public static final OutboundQueuePolicy DEFAULT = DROP_OLDEST;
}
