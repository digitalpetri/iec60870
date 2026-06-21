package com.digitalpetri.iec104.asdu.element;

import com.digitalpetri.iec104.AsduDecodeException;

/**
 * Regulating step command state (RCS), the two-bit state field of a regulating step command (RCO)
 * per IEC 60870-5-101 clause 7.2.6.17.
 *
 * <p>The two reserved encodings {@code 0} and {@code 3} are both defined as "not permitted" by the
 * standard and are retained here as {@link #INVALID0} and {@link #INVALID3} so that the exact wire
 * value round-trips.
 */
public enum StepCommandState {

  /** Not permitted (encoding {@code 0}). */
  INVALID0(0),

  /** Next step LOWER (encoding {@code 1}). */
  NEXT_STEP_LOWER(1),

  /** Next step HIGHER (encoding {@code 2}). */
  NEXT_STEP_HIGHER(2),

  /** Not permitted (encoding {@code 3}). */
  INVALID3(3);

  private final int value;

  StepCommandState(int value) {
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
   * @return the matching {@link StepCommandState}.
   * @throws AsduDecodeException if {@code value} does not map to a defined state.
   */
  public static StepCommandState fromValue(int value) {
    for (StepCommandState state : values()) {
      if (state.value == value) {
        return state;
      }
    }
    throw new AsduDecodeException("invalid StepCommandState value: " + value);
  }
}
