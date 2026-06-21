package com.digitalpetri.iec104.asdu.element;

import com.digitalpetri.iec104.AsduDecodeException;

/**
 * Double-point information state (DPI) as defined in IEC 60870-5-101 §7.2.6.2.
 *
 * <p>The double-point state is a two-bit field carried in the DIQ information element. It
 * distinguishes the two determined states ({@code OFF}, {@code ON}) from the two indeterminate
 * encodings ({@code 0} and {@code 3}).
 */
public enum DoublePointState {

  /** Indeterminate or intermediate state, encoded as the wire value {@code 0}. */
  INDETERMINATE_OR_INTERMEDIATE(0),

  /** Determined state OFF, encoded as the wire value {@code 1}. */
  OFF(1),

  /** Determined state ON, encoded as the wire value {@code 2}. */
  ON(2),

  /** Indeterminate state, encoded as the wire value {@code 3}. */
  INDETERMINATE(3);

  private final int value;

  DoublePointState(int value) {
    this.value = value;
  }

  /**
   * Returns the two-bit wire value of this double-point state.
   *
   * @return the wire value in the range {@code 0..3}.
   */
  public int value() {
    return value;
  }

  /**
   * Returns the double-point state matching the given two-bit wire value.
   *
   * @param value the wire value in the range {@code 0..3}.
   * @return the matching {@link DoublePointState}.
   * @throws AsduDecodeException if {@code value} does not correspond to a defined double-point
   *     state.
   */
  public static DoublePointState fromValue(int value) {
    for (DoublePointState state : values()) {
      if (state.value == value) {
        return state;
      }
    }
    throw new AsduDecodeException("invalid DoublePointState value: " + value);
  }
}
