package com.digitalpetri.iec104.server;

/**
 * The policy applied when a started connection's outbound send window is full and a {@linkplain
 * Iec104Server#publish published} monitor ASDU cannot be transmitted immediately.
 *
 * <p>Each started connection has a bounded buffer of pending outbound monitor ASDUs (the APCI
 * {@code k} window plus the publisher's own queue). When that buffer is full, this policy decides
 * what happens to a newly published value rather than letting the buffer grow without bound.
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
