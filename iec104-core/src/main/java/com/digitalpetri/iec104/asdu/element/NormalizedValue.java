package com.digitalpetri.iec104.asdu.element;

import io.netty.buffer.ByteBuf;

/**
 * NVA — normalized value (IEC 60870-5-101 §7.2.6.6).
 *
 * <p>A signed 16-bit fixed-point fraction representing a value in the range {@code [-1, 1 -
 * 2^-15]}. The {@link #rawValue()} component is the raw signed integer as it appears on the wire;
 * {@link #doubleValue()} converts it to its fractional representation.
 *
 * @param rawValue the raw signed 16-bit value (I16).
 */
public record NormalizedValue(short rawValue) {

  /** Scale factor mapping the raw 16-bit value onto the fractional range. */
  private static final double SCALE = 32768.0;

  /**
   * Returns the fractional value of this normalized value.
   *
   * @return {@code rawValue / 32768.0}.
   */
  public double doubleValue() {
    return rawValue / SCALE;
  }

  /**
   * Creates a normalized value from a fractional value.
   *
   * <p>The fractional value is scaled by {@code 32768} and rounded to the nearest representable raw
   * 16-bit value. Values at or beyond the representable bounds saturate to {@link Short#MIN_VALUE}
   * or {@link Short#MAX_VALUE}.
   *
   * @param value the fractional value, nominally in the range {@code [-1, 1 - 2^-15]}.
   * @return the corresponding normalized value.
   */
  public static NormalizedValue of(double value) {
    long scaled = Math.round(value * SCALE);
    if (scaled > Short.MAX_VALUE) {
      scaled = Short.MAX_VALUE;
    } else if (scaled < Short.MIN_VALUE) {
      scaled = Short.MIN_VALUE;
    }
    return new NormalizedValue((short) scaled);
  }

  /** Serde for the {@link NormalizedValue} element, encoded as two little-endian octets. */
  public static final class Serde {

    private Serde() {}

    /**
     * Encodes the raw value as a little-endian 16-bit integer into {@code buffer}.
     *
     * <p>Does not release the buffer.
     *
     * @param value the normalized value to encode.
     * @param buffer the caller-owned buffer to write into.
     */
    public static void encode(NormalizedValue value, ByteBuf buffer) {
      buffer.writeShortLE(value.rawValue());
    }

    /**
     * Decodes a little-endian 16-bit integer from {@code buffer} into a {@link NormalizedValue}.
     *
     * <p>Does not release the buffer.
     *
     * @param buffer the caller-owned buffer to read from.
     * @return the decoded normalized value.
     */
    public static NormalizedValue decode(ByteBuf buffer) {
      return new NormalizedValue(buffer.readShortLE());
    }
  }
}
