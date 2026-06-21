package com.digitalpetri.iec104.asdu.element;

import com.digitalpetri.iec104.AsduDecodeException;

/**
 * Double command state (DCS), the two-bit state field of a double command (DCO) per IEC 60870-5-101
 * clause 7.2.6.16.
 *
 * <p>The two reserved encodings {@code 0} and {@code 3} are both defined as "not permitted" by the
 * standard and are retained here as {@link #NOT_PERMITTED0} and {@link #NOT_PERMITTED3} so that the
 * exact wire value round-trips.
 */
public enum DoubleCommandState {

  /** Not permitted (encoding {@code 0}). */
  NOT_PERMITTED0(0),

  /** Command OFF (encoding {@code 1}). */
  OFF(1),

  /** Command ON (encoding {@code 2}). */
  ON(2),

  /** Not permitted (encoding {@code 3}). */
  NOT_PERMITTED3(3);

  private final int value;

  DoubleCommandState(int value) {
    this.value = value;
  }

  /**
   * Returns the two-bit wire encoding of this state.
   *
   * @return the wire value in the range {@code 0..3}.
   */
  public int value() {
    return value;
  }

  /**
   * Returns the state for the given two-bit wire encoding.
   *
   * @param value the wire value, expected in the range {@code 0..3}.
   * @return the matching {@link DoubleCommandState}.
   * @throws AsduDecodeException if {@code value} does not map to a defined state.
   */
  public static DoubleCommandState fromValue(int value) {
    for (DoubleCommandState state : values()) {
      if (state.value == value) {
        return state;
      }
    }
    throw new AsduDecodeException("invalid DoubleCommandState value: " + value);
  }
}
