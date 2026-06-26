package com.digitalpetri.iec60870.cs101;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.digitalpetri.iec60870.AsduDecodeException;
import com.digitalpetri.iec60870.ProtocolProfile;
import com.digitalpetri.iec60870.address.CommonAddress;
import com.digitalpetri.iec60870.address.InformationObjectAddress;
import com.digitalpetri.iec60870.address.OriginatorAddress;
import com.digitalpetri.iec60870.asdu.Asdu;
import com.digitalpetri.iec60870.asdu.AsduType;
import com.digitalpetri.iec60870.asdu.Cause;
import com.digitalpetri.iec60870.asdu.InformationObject;
import com.digitalpetri.iec60870.asdu.element.Qds;
import com.digitalpetri.iec60870.asdu.object.SinglePointInformation;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.Unpooled;
import io.netty.buffer.UnpooledByteBufAllocator;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link Ft12Framer}, the {@link Ft12Frame}&lt;-&gt;{@link ByteBuf} whole-frame
 * bridge.
 *
 * <p>The expected wire bytes are derived independently from the frozen FT1.2 codec specification
 * (start octets {@code 0x68}/{@code 0x10}, the doubled length octet, the little-endian link
 * address, the arithmetic-sum-modulo-256 checksum, and the {@code 0x16} end octet) rather than
 * copied from the production encoder, so a framing bug is caught. The embedded ASDU bytes are
 * likewise hand-derived from the {@code M_SP_NA_1} encoding under {@link ProtocolProfile}{@code (1,
 * 1, 2, 255)}.
 */
class Ft12FramerTest {

  /** CS101-style profile: 1-octet COT, 1-octet common address, 2-octet IOA. */
  private static final ProtocolProfile PROFILE = new ProtocolProfile(1, 1, 2, 255);

  private static final ByteBufAllocator ALLOC = UnpooledByteBufAllocator.DEFAULT;

  /**
   * Builds the reference ASDU used throughout: {@code M_SP_NA_1}, SQ=0 with one object, cause
   * SPONTANEOUS, common address 1, IOA 100, single-point ON with clear quality.
   *
   * <p>Under {@code ProtocolProfile(1, 1, 2, 255)} this encodes to the seven octets {@code 01 01 03
   * 01 64 00 01}: type(0x01), VSQ(0x01), COT(0x03), CA(0x01), IOA little-endian(0x64 0x00),
   * SIQ(0x01).
   *
   * @return the reference single-point ASDU.
   */
  private static Asdu singlePointAsdu() {
    InformationObject object =
        new SinglePointInformation(
            InformationObjectAddress.of(100), true, new Qds(false, false, false, false, false));
    return new Asdu(
        AsduType.M_SP_NA_1,
        false,
        Cause.SPONTANEOUS,
        false,
        false,
        OriginatorAddress.none(),
        CommonAddress.of(1),
        List.of(object));
  }

  /**
   * Independently encodes {@code asdu} via {@link Asdu.Serde} for byte-length / identity checks.
   */
  private static byte[] serdeBytes(Asdu asdu) {
    ByteBuf buffer = Unpooled.buffer();
    try {
      Asdu.Serde.encode(asdu, PROFILE, buffer);
      return toBytes(buffer);
    } finally {
      buffer.release();
    }
  }

  /** Copies all readable bytes of {@code buffer} into a fresh array without releasing it. */
  private static byte[] toBytes(ByteBuf buffer) {
    byte[] bytes = new byte[buffer.readableBytes()];
    buffer.getBytes(buffer.readerIndex(), bytes);
    return bytes;
  }

  /** A representative link address for the given little-endian address width. */
  private static int addressFor(int linkAddressLength) {
    return switch (linkAddressLength) {
      case 0 -> 0; // balanced: address field absent, must be zero.
      case 1 -> 0x2A; // 42.
      case 2 -> 0xABCD;
      default -> throw new IllegalArgumentException("linkAddressLength: " + linkAddressLength);
    };
  }

  // --- exact byte layout: fixed frame ------------------------------------------------------------

  @Test
  void encodesFixedFrameToExactBytes() {
    // control = primary(dir=false, fcb=false, fcv=false, fc=9): PRM(0x40)|fc9(0x09) = 0x49.
    // linkAddress = 1, linkAddressLength = 1 -> userData = {0x49, 0x01}.
    // checksum = (0x49 + 0x01) & 0xFF = 0x4A; frame = 0x10 | userData | CS | END.
    Ft12Frame frame =
        new Ft12Frame.FixedLength(LinkControlField.primary(false, false, false, 9), 1);

    ByteBuf encoded = Ft12Framer.encode(frame, PROFILE, 1, ALLOC);
    try {
      byte[] expected = {0x10, 0x49, 0x01, 0x4A, 0x16};
      assertArrayEquals(expected, toBytes(encoded));
      // Total fixed-frame size is 4 + linkAddressLength.
      assertEquals(4 + 1, encoded.readableBytes());
    } finally {
      encoded.release();
    }
  }

  // --- exact byte layout: variable frame ---------------------------------------------------------

  @Test
  void encodesVariableFrameToExactBytes() {
    // control = primary(dir=false, fcb=true, fcv=true, fc=3): PRM|FCB|FCV|fc3 = 0x73.
    // linkAddress = 1 (1 octet), asdu = 01 01 03 01 64 00 01 (7 octets).
    // userData = 73 | 01 | 01 01 03 01 64 00 01  -> L = 1 + 1 + 7 = 9 (0x09), sent twice.
    // checksum = (0x73+0x01+0x01+0x01+0x03+0x01+0x64+0x00+0x01) & 0xFF = 223 = 0xDF.
    // frame = 0x68 | L | L | 0x68 | userData | CS | 0x16.
    Ft12Frame frame =
        new Ft12Frame.Variable(
            LinkControlField.primary(false, true, true, 3), 1, singlePointAsdu());

    ByteBuf encoded = Ft12Framer.encode(frame, PROFILE, 1, ALLOC);
    try {
      byte[] expected = {
        0x68,
        0x09,
        0x09,
        0x68,
        0x73,
        0x01,
        0x01,
        0x01,
        0x03,
        0x01,
        0x64,
        0x00,
        0x01,
        (byte) 0xDF,
        0x16
      };
      assertArrayEquals(expected, toBytes(encoded));

      // Independent structural checks on the framing octets.
      assertEquals(0x68, encoded.getUnsignedByte(0), "first start octet");
      assertEquals(encoded.getUnsignedByte(1), encoded.getUnsignedByte(2), "L1 must equal L2");
      assertEquals(0x68, encoded.getUnsignedByte(3), "repeated start octet");
      assertEquals(0x16, encoded.getUnsignedByte(encoded.readableBytes() - 1), "end octet");
    } finally {
      encoded.release();
    }
  }

  @Test
  void variableLengthCountsUserDataAndImpliesAsduLength() {
    int linkAddressLength = 1;
    Asdu asdu = singlePointAsdu();
    int asduLen = serdeBytes(asdu).length; // independent ASDU length.

    Ft12Frame frame =
        new Ft12Frame.Variable(LinkControlField.primary(false, true, true, 3), 1, asdu);
    ByteBuf encoded = Ft12Framer.encode(frame, PROFILE, linkAddressLength, ALLOC);
    try {
      int l = encoded.getUnsignedByte(1); // declared user-data length.
      // L counts control(1) + link address(linkAddressLength) + asdu.
      assertEquals(1 + linkAddressLength + asduLen, l);
      // The ASDU length is implied: asduLen == L - 1 - linkAddressLength.
      assertEquals(asduLen, l - 1 - linkAddressLength);
    } finally {
      encoded.release();
    }
  }

  // --- round trips across all three shapes and address widths ------------------------------------

  @Test
  void roundTripsSingleCharForEachAddressWidth() {
    for (int len = 0; len <= 2; len++) {
      assertRoundTrip(new Ft12Frame.SingleChar(), len);
    }
  }

  @Test
  void roundTripsFixedFrameForEachAddressWidth() {
    for (int len = 0; len <= 2; len++) {
      Ft12Frame frame =
          new Ft12Frame.FixedLength(
              LinkControlField.primary(false, true, false, 0), addressFor(len));
      assertRoundTrip(frame, len);
    }
  }

  @Test
  void roundTripsVariableFrameForEachAddressWidth() {
    for (int len = 0; len <= 2; len++) {
      Ft12Frame frame =
          new Ft12Frame.Variable(
              LinkControlField.primary(false, true, true, 3), addressFor(len), singlePointAsdu());
      assertRoundTrip(frame, len);
    }
  }

  /**
   * Encodes {@code frame}, decodes the produced buffer, and asserts equality + full consumption.
   */
  private static void assertRoundTrip(Ft12Frame frame, int linkAddressLength) {
    ByteBuf encoded = Ft12Framer.encode(frame, PROFILE, linkAddressLength, ALLOC);
    try {
      Ft12Frame decoded = Ft12Framer.decode(PROFILE, linkAddressLength, encoded);
      assertEquals(frame, decoded, "round trip with linkAddressLength=" + linkAddressLength);
      assertEquals(0, encoded.readableBytes(), "decode should consume the whole frame");
    } finally {
      encoded.release();
    }
  }

  @Test
  void encodesSingleCharAsTheSingleAckOctet() {
    ByteBuf encoded = Ft12Framer.encode(new Ft12Frame.SingleChar(), PROFILE, 1, ALLOC);
    try {
      assertArrayEquals(new byte[] {(byte) 0xE5}, toBytes(encoded));
    } finally {
      encoded.release();
    }
  }

  // --- decode of hand-built frames ---------------------------------------------------------------

  @Test
  void decodesHandBuiltVariableFrame() {
    byte[] wire = {
      0x68,
      0x09,
      0x09,
      0x68,
      0x73,
      0x01,
      0x01,
      0x01,
      0x03,
      0x01,
      0x64,
      0x00,
      0x01,
      (byte) 0xDF,
      0x16
    };
    ByteBuf buffer = Unpooled.wrappedBuffer(wire);
    try {
      Ft12Frame.Variable decoded =
          assertInstanceOf(Ft12Frame.Variable.class, Ft12Framer.decode(PROFILE, 1, buffer));
      assertEquals(LinkControlField.primary(false, true, true, 3), decoded.control());
      assertEquals(1, decoded.linkAddress());
      assertEquals(singlePointAsdu(), decoded.asdu());
    } finally {
      buffer.release();
    }
  }

  @Test
  void decodesLittleEndianTwoOctetAddress() {
    // Fixed frame, 2-octet address 0xABCD written low byte first: 0xCD 0xAB.
    // control = 0x49, userData = {0x49, 0xCD, 0xAB}, CS = (0x49+0xCD+0xAB)&0xFF = 0x1C1 & 0xFF =
    // 0xC1.
    byte[] wire = {0x10, 0x49, (byte) 0xCD, (byte) 0xAB, (byte) 0xC1, 0x16};
    ByteBuf buffer = Unpooled.wrappedBuffer(wire);
    try {
      Ft12Frame.FixedLength decoded =
          assertInstanceOf(Ft12Frame.FixedLength.class, Ft12Framer.decode(PROFILE, 2, buffer));
      assertEquals(0xABCD, decoded.linkAddress());
    } finally {
      buffer.release();
    }
  }

  // --- malformed frames are rejected -------------------------------------------------------------

  @Test
  void decodeRejectsBadStartOctet() {
    assertDecodeRejects(new byte[] {0x00, 0x49, 0x01, 0x4A, 0x16}, 1);
  }

  @Test
  void decodeRejectsMismatchedLengthOctets() {
    // Variable frame header with L1=9, L2=8.
    assertDecodeRejects(new byte[] {0x68, 0x09, 0x08, 0x68}, 1);
  }

  @Test
  void decodeRejectsWrongChecksum() {
    // Valid fixed frame whose checksum octet is corrupted (0x4A -> 0x4B).
    assertDecodeRejects(new byte[] {0x10, 0x49, 0x01, 0x4B, 0x16}, 1);
  }

  @Test
  void decodeRejectsMissingFixedEndOctet() {
    // Valid fixed frame whose end octet is not 0x16.
    assertDecodeRejects(new byte[] {0x10, 0x49, 0x01, 0x4A, 0x17}, 1);
  }

  @Test
  void decodeRejectsMissingVariableEndOctet() {
    byte[] wire = {
      0x68,
      0x09,
      0x09,
      0x68,
      0x73,
      0x01,
      0x01,
      0x01,
      0x03,
      0x01,
      0x64,
      0x00,
      0x01,
      (byte) 0xDF,
      0x00 // end octet corrupted from 0x16.
    };
    assertDecodeRejects(wire, 1);
  }

  @Test
  void decodeRejectsReservedSingleCharacter() {
    assertDecodeRejects(new byte[] {(byte) 0xA2}, 1);
  }

  /**
   * Wraps {@code wire} and asserts {@link Ft12Framer#decode} throws {@link AsduDecodeException}.
   */
  private static void assertDecodeRejects(byte[] wire, int linkAddressLength) {
    ByteBuf buffer = Unpooled.wrappedBuffer(wire);
    try {
      assertThrows(
          AsduDecodeException.class, () -> Ft12Framer.decode(PROFILE, linkAddressLength, buffer));
    } finally {
      buffer.release();
    }
  }
}
