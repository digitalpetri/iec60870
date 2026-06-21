package com.digitalpetri.iec104.asdu.element;

import com.digitalpetri.iec104.AsduDecodeException;
import io.netty.buffer.ByteBuf;

/**
 * Qualifier of parameter of measured values (QPM) per IEC 60870-5-101 clause 7.2.6.24.
 *
 * <p>Encoded as a single octet: the kind of parameter ({@code KPA}) occupies bits 1..6, the local
 * parameter change flag ({@code LPC}) occupies bit 7, and the parameter operation flag ({@code
 * POP}) occupies bit 8. Standard {@code KPA} values are {@code 1} threshold value, {@code 2}
 * smoothing factor, {@code 3} low limit, and {@code 4} high limit; {@code 0} is not used. The
 * {@code LPC} and {@code POP} flags are not used by this standard and are set to zero.
 *
 * @param kindOfParameter the {@code KPA} field, in the range {@code 0..63}.
 * @param localParameterChange the {@code LPC} flag; {@code true} indicates a local parameter
 *     change.
 * @param notInOperation the {@code POP} flag; {@code true} indicates the parameter is not in
 *     operation.
 */
public record QualifierOfParameter(
    int kindOfParameter, boolean localParameterChange, boolean notInOperation) {

  /** Bit mask of the six-bit {@code KPA} field (bits 1..6). */
  private static final int KPA_MASK = 0x3F;

  /** Bit mask of the {@code LPC} flag (bit 7). */
  private static final int LPC_MASK = 0x40;

  /** Bit mask of the {@code POP} flag (bit 8). */
  private static final int POP_MASK = 0x80;

  /**
   * Validates the kind-of-parameter range.
   *
   * @param kindOfParameter the {@code KPA} field, in the range {@code 0..63}.
   * @param localParameterChange the {@code LPC} flag; {@code true} indicates a local parameter
   *     change.
   * @param notInOperation the {@code POP} flag; {@code true} indicates the parameter is not in
   *     operation.
   * @throws IllegalArgumentException if {@code kindOfParameter} is outside {@code 0..63}.
   */
  public QualifierOfParameter {
    if (kindOfParameter < 0 || kindOfParameter > KPA_MASK) {
      throw new IllegalArgumentException(
          "kindOfParameter out of range [0, 63]: " + kindOfParameter);
    }
  }

  /** Serde for the {@link QualifierOfParameter} octet (QPM, 7.2.6.24). */
  public static final class Serde {

    private Serde() {}

    /**
     * Encodes the QPM as a single octet into {@code buffer}: {@code KPA} in bits 1..6, {@code LPC}
     * in bit 7, and {@code POP} in bit 8. Does not release the buffer.
     *
     * @param qpm the qualifier of parameter to encode.
     * @param buffer the caller-owned buffer to write into.
     */
    public static void encode(QualifierOfParameter qpm, ByteBuf buffer) {
      int octet =
          (qpm.kindOfParameter() & KPA_MASK)
              | (qpm.localParameterChange() ? LPC_MASK : 0)
              | (qpm.notInOperation() ? POP_MASK : 0);
      buffer.writeByte(octet);
    }

    /**
     * Decodes one QPM octet from {@code buffer}: {@code KPA} from bits 1..6, {@code LPC} from bit
     * 7, and {@code POP} from bit 8. Does not release the buffer.
     *
     * @param buffer the caller-owned buffer to read from.
     * @return the decoded {@link QualifierOfParameter}.
     * @throws AsduDecodeException if the buffer does not contain at least one readable octet.
     */
    public static QualifierOfParameter decode(ByteBuf buffer) {
      if (!buffer.isReadable()) {
        throw new AsduDecodeException("QualifierOfParameter requires 1 octet");
      }
      int octet = buffer.readUnsignedByte();
      int kindOfParameter = octet & KPA_MASK;
      boolean localParameterChange = (octet & LPC_MASK) != 0;
      boolean notInOperation = (octet & POP_MASK) != 0;
      return new QualifierOfParameter(kindOfParameter, localParameterChange, notInOperation);
    }
  }
}
