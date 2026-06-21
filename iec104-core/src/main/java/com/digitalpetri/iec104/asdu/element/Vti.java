package com.digitalpetri.iec104.asdu.element;

import io.netty.buffer.ByteBuf;

/**
 * VTI — value with transient state indication (IEC 60870-5-101 §7.2.6.5).
 *
 * <p>Used for transformer tap positions or other step-position information. The value is a signed
 * 7-bit integer and the transient flag indicates that the equipment is currently changing state.
 *
 * @param value the signed step position in the range {@code -64..63} (I7).
 * @param transientState whether the equipment is in a transient state (TR).
 */
public record Vti(int value, boolean transientState) {

  /** Minimum value of the signed 7-bit field. */
  private static final int MIN_VALUE = -64;

  /** Maximum value of the signed 7-bit field. */
  private static final int MAX_VALUE = 63;

  /** Mask selecting the 7 value bits (bits 1..7). */
  private static final int VALUE_MASK = 0x7F;

  /** Sign bit of the 7-bit value (bit 7). */
  private static final int SIGN_MASK = 0x40;

  /** TR — transient-state bit (bit 8). */
  private static final int TR_MASK = 0x80;

  /**
   * Validates that the value lies within the signed 7-bit range.
   *
   * @param value the signed step position in the range {@code -64..63} (I7).
   * @param transientState whether the equipment is in a transient state (TR).
   * @throws IllegalArgumentException if {@code value} is outside {@code -64..63}.
   */
  public Vti {
    if (value < MIN_VALUE || value > MAX_VALUE) {
      throw new IllegalArgumentException("value out of range [-64, 63]: " + value);
    }
  }

  /** Serde for the {@link Vti} element, encoded as a single octet. */
  public static final class Serde {

    private Serde() {}

    /**
     * Encodes the value and transient flag as one octet into {@code buffer}.
     *
     * <p>Wire layout (bit 1 = least significant bit): I7 signed value in bits 1..7, TR in bit 8.
     * Does not release the buffer.
     *
     * @param vti the value to encode.
     * @param buffer the caller-owned buffer to write into.
     */
    public static void encode(Vti vti, ByteBuf buffer) {
      int b = vti.value() & VALUE_MASK;
      if (vti.transientState()) {
        b |= TR_MASK;
      }
      buffer.writeByte(b);
    }

    /**
     * Decodes one octet from {@code buffer} into a {@link Vti}.
     *
     * <p>The 7-bit value is sign-extended from bit 7. Does not release the buffer.
     *
     * @param buffer the caller-owned buffer to read from.
     * @return the decoded value with transient indication.
     */
    public static Vti decode(ByteBuf buffer) {
      int b = buffer.readUnsignedByte();
      int raw = b & VALUE_MASK;
      int value = (raw & SIGN_MASK) != 0 ? raw - 0x80 : raw;
      boolean transientState = (b & TR_MASK) != 0;
      return new Vti(value, transientState);
    }
  }
}
