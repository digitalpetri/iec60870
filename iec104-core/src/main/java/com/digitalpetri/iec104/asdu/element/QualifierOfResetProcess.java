package com.digitalpetri.iec104.asdu.element;

import static org.joou.Unsigned.ubyte;

import com.digitalpetri.iec104.AsduDecodeException;
import io.netty.buffer.ByteBuf;
import org.joou.UByte;

/**
 * Qualifier of reset process command (QRP) per IEC 60870-5-101 clause 7.2.6.27.
 *
 * <p>Encoded as a single unsigned octet. Value {@code 1} requests a general reset of the process;
 * value {@code 2} requests a reset of pending information with time tag in the event buffer. Other
 * values are reserved or private and are preserved verbatim by {@link #of(int)}.
 *
 * @param value the QRP value, in the range {@code 0..255}.
 */
public record QualifierOfResetProcess(UByte value) {

  /** General reset of the process (value {@code 1}). */
  public static final QualifierOfResetProcess GENERAL = of(1);

  /** Reset of pending information with time tag of the event buffer (value {@code 2}). */
  public static final QualifierOfResetProcess EVENT_BUFFER = of(2);

  /**
   * Validates that the value is present.
   *
   * @param value the QRP value, in the range {@code 0..255}.
   * @throws IllegalArgumentException if {@code value} is {@code null}.
   */
  public QualifierOfResetProcess {
    if (value == null) {
      throw new IllegalArgumentException("value must not be null");
    }
  }

  /**
   * Creates a qualifier of reset process from a raw octet value.
   *
   * @param value the QRP value, in the range {@code 0..255}.
   * @return the {@link QualifierOfResetProcess} carrying {@code value}.
   * @throws IllegalArgumentException if {@code value} is outside {@code 0..255}.
   */
  public static QualifierOfResetProcess of(int value) {
    if (value < 0 || value > 0xFF) {
      throw new IllegalArgumentException("value out of range [0, 255]: " + value);
    }
    return new QualifierOfResetProcess(ubyte(value));
  }

  /** Serde for the {@link QualifierOfResetProcess} octet (QRP, 7.2.6.27). */
  public static final class Serde {

    private Serde() {}

    /**
     * Encodes the QRP as a single unsigned octet into {@code buffer}. Does not release the buffer.
     *
     * @param qrp the qualifier of reset process to encode.
     * @param buffer the caller-owned buffer to write into.
     */
    public static void encode(QualifierOfResetProcess qrp, ByteBuf buffer) {
      buffer.writeByte(qrp.value().intValue());
    }

    /**
     * Decodes one QRP octet from {@code buffer}. Does not release the buffer.
     *
     * @param buffer the caller-owned buffer to read from.
     * @return the decoded {@link QualifierOfResetProcess}.
     * @throws AsduDecodeException if the buffer does not contain at least one readable octet.
     */
    public static QualifierOfResetProcess decode(ByteBuf buffer) {
      if (!buffer.isReadable()) {
        throw new AsduDecodeException("QualifierOfResetProcess requires 1 octet");
      }
      return of(buffer.readUnsignedByte());
    }
  }
}
