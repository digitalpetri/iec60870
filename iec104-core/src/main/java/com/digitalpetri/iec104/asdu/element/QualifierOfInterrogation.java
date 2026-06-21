package com.digitalpetri.iec104.asdu.element;

import static org.joou.Unsigned.ubyte;

import com.digitalpetri.iec104.AsduDecodeException;
import io.netty.buffer.ByteBuf;
import java.util.Objects;
import org.joou.UByte;

/**
 * Qualifier of interrogation (QOI) per IEC 60870-5-101 clause 7.2.6.22.
 *
 * <p>Encoded as a single unsigned octet. Value {@code 20} requests a global station interrogation;
 * values {@code 21..36} request interrogation of groups {@code 1..16} respectively. Other values
 * are either reserved or private and are preserved verbatim by {@link #of(int)}.
 *
 * @param value the QOI value, in the range {@code 0..255}.
 */
public record QualifierOfInterrogation(UByte value) {

  /** Global station interrogation (value {@code 20}). */
  public static final QualifierOfInterrogation STATION = of(20);

  /** Interrogation of group 1 (value {@code 21}). */
  public static final QualifierOfInterrogation GROUP_1 = of(21);

  /** Interrogation of group 2 (value {@code 22}). */
  public static final QualifierOfInterrogation GROUP_2 = of(22);

  /** Interrogation of group 3 (value {@code 23}). */
  public static final QualifierOfInterrogation GROUP_3 = of(23);

  /** Interrogation of group 4 (value {@code 24}). */
  public static final QualifierOfInterrogation GROUP_4 = of(24);

  /** Interrogation of group 5 (value {@code 25}). */
  public static final QualifierOfInterrogation GROUP_5 = of(25);

  /** Interrogation of group 6 (value {@code 26}). */
  public static final QualifierOfInterrogation GROUP_6 = of(26);

  /** Interrogation of group 7 (value {@code 27}). */
  public static final QualifierOfInterrogation GROUP_7 = of(27);

  /** Interrogation of group 8 (value {@code 28}). */
  public static final QualifierOfInterrogation GROUP_8 = of(28);

  /** Interrogation of group 9 (value {@code 29}). */
  public static final QualifierOfInterrogation GROUP_9 = of(29);

  /** Interrogation of group 10 (value {@code 30}). */
  public static final QualifierOfInterrogation GROUP_10 = of(30);

  /** Interrogation of group 11 (value {@code 31}). */
  public static final QualifierOfInterrogation GROUP_11 = of(31);

  /** Interrogation of group 12 (value {@code 32}). */
  public static final QualifierOfInterrogation GROUP_12 = of(32);

  /** Interrogation of group 13 (value {@code 33}). */
  public static final QualifierOfInterrogation GROUP_13 = of(33);

  /** Interrogation of group 14 (value {@code 34}). */
  public static final QualifierOfInterrogation GROUP_14 = of(34);

  /** Interrogation of group 15 (value {@code 35}). */
  public static final QualifierOfInterrogation GROUP_15 = of(35);

  /** Interrogation of group 16 (value {@code 36}). */
  public static final QualifierOfInterrogation GROUP_16 = of(36);

  /**
   * Validates that the value is present.
   *
   * @param value the QOI value, in the range {@code 0..255}.
   * @throws NullPointerException if {@code value} is {@code null}.
   */
  public QualifierOfInterrogation {
    Objects.requireNonNull(value, "value");
  }

  /**
   * Creates a qualifier of interrogation from a raw octet value.
   *
   * @param value the QOI value, in the range {@code 0..255}.
   * @return the {@link QualifierOfInterrogation} carrying {@code value}.
   * @throws IllegalArgumentException if {@code value} is outside {@code 0..255}.
   */
  public static QualifierOfInterrogation of(int value) {
    if (value < 0 || value > 0xFF) {
      throw new IllegalArgumentException("value out of range [0, 255]: " + value);
    }
    return new QualifierOfInterrogation(ubyte(value));
  }

  /** Serde for the {@link QualifierOfInterrogation} octet (QOI, 7.2.6.22). */
  public static final class Serde {

    private Serde() {}

    /**
     * Encodes the QOI as a single unsigned octet into {@code buffer}. Does not release the buffer.
     *
     * @param qoi the qualifier of interrogation to encode.
     * @param buffer the caller-owned buffer to write into.
     */
    public static void encode(QualifierOfInterrogation qoi, ByteBuf buffer) {
      buffer.writeByte(qoi.value().intValue());
    }

    /**
     * Decodes one QOI octet from {@code buffer}. Does not release the buffer.
     *
     * @param buffer the caller-owned buffer to read from.
     * @return the decoded {@link QualifierOfInterrogation}.
     * @throws AsduDecodeException if the buffer does not contain at least one readable octet.
     */
    public static QualifierOfInterrogation decode(ByteBuf buffer) {
      if (!buffer.isReadable()) {
        throw new AsduDecodeException("QualifierOfInterrogation requires 1 octet");
      }
      return of(buffer.readUnsignedByte());
    }
  }
}
