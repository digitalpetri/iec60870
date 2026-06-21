package com.digitalpetri.iec104.asdu.element;

import io.netty.buffer.ByteBuf;

/**
 * SCD — status and status change detection (IEC 60870-5-101 §7.2.6.40).
 *
 * <p>Carries 16 status bits and 16 corresponding change-detected bits. The {@code statusBits}
 * occupy wire bits 1..16 and the {@code changeDetectedBits} occupy wire bits 17..32. Both
 * components are stored in the low 16 bits of an {@code int}; bits outside this range are masked
 * off in the compact constructor.
 *
 * @param statusBits the 16 status bits (ST), held in the low 16 bits.
 * @param changeDetectedBits the 16 change-detected bits (CD), held in the low 16 bits.
 */
public record Scd(int statusBits, int changeDetectedBits) {

  /** Mask retaining the low 16 bits of each field. */
  private static final int FIELD_MASK = 0xFFFF;

  /** Number of bits the change-detected field is shifted within the 32-bit word. */
  private static final int CD_SHIFT = 16;

  /**
   * Masks each component to 16 bits.
   *
   * @param statusBits the 16 status bits (ST), held in the low 16 bits.
   * @param changeDetectedBits the 16 change-detected bits (CD), held in the low 16 bits.
   */
  public Scd {
    statusBits &= FIELD_MASK;
    changeDetectedBits &= FIELD_MASK;
  }

  /** Serde for the {@link Scd} element, encoded as four little-endian octets. */
  public static final class Serde {

    private Serde() {}

    /**
     * Encodes the status and change-detected bits as a little-endian 32-bit word into {@code
     * buffer}.
     *
     * <p>Wire layout: ST in bits 1..16 (low half) and CD in bits 17..32 (high half), written
     * least-significant-octet-first. Does not release the buffer.
     *
     * @param scd the element to encode.
     * @param buffer the caller-owned buffer to write into.
     */
    public static void encode(Scd scd, ByteBuf buffer) {
      int word =
          (scd.statusBits() & FIELD_MASK) | ((scd.changeDetectedBits() & FIELD_MASK) << CD_SHIFT);
      buffer.writeIntLE(word);
    }

    /**
     * Decodes a little-endian 32-bit word from {@code buffer} into an {@link Scd}.
     *
     * <p>Does not release the buffer.
     *
     * @param buffer the caller-owned buffer to read from.
     * @return the decoded element.
     */
    public static Scd decode(ByteBuf buffer) {
      int word = buffer.readIntLE();
      int statusBits = word & FIELD_MASK;
      int changeDetectedBits = (word >>> CD_SHIFT) & FIELD_MASK;
      return new Scd(statusBits, changeDetectedBits);
    }
  }
}
