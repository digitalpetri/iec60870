package com.digitalpetri.iec104.asdu.element;

import io.netty.buffer.ByteBuf;

/**
 * BCR — binary counter reading (IEC 60870-5-101 §7.2.6.9).
 *
 * <p>A signed 32-bit counter value accompanied by a sequence number and three status bits: carry,
 * counter-adjusted, and invalid.
 *
 * @param value the signed 32-bit counter reading (I32).
 * @param sequenceNumber the sequence number in the range {@code 0..31} (SQ).
 * @param carry whether a counter overflow occurred in the integration period (CY).
 * @param adjusted whether the counter was adjusted since the last reading (CA).
 * @param invalid whether the counter reading is invalid (IV).
 */
public record BinaryCounterReading(
    int value, int sequenceNumber, boolean carry, boolean adjusted, boolean invalid) {

  /** Minimum sequence number. */
  private static final int MIN_SEQUENCE_NUMBER = 0;

  /** Maximum sequence number. */
  private static final int MAX_SEQUENCE_NUMBER = 31;

  /** Mask selecting the sequence-number bits (bits 1..5) of the status octet. */
  private static final int SQ_MASK = 0x1F;

  /** CY — carry bit (bit 6) of the status octet. */
  private static final int CY_MASK = 0x20;

  /** CA — counter-adjusted bit (bit 7) of the status octet. */
  private static final int CA_MASK = 0x40;

  /** IV — invalid bit (bit 8) of the status octet. */
  private static final int IV_MASK = 0x80;

  /**
   * Validates the components.
   *
   * @param value the signed 32-bit counter reading (I32).
   * @param sequenceNumber the sequence number in the range {@code 0..31} (SQ).
   * @param carry whether a counter overflow occurred in the integration period (CY).
   * @param adjusted whether the counter was adjusted since the last reading (CA).
   * @param invalid whether the counter reading is invalid (IV).
   * @throws IllegalArgumentException if {@code sequenceNumber} is outside {@code 0..31}.
   */
  public BinaryCounterReading {
    if (sequenceNumber < MIN_SEQUENCE_NUMBER || sequenceNumber > MAX_SEQUENCE_NUMBER) {
      throw new IllegalArgumentException("sequenceNumber out of range [0, 31]: " + sequenceNumber);
    }
  }

  /** Serde for the {@link BinaryCounterReading} element, encoded as five octets. */
  public static final class Serde {

    private Serde() {}

    /**
     * Encodes the counter reading as five octets into {@code buffer}.
     *
     * <p>Wire layout: a little-endian 32-bit value (I32) followed by one status octet with the
     * sequence number in bits 1..5, CY(b6), CA(b7), and IV(b8). Does not release the buffer.
     *
     * @param bcr the counter reading to encode.
     * @param buffer the caller-owned buffer to write into.
     */
    public static void encode(BinaryCounterReading bcr, ByteBuf buffer) {
      buffer.writeIntLE(bcr.value());
      int status = bcr.sequenceNumber() & SQ_MASK;
      if (bcr.carry()) {
        status |= CY_MASK;
      }
      if (bcr.adjusted()) {
        status |= CA_MASK;
      }
      if (bcr.invalid()) {
        status |= IV_MASK;
      }
      buffer.writeByte(status);
    }

    /**
     * Decodes five octets from {@code buffer} into a {@link BinaryCounterReading}.
     *
     * <p>Does not release the buffer.
     *
     * @param buffer the caller-owned buffer to read from.
     * @return the decoded counter reading.
     */
    public static BinaryCounterReading decode(ByteBuf buffer) {
      int value = buffer.readIntLE();
      int status = buffer.readUnsignedByte();
      int sequenceNumber = status & SQ_MASK;
      boolean carry = (status & CY_MASK) != 0;
      boolean adjusted = (status & CA_MASK) != 0;
      boolean invalid = (status & IV_MASK) != 0;
      return new BinaryCounterReading(value, sequenceNumber, carry, adjusted, invalid);
    }
  }
}
