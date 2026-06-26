package com.digitalpetri.iec60870.cs101;

import com.digitalpetri.iec60870.AsduDecodeException;
import com.digitalpetri.iec60870.ProtocolProfile;
import com.digitalpetri.iec60870.asdu.Asdu;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;

/**
 * Bridges {@link Ft12Frame} values to and from complete FT1.2 frame {@link ByteBuf}s.
 *
 * <p>This is the {@code Ft12Frame}&lt;-&gt;{@code ByteBuf} seam, the FT1.2 peer of the 104 {@code
 * ApduFramer}. It owns the start-octet, doubled-length, checksum, and end-octet handling around
 * {@link Asdu.Serde}, producing and parsing the three FT1.2 frame shapes:
 *
 * <ul>
 *   <li><b>Variable</b>: {@code 0x68 | L | L | 0x68 | <userData> | CS | 0x16}, where {@code
 *       userData = control(1) + linkAddress(linkAddressLength, little-endian) + asduBytes} and
 *       {@code L} is the user-data length (sent twice);
 *   <li><b>Fixed</b>: {@code 0x10 | <userData> | CS | 0x16}, where {@code userData = control(1) +
 *       linkAddress(linkAddressLength, little-endian)};
 *   <li><b>Single character</b>: the one octet {@code 0xE5}.
 * </ul>
 *
 * <p>The checksum {@code CS} is the 8-bit arithmetic sum (modulo 256) of the user-data octets, and
 * there is no length field for the ASDU: a variable frame's ASDU length is {@code L - 1 -
 * linkAddressLength}.
 *
 * <p>The framer performs no stream reassembly: {@link #decode(ProtocolProfile, int, ByteBuf)}
 * requires a buffer that already holds exactly one complete frame positioned at its start octet.
 * Length-based framing of a partial byte stream remains the transport's responsibility.
 */
public final class Ft12Framer {

  /**
   * Start octet of a variable-length frame, also repeated as the second start octet ({@code 0x68}).
   */
  private static final int START_VARIABLE = 0x68;

  /** Start octet of a fixed-length frame ({@code 0x10}). */
  private static final int START_FIXED = 0x10;

  /** End octet that terminates a fixed- or variable-length frame ({@code 0x16}). */
  private static final int END = 0x16;

  /** The single-character positive acknowledgement octet ({@code 0xE5}). */
  private static final int SINGLE_ACK = 0xE5;

  /** The reserved single-character octet ({@code 0xA2}); recognized on decode but never emitted. */
  private static final int SINGLE_RESERVED_A2 = 0xA2;

  /** The maximum user-data length a variable-length frame can declare in its length octet. */
  private static final int MAX_USER_DATA_LENGTH = 255;

  private Ft12Framer() {}

  /**
   * Encodes {@code frame} into a freshly allocated, complete FT1.2 frame buffer.
   *
   * <p>Ownership of the returned buffer transfers to the caller, which must release it.
   *
   * @param frame the frame to encode.
   * @param profile the protocol profile that governs ASDU field widths (used only by
   *     variable-length frames).
   * @param linkAddressLength the number of little-endian link-address octets, in the range {@code
   *     0..2}; {@code 0} (balanced only) omits the address field entirely.
   * @param alloc the allocator used to obtain the destination buffer.
   * @return a newly allocated buffer holding the complete FT1.2 frame.
   * @throws IllegalArgumentException if {@code linkAddressLength} is outside {@code 0..2}, the link
   *     address is non-zero while {@code linkAddressLength} is {@code 0}, the link address does not
   *     fit in {@code linkAddressLength} octets, or a variable frame's user-data length exceeds
   *     {@code 255}.
   */
  public static ByteBuf encode(
      Ft12Frame frame, ProtocolProfile profile, int linkAddressLength, ByteBufAllocator alloc) {
    checkLinkAddressLength(linkAddressLength);

    ByteBuf buffer = alloc.buffer();
    try {
      if (frame instanceof Ft12Frame.SingleChar) {
        buffer.writeByte(SINGLE_ACK);
      } else if (frame instanceof Ft12Frame.FixedLength fixed) {
        encodeFixed(fixed, linkAddressLength, buffer);
      } else if (frame instanceof Ft12Frame.Variable variable) {
        encodeVariable(variable, profile, linkAddressLength, buffer);
      } else {
        // Unreachable: Ft12Frame is sealed over the three handled subtypes.
        throw new IllegalArgumentException(
            "unknown FT1.2 frame type: " + frame.getClass().getName());
      }
      return buffer;
    } catch (RuntimeException e) {
      buffer.release();
      throw e;
    }
  }

  /**
   * Decodes one complete FT1.2 frame from {@code wholeFrame}.
   *
   * <p>The buffer must be positioned at the start octet and contain exactly one whole frame. The
   * frame octets are consumed from the buffer; the buffer is not released.
   *
   * @param profile the protocol profile that governs ASDU field widths (used only by
   *     variable-length frames).
   * @param linkAddressLength the number of little-endian link-address octets, in the range {@code
   *     0..2}; {@code 0} (balanced only) means the address field is absent.
   * @param wholeFrame the caller-owned buffer holding one complete FT1.2 frame.
   * @return the decoded frame.
   * @throws IllegalArgumentException if {@code linkAddressLength} is outside {@code 0..2}.
   * @throws AsduDecodeException if the start octet is unrecognized, the reserved single character
   *     {@code 0xA2} is seen, the doubled length octets disagree, the buffer is too short for the
   *     declared frame, the checksum or end octet is wrong, or the embedded ASDU is malformed.
   */
  public static Ft12Frame decode(
      ProtocolProfile profile, int linkAddressLength, ByteBuf wholeFrame) {
    checkLinkAddressLength(linkAddressLength);

    if (!wholeFrame.isReadable()) {
      throw new AsduDecodeException("empty buffer: no FT1.2 start octet to read");
    }

    int start = wholeFrame.readUnsignedByte();
    return switch (start) {
      case SINGLE_ACK -> new Ft12Frame.SingleChar();
      case SINGLE_RESERVED_A2 ->
          throw new AsduDecodeException("reserved single character 0xA2 must not be used");
      case START_FIXED -> decodeFixed(linkAddressLength, wholeFrame);
      case START_VARIABLE -> decodeVariable(profile, linkAddressLength, wholeFrame);
      default ->
          throw new AsduDecodeException(String.format("invalid FT1.2 start octet: 0x%02X", start));
    };
  }

  /**
   * Encodes a fixed-length frame body (start octet, user data, checksum, end octet) into {@code
   * buffer}.
   *
   * @param fixed the fixed-length frame to encode.
   * @param linkAddressLength the number of little-endian link-address octets.
   * @param buffer the destination buffer.
   */
  private static void encodeFixed(
      Ft12Frame.FixedLength fixed, int linkAddressLength, ByteBuf buffer) {
    buffer.writeByte(START_FIXED);

    int userDataStart = buffer.writerIndex();
    buffer.writeByte(fixed.control().toOctet());
    writeLinkAddress(buffer, fixed.linkAddress(), linkAddressLength);
    int userDataEnd = buffer.writerIndex();

    buffer.writeByte(checksum(buffer, userDataStart, userDataEnd));
    buffer.writeByte(END);
  }

  /**
   * Encodes a variable-length frame body (start octets, back-patched doubled length, user data,
   * checksum, end octet) into {@code buffer}.
   *
   * @param variable the variable-length frame to encode.
   * @param profile the protocol profile that governs ASDU field widths.
   * @param linkAddressLength the number of little-endian link-address octets.
   * @param buffer the destination buffer.
   * @throws IllegalArgumentException if the resulting user-data length exceeds {@code 255}.
   */
  private static void encodeVariable(
      Ft12Frame.Variable variable, ProtocolProfile profile, int linkAddressLength, ByteBuf buffer) {
    buffer.writeByte(START_VARIABLE);

    int length1Index = buffer.writerIndex();
    buffer.writeByte(0); // L placeholder; back-patched below.
    int length2Index = buffer.writerIndex();
    buffer.writeByte(0); // L placeholder; back-patched below.

    buffer.writeByte(START_VARIABLE); // repeated start octet.

    int userDataStart = buffer.writerIndex();
    buffer.writeByte(variable.control().toOctet());
    writeLinkAddress(buffer, variable.linkAddress(), linkAddressLength);
    Asdu.Serde.encode(variable.asdu(), profile, buffer);
    int userDataEnd = buffer.writerIndex();

    int length = userDataEnd - userDataStart;
    if (length > MAX_USER_DATA_LENGTH) {
      throw new IllegalArgumentException(
          "FT1.2 user-data length " + length + " exceeds maximum " + MAX_USER_DATA_LENGTH);
    }
    buffer.setByte(length1Index, length);
    buffer.setByte(length2Index, length);

    buffer.writeByte(checksum(buffer, userDataStart, userDataEnd));
    buffer.writeByte(END);
  }

  /**
   * Decodes a fixed-length frame from {@code buffer}, which is positioned just past the start
   * octet.
   *
   * @param linkAddressLength the number of little-endian link-address octets.
   * @param buffer the source buffer.
   * @return the decoded fixed-length frame.
   * @throws AsduDecodeException if the buffer is too short or the checksum or end octet is wrong.
   */
  private static Ft12Frame.FixedLength decodeFixed(int linkAddressLength, ByteBuf buffer) {
    int needed = 1 + linkAddressLength + 2; // control + address + checksum + end.
    if (buffer.readableBytes() < needed) {
      throw new AsduDecodeException(
          "truncated fixed-length FT1.2 frame: need "
              + needed
              + " octets but only "
              + buffer.readableBytes()
              + " readable");
    }

    int userDataStart = buffer.readerIndex();
    int controlOctet = buffer.readUnsignedByte();
    int linkAddress = readLinkAddress(buffer, linkAddressLength);
    int userDataEnd = buffer.readerIndex();

    int cs = buffer.readUnsignedByte();
    int end = buffer.readUnsignedByte();
    verifyChecksumAndEnd(buffer, userDataStart, userDataEnd, cs, end);

    return new Ft12Frame.FixedLength(LinkControlField.fromOctet(controlOctet), linkAddress);
  }

  /**
   * Decodes a variable-length frame from {@code buffer}, which is positioned just past the first
   * start octet.
   *
   * @param profile the protocol profile that governs ASDU field widths.
   * @param linkAddressLength the number of little-endian link-address octets.
   * @param buffer the source buffer.
   * @return the decoded variable-length frame.
   * @throws AsduDecodeException if the doubled length octets disagree, the repeated start octet is
   *     wrong, the buffer is too short, the user-data length cannot hold the control field and link
   *     address, the checksum or end octet is wrong, or the embedded ASDU is malformed.
   */
  private static Ft12Frame.Variable decodeVariable(
      ProtocolProfile profile, int linkAddressLength, ByteBuf buffer) {
    if (buffer.readableBytes() < 3) {
      throw new AsduDecodeException(
          "truncated variable-length FT1.2 header: need 3 octets after the start octet but only "
              + buffer.readableBytes()
              + " readable");
    }

    int length1 = buffer.readUnsignedByte();
    int length2 = buffer.readUnsignedByte();
    if (length1 != length2) {
      throw new AsduDecodeException("FT1.2 length octets differ: L1=" + length1 + " L2=" + length2);
    }

    int secondStart = buffer.readUnsignedByte();
    if (secondStart != START_VARIABLE) {
      throw new AsduDecodeException(
          String.format("invalid second FT1.2 start octet: 0x%02X (expected 0x68)", secondStart));
    }

    int length = length1;
    if (buffer.readableBytes() < length + 2) {
      throw new AsduDecodeException(
          "truncated variable-length FT1.2 frame: declared user-data length "
              + length
              + " requires "
              + (length + 2)
              + " more octets but only "
              + buffer.readableBytes()
              + " readable");
    }

    int asduLength = length - 1 - linkAddressLength;
    if (asduLength < 0) {
      throw new AsduDecodeException(
          "FT1.2 user-data length "
              + length
              + " is too small for a 1-octet control field and a "
              + linkAddressLength
              + "-octet link address");
    }

    int userDataStart = buffer.readerIndex();
    int controlOctet = buffer.readUnsignedByte();
    int linkAddress = readLinkAddress(buffer, linkAddressLength);
    ByteBuf asduBytes = buffer.readSlice(asduLength);
    int userDataEnd = buffer.readerIndex();

    int cs = buffer.readUnsignedByte();
    int end = buffer.readUnsignedByte();
    verifyChecksumAndEnd(buffer, userDataStart, userDataEnd, cs, end);

    Asdu asdu = Asdu.Serde.decode(profile, asduBytes);

    return new Ft12Frame.Variable(LinkControlField.fromOctet(controlOctet), linkAddress, asdu);
  }

  /**
   * Writes a link address as {@code linkAddressLength} little-endian octets (low octet first).
   *
   * <p>This is a backstop against silently truncating an out-of-range address: a value that does
   * not fit in {@code linkAddressLength} octets is rejected rather than written as its low octets
   * only, which would mis-address the frame. Configuration-time validation in {@link LinkSettings}
   * should already prevent this.
   *
   * @param buffer the destination buffer.
   * @param linkAddress the link address; must be {@code 0} when {@code linkAddressLength} is {@code
   *     0}, and otherwise must fit in {@code linkAddressLength} octets.
   * @param linkAddressLength the number of octets to write, in the range {@code 0..2}.
   * @throws IllegalArgumentException if {@code linkAddressLength} is {@code 0} but {@code
   *     linkAddress} is non-zero, or if {@code linkAddress} does not fit in {@code
   *     linkAddressLength} octets.
   */
  private static void writeLinkAddress(ByteBuf buffer, int linkAddress, int linkAddressLength) {
    if (linkAddressLength == 0) {
      if (linkAddress != 0) {
        throw new IllegalArgumentException(
            "linkAddress must be 0 when linkAddressLength is 0: " + linkAddress);
      }
      return;
    }
    int maxAddress = (1 << (8 * linkAddressLength)) - 1;
    if (linkAddress < 0 || linkAddress > maxAddress) {
      throw new IllegalArgumentException(
          "linkAddress "
              + linkAddress
              + " does not fit in "
              + linkAddressLength
              + " octet(s) (0.."
              + maxAddress
              + ")");
    }
    for (int i = 0; i < linkAddressLength; i++) {
      buffer.writeByte((linkAddress >> (8 * i)) & 0xFF);
    }
  }

  /**
   * Reads a link address from {@code linkAddressLength} little-endian octets (low octet first).
   *
   * @param buffer the source buffer.
   * @param linkAddressLength the number of octets to read, in the range {@code 0..2}.
   * @return the decoded link address, or {@code 0} when {@code linkAddressLength} is {@code 0}.
   */
  private static int readLinkAddress(ByteBuf buffer, int linkAddressLength) {
    int address = 0;
    for (int i = 0; i < linkAddressLength; i++) {
      address |= buffer.readUnsignedByte() << (8 * i);
    }
    return address;
  }

  /**
   * Computes the FT1.2 checksum: the 8-bit arithmetic sum (modulo 256) of the buffer octets in the
   * half-open range {@code [startIndex, endIndex)}.
   *
   * @param buffer the buffer to read from (by absolute index, leaving the reader index unchanged).
   * @param startIndex the inclusive start index of the user data.
   * @param endIndex the exclusive end index of the user data.
   * @return the checksum octet in the range {@code 0..255}.
   */
  private static int checksum(ByteBuf buffer, int startIndex, int endIndex) {
    int sum = 0;
    for (int i = startIndex; i < endIndex; i++) {
      sum += buffer.getUnsignedByte(i);
    }
    return sum & 0xFF;
  }

  /**
   * Verifies the trailing checksum and end octet of a decoded frame.
   *
   * @param buffer the buffer holding the user data (read by absolute index).
   * @param userDataStart the inclusive start index of the user data.
   * @param userDataEnd the exclusive end index of the user data.
   * @param cs the checksum octet read from the wire.
   * @param end the end octet read from the wire.
   * @throws AsduDecodeException if the checksum does not match the user data or the end octet is
   *     not {@code 0x16}.
   */
  private static void verifyChecksumAndEnd(
      ByteBuf buffer, int userDataStart, int userDataEnd, int cs, int end) {
    int expected = checksum(buffer, userDataStart, userDataEnd);
    if (cs != expected) {
      throw new AsduDecodeException(
          String.format("FT1.2 checksum mismatch: got 0x%02X, expected 0x%02X", cs, expected));
    }
    if (end != END) {
      throw new AsduDecodeException(
          String.format("invalid FT1.2 end octet: 0x%02X (expected 0x16)", end));
    }
  }

  /**
   * Validates that a link-address length is one of {@code 0}, {@code 1}, or {@code 2}.
   *
   * @param linkAddressLength the candidate length.
   * @throws IllegalArgumentException if {@code linkAddressLength} is outside {@code 0..2}.
   */
  private static void checkLinkAddressLength(int linkAddressLength) {
    if (linkAddressLength < 0 || linkAddressLength > 2) {
      throw new IllegalArgumentException(
          "linkAddressLength must be in range 0..2: " + linkAddressLength);
    }
  }
}
