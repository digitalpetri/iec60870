package com.digitalpetri.iec60870.point;

import com.digitalpetri.iec60870.asdu.InformationObject;
import com.digitalpetri.iec60870.asdu.time.Cp24Time2a;
import com.digitalpetri.iec60870.asdu.time.Cp24TimeTagged;
import com.digitalpetri.iec60870.asdu.time.Cp56Time2a;
import com.digitalpetri.iec60870.asdu.time.Cp56TimeTagged;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * A received time tag: the raw wire fields, their flags, and the instant a {@link TimePolicy}
 * resolved from them.
 *
 * <p>Exactly one of {@link #cp56()} and {@link #cp24()} is present, matching {@link #style()}. The
 * flag accessors read the IV, SU, and RES1/GEN bits from whichever is present.
 *
 * <p>{@link #instant()} is the policy's resolution and stays present for a tag marked invalid, so
 * an application that wants the device's value can still have it. {@link #timestamp()} drops the
 * instant of an invalid tag, so a consumer that ignores flags falls back to its own receive time.
 *
 * <pre>{@code
 * case ClientEvent.PointUpdated u -> {
 *   Instant sourceTime = u.timestamp().orElse(receivedAt);
 *   boolean substituted = u.timeTag().map(TimeTag::substituted).orElse(false);
 * }
 * }</pre>
 *
 * @param style the tag format, {@link TimeTagStyle#CP24} or {@link TimeTagStyle#CP56}.
 * @param cp56 the raw CP56Time2a fields exactly as received, or {@code null} for a CP24 tag.
 * @param cp24 the raw CP24Time2a fields exactly as received, or {@code null} for a CP56 tag.
 * @param instant the instant the policy resolved, or empty if it could not be resolved.
 * @param inferred {@code true} when a CP24 tag was completed from the receive time.
 */
public record TimeTag(
    TimeTagStyle style,
    @Nullable Cp56Time2a cp56,
    @Nullable Cp24Time2a cp24,
    Optional<Instant> instant,
    boolean inferred) {

  /**
   * Validates the components.
   *
   * @param style the tag format, {@link TimeTagStyle#CP24} or {@link TimeTagStyle#CP56}.
   * @param cp56 the raw CP56Time2a fields exactly as received, or {@code null} for a CP24 tag.
   * @param cp24 the raw CP24Time2a fields exactly as received, or {@code null} for a CP56 tag.
   * @param instant the instant the policy resolved, or empty if it could not be resolved.
   * @param inferred {@code true} when a CP24 tag was completed from the receive time.
   * @throws NullPointerException if {@code style} or {@code instant} is null.
   * @throws IllegalArgumentException if {@code style} is {@link TimeTagStyle#NONE}, the present raw
   *     field does not match {@code style}, or a CP56 tag is marked {@code inferred}.
   */
  public TimeTag {
    Objects.requireNonNull(style, "style");
    Objects.requireNonNull(instant, "instant");
    switch (style) {
      case CP56 -> {
        if (cp56 == null || cp24 != null) {
          throw new IllegalArgumentException("a CP56 time tag requires cp56 and no cp24");
        }
        if (inferred) {
          throw new IllegalArgumentException("a CP56 time tag cannot be inferred");
        }
      }
      case CP24 -> {
        if (cp24 == null || cp56 != null) {
          throw new IllegalArgumentException("a CP24 time tag requires cp24 and no cp56");
        }
      }
      case NONE -> throw new IllegalArgumentException("style must be CP24 or CP56");
    }
  }

  /**
   * Resolves a CP56Time2a time tag with a policy.
   *
   * @param time the raw time tag.
   * @param policy the policy that resolves the instant.
   * @return the time tag.
   * @throws NullPointerException if {@code time} or {@code policy} is null.
   */
  public static TimeTag of(Cp56Time2a time, TimePolicy policy) {
    return new TimeTag(TimeTagStyle.CP56, time, null, policy.toInstant(time), false);
  }

  /**
   * Resolves a CP24Time2a time tag with a policy, completing it from the receive time when the
   * policy enables CP24 completion.
   *
   * @param time the raw time tag.
   * @param policy the policy that completes the instant.
   * @param receivedAt the time the carrying ASDU was received.
   * @return the time tag; its instant is empty unless the policy completes CP24 tags.
   * @throws NullPointerException if {@code time}, {@code policy}, or {@code receivedAt} is null.
   */
  public static TimeTag of(Cp24Time2a time, TimePolicy policy, Instant receivedAt) {
    Optional<Instant> completed = policy.complete(time, receivedAt);
    return new TimeTag(TimeTagStyle.CP24, null, time, completed, completed.isPresent());
  }

  /**
   * Reads and resolves the time tag of any information object that carries one.
   *
   * <p>This covers monitor objects as well as types the point model does not map, such as the
   * protection-equipment events, so raw-ASDU code can resolve their tags with the same policy.
   *
   * @param object the information object.
   * @param policy the policy that resolves the instant.
   * @param receivedAt the time the carrying ASDU was received.
   * @return the time tag, or empty if {@code object} carries no CP56Time2a or CP24Time2a tag.
   * @throws NullPointerException if any argument is null.
   */
  public static Optional<TimeTag> from(
      InformationObject object, TimePolicy policy, Instant receivedAt) {
    Objects.requireNonNull(object, "object");
    Objects.requireNonNull(policy, "policy");
    Objects.requireNonNull(receivedAt, "receivedAt");
    if (object instanceof Cp56TimeTagged tagged) {
      return Optional.of(of(tagged.time(), policy));
    }
    if (object instanceof Cp24TimeTagged tagged) {
      return Optional.of(of(tagged.time(), policy, receivedAt));
    }
    return Optional.empty();
  }

  /**
   * Returns whether the tag is marked invalid (IV=1), for example by a device whose clock is not
   * synchronized.
   *
   * @return {@code true} if the IV bit is set.
   */
  public boolean invalid() {
    return cp56 != null ? cp56.invalid() : Objects.requireNonNull(cp24).invalid();
  }

  /**
   * Returns whether the tag reports summer time (SU=1). CP24Time2a carries no SU bit.
   *
   * @return {@code true} if the SU bit is set; always {@code false} for a CP24 tag.
   */
  public boolean summerTime() {
    return cp56 != null && cp56.summerTime();
  }

  /**
   * Returns whether the tag was substituted by intermediate equipment or the controlling station
   * rather than added by the acquiring device (RES1/GEN=1).
   *
   * @return {@code true} if the RES1/GEN bit is set.
   */
  public boolean substituted() {
    return cp56 != null ? !cp56.genuine() : !Objects.requireNonNull(cp24).genuine();
  }

  /**
   * Returns the resolved instant unless the tag is marked invalid.
   *
   * @return {@link #instant()}, or empty if {@link #invalid()} is {@code true}.
   */
  public Optional<Instant> timestamp() {
    return invalid() ? Optional.empty() : instant;
  }
}
