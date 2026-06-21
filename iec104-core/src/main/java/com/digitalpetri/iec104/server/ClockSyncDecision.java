package com.digitalpetri.iec104.server;

import com.digitalpetri.iec104.asdu.Cause;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * A handler's decision on a received {@link ClockSyncRequest}.
 *
 * <p>{@link #accept()} confirms the clock synchronization positively; the server replies with a
 * positive activation confirmation echoing the supplied time. {@link #reject(Cause)} declines it;
 * the server replies with a negative activation confirmation carrying the given cause.
 *
 * @param accepted whether the clock synchronization was accepted.
 * @param rejectCause the cause carried by the negative confirmation when {@code accepted} is {@code
 *     false}, or {@code null} when {@code accepted} is {@code true}.
 */
public record ClockSyncDecision(boolean accepted, @Nullable Cause rejectCause) {

  /** A reusable positive decision. */
  private static final ClockSyncDecision ACCEPTED = new ClockSyncDecision(true, null);

  /**
   * Validates the components.
   *
   * @param accepted whether the clock synchronization was accepted.
   * @param rejectCause the cause carried by the negative confirmation when {@code accepted} is
   *     {@code false}, or {@code null} when {@code accepted} is {@code true}.
   * @throws IllegalArgumentException if an accepted decision carries a reject cause, or a rejected
   *     decision carries none.
   */
  public ClockSyncDecision {
    if (accepted && rejectCause != null) {
      throw new IllegalArgumentException("an accepted decision must not carry a reject cause");
    }
    if (!accepted && rejectCause == null) {
      throw new IllegalArgumentException("a rejected decision must carry a reject cause");
    }
  }

  /**
   * Returns a positive clock synchronization decision.
   *
   * @return the accept decision.
   */
  public static ClockSyncDecision accept() {
    return ACCEPTED;
  }

  /**
   * Creates a negative decision that declines the clock synchronization with the given cause.
   *
   * @param cause the cause carried by the negative activation confirmation.
   * @return the decision.
   * @throws NullPointerException if {@code cause} is null.
   */
  public static ClockSyncDecision reject(Cause cause) {
    return new ClockSyncDecision(false, Objects.requireNonNull(cause, "cause"));
  }

  /**
   * Returns the reject cause of a negative decision.
   *
   * @return the reject cause, or an empty {@link Optional} for a positive decision.
   */
  public Optional<Cause> rejectCauseOptional() {
    return Optional.ofNullable(rejectCause);
  }
}
