package com.digitalpetri.iec104.asdu.element;

import com.digitalpetri.iec104.AsduDecodeException;

/**
 * Event state (ES) of protection equipment as defined in IEC 60870-5-101 §7.2.6.10.
 *
 * <p>The event state is a two-bit field carried in the SEP information element. The two {@code
 * INDETERMINATE} values are distinct wire encodings ({@code 0} and {@code 3}) that both denote an
 * indeterminate state.
 */
public enum EventState {

  /** Indeterminate state, encoded as the wire value {@code 0}. */
  INDETERMINATE0(0),

  /** Determined state OFF, encoded as the wire value {@code 1}. */
  OFF(1),

  /** Determined state ON, encoded as the wire value {@code 2}. */
  ON(2),

  /** Indeterminate state, encoded as the wire value {@code 3}. */
  INDETERMINATE3(3);

  private final int value;

  EventState(int value) {
    this.value = value;
  }

  /**
   * Returns the two-bit wire value of this event state.
   *
   * @return the wire value in the range {@code 0..3}.
   */
  public int value() {
    return value;
  }

  /**
   * Returns the event state matching the given two-bit wire value.
   *
   * @param value the wire value in the range {@code 0..3}.
   * @return the matching {@link EventState}.
   * @throws AsduDecodeException if {@code value} does not correspond to a defined event state.
   */
  public static EventState fromValue(int value) {
    for (EventState state : values()) {
      if (state.value == value) {
        return state;
      }
    }
    throw new AsduDecodeException("invalid EventState value: " + value);
  }
}
