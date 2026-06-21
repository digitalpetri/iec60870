package com.digitalpetri.iec104.asdu.element;

import com.digitalpetri.iec104.AsduDecodeException;
import io.netty.buffer.ByteBuf;

/**
 * Qualifier of set-point command (QOS) per IEC 60870-5-101 clause 7.2.6.39.
 *
 * <p>Encoded as a single octet: the qualifier of set-point ({@code QL}) occupies bits 1..7 and the
 * select/execute flag ({@code S/E}) occupies bit 8. {@code QL} value {@code 0} is the default;
 * values {@code 1..63} are reserved for standard definitions and {@code 64..127} for private use.
 *
 * @param ql the {@code QL} field, in the range {@code 0..127}.
 * @param select {@code true} for a select command ({@code S/E = 1}); {@code false} for an execute
 *     command ({@code S/E = 0}).
 */
public record QualifierOfSetpoint(int ql, boolean select) {

  /** Bit mask of the select/execute flag (bit 8). */
  private static final int SELECT_MASK = 0x80;

  /** Bit mask of the seven-bit {@code QL} field (bits 1..7). */
  private static final int QL_MASK = 0x7F;

  /**
   * Validates the {@code QL} range.
   *
   * @param ql the {@code QL} field, in the range {@code 0..127}.
   * @param select {@code true} for a select command ({@code S/E = 1}); {@code false} for an execute
   *     command ({@code S/E = 0}).
   * @throws IllegalArgumentException if {@code ql} is outside {@code 0..127}.
   */
  public QualifierOfSetpoint {
    if (ql < 0 || ql > QL_MASK) {
      throw new IllegalArgumentException("ql out of range [0, 127]: " + ql);
    }
  }

  /** Serde for the {@link QualifierOfSetpoint} octet (QOS, 7.2.6.39). */
  public static final class Serde {

    private Serde() {}

    /**
     * Encodes the QOS as a single octet into {@code buffer}: {@code QL} in bits 1..7 and {@code
     * S/E} in bit 8. Does not release the buffer.
     *
     * @param qos the qualifier of set-point to encode.
     * @param buffer the caller-owned buffer to write into.
     */
    public static void encode(QualifierOfSetpoint qos, ByteBuf buffer) {
      int octet = (qos.ql() & QL_MASK) | (qos.select() ? SELECT_MASK : 0);
      buffer.writeByte(octet);
    }

    /**
     * Decodes one QOS octet from {@code buffer}: {@code QL} from bits 1..7 and {@code S/E} from bit
     * 8. Does not release the buffer.
     *
     * @param buffer the caller-owned buffer to read from.
     * @return the decoded {@link QualifierOfSetpoint}.
     * @throws AsduDecodeException if the buffer does not contain at least one readable octet.
     */
    public static QualifierOfSetpoint decode(ByteBuf buffer) {
      if (!buffer.isReadable()) {
        throw new AsduDecodeException("QualifierOfSetpoint requires 1 octet");
      }
      int octet = buffer.readUnsignedByte();
      int ql = octet & QL_MASK;
      boolean select = (octet & SELECT_MASK) != 0;
      return new QualifierOfSetpoint(ql, select);
    }
  }
}
