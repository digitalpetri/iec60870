package com.digitalpetri.iec104.server;

import com.digitalpetri.iec104.asdu.Cause;
import com.digitalpetri.iec104.point.PointValue;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * A handler's decision on a received {@link CommandRequest}.
 *
 * <p>A decision is one of three outcomes:
 *
 * <ul>
 *   <li>{@link #accept()} — confirm the command positively without changing the station image. The
 *       server replies with a positive activation confirmation and, for an executing command, a
 *       following activation termination.
 *   <li>{@link #acceptAndUpdate(PointValue)} — confirm positively and update the commanded point's
 *       value image. On an executing command the server additionally emits a monitor ASDU carrying
 *       the new value with a {@linkplain Cause#RETURN_REMOTE return-information} cause.
 *   <li>{@link #reject(Cause)} — confirm negatively with the given cause; no image update or return
 *       information is sent.
 * </ul>
 *
 * <p>An update is applied (and return information emitted) only for an {@linkplain
 * CommandMode#EXECUTE executing} command; for a {@linkplain CommandMode#SELECT selection} the
 * decision is confirmed but the image is left unchanged.
 *
 * @param accepted whether the command was confirmed positively.
 * @param updatedValue the new point value to write to the image on an accepted executing command,
 *     or {@code null} to leave the image unchanged.
 * @param rejectCause the cause carried by the negative confirmation when {@code accepted} is {@code
 *     false}, or {@code null} when {@code accepted} is {@code true}.
 */
public record CommandDecision(
    boolean accepted, @Nullable PointValue<?> updatedValue, @Nullable Cause rejectCause) {

  /**
   * Validates the components.
   *
   * @param accepted whether the command was confirmed positively.
   * @param updatedValue the new point value to write to the image on an accepted executing command,
   *     or {@code null} to leave the image unchanged.
   * @param rejectCause the cause carried by the negative confirmation when {@code accepted} is
   *     {@code false}, or {@code null} when {@code accepted} is {@code true}.
   * @throws IllegalArgumentException if an accepted decision carries a reject cause, a rejected
   *     decision carries no reject cause, or a rejected decision carries an updated value.
   */
  public CommandDecision {
    if (accepted && rejectCause != null) {
      throw new IllegalArgumentException("an accepted decision must not carry a reject cause");
    }
    if (!accepted && rejectCause == null) {
      throw new IllegalArgumentException("a rejected decision must carry a reject cause");
    }
    if (!accepted && updatedValue != null) {
      throw new IllegalArgumentException("a rejected decision must not carry an updated value");
    }
  }

  /**
   * Creates a positive decision that does not change the station image.
   *
   * @return the decision.
   */
  public static CommandDecision accept() {
    return new CommandDecision(true, null, null);
  }

  /**
   * Creates a positive decision that updates the commanded point's value image and, on an executing
   * command, emits return information.
   *
   * @param value the new point value; its runtime type must match the commanded point's type.
   * @return the decision.
   * @throws NullPointerException if {@code value} is null.
   */
  public static CommandDecision acceptAndUpdate(PointValue<?> value) {
    return new CommandDecision(true, Objects.requireNonNull(value, "value"), null);
  }

  /**
   * Creates a negative decision that declines the command with the given cause.
   *
   * @param cause the cause carried by the negative activation confirmation (for example {@link
   *     Cause#UNKNOWN_INFORMATION_OBJECT_ADDRESS}).
   * @return the decision.
   * @throws NullPointerException if {@code cause} is null.
   */
  public static CommandDecision reject(Cause cause) {
    return new CommandDecision(false, null, Objects.requireNonNull(cause, "cause"));
  }

  /**
   * Returns the updated value of an accept-and-update decision.
   *
   * @return the updated value, or an empty {@link Optional} if the image is to be left unchanged.
   */
  public Optional<PointValue<?>> updatedValueOptional() {
    return Optional.ofNullable(updatedValue);
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
