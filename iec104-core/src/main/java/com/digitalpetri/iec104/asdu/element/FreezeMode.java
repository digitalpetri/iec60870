package com.digitalpetri.iec104.asdu.element;

import com.digitalpetri.iec104.AsduDecodeException;

/**
 * Counter freeze mode (FRZ), the two-bit freeze field of a qualifier of counter interrogation (QCC)
 * per IEC 60870-5-101 clause 7.2.6.23.
 *
 * <p>The action specified by the freeze mode is applied only to the counter group selected by the
 * request field of the enclosing {@link QualifierOfCounterInterrogation}.
 */
public enum FreezeMode {

  /** Read with no freeze or reset (encoding {@code 0}). */
  READ(0),

  /**
   * Counter freeze without reset; the frozen value represents the integrated total (encoding {@code
   * 1}).
   */
  FREEZE_NO_RESET(1),

  /**
   * Counter freeze with reset; the frozen value represents incremental information (encoding {@code
   * 2}).
   */
  FREEZE_WITH_RESET(2),

  /** Counter reset (encoding {@code 3}). */
  RESET(3);

  private final int value;

  FreezeMode(int value) {
    this.value = value;
  }

  /**
   * Returns the two-bit wire encoding of this freeze mode.
   *
   * @return the wire value in the range {@code 0..3}.
   */
  public int value() {
    return value;
  }

  /**
   * Returns the freeze mode for the given two-bit wire encoding.
   *
   * @param value the wire value, expected in the range {@code 0..3}.
   * @return the matching {@link FreezeMode}.
   * @throws AsduDecodeException if {@code value} does not map to a defined freeze mode.
   */
  public static FreezeMode fromValue(int value) {
    for (FreezeMode mode : values()) {
      if (mode.value == value) {
        return mode;
      }
    }
    throw new AsduDecodeException("invalid FreezeMode value: " + value);
  }
}
