package com.digitalpetri.iec60870.apci;

/**
 * The action an {@link ApciSession} takes when its bounded outbound send queue is full and another
 * application ASDU is offered.
 *
 * <p>This is the transport-agnostic, core-level counterpart of the server-facing event-queue
 * policy: it decides what happens to a queued I-frame that cannot yet be transmitted because the
 * {@code k} window is closed (or, for a server, data transfer has not started) and the queue has
 * reached its configured bound. {@code BLOCK} is not applied inside the session lock; the session
 * exposes its pending count and a drain signal so a publishing thread can await capacity off-lock
 * before offering more.
 */
public enum OutboundQueuePolicy {

  /** Discard the oldest queued ASDU to make room for the newly offered one. */
  DROP_OLDEST,

  /** Discard the newly offered ASDU, keeping the already queued ones. */
  DROP_NEWEST,

  /** Apply backpressure to the publishing thread; the session never drops a value on overflow. */
  BLOCK
}
