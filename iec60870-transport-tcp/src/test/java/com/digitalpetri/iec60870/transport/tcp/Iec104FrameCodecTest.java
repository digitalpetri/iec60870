package com.digitalpetri.iec60870.transport.tcp;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
import com.digitalpetri.iec60870.cs104.Apdu;
import com.digitalpetri.iec60870.cs104.ControlField;
import com.digitalpetri.iec60870.cs104.UFunction;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * {@link EmbeddedChannel} tests for {@link Iec104FrameDecoder}'s octet framing.
 *
 * <p>The decoder is now pure framing: it emits a sliced whole-frame {@link ByteBuf} per complete
 * frame and does not parse an {@code Apdu} (that lives in {@code ApduFramer}, exercised by {@code
 * ApduFramerTest}). These tests therefore assert the <em>bytes</em> of the emitted frame, covering
 * partial reads (feeding bytes a few at a time and asserting a frame only emerges once it is fully
 * buffered), two frames buffered together, and malformed frames (bad START octet, impossible
 * length) handled without hanging.
 */
class Iec104FrameCodecTest {

  private static final ProtocolProfile PROFILE = ProtocolProfile.iec104Default();

  /** Encodes an APDU to its raw on-wire octets, the same bytes the decoder slices back out. */
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

  /**
   * Reads one emitted whole-frame {@link ByteBuf} off the channel, copies its bytes, releases it.
   */
  private static byte[] readFrameBytes(EmbeddedChannel channel) {
    ByteBuf frame = channel.readInbound();
    assertNotNull(frame, "expected one emitted whole-frame ByteBuf");
    try {
      byte[] bytes = new byte[frame.readableBytes()];
      frame.getBytes(frame.readerIndex(), bytes);
      return bytes;
    } finally {
      frame.release();
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

  // --- partial reads -------------------------------------------------------

  @Test
  void partialReadEmitsOnlyWhenComplete() {
    byte[] frame = wireBytes(iFrame());
    EmbeddedChannel channel = new EmbeddedChannel(new Iec104FrameDecoder());
    try {
      // Feed all but the last octet, one or two at a time: nothing should be emitted.
      int i = 0;
      for (; i < frame.length - 1; i += 2) {
        int end = Math.min(i + 2, frame.length - 1);
        channel.writeInbound(Unpooled.wrappedBuffer(frame, i, end - i));
        assertNull(channel.readInbound(), "no frame should emerge before it is complete");
      }

      // Feed the final octet: exactly one whole frame should now emerge, byte-identical.
      channel.writeInbound(Unpooled.wrappedBuffer(frame, frame.length - 1, 1));

      assertArrayEquals(frame, readFrameBytes(channel));
      assertNull(channel.readInbound());
    } finally {
      assertFalse(channel.finish());
    }
  }

  @Test
  void partialReadWithdrawnLengthOctet() {
    // Feeding only the START octet must not consume it or hang; the frame completes after both
    // the length octet and body arrive.
    byte[] frame = wireBytes(sFrame());
    EmbeddedChannel channel = new EmbeddedChannel(new Iec104FrameDecoder());
    try {
      channel.writeInbound(Unpooled.wrappedBuffer(frame, 0, 1));
      assertNull(channel.readInbound());

      channel.writeInbound(Unpooled.wrappedBuffer(frame, 1, frame.length - 1));
      assertArrayEquals(frame, readFrameBytes(channel));
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

    EmbeddedChannel channel = new EmbeddedChannel(new Iec104FrameDecoder());
    try {
      channel.writeInbound(Unpooled.wrappedBuffer(combined));

      assertArrayEquals(first, readFrameBytes(channel));
      assertArrayEquals(second, readFrameBytes(channel));
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
    EmbeddedChannel channel = new EmbeddedChannel(new Iec104FrameDecoder());
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
    EmbeddedChannel channel = new EmbeddedChannel(new Iec104FrameDecoder());
    try {
      assertFalse(channel.writeInbound(Unpooled.wrappedBuffer(truncated)));
      assertNull(channel.readInbound());
    } finally {
      channel.finishAndReleaseAll();
    }
  }
}
