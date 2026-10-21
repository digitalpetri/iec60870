package com.digitalpetri.iec60870.cs104;

import com.digitalpetri.iec60870.AsduDecodeException;
import com.digitalpetri.iec60870.ProtocolProfile;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;

/**
 * Bridges {@link Apdu} values to and from complete, length-delimited APDU frame {@link ByteBuf}s.
 *
 * <p>This is the {@code Apdu}&lt;-&gt;{@code ByteBuf} seam extracted from the transport's frame
 * encoder/decoder. It owns the START/length-octet handling around {@link Apdu.Serde}; the produced
 * bytes are therefore byte-identical to the previous {@code Iec104FrameEncoder} output, and {@link
 * #decode(ProtocolProfile, ByteBuf)} parses exactly one whole frame the way the previous {@code
 * Iec104FrameDecoder} did once it had sliced a complete frame off the wire.
 *
 * <p>The framer performs no stream reassembly: {@link #decode(ProtocolProfile, ByteBuf)} requires a
 * buffer that already holds one complete APDU frame (START octet, length octet, the four APCI
 * control octets, and — for I-format APDUs — the full ASDU body). Length-based framing of a partial
 * byte stream remains the transport's responsibility.
 */
public final class ApduFramer {

  private ApduFramer() {}

  /**
   * Encodes {@code apdu} into a freshly allocated, complete APDU frame buffer.
   *
   * <p>The returned buffer carries the START octet ({@code 0x68}), the length octet, the four APCI
   * control octets, and (for I-format APDUs) the ASDU body. Ownership of the returned buffer
   * transfers to the caller, which must release it.
   *
   * @param apdu the APDU to encode.
   * @param profile the protocol profile that governs ASDU field widths during encode.
   * @param alloc the allocator used to obtain the destination buffer.
   * @return a newly allocated buffer holding the complete length-delimited APDU frame.
   * @throws IllegalArgumentException if the resulting APDU length exceeds {@link
   *     Apdu#MAX_APDU_LENGTH}.
   */
  public static ByteBuf encode(Apdu apdu, ProtocolProfile profile, ByteBufAllocator alloc) {
    ByteBuf frame = alloc.buffer();
    try {
      Apdu.Serde.encode(apdu, profile, frame);
      return frame;
    } catch (RuntimeException e) {
      frame.release();
      throw e;
    }
  }

  /**
   * Decodes one complete APDU frame from {@code wholeFrame}.
   *
   * <p>The buffer must be positioned at the START octet and contain exactly one whole frame as
   * indicated by its length octet. The frame octets are consumed from the buffer; the buffer is not
   * released.
   *
   * @param profile the protocol profile that governs ASDU field widths during decode.
   * @param wholeFrame the caller-owned buffer holding one complete APDU frame.
   * @return the decoded APDU.
   * @throws AsduDecodeException if the START octet is not {@code 0x68}, the length octet is out of
   *     range, the buffer is too short for the declared length, or the control field or ASDU is
   *     malformed.
   */
  public static Apdu decode(ProtocolProfile profile, ByteBuf wholeFrame) {
    return Apdu.Serde.decode(profile, wholeFrame);
  }

  /**
   * Decodes the APCI of one complete APDU frame without decoding its ASDU.
   *
   * <p>This validates everything that keeps the byte stream and the APCI state trustworthy: the
   * START octet, the length octet, the control field, and the body length for the frame format.
   * After it returns, the reader index is at the first ASDU octet, and {@link Header#asduLength()}
   * octets of ASDU follow (none for S- and U-format frames). A caller can then decode the ASDU
   * separately and still process the control field when the ASDU is undecodable:
   *
   * <pre>{@code
   * ApduFramer.Header header = ApduFramer.decodeHeader(frame);
   * if (header.control() instanceof ControlField.TypeI) {
   *   ByteBuf asduOctets = frame.readSlice(header.asduLength());
   *   Asdu asdu = Asdu.Serde.decode(profile, asduOctets);
   * }
   * }</pre>
   *
   * @param wholeFrame the caller-owned buffer holding one complete APDU frame, positioned at the
   *     START octet; the buffer is not released.
   * @return the control field and the ASDU length.
   * @throws AsduDecodeException if the START octet is not {@code 0x68}, the length octet is out of
   *     range, the buffer is too short for the declared length, the control field is malformed, an
   *     I-format frame has an empty body, or an S- or U-format frame has a body.
   */
  public static Header decodeHeader(ByteBuf wholeFrame) {
    return Apdu.Serde.decodeHeader(wholeFrame);
  }

  /**
   * Encodes an I-format APDU frame around ASDU octets that bypass the typed encoder.
   *
   * <p>The octets are written as given, after the START octet, the length octet, and {@code
   * control}. Ownership of the returned buffer transfers to the caller, which must release it.
   *
   * @param control the I-format control field.
   * @param asdu the complete ASDU octets, starting at the type identification.
   * @param alloc the allocator used to obtain the destination buffer.
   * @return a newly allocated buffer holding the complete length-delimited APDU frame.
   * @throws IllegalArgumentException if {@code asdu} is empty or the resulting APDU length exceeds
   *     {@link Apdu#MAX_APDU_LENGTH}.
   */
  public static ByteBuf encode(ControlField.TypeI control, byte[] asdu, ByteBufAllocator alloc) {
    int length = Apdu.CONTROL_FIELD_LENGTH + asdu.length;
    if (asdu.length == 0) {
      throw new IllegalArgumentException("I-format APDU requires a non-empty ASDU");
    }
    if (length > Apdu.MAX_APDU_LENGTH) {
      throw new IllegalArgumentException(
          "APDU length " + length + " exceeds maximum " + Apdu.MAX_APDU_LENGTH);
    }
    ByteBuf frame = alloc.buffer(2 + length);
    frame.writeByte(Apdu.START_OCTET);
    frame.writeByte(length);
    ControlField.Serde.encode(control, frame);
    frame.writeBytes(asdu);
    return frame;
  }

  /**
   * The APCI of one APDU frame, as decoded by {@link #decodeHeader(ByteBuf)}.
   *
   * @param control the APCI control field.
   * @param asduLength the number of ASDU octets that follow the control field; positive for an
   *     I-format frame and zero otherwise.
   */
  public record Header(ControlField control, int asduLength) {}
}
