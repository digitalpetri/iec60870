package com.digitalpetri.iec60870;

/**
 * Reports that a facade closed its connection because an event subscriber fell too far behind.
 *
 * <p>A facade that delivers events losslessly holds every event until each subscriber consumes it.
 * When the session cannot slow the peer down far enough and a subscriber's backlog reaches the
 * facade's hard limit, the facade closes the connection with this cause instead of dropping events.
 * Events published before the close are still delivered. The application should treat the close
 * like any other connection loss: reconnect and re-interrogate to refresh its image. The facade
 * also fails lifecycle operations such as connecting with this exception until the subscriber has
 * caught up, so the lifecycle events those operations publish are never dropped.
 */
public class EventBacklogExceededException extends Iec60870Exception {

  /**
   * Creates an exception with the given detail message.
   *
   * @param message the detail message describing the backlog.
   */
  public EventBacklogExceededException(String message) {
    super(message);
  }
}
