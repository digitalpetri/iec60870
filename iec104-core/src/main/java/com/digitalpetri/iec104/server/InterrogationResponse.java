package com.digitalpetri.iec104.server;

import com.digitalpetri.iec104.asdu.Cause;
import com.digitalpetri.iec104.asdu.InformationObject;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * A handler's answer to an {@link InterrogationRequest}.
 *
 * <p>A positive response (built with {@link #of(List)}) accepts the interrogation: the server sends
 * an activation confirmation, then one monitor ASDU per reported point with an
 * interrogation-response cause, then an activation termination. A negative response (built with
 * {@link #reject(Cause)}) declines the interrogation: the server sends a negative activation
 * confirmation carrying the given cause and reports no points.
 *
 * <p>Build the standard positive response from the station image with {@link
 * ServerContext#defaultInterrogation(InterrogationRequest)}.
 *
 * @param accepted whether the interrogation was accepted.
 * @param objects the monitor objects to report (empty for a negative response).
 * @param rejectCause the cause carried by the negative activation confirmation, or {@code null}
 *     when {@code accepted} is {@code true}.
 */
public record InterrogationResponse(
    boolean accepted, List<InformationObject> objects, @Nullable Cause rejectCause) {

  /**
   * Validates the response and defensively copies the objects list.
   *
   * @param accepted whether the interrogation was accepted.
   * @param objects the monitor objects to report (empty for a negative response).
   * @param rejectCause the cause carried by the negative activation confirmation, or {@code null}
   *     when {@code accepted} is {@code true}.
   * @throws NullPointerException if {@code objects} is null.
   * @throws IllegalArgumentException if {@code accepted} is {@code false} but no reject cause is
   *     given, or {@code accepted} is {@code true} but a reject cause is given.
   */
  public InterrogationResponse {
    Objects.requireNonNull(objects, "objects");
    if (accepted && rejectCause != null) {
      throw new IllegalArgumentException("an accepted response must not carry a reject cause");
    }
    if (!accepted && rejectCause == null) {
      throw new IllegalArgumentException("a rejected response must carry a reject cause");
    }
    objects = List.copyOf(objects);
  }

  /**
   * Creates a positive response that reports the given monitor objects.
   *
   * @param objects the monitor objects to report.
   * @return the response.
   * @throws NullPointerException if {@code objects} is null.
   */
  public static InterrogationResponse of(List<InformationObject> objects) {
    return new InterrogationResponse(true, objects, null);
  }

  /**
   * Creates a negative response that declines the interrogation with the given cause.
   *
   * @param cause the cause carried by the negative activation confirmation (for example {@link
   *     Cause#UNKNOWN_COMMON_ADDRESS}).
   * @return the response.
   * @throws NullPointerException if {@code cause} is null.
   */
  public static InterrogationResponse reject(Cause cause) {
    return new InterrogationResponse(false, List.of(), Objects.requireNonNull(cause, "cause"));
  }

  /**
   * Returns the reject cause of a negative response.
   *
   * @return the reject cause, or an empty {@link Optional} for a positive response.
   */
  public Optional<Cause> rejectCauseOptional() {
    return Optional.ofNullable(rejectCause);
  }
}
