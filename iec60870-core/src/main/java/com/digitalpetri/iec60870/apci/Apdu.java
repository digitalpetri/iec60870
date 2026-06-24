package com.digitalpetri.iec60870.apci;

import com.digitalpetri.iec60870.AsduDecodeException;
import com.digitalpetri.iec60870.ProtocolProfile;
import com.digitalpetri.iec60870.asdu.Asdu;
import io.netty.buffer.ByteBuf;
import org.jspecify.annotations.Nullable;

/**
 * A complete IEC 60870-5-104 Application Protocol Data Unit (APDU): an APCI control field
 * optionally followed by an ASDU.
 *
 * <p>An ASDU is present if and only if the control field is an {@link ControlField.TypeI I-format}
 * control field. {@link ControlField.TypeS S-format} and {@link ControlField.TypeU U-format} APDUs
 * consist of the APCI alone and carry no ASDU. This invariant is enforced by the compact
 * constructor.
 *
 * @param control the APCI control field.
 * @param asdu the ASDU, non-{@code null} if and only if {@code control} is an {@link
 *     ControlField.TypeI}.
 */
public record Apdu(ControlField control, @Nullable Asdu asdu) {

  /** The fixed START octet ({@code 0x68}) that delimits the beginning of an APDU. */
  public static final int START_OCTET = 0x68;

  /** The number of control-field octets in the APCI, namely {@code 4}. */
  public static final int CONTROL_FIELD_LENGTH = 4;

  /**
   * The maximum value of the APDU length octet, namely {@code 253}.
   *
   * <p>This is {@code 255} (the maximum representable octet plus framing budget) minus the START
   * and length octets, and it bounds the combined size of the four control octets and the ASDU
   * body.
   */
  public static final int MAX_APDU_LENGTH = 253;

  /**
   * Validates the components.
   *
   * @param control the APCI control field.
   * @param asdu the ASDU, non-{@code null} if and only if {@code control} is an {@link
   *     ControlField.TypeI}.
   * @throws IllegalArgumentException if {@code control} is an {@link ControlField.TypeI} but {@code
   *     asdu} is {@code null}, or if {@code control} is not an {@link ControlField.TypeI} but
   *     {@code asdu} is non-{@code null}.
   */
  public Apdu {
    boolean isInformation = control instanceof ControlField.TypeI;
    if (isInformation && asdu == null) {
      throw new IllegalArgumentException("I-format APDU requires a non-null ASDU");
    }
    if (!isInformation && asdu != null) {
      throw new IllegalArgumentException("non-I-format APDU must not carry an ASDU");
    }
  }

  /**
   * Encodes and decodes a complete {@link Apdu}, including the START octet, the length octet, the
   * four APCI control octets, and the ASDU body (for I-format APDUs).
   *
   * <p>Wire layout:
   *
   * <ul>
   *   <li>octet 1: START {@code = 0x68}.
   *   <li>octet 2: length {@code = 4 + asduBytes}; it counts the four control octets plus the ASDU
   *       body and excludes the START and length octets themselves.
   *   <li>octets 3..6: the four APCI control octets (see {@link ControlField.Serde}).
   *   <li>octets 7..n: the ASDU body, present only for {@link ControlField.TypeI} APDUs.
   * </ul>
   *
   * <p>{@link #encode(Apdu, ProtocolProfile, ByteBuf)} writes into a caller-owned buffer and never
   * releases it; {@link #decode(ProtocolProfile, ByteBuf)} reads from a caller-owned buffer and
   * never releases it. Decode assumes a full APDU is present in the buffer; the transport layer is
   * responsible for length-based framing.
   */
  public static final class Serde {

    private Serde() {}

    /**
     * Encodes an APDU into {@code buffer}, writing the START octet, the length octet, the four APCI
     * control octets, and (for I-format APDUs) the ASDU body.
     *
     * <p>The length octet is written by back-patching once the ASDU body size is known.
     *
     * @param apdu the APDU to encode.
     * @param profile the protocol profile that governs ASDU field widths.
     * @param buffer the caller-owned destination buffer; the APDU is appended and the buffer is not
     *     released.
     * @throws IllegalArgumentException if the resulting APDU length exceeds {@link
     *     #MAX_APDU_LENGTH}.
     */
    public static void encode(Apdu apdu, ProtocolProfile profile, ByteBuf buffer) {
      buffer.writeByte(START_OCTET);

      int lengthIndex = buffer.writerIndex();
      buffer.writeByte(0); // length placeholder; back-patched below.

      int bodyStart = buffer.writerIndex();
      ControlField.Serde.encode(apdu.control(), buffer);

      Asdu asdu = apdu.asdu();
      if (asdu != null) {
        Asdu.Serde.encode(asdu, profile, buffer);
      }

      int length = buffer.writerIndex() - bodyStart;
      if (length > MAX_APDU_LENGTH) {
        throw new IllegalArgumentException(
            "APDU length " + length + " exceeds maximum " + MAX_APDU_LENGTH);
      }
      buffer.setByte(lengthIndex, length);
    }

    /**
     * Decodes a complete APDU from {@code buffer}.
     *
     * <p>The buffer must contain at least the START octet, the length octet, and the four control
     * octets; for I-format APDUs it must also contain the full ASDU body indicated by the length
     * octet.
     *
     * @param profile the protocol profile that governs ASDU field widths.
     * @param buffer the caller-owned source buffer, positioned at the START octet; the APDU is
     *     consumed and the buffer is not released.
     * @return the decoded APDU.
     * @throws AsduDecodeException if the START octet is not {@code 0x68}, the length octet is less
     *     than {@link #CONTROL_FIELD_LENGTH} or greater than {@link #MAX_APDU_LENGTH}, the buffer
     *     is too short for the declared length, or the embedded control field or ASDU is malformed.
     */
    public static Apdu decode(ProtocolProfile profile, ByteBuf buffer) {
      if (buffer.readableBytes() < 2) {
        throw new AsduDecodeException(
            "APDU requires at least a START and length octet but only "
                + buffer.readableBytes()
                + " are readable");
      }

      int start = buffer.readUnsignedByte();
      if (start != START_OCTET) {
        throw new AsduDecodeException(
            String.format("invalid START octet: 0x%02X (expected 0x68)", start));
      }

      int length = buffer.readUnsignedByte();
      if (length < CONTROL_FIELD_LENGTH) {
        throw new AsduDecodeException(
            "APDU length "
                + length
                + " is less than the control-field length "
                + CONTROL_FIELD_LENGTH);
      }
      if (length > MAX_APDU_LENGTH) {
        throw new AsduDecodeException(
            "APDU length " + length + " exceeds maximum " + MAX_APDU_LENGTH);
      }
      if (buffer.readableBytes() < length) {
        throw new AsduDecodeException(
            "APDU declares length "
                + length
                + " but only "
                + buffer.readableBytes()
                + " octets are readable");
      }

      ControlField control = ControlField.Serde.decode(buffer);

      int asduLength = length - CONTROL_FIELD_LENGTH;
      Asdu asdu = null;
      if (control instanceof ControlField.TypeI) {
        if (asduLength == 0) {
          throw new AsduDecodeException("I-format APDU declares an empty ASDU body");
        }
        asdu = Asdu.Serde.decode(profile, buffer);
      } else if (asduLength != 0) {
        throw new AsduDecodeException(
            "non-I-format APDU declares a non-empty body of " + asduLength + " octets");
      }

      return new Apdu(control, asdu);
    }
  }
}
