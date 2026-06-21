package com.digitalpetri.iec104.apci;

import com.digitalpetri.iec104.AsduDecodeException;
import io.netty.buffer.ByteBuf;
import java.util.Objects;

/**
 * The four-octet APCI control field that prefixes every IEC 60870-5-104 APDU.
 *
 * <p>The least significant bit of the first control octet selects the format: {@link TypeI}
 * (numbered information transfer), {@link TypeS} (numbered supervisory acknowledge), or {@link
 * TypeU} (unnumbered control function). Only {@link TypeI} carries an ASDU; the other two formats
 * consist of the APCI alone.
 *
 * @see Apdu
 */
public sealed interface ControlField
    permits ControlField.TypeI, ControlField.TypeS, ControlField.TypeU {

  /**
   * The largest value a 15-bit send or receive sequence number may take, namely {@code 32767}.
   *
   * <p>Sequence numbers wrap modulo {@code 32768}.
   */
  int MAX_SEQUENCE_NUMBER = 32767;

  /**
   * Numbered information-transfer (I-format) control field.
   *
   * <p>I-format APDUs always carry an ASDU. The send sequence number is the count of the APDU being
   * sent; the receive sequence number acknowledges all I-format APDUs received up to, but not
   * including, that number.
   *
   * @param sendSequenceNumber the send sequence number N(S), in the range {@code 0..32767}.
   * @param receiveSequenceNumber the receive sequence number N(R), in the range {@code 0..32767}.
   */
  record TypeI(int sendSequenceNumber, int receiveSequenceNumber) implements ControlField {

    /**
     * Validates that both sequence numbers are within the 15-bit range.
     *
     * @param sendSequenceNumber the send sequence number N(S), in the range {@code 0..32767}.
     * @param receiveSequenceNumber the receive sequence number N(R), in the range {@code 0..32767}.
     * @throws IllegalArgumentException if either sequence number is outside {@code 0..32767}.
     */
    public TypeI {
      checkSequenceNumber(sendSequenceNumber, "sendSequenceNumber");
      checkSequenceNumber(receiveSequenceNumber, "receiveSequenceNumber");
    }
  }

  /**
   * Numbered supervisory (S-format) control field.
   *
   * <p>S-format APDUs consist of the APCI only and are used to acknowledge received I-format APDUs
   * when there is no I-format traffic in the reverse direction to carry the acknowledgement.
   *
   * @param receiveSequenceNumber the receive sequence number N(R), in the range {@code 0..32767}.
   */
  record TypeS(int receiveSequenceNumber) implements ControlField {

    /**
     * Validates that the receive sequence number is within the 15-bit range.
     *
     * @param receiveSequenceNumber the receive sequence number N(R), in the range {@code 0..32767}.
     * @throws IllegalArgumentException if the receive sequence number is outside {@code 0..32767}.
     */
    public TypeS {
      checkSequenceNumber(receiveSequenceNumber, "receiveSequenceNumber");
    }
  }

  /**
   * Unnumbered control-function (U-format) control field.
   *
   * <p>U-format APDUs consist of the APCI only and carry exactly one control function (STARTDT,
   * STOPDT, or TESTFR) in either its activation or confirmation form.
   *
   * @param function the unnumbered control function.
   */
  record TypeU(UFunction function) implements ControlField {

    /**
     * Validates that a function is present.
     *
     * @param function the unnumbered control function.
     * @throws NullPointerException if {@code function} is {@code null}.
     */
    public TypeU {
      Objects.requireNonNull(function, "function");
    }
  }

  /**
   * Validates that a 15-bit sequence number is within range.
   *
   * @param value the candidate sequence number.
   * @param name the field name used in the exception message.
   * @throws IllegalArgumentException if {@code value} is outside {@code 0..32767}.
   */
  private static void checkSequenceNumber(int value, String name) {
    if (value < 0 || value > MAX_SEQUENCE_NUMBER) {
      throw new IllegalArgumentException(
          name + " must be in range 0.." + MAX_SEQUENCE_NUMBER + ": " + value);
    }
  }

  /**
   * Encodes and decodes the four control octets of a {@link ControlField}.
   *
   * <p>Wire layout (octets numbered 1..4 in transmission order, all little-endian within the
   * field):
   *
   * <ul>
   *   <li><b>I-format</b>: octet 1 {@code = (N(S) << 1) & 0xFE} with bit 1 cleared to {@code 0};
   *       octet 2 {@code = (N(S) >> 7) & 0xFF}; octet 3 {@code = (N(R) << 1) & 0xFE}; octet 4
   *       {@code = (N(R) >> 7) & 0xFF}.
   *   <li><b>S-format</b>: octet 1 {@code = 0x01}; octet 2 {@code = 0x00}; octet 3 {@code = (N(R)
   *       << 1) & 0xFE}; octet 4 {@code = (N(R) >> 7) & 0xFF}.
   *   <li><b>U-format</b>: octet 1 {@code = function bits | 0x03} (STARTDT act {@code 0x07}, con
   *       {@code 0x0B}; STOPDT act {@code 0x13}, con {@code 0x23}; TESTFR act {@code 0x43}, con
   *       {@code 0x83}); octets 2..4 {@code = 0x00}.
   * </ul>
   *
   * <p>{@link #encode(ControlField, ByteBuf)} writes into a caller-owned buffer and never releases
   * it; {@link #decode(ByteBuf)} reads four octets from a caller-owned buffer and never releases
   * it.
   */
  static final class Serde {

    /** First-control-octet value identifying the STARTDT-activation U-format function. */
    private static final int U_STARTDT_ACT = 0x07;

    /** First-control-octet value identifying the STARTDT-confirmation U-format function. */
    private static final int U_STARTDT_CON = 0x0B;

    /** First-control-octet value identifying the STOPDT-activation U-format function. */
    private static final int U_STOPDT_ACT = 0x13;

    /** First-control-octet value identifying the STOPDT-confirmation U-format function. */
    private static final int U_STOPDT_CON = 0x23;

    /** First-control-octet value identifying the TESTFR-activation U-format function. */
    private static final int U_TESTFR_ACT = 0x43;

    /** First-control-octet value identifying the TESTFR-confirmation U-format function. */
    private static final int U_TESTFR_CON = 0x83;

    private Serde() {}

    /**
     * Encodes a control field as four octets into {@code buffer}.
     *
     * @param control the control field to encode.
     * @param buffer the caller-owned destination buffer; four octets are appended and the buffer is
     *     not released.
     */
    public static void encode(ControlField control, ByteBuf buffer) {
      if (control instanceof TypeI i) {
        int ns = i.sendSequenceNumber();
        int nr = i.receiveSequenceNumber();
        buffer.writeByte((ns << 1) & 0xFE);
        buffer.writeByte((ns >> 7) & 0xFF);
        buffer.writeByte((nr << 1) & 0xFE);
        buffer.writeByte((nr >> 7) & 0xFF);
      } else if (control instanceof TypeS s) {
        int nr = s.receiveSequenceNumber();
        buffer.writeByte(0x01);
        buffer.writeByte(0x00);
        buffer.writeByte((nr << 1) & 0xFE);
        buffer.writeByte((nr >> 7) & 0xFF);
      } else if (control instanceof TypeU u) {
        buffer.writeByte(octet1For(u.function()));
        buffer.writeByte(0x00);
        buffer.writeByte(0x00);
        buffer.writeByte(0x00);
      } else {
        // Unreachable: ControlField is sealed over the three handled subtypes.
        throw new IllegalArgumentException(
            "unknown control field type: " + control.getClass().getName());
      }
    }

    /**
     * Decodes four control octets from {@code buffer} into a {@link ControlField}.
     *
     * <p>The format is selected from the two low bits of the first octet: bit 1 {@code = 0} yields
     * {@link TypeI}; bit 1 {@code = 1} with bit 2 {@code = 0} yields {@link TypeS}; bit 1 {@code =
     * 1} with bit 2 {@code = 1} yields {@link TypeU}.
     *
     * @param buffer the caller-owned source buffer, positioned at the first control octet; four
     *     octets are consumed and the buffer is not released.
     * @return the decoded control field.
     * @throws AsduDecodeException if fewer than four octets are readable or the first octet encodes
     *     an unrecognized U-format function.
     */
    public static ControlField decode(ByteBuf buffer) {
      if (buffer.readableBytes() < 4) {
        throw new AsduDecodeException(
            "control field requires 4 octets but only " + buffer.readableBytes() + " are readable");
      }
      int octet1 = buffer.readUnsignedByte();
      int octet2 = buffer.readUnsignedByte();
      int octet3 = buffer.readUnsignedByte();
      int octet4 = buffer.readUnsignedByte();

      if ((octet1 & 0x01) == 0x00) {
        int ns = ((octet1 & 0xFE) >> 1) | (octet2 << 7);
        int nr = ((octet3 & 0xFE) >> 1) | (octet4 << 7);
        return new TypeI(ns, nr);
      } else if ((octet1 & 0x03) == 0x01) {
        int nr = ((octet3 & 0xFE) >> 1) | (octet4 << 7);
        return new TypeS(nr);
      } else {
        return new TypeU(functionFor(octet1));
      }
    }

    /**
     * Maps an unnumbered control function to its first-control-octet value.
     *
     * @param function the control function.
     * @return the first-control-octet value.
     */
    private static int octet1For(UFunction function) {
      return switch (function) {
        case STARTDT_ACT -> U_STARTDT_ACT;
        case STARTDT_CON -> U_STARTDT_CON;
        case STOPDT_ACT -> U_STOPDT_ACT;
        case STOPDT_CON -> U_STOPDT_CON;
        case TESTFR_ACT -> U_TESTFR_ACT;
        case TESTFR_CON -> U_TESTFR_CON;
      };
    }

    /**
     * Maps a first-control-octet value to its unnumbered control function.
     *
     * @param octet1 the first control octet of a U-format control field.
     * @return the corresponding control function.
     * @throws AsduDecodeException if {@code octet1} does not encode a defined U-format function.
     */
    private static UFunction functionFor(int octet1) {
      return switch (octet1) {
        case U_STARTDT_ACT -> UFunction.STARTDT_ACT;
        case U_STARTDT_CON -> UFunction.STARTDT_CON;
        case U_STOPDT_ACT -> UFunction.STOPDT_ACT;
        case U_STOPDT_CON -> UFunction.STOPDT_CON;
        case U_TESTFR_ACT -> UFunction.TESTFR_ACT;
        case U_TESTFR_CON -> UFunction.TESTFR_CON;
        default ->
            throw new AsduDecodeException(
                String.format("invalid U-format control octet: 0x%02X", octet1));
      };
    }
  }
}
