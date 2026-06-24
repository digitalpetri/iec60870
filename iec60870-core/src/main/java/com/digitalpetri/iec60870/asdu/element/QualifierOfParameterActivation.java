package com.digitalpetri.iec60870.asdu.element;

import static org.joou.Unsigned.ubyte;

import com.digitalpetri.iec60870.AsduDecodeException;
import io.netty.buffer.ByteBuf;
import java.util.Objects;
import org.joou.UByte;

/**
 * Qualifier of parameter activation (QPA) per IEC 60870-5-101 clause 7.2.6.25.
 *
 * <p>Encoded as a single unsigned octet. Value {@code 1} activates or deactivates the previously
 * loaded parameters; {@code 2} acts on the parameter of the addressed object; {@code 3} acts on
 * persistent cyclic or periodic transmission of the addressed object. Other values are reserved or
 * private and are preserved verbatim by {@link #of(int)}.
 *
 * @param value the QPA value, in the range {@code 0..255}.
 */
public record QualifierOfParameterActivation(UByte value) {

  /**
   * Validates that the value is present.
   *
   * @param value the QPA value, in the range {@code 0..255}.
   * @throws NullPointerException if {@code value} is {@code null}.
   */
  public QualifierOfParameterActivation {
    Objects.requireNonNull(value, "value");
  }

  /**
   * Creates a qualifier of parameter activation from a raw octet value.
   *
   * @param value the QPA value, in the range {@code 0..255}.
   * @return the {@link QualifierOfParameterActivation} carrying {@code value}.
   * @throws IllegalArgumentException if {@code value} is outside {@code 0..255}.
   */
  public static QualifierOfParameterActivation of(int value) {
    if (value < 0 || value > 0xFF) {
      throw new IllegalArgumentException("value out of range [0, 255]: " + value);
    }
    return new QualifierOfParameterActivation(ubyte(value));
  }

  /** Serde for the {@link QualifierOfParameterActivation} octet (QPA, 7.2.6.25). */
  public static final class Serde {

    private Serde() {}

    /**
     * Encodes the QPA as a single unsigned octet into {@code buffer}. Does not release the buffer.
     *
     * @param qpa the qualifier of parameter activation to encode.
     * @param buffer the caller-owned buffer to write into.
     */
    public static void encode(QualifierOfParameterActivation qpa, ByteBuf buffer) {
      buffer.writeByte(qpa.value().intValue());
    }

    /**
     * Decodes one QPA octet from {@code buffer}. Does not release the buffer.
     *
     * @param buffer the caller-owned buffer to read from.
     * @return the decoded {@link QualifierOfParameterActivation}.
     * @throws AsduDecodeException if the buffer does not contain at least one readable octet.
     */
    public static QualifierOfParameterActivation decode(ByteBuf buffer) {
      if (!buffer.isReadable()) {
        throw new AsduDecodeException("QualifierOfParameterActivation requires 1 octet");
      }
      return of(buffer.readUnsignedByte());
    }
  }
}
