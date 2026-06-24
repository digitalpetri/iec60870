package com.digitalpetri.iec60870;

import java.time.Duration;
import org.joou.UShort;

/**
 * APCI flow-control parameters that govern the I-format sliding window and the protocol timers.
 *
 * <p>{@code k} is the maximum number of I-format frames a station may send before it must receive
 * an acknowledgement, and {@code w} is the number of received I-format frames after which the
 * station sends an acknowledgement. {@code w} must not exceed {@code k}. The four durations are the
 * standard IEC 60870-5-104 timeouts:
 *
 * <ul>
 *   <li>{@code t0} — connection establishment timeout.
 *   <li>{@code t1} — timeout on sent frames awaiting acknowledgement.
 *   <li>{@code t2} — timeout for acknowledging received frames (must be shorter than {@code t1}).
 *   <li>{@code t3} — idle timeout after which a {@code TESTFR} frame is sent.
 * </ul>
 *
 * <p>Use {@link #defaults()} for the standard parameter set.
 *
 * @param k the maximum number of outstanding unacknowledged I-format frames, in the range {@code
 *     1..32767}.
 * @param w the number of received I-format frames after which an acknowledgement is sent, in the
 *     range {@code 1..32767} and not greater than {@code k}.
 * @param t0 the connection establishment timeout; must be positive.
 * @param t1 the timeout for an outstanding I-format frame awaiting acknowledgement; must be
 *     positive.
 * @param t2 the timeout for acknowledging received I-format frames; must be positive.
 * @param t3 the idle timeout after which a test frame is sent; must be positive.
 */
public record ApciSettings(UShort k, UShort w, Duration t0, Duration t1, Duration t2, Duration t3) {

  /**
   * Validates the components.
   *
   * @param k the maximum number of outstanding unacknowledged I-format frames, in the range {@code
   *     1..32767}.
   * @param w the number of received I-format frames after which an acknowledgement is sent, in the
   *     range {@code 1..32767} and not greater than {@code k}.
   * @param t0 the connection establishment timeout; must be positive.
   * @param t1 the timeout for an outstanding I-format frame awaiting acknowledgement; must be
   *     positive.
   * @param t2 the timeout for acknowledging received I-format frames; must be positive.
   * @param t3 the idle timeout after which a test frame is sent; must be positive.
   * @throws IllegalArgumentException if {@code k} or {@code w} is not in {@code 1..32767}, if
   *     {@code w} is greater than {@code k}, or if any duration is null, zero, or negative.
   */
  public ApciSettings {
    int kValue = k.intValue();
    int wValue = w.intValue();
    if (kValue < 1 || kValue > 32767) {
      throw new IllegalArgumentException("k must be in 1..32767: " + kValue);
    }
    if (wValue < 1 || wValue > 32767) {
      throw new IllegalArgumentException("w must be in 1..32767: " + wValue);
    }
    if (wValue > kValue) {
      throw new IllegalArgumentException("w must not exceed k: w=" + wValue + ", k=" + kValue);
    }
    requirePositive(t0, "t0");
    requirePositive(t1, "t1");
    requirePositive(t2, "t2");
    requirePositive(t3, "t3");
  }

  private static void requirePositive(Duration duration, String name) {
    if (duration.isZero() || duration.isNegative()) {
      throw new IllegalArgumentException(name + " must be positive: " + duration);
    }
  }

  /**
   * Returns the standard IEC 60870-5-104 APCI parameter set.
   *
   * <p>The returned settings use {@code k=12}, {@code w=8}, {@code t0=30s}, {@code t1=15s}, {@code
   * t2=10s}, and {@code t3=20s}.
   *
   * @return the default APCI settings.
   */
  public static ApciSettings defaults() {
    return new ApciSettings(
        UShort.valueOf(12),
        UShort.valueOf(8),
        Duration.ofSeconds(30),
        Duration.ofSeconds(15),
        Duration.ofSeconds(10),
        Duration.ofSeconds(20));
  }
}
