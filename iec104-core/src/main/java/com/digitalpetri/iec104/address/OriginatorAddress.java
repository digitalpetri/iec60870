package com.digitalpetri.iec104.address;

import java.util.Objects;
import org.joou.UByte;

/**
 * The originator address, identifying the controlling station that originated a control-direction
 * message.
 *
 * <p>The value is an unsigned 8-bit integer. It occupies the second cause-of-transmission octet and
 * is present on the wire only when the {@link com.digitalpetri.iec104.ProtocolProfile} uses a
 * two-octet cause of transmission. The value {@code 0} ({@link #none()}) means no specific
 * originator.
 *
 * @param value the unsigned 8-bit originator address value.
 */
public record OriginatorAddress(UByte value) {

  /**
   * Validates the originator address.
   *
   * @param value the unsigned 8-bit originator address value.
   * @throws NullPointerException if {@code value} is null.
   */
  public OriginatorAddress {
    Objects.requireNonNull(value, "value");
  }

  /**
   * Creates an originator address from an {@code int} value.
   *
   * @param value the originator address value, in the range {@code 0..255}.
   * @return the originator address.
   * @throws IllegalArgumentException if {@code value} is not in the range {@code 0..255}.
   */
  public static OriginatorAddress of(int value) {
    if (value < 0 || value > 255) {
      throw new IllegalArgumentException("originator address must be in 0..255: " + value);
    }
    return new OriginatorAddress(UByte.valueOf(value));
  }

  /**
   * Returns the originator address {@code 0}, meaning no specific originator.
   *
   * @return the {@code 0} originator address.
   */
  public static OriginatorAddress none() {
    return of(0);
  }
}
