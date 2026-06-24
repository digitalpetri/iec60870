package com.digitalpetri.iec60870.transport.tcp;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.digitalpetri.iec60870.ProtocolProfile;
import com.digitalpetri.iec60870.address.CommonAddress;
import com.digitalpetri.iec60870.address.InformationObjectAddress;
import com.digitalpetri.iec60870.address.OriginatorAddress;
import com.digitalpetri.iec60870.apci.Apdu;
import com.digitalpetri.iec60870.apci.ControlField;
import com.digitalpetri.iec60870.apci.UFunction;
import com.digitalpetri.iec60870.asdu.Asdu;
import com.digitalpetri.iec60870.asdu.AsduType;
import com.digitalpetri.iec60870.asdu.Cause;
import com.digitalpetri.iec60870.asdu.InformationObject;
import com.digitalpetri.iec60870.asdu.element.Qds;
import com.digitalpetri.iec60870.asdu.object.SinglePointInformation;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * {@link EmbeddedChannel} tests for {@link Iec104FrameDecoder} and {@link Iec104FrameEncoder}.
 *
 * <p>Covers round-tripping U-, S-, and I-format APDUs, partial reads (feeding bytes a few at a time
 * and asserting an {@link Apdu} only emerges once the whole frame is buffered), two frames buffered
 * together, and malformed frames (bad START octet, impossible length) handled without hanging.
 */
class Iec104FrameCodecTest {

  private static final ProtocolProfile PROFILE = ProtocolProfile.iec104Default();

  /** Encodes an APDU to its raw on-wire octets using the same Serde the encoder delegates to. */
  private static byte[] wireBytes(Apdu apdu) {
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

  /** Encodes via the encoder channel, feeds the produced bytes through the decoder channel. */
  private static void assertRoundTrip(Apdu apdu) {
    EmbeddedChannel encoderChannel = new EmbeddedChannel(new Iec104FrameEncoder(PROFILE));
    EmbeddedChannel decoderChannel = new EmbeddedChannel(new Iec104FrameDecoder(PROFILE));
    try {
      assertTrue(encoderChannel.writeOutbound(apdu));
      ByteBuf encoded = encoderChannel.readOutbound();

      byte[] onWire = new byte[encoded.readableBytes()];
      encoded.getBytes(encoded.readerIndex(), onWire);
      assertArrayEquals(wireBytes(apdu), onWire);

      assertTrue(decoderChannel.writeInbound(encoded));

      Apdu decoded = decoderChannel.readInbound();
      assertEquals(apdu, decoded);
      assertNull(decoderChannel.readInbound());
    } finally {
      assertFalse(encoderChannel.finish());
      assertFalse(decoderChannel.finish());
    }
  }

  // --- partial reads -------------------------------------------------------

  @Test
  void partialReadEmitsOnlyWhenComplete() {
    byte[] frame = wireBytes(iFrame());
    EmbeddedChannel channel = new EmbeddedChannel(new Iec104FrameDecoder(PROFILE));
    try {
      // Feed all but the last octet, one or two at a time: nothing should be emitted.
      int i = 0;
      for (; i < frame.length - 1; i += 2) {
        int end = Math.min(i + 2, frame.length - 1);
        channel.writeInbound(Unpooled.wrappedBuffer(frame, i, end - i));
        assertNull(channel.readInbound(), "no Apdu should emerge before the frame is complete");
      }

      // Feed the final octet: exactly one Apdu should now emerge.
      channel.writeInbound(Unpooled.wrappedBuffer(frame, frame.length - 1, 1));

      Apdu decoded = channel.readInbound();
      assertEquals(iFrame(), decoded);
      assertNull(channel.readInbound());
    } finally {
      assertFalse(channel.finish());
    }
  }

  @Test
  void partialReadWithdrawnLengthOctet() {
    // Feeding only the START octet must not consume it or hang; the frame completes once the
    // length octet and body arrive.
    byte[] frame = wireBytes(sFrame());
    EmbeddedChannel channel = new EmbeddedChannel(new Iec104FrameDecoder(PROFILE));
    try {
      channel.writeInbound(Unpooled.wrappedBuffer(frame, 0, 1));
      assertNull(channel.readInbound());

      channel.writeInbound(Unpooled.wrappedBuffer(frame, 1, frame.length - 1));
      assertEquals(sFrame(), channel.readInbound());
      assertNull(channel.readInbound());
    } finally {
      assertFalse(channel.finish());
    }
  }

  // --- two frames in one buffer -------------------------------------------

  @Test
  void twoFramesInOneBuffer() {
    byte[] first = wireBytes(uFrame());
    byte[] second = wireBytes(iFrame());
    byte[] combined = new byte[first.length + second.length];
    System.arraycopy(first, 0, combined, 0, first.length);
    System.arraycopy(second, 0, combined, first.length, second.length);

    EmbeddedChannel channel = new EmbeddedChannel(new Iec104FrameDecoder(PROFILE));
    try {
      assertTrue(channel.writeInbound(Unpooled.wrappedBuffer(combined)));

      assertEquals(uFrame(), channel.readInbound());
      assertEquals(iFrame(), channel.readInbound());
      assertNull(channel.readInbound());
    } finally {
      assertFalse(channel.finish());
    }
  }

  // --- malformed frames ----------------------------------------------------

  @Test
  void malformedBadStartOctet() {
    // A valid-length-looking frame whose START octet is wrong must raise without hanging.
    byte[] bad = {0x00, 0x04, 0x07, 0x00, 0x00, 0x00};
    EmbeddedChannel channel = new EmbeddedChannel(new Iec104FrameDecoder(PROFILE));
    assertThrows(
        Exception.class,
        () -> {
          channel.writeInbound(Unpooled.wrappedBuffer(bad));
          channel.finishAndReleaseAll();
        });
    assertNull(channel.readInbound());
  }

  @Test
  void impossibleLengthIsBufferedNotHung() {
    // A frame whose length octet exceeds the data present must not emit and must not consume,
    // simply waiting for more bytes (it will never hang the decoder).
    byte[] truncated = {(byte) 0x68, (byte) 0x20, 0x07, 0x00};
    EmbeddedChannel channel = new EmbeddedChannel(new Iec104FrameDecoder(PROFILE));
    try {
      assertFalse(channel.writeInbound(Unpooled.wrappedBuffer(truncated)));
      assertNull(channel.readInbound());
    } finally {
      channel.finishAndReleaseAll();
    }
  }

  @Test
  void malformedControlFieldRaises() {
    // START and length are valid, but the body declares an undefined U-format function (0xFF),
    // which the core control-field decoder rejects.
    byte[] bad = {(byte) 0x68, 0x04, (byte) 0xFF, 0x00, 0x00, 0x00};
    EmbeddedChannel channel = new EmbeddedChannel(new Iec104FrameDecoder(PROFILE));
    assertThrows(
        Exception.class,
        () -> {
          channel.writeInbound(Unpooled.wrappedBuffer(bad));
          channel.finishAndReleaseAll();
        });
    assertNull(channel.readInbound());
  }

  // --- encoder sanity ------------------------------------------------------

  @Test
  void encoderProducesStartAndLengthOctets() {
    EmbeddedChannel channel = new EmbeddedChannel(new Iec104FrameEncoder(PROFILE));
    try {
      assertTrue(channel.writeOutbound(uFrame()));
      ByteBuf out = channel.readOutbound();
      try {
        assertEquals(6, out.readableBytes());
        assertEquals(0x68, out.getUnsignedByte(out.readerIndex()));
        assertEquals(0x04, out.getUnsignedByte(out.readerIndex() + 1));
      } finally {
        out.release();
      }
    } finally {
      assertFalse(channel.finish());
    }
  }

  @Test
  void decodedIFrameRetainsControlAndAsdu() {
    EmbeddedChannel channel = new EmbeddedChannel(new Iec104FrameDecoder(PROFILE));
    try {
      channel.writeInbound(Unpooled.wrappedBuffer(wireBytes(iFrame())));
      Apdu decoded = channel.readInbound();
      assertInstanceOf(ControlField.TypeI.class, decoded.control());
      assertEquals(iFrame().asdu(), decoded.asdu());
    } finally {
      assertFalse(channel.finish());
    }
  }
}
