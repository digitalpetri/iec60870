package com.digitalpetri.iec104.server;

import com.digitalpetri.iec104.asdu.Cause;
import com.digitalpetri.iec104.asdu.InformationObject;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * A handler's answer to a {@link ReadRequest}.
 *
 * <p>A positive response (built with {@link #of(InformationObject)}) reports the read point's
 * current value as a single monitor ASDU with the {@link Cause#REQUEST} cause. A negative response
 * (built with {@link #reject(Cause)}) declines the read, for example because the addressed point is
 * unknown; the server mirrors the read command back with the P/N bit set and the given cause.
 *
 * <p>Build the standard positive response from the station image with {@link
 * ServerContext#defaultRead(ReadRequest)}.
 *
 * @param object the monitor object reporting the point's value, or {@code null} for a negative
 *     response.
 * @param rejectCause the cause carried by a negative response, or {@code null} for a positive
 *     response.
 */
public record ReadResponse(@Nullable InformationObject object, @Nullable Cause rejectCause) {

  /**
   * Validates that exactly one of the object or reject cause is present.
   *
   * @param object the monitor object reporting the point's value, or {@code null} for a negative
   *     response.
   * @param rejectCause the cause carried by a negative response, or {@code null} for a positive
   *     response.
   * @throws IllegalArgumentException if both or neither of {@code object} and {@code rejectCause}
   *     are present.
   */
  public ReadResponse {
    if ((object == null) == (rejectCause == null)) {
      throw new IllegalArgumentException(
          "a read response must carry either an object or a reject cause, not both or neither");
    }
  }

  /**
   * Creates a positive response reporting the given monitor object.
   *
   * @param object the monitor object reporting the point's value.
   * @return the response.
   * @throws NullPointerException if {@code object} is null.
   */
  public static ReadResponse of(InformationObject object) {
    return new ReadResponse(Objects.requireNonNull(object, "object"), null);
  }

  /**
   * Creates a negative response declining the read with the given cause.
   *
   * @param cause the cause carried by the negative confirmation (for example {@link
   *     Cause#UNKNOWN_INFORMATION_OBJECT_ADDRESS}).
   * @return the response.
   * @throws NullPointerException if {@code cause} is null.
   */
  public static ReadResponse reject(Cause cause) {
    return new ReadResponse(null, Objects.requireNonNull(cause, "cause"));
  }

  /**
   * Reports whether this response reports a value (rather than declining the read).
   *
   * @return {@code true} if the response carries a monitor object.
   */
  public boolean accepted() {
    return object != null;
  }

  /**
   * Returns the reported monitor object of a positive response.
   *
   * @return the monitor object, or an empty {@link Optional} for a negative response.
   */
  public Optional<InformationObject> objectOptional() {
    return Optional.ofNullable(object);
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
