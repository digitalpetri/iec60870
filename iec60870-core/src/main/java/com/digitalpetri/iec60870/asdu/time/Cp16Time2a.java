package com.digitalpetri.iec60870.asdu.time;

import io.netty.buffer.ByteBuf;

/**
 * CP16Time2a — two octet binary time, as defined by IEC 60870-5-4 §6.8 and IEC 60870-5-101
 * §7.2.6.20.
 *
 * <p>This element carries an unstructured count of milliseconds and is used to express an elapsed
 * duration such as a relay operating time or relay duration time. It does not carry a calendar date
 * or a flag for validity.
 *
 * <p>The component accepts any value the 16-bit wire field can hold, so a received value is kept
 * exactly as sent. {@link #isWellFormed()} reports whether it is within the specified range.
 *
 * @param milliseconds the elapsed time in milliseconds, specified as {@code 0..59999}; any value in
 *     {@code 0..65535} is accepted.
 */
public record Cp16Time2a(int milliseconds) {

  /**
   * Validates that the component fits its wire field.
   *
   * @param milliseconds the elapsed time in milliseconds, in the range {@code 0..65535}.
   * @throws IllegalArgumentException if {@code milliseconds} is outside {@code 0..65535}.
   */
  public Cp16Time2a {
    if (milliseconds < 0 || milliseconds > 0xFFFF) {
      throw new IllegalArgumentException("milliseconds out of range [0, 65535]: " + milliseconds);
    }
  }

  /**
   * Reports whether the elapsed time is within its specified range, {@code 0..59999}.
   *
   * @return {@code true} if {@code milliseconds} is at most {@code 59999}.
   */
  public boolean isWellFormed() {
    return milliseconds <= 59999;
  }

  /**
   * Encoder and decoder for the two-octet CP16Time2a wire representation.
   *
   * <p>Wire layout (Mode 1, least significant octet first):
   *
   * <ul>
   *   <li>octets 1-2: milliseconds as an unsigned 16-bit integer, little-endian, range {@code
   *       0..59999}.
   * </ul>
   */
  public static final class Serde {

    private Serde() {}

    /**
     * Encodes {@code time} into {@code buffer} as two little-endian octets.
     *
     * <p>The buffer is owned by the caller; this method writes into it and never releases it.
     *
     * @param time the time element to encode.
     * @param buffer the destination buffer to write the two octets into.
     */
    public static void encode(Cp16Time2a time, ByteBuf buffer) {
      buffer.writeShortLE(time.milliseconds());
    }

    /**
     * Decodes a {@code Cp16Time2a} from the next two octets of {@code buffer}.
     *
     * <p>The buffer is owned by the caller; this method reads from it and never releases it. The
     * value is kept as received, even when it is out of range; see {@link
     * Cp16Time2a#isWellFormed()}.
     *
     * @param buffer the source buffer positioned at the first octet of the time element.
     * @return the decoded time element.
     */
    public static Cp16Time2a decode(ByteBuf buffer) {
      return new Cp16Time2a(buffer.readUnsignedShortLE());
    }
  }
}
