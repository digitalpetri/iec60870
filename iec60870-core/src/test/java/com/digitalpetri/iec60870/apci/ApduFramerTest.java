package com.digitalpetri.iec60870.apci;

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
 * Unit tests for {@link ApduFramer}, the {@link Apdu}&lt;-&gt;{@link ByteBuf} frame bridge.
 *
 * <p>Covers the {@code Apdu}&lt;-&gt;bytes round-trip coverage (U-, S-, and I-format) that is
 * removed from {@code Iec104FrameCodecTest} once the framing leaves the Netty codec, plus
 * byte-layout assertions (START octet, length octet) and decode of hand-built frames.
 */
class ApduFramerTest {

  private static final ProtocolProfile PROFILE = ProtocolProfile.iec104Default();
  private static final ByteBufAllocator ALLOC = UnpooledByteBufAllocator.DEFAULT;

  private static Apdu uFrame() {
    return new Apdu(new ControlField.TypeU(UFunction.STARTDT_ACT), null);
  }

  private static Apdu sFrame() {
    return new Apdu(new ControlField.TypeS(7), null);
  }

  private static Apdu iFrame() {
    InformationObject object =
        new SinglePointInformation(
            InformationObjectAddress.of(100), true, new Qds(false, false, false, false, false));
    Asdu asdu =
        new Asdu(
            AsduType.M_SP_NA_1,
            false,
            Cause.SPONTANEOUS,
            false,
            false,
            OriginatorAddress.none(),
            CommonAddress.of(1),
            List.of(object));
    return new Apdu(new ControlField.TypeI(0, 0), asdu);
  }

  /** Encodes an APDU to its raw on-wire octets via {@link Apdu.Serde} for byte-identity checks. */
  private static byte[] serdeBytes(Apdu apdu) {
    ByteBuf buffer = Unpooled.buffer();
    try {
      Apdu.Serde.encode(apdu, PROFILE, buffer);
      byte[] bytes = new byte[buffer.readableBytes()];
      buffer.getBytes(buffer.readerIndex(), bytes);
      return bytes;
    } finally {
      buffer.release();
    }
  }

  private static byte[] toBytes(ByteBuf buffer) {
    byte[] bytes = new byte[buffer.readableBytes()];
    buffer.getBytes(buffer.readerIndex(), bytes);
    return bytes;
  }

  // --- round trips ---------------------------------------------------------

  @Test
  void roundTripUFrame() {
    assertRoundTrip(uFrame());
  }

  @Test
  void roundTripSFrame() {
    assertRoundTrip(sFrame());
  }

  @Test
  void roundTripIFrame() {
    assertRoundTrip(iFrame());
  }

  /** Encodes via the framer, then decodes the produced frame back to an equal {@link Apdu}. */
  private static void assertRoundTrip(Apdu apdu) {
    ByteBuf frame = ApduFramer.encode(apdu, PROFILE, ALLOC);
    try {
      // Byte-identical to the Serde (and therefore the previous Iec104FrameEncoder) output.
      assertArrayEquals(serdeBytes(apdu), toBytes(frame));

      Apdu decoded = ApduFramer.decode(PROFILE, frame);
      assertEquals(apdu, decoded);
      assertEquals(0, frame.readableBytes(), "decode should consume the whole frame");
    } finally {
      frame.release();
    }
  }

  // --- byte layout ---------------------------------------------------------

  @Test
  void encodeStartsWithStartAndLengthOctets() {
    ByteBuf frame = ApduFramer.encode(uFrame(), PROFILE, ALLOC);
    try {
      // U-frame APDU: START + length(0x04) + four control octets = 6 octets total.
      assertEquals(6, frame.readableBytes());
      assertEquals(Apdu.START_OCTET, frame.getUnsignedByte(frame.readerIndex()));
      assertEquals(0x04, frame.getUnsignedByte(frame.readerIndex() + 1));
    } finally {
      frame.release();
    }
  }

  @Test
  void encodeLengthOctetCountsBodyOnly() {
    // The length octet counts the four control octets plus the ASDU body, excluding START+length.
    ByteBuf frame = ApduFramer.encode(iFrame(), PROFILE, ALLOC);
    try {
      int declaredLength = frame.getUnsignedByte(frame.readerIndex() + 1);
      assertEquals(frame.readableBytes() - 2, declaredLength);
    } finally {
      frame.release();
    }
  }

  // --- decode of hand-built frames -----------------------------------------

  @Test
  void decodeHandBuiltSFrame() {
    // 0x68, length=4, S-format control field with receive sequence number 7 (7 << 1 = 0x0E).
    byte[] wire = {(byte) 0x68, 0x04, 0x01, 0x00, 0x0E, 0x00};
    ByteBuf buffer = Unpooled.wrappedBuffer(wire);
    try {
      Apdu decoded = ApduFramer.decode(PROFILE, buffer);
      ControlField.TypeS control = assertInstanceOf(ControlField.TypeS.class, decoded.control());
      assertEquals(7, control.receiveSequenceNumber());
      assertEquals(null, decoded.asdu());
    } finally {
      buffer.release();
    }
  }

  @Test
  void decodeHandBuiltIFrameYieldsControlAndAsdu() {
    byte[] wire = serdeBytes(iFrame());
    ByteBuf buffer = Unpooled.wrappedBuffer(wire);
    try {
      Apdu decoded = ApduFramer.decode(PROFILE, buffer);
      assertInstanceOf(ControlField.TypeI.class, decoded.control());
      assertEquals(iFrame().asdu(), decoded.asdu());
    } finally {
      buffer.release();
    }
  }

  // --- malformed frames ----------------------------------------------------

  @Test
  void decodeRejectsBadStartOctet() {
    byte[] bad = {0x00, 0x04, 0x07, 0x00, 0x00, 0x00};
    ByteBuf buffer = Unpooled.wrappedBuffer(bad);
    try {
      assertThrows(AsduDecodeException.class, () -> ApduFramer.decode(PROFILE, buffer));
    } finally {
      buffer.release();
    }
  }

  @Test
  void decodeRejectsMalformedControlField() {
    // START and length valid, but 0xFF is not a defined U-format function.
    byte[] bad = {(byte) 0x68, 0x04, (byte) 0xFF, 0x00, 0x00, 0x00};
    ByteBuf buffer = Unpooled.wrappedBuffer(bad);
    try {
      assertThrows(AsduDecodeException.class, () -> ApduFramer.decode(PROFILE, buffer));
    } finally {
      buffer.release();
    }
  }
}
