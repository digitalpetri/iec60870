package com.digitalpetri.iec60870.server;

import com.digitalpetri.iec60870.asdu.Cause;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * A handler's decision on a received {@link ResetRequest}.
 *
 * <p>{@link #accept()} confirms the reset positively; the server replies with a positive activation
 * confirmation. {@link #reject(Cause)} declines it; the server replies with a negative activation
 * confirmation carrying the given cause. Performing the actual reset (clearing buffers,
 * reinitializing the process) is the application's responsibility within the handler.
 *
 * @param accepted whether the reset was accepted.
 * @param rejectCause the cause carried by the negative confirmation when {@code accepted} is {@code
 *     false}, or {@code null} when {@code accepted} is {@code true}.
 */
public record ResetDecision(boolean accepted, @Nullable Cause rejectCause) {

  /** A reusable positive decision. */
  private static final ResetDecision ACCEPTED = new ResetDecision(true, null);

  /**
   * Validates the decision.
   *
   * @param accepted whether the reset was accepted.
   * @param rejectCause the cause carried by the negative confirmation when {@code accepted} is
   *     {@code false}, or {@code null} when {@code accepted} is {@code true}.
   * @throws IllegalArgumentException if an accepted decision carries a reject cause, or a rejected
   *     decision carries none.
   */
  public ResetDecision {
    if (accepted && rejectCause != null) {
      throw new IllegalArgumentException("an accepted decision must not carry a reject cause");
    }
    if (!accepted && rejectCause == null) {
      throw new IllegalArgumentException("a rejected decision must carry a reject cause");
    }
  }

  /**
   * Returns a positive reset decision.
   *
   * @return the accept decision.
   */
  public static ResetDecision accept() {
    return ACCEPTED;
  }

  /**
   * Creates a negative decision that declines the reset with the given cause.
   *
   * @param cause the cause carried by the negative activation confirmation.
   * @return the decision.
   * @throws NullPointerException if {@code cause} is null.
   */
  public static ResetDecision reject(Cause cause) {
    return new ResetDecision(false, Objects.requireNonNull(cause, "cause"));
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
