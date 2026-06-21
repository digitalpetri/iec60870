package com.digitalpetri.iec104.client;

import com.digitalpetri.iec104.address.PointAddress;
import com.digitalpetri.iec104.asdu.Asdu;
import com.digitalpetri.iec104.asdu.Cause;
import com.digitalpetri.iec104.point.PointValue;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * An event published by an {@link Iec104Client} to subscribers of {@link Iec104Client#events()}.
 *
 * <p>Events are delivered serially on the client's configured callback executor: a subscriber never
 * observes two events concurrently. The sealed hierarchy lets a subscriber dispatch on the concrete
 * event type with a {@code switch}:
 *
 * <pre>{@code
 * client.events().subscribe(subscriber);
 * // inside Flow.Subscriber#onNext(ClientEvent event):
 * switch (event) {
 *   case ClientEvent.PointUpdated u -> handle(u.address(), u.value());
 *   case ClientEvent.ConnectionClosed c -> reconnect(c.cause());
 *   default -> {}
 * }
 * }</pre>
 */
public sealed interface ClientEvent
    permits ClientEvent.ConnectionOpened,
        ClientEvent.ConnectionClosed,
        ClientEvent.DataTransferStarted,
        ClientEvent.DataTransferStopped,
        ClientEvent.PointUpdated,
        ClientEvent.AsduReceived,
        ClientEvent.NegativeConfirmation,
        ClientEvent.ProtocolWarning {

  /** Published once the underlying transport connection has been established. */
  record ConnectionOpened() implements ClientEvent {}

  /**
   * Published once when the connection is closed or lost.
   *
   * @param cause the failure that caused the connection to close, or {@code null} for an orderly
   *     close.
   */
  record ConnectionClosed(@Nullable Throwable cause) implements ClientEvent {

    /**
     * Returns the cause of the close.
     *
     * @return the failure that closed the connection, or an empty {@link Optional} for an orderly
     *     close.
     */
    public Optional<Throwable> causeOptional() {
      return Optional.ofNullable(cause);
    }
  }

  /** Published when user-data transfer has started (a {@code STARTDT} handshake completed). */
  record DataTransferStarted() implements ClientEvent {}

  /** Published when user-data transfer has stopped (a {@code STOPDT} handshake completed). */
  record DataTransferStopped() implements ClientEvent {}

  /**
   * Published once per information object carried by a received monitor ASDU.
   *
   * @param address the fully qualified address of the updated point.
   * @param value the decoded point value, including its quality.
   * @param cause the cause of transmission of the carrying ASDU.
   * @param timestamp the acquisition timestamp recovered from the object's time tag, if present.
   */
  record PointUpdated(
      PointAddress address, PointValue<?> value, Cause cause, Optional<Instant> timestamp)
      implements ClientEvent {

    /**
     * Validates the components.
     *
     * @param address the fully qualified address of the updated point.
     * @param value the decoded point value, including its quality.
     * @param cause the cause of transmission of the carrying ASDU.
     * @param timestamp the acquisition timestamp recovered from the object's time tag, if present.
     * @throws NullPointerException if {@code address}, {@code value}, {@code cause}, or {@code
     *     timestamp} is null.
     */
    public PointUpdated {
      Objects.requireNonNull(address, "address");
      Objects.requireNonNull(value, "value");
      Objects.requireNonNull(cause, "cause");
      Objects.requireNonNull(timestamp, "timestamp");
    }
  }

  /**
   * Published for every ASDU received from the peer, in addition to any {@link PointUpdated} events
   * derived from its monitor objects.
   *
   * @param asdu the received ASDU.
   */
  record AsduReceived(Asdu asdu) implements ClientEvent {

    /**
     * Validates the components.
     *
     * @param asdu the received ASDU.
     * @throws NullPointerException if {@code asdu} is null.
     */
    public AsduReceived {
      Objects.requireNonNull(asdu, "asdu");
    }
  }

  /**
   * Published when the peer sends a negative confirmation (an ASDU with the P/N bit set) that is
   * not directly correlated to a pending blocking request.
   *
   * @param asdu the confirming ASDU.
   * @param cause the cause of transmission of the confirmation.
   */
  record NegativeConfirmation(Asdu asdu, Cause cause) implements ClientEvent {

    /**
     * Validates the components.
     *
     * @param asdu the confirming ASDU.
     * @param cause the cause of transmission of the confirmation.
     * @throws NullPointerException if {@code asdu} or {@code cause} is null.
     */
    public NegativeConfirmation {
      Objects.requireNonNull(asdu, "asdu");
      Objects.requireNonNull(cause, "cause");
    }
  }

  /**
   * Published when the client encounters a recoverable protocol anomaly that does not warrant
   * closing the connection, for example an unexpected or unmatched response.
   *
   * @param message a human-readable description of the anomaly.
   */
  record ProtocolWarning(String message) implements ClientEvent {

    /**
     * Validates the components.
     *
     * @param message a human-readable description of the anomaly.
     * @throws NullPointerException if {@code message} is null.
     */
    public ProtocolWarning {
      Objects.requireNonNull(message, "message");
    }
  }
}
