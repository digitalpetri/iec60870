package com.digitalpetri.iec60870.transport.tcp;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

/**
 * {@link EmbeddedChannel} tests for {@link Ft12FrameDecoder}'s FT1.2 octet framing.
 *
 * <p>The decoder is pure framing: it emits one sliced whole-frame {@link ByteBuf} per complete
 * FT1.2 frame and does not parse the link control field, link address, or checksum (those live in
 * {@code Ft12Framer}). These tests therefore drive the decoder with hand-built FT1.2 byte streams
 * across the three frame shapes — variable ({@code 0x68 L L 0x68 ... CS 0x16}), fixed ({@code 0x10
 * ... CS 0x16}), and the single-character {@code 0xE5} — and assert the <em>bytes</em> of each
 * emitted frame. They also cover a frame split across two {@code writeInbound} calls (partial-read
 * handling, where no frame emerges until the last octet arrives), two frames buffered together, and
 * two framing-level structural errors the decoder treats as recoverable line corruption — an
 * unrecognized start octet and a variable frame whose doubled length octets disagree — discarding
 * the offending octet and resyncing on the next valid frame rather than failing the channel. Every
 * emitted buffer is released.
 */
class Ft12FrameDecoderTest {

  private static final int START_VARIABLE = 0x68;
  private static final int START_FIXED = 0x10;
  private static final int SINGLE_ACK = 0xE5;
  private static final int END = 0x16;

  /** Variable-frame overhead: {@code 0x68 L L 0x68 ... CS 0x16}. */
  private static final int VARIABLE_OVERHEAD = 6;

  /** Fixed-frame overhead: {@code 0x10 ... CS 0x16}. */
  private static final int FIXED_OVERHEAD = 3;

  // --- whole-frame emission, one shape per test -------------------------------------------------

  @Test
  void emitsVariableFrame() {
    // user data = control + 1-octet link address + a small ASDU body.
    byte[] frame = variableFrame(0x73, 0x01, 0x01, 0x06, 0x01, 0x05, 0x00, 0x64, 0x01);
    EmbeddedChannel channel = new EmbeddedChannel(new Ft12FrameDecoder(1));
    try {
      channel.writeInbound(Unpooled.wrappedBuffer(frame));

      assertArrayEquals(frame, readFrameBytes(channel));
      assertNull(channel.readInbound());
    } finally {
      assertFalse(channel.finish());
    }
  }

  @Test
  void emitsFixedFrameOneOctetAddress() {
    // user data = control + 1-octet link address; total frame length = 4 + 1 = 5.
    byte[] frame = fixedFrame(0x49, 0x01);
    EmbeddedChannel channel = new EmbeddedChannel(new Ft12FrameDecoder(1));
    try {
      channel.writeInbound(Unpooled.wrappedBuffer(frame));

      byte[] emitted = readFrameBytes(channel);
      assertEquals(5, emitted.length, "a 1-octet-address fixed frame is 5 octets");
      assertArrayEquals(frame, emitted);
      assertNull(channel.readInbound());
    } finally {
      assertFalse(channel.finish());
    }
  }

  @Test
  void emitsFixedFrameTwoOctetAddress() {
    // user data = control + 2-octet link address; total frame length = 4 + 2 = 6.
    byte[] frame = fixedFrame(0x49, 0x01, 0x00);
    EmbeddedChannel channel = new EmbeddedChannel(new Ft12FrameDecoder(2));
    try {
      channel.writeInbound(Unpooled.wrappedBuffer(frame));

      byte[] emitted = readFrameBytes(channel);
      assertEquals(6, emitted.length, "a 2-octet-address fixed frame is 6 octets");
      assertArrayEquals(frame, emitted);
      assertNull(channel.readInbound());
    } finally {
      assertFalse(channel.finish());
    }
  }

  @Test
  void emitsSingleCharacterAck() {
    EmbeddedChannel channel = new EmbeddedChannel(new Ft12FrameDecoder(1));
    try {
      channel.writeInbound(Unpooled.wrappedBuffer(new byte[] {(byte) SINGLE_ACK}));

      assertArrayEquals(new byte[] {(byte) SINGLE_ACK}, readFrameBytes(channel));
      assertNull(channel.readInbound());
    } finally {
      assertFalse(channel.finish());
    }
  }

  // --- partial reads ----------------------------------------------------------------------------

  @Test
  void variableFrameSplitAcrossTwoWritesEmitsOnceComplete() {
    byte[] frame = variableFrame(0x73, 0x0a, 0x01, 0x06, 0x01, 0x14, 0x00, 0x65, 0x00);
    EmbeddedChannel channel = new EmbeddedChannel(new Ft12FrameDecoder(1));
    try {
      // First write carries the 4-octet header plus part of the body: not yet a whole frame.
      int split = 6;
      channel.writeInbound(Unpooled.wrappedBuffer(frame, 0, split));
      assertNull(channel.readInbound(), "no frame should emerge before it is fully buffered");

      // Second write delivers the remainder; exactly one whole frame now emerges, byte-identical.
      channel.writeInbound(Unpooled.wrappedBuffer(frame, split, frame.length - split));
      assertArrayEquals(frame, readFrameBytes(channel));
      assertNull(channel.readInbound());
    } finally {
      assertFalse(channel.finish());
    }
  }

  @Test
  void partialVariableHeaderIsBufferedNotSized() {
    byte[] frame = variableFrame(0x73, 0x01, 0x01, 0x06, 0x01, 0x05, 0x00, 0x64, 0x01);
    EmbeddedChannel channel = new EmbeddedChannel(new Ft12FrameDecoder(1));
    try {
      // Only the start octet and one length octet: fewer than the 3 octets needed to size the
      // frame, so the decoder cannot yet determine its length and must emit nothing.
      channel.writeInbound(Unpooled.wrappedBuffer(frame, 0, 2));
      assertNull(channel.readInbound());

      channel.writeInbound(Unpooled.wrappedBuffer(frame, 2, frame.length - 2));
      assertArrayEquals(frame, readFrameBytes(channel));
      assertNull(channel.readInbound());
    } finally {
      assertFalse(channel.finish());
    }
  }

  // --- two frames in one buffer -----------------------------------------------------------------

  @Test
  void twoFramesInOneBufferEmitInOrder() {
    byte[] first = variableFrame(0x73, 0x01, 0x01, 0x06, 0x01, 0x05, 0x00, 0x64, 0x01);
    byte[] second = fixedFrame(0x49, 0x01);
    byte[] combined = concat(first, second);

    EmbeddedChannel channel = new EmbeddedChannel(new Ft12FrameDecoder(1));
    try {
      channel.writeInbound(Unpooled.wrappedBuffer(combined));

      assertArrayEquals(first, readFrameBytes(channel));
      assertArrayEquals(second, readFrameBytes(channel));
      assertNull(channel.readInbound());
    } finally {
      assertFalse(channel.finish());
    }
  }

  @Test
  void variableThenSingleCharacterEmitInOrder() {
    byte[] variable = variableFrame(0x73, 0x01, 0x01, 0x06, 0x01, 0x05, 0x00, 0x64, 0x01);
    byte[] single = {(byte) SINGLE_ACK};

    EmbeddedChannel channel = new EmbeddedChannel(new Ft12FrameDecoder(1));
    try {
      channel.writeInbound(Unpooled.wrappedBuffer(concat(variable, single)));

      assertArrayEquals(variable, readFrameBytes(channel));
      assertArrayEquals(single, readFrameBytes(channel));
      assertNull(channel.readInbound());
    } finally {
      assertFalse(channel.finish());
    }
  }

  // --- structural errors are resynced, not fatal ------------------------------------------------

  @Test
  void badStartOctetIsResyncedNotFatal() {
    // 0x00 is none of 0xE5/0xA2/0x10/0x68 and cannot start a frame. Rather than failing the
    // channel, the decoder discards the stray octet(s) and resyncs on the next valid frame, so a
    // line glitch costs one dropped frame, not the whole link.
    byte[] valid = fixedFrame(0x49, 0x01);
    byte[] stream = concat(new byte[] {0x00, 0x00}, valid);

    EmbeddedChannel channel = new EmbeddedChannel(new Ft12FrameDecoder(1));
    try {
      channel.writeInbound(Unpooled.wrappedBuffer(stream));

      assertArrayEquals(
          valid, readFrameBytes(channel), "the decoder resyncs and emits the next valid frame");
      assertNull(channel.readInbound());
    } finally {
      assertFalse(channel.finish());
    }
  }

  @Test
  void variableLengthOctetMismatchIsResyncedNotFatal() {
    // A variable frame whose two length octets disagree (L1=5, L2=6) is corrupt framing. The
    // decoder discards the leading start octet and resyncs on the following octets instead of
    // failing the channel; a valid frame that follows is still emitted.
    byte[] corrupt = {(byte) START_VARIABLE, 0x05, 0x06, (byte) START_VARIABLE};
    byte[] valid = fixedFrame(0x49, 0x01);
    byte[] stream = concat(corrupt, valid);

    EmbeddedChannel channel = new EmbeddedChannel(new Ft12FrameDecoder(1));
    try {
      channel.writeInbound(Unpooled.wrappedBuffer(stream));

      assertArrayEquals(
          valid, readFrameBytes(channel), "the decoder resyncs and emits the next valid frame");
      assertNull(channel.readInbound());
    } finally {
      assertFalse(channel.finish());
    }
  }

  // --- helpers ----------------------------------------------------------------------------------

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

  /**
   * Builds a raw variable-length FT1.2 frame {@code 0x68 L L 0x68 <userData> CS 0x16}, where {@code
   * CS} is the 8-bit arithmetic sum of the user-data octets.
   */
  private static byte[] variableFrame(int... userData) {
    int l = userData.length;
    byte[] out = new byte[VARIABLE_OVERHEAD + l];
    out[0] = (byte) START_VARIABLE;
    out[1] = (byte) l;
    out[2] = (byte) l;
    out[3] = (byte) START_VARIABLE;
    int sum = 0;
    for (int i = 0; i < l; i++) {
      out[4 + i] = (byte) userData[i];
      sum += userData[i] & 0xFF;
    }
    out[4 + l] = (byte) (sum & 0xFF);
    out[5 + l] = (byte) END;
    return out;
  }

  /**
   * Builds a raw fixed-length FT1.2 frame {@code 0x10 <userData> CS 0x16}, where {@code userData}
   * is the control octet followed by the link-address octets and {@code CS} is the 8-bit arithmetic
   * sum of the user-data octets.
   */
  private static byte[] fixedFrame(int... userData) {
    int n = userData.length;
    byte[] out = new byte[FIXED_OVERHEAD + n];
    out[0] = (byte) START_FIXED;
    int sum = 0;
    for (int i = 0; i < n; i++) {
      out[1 + i] = (byte) userData[i];
      sum += userData[i] & 0xFF;
    }
    out[1 + n] = (byte) (sum & 0xFF);
    out[2 + n] = (byte) END;
    return out;
  }

  private static byte[] concat(byte[] a, byte[] b) {
    byte[] out = new byte[a.length + b.length];
    System.arraycopy(a, 0, out, 0, a.length);
    System.arraycopy(b, 0, out, a.length, b.length);
    return out;
  }
}
