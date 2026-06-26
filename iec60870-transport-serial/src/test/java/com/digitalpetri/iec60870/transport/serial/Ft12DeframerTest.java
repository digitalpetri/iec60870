package com.digitalpetri.iec60870.transport.serial;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.UnpooledByteBufAllocator;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link Ft12Deframer}, the incremental length-based FT1.2 deframer.
 *
 * <p>Each test drives the deframer with hand-built raw FT1.2 byte streams (variable {@code 0x68 L L
 * 0x68 ... CS 0x16}, fixed {@code 0x10 ... CS 0x16}, and the {@code 0xE5} single character) and
 * verifies the emitted whole frames. Because the deframer releases each delivered frame as soon as
 * the consumer returns, the consumer copies the frame bytes out for later assertion.
 */
class Ft12DeframerTest {

  private static final int START_VARIABLE = 0x68;
  private static final int START_FIXED = 0x10;
  private static final int SINGLE_ACK = 0xE5;
  private static final int SINGLE_RESERVED = 0xA2;
  private static final int END = 0x16;

  private final ByteBufAllocator alloc = UnpooledByteBufAllocator.DEFAULT;
  private final List<byte[]> frames = new ArrayList<>();

  private @Nullable Ft12Deframer deframer;

  @AfterEach
  void releaseAccumulation() {
    if (deframer != null) {
      deframer.reset();
    }
  }

  private Ft12Deframer newDeframer(int linkAddressLength) {
    Ft12Deframer d =
        new Ft12Deframer(
            linkAddressLength,
            alloc,
            buf -> {
              byte[] copy = new byte[buf.readableBytes()];
              buf.getBytes(buf.readerIndex(), copy);
              frames.add(copy);
            });
    deframer = d;
    return d;
  }

  private void feed(byte[] bytes) {
    ByteBuf chunk = alloc.buffer(bytes.length);
    try {
      chunk.writeBytes(bytes);
      deframer.feed(chunk);
    } finally {
      chunk.release();
    }
  }

  @Test
  void emitsVariableFrame() {
    newDeframer(1);
    byte[] frame = variableFrame(0x73, 0x01, 0x01, 0x06, 0x01, 0x05, 0x00, 0x64, 0x01);

    feed(frame);

    assertEquals(1, frames.size());
    assertArrayEquals(frame, frames.get(0));
  }

  @Test
  void emitsFixedFrameOneOctetAddress() {
    newDeframer(1);
    byte[] frame = fixedFrame(0x49, 0x01); // total length 4 + 1 = 5

    feed(frame);

    assertEquals(1, frames.size());
    assertArrayEquals(frame, frames.get(0));
    assertEquals(5, frames.get(0).length);
  }

  @Test
  void emitsFixedFrameTwoOctetAddress() {
    newDeframer(2);
    byte[] frame = fixedFrame(0x49, 0x01, 0x00); // total length 4 + 2 = 6

    feed(frame);

    assertEquals(1, frames.size());
    assertArrayEquals(frame, frames.get(0));
    assertEquals(6, frames.get(0).length);
  }

  @Test
  void emitsSingleCharAck() {
    newDeframer(1);

    feed(new byte[] {(byte) SINGLE_ACK});

    assertEquals(1, frames.size());
    assertArrayEquals(new byte[] {(byte) SINGLE_ACK}, frames.get(0));
  }

  @Test
  void emitsReservedSingleCharForUpperLayerToReject() {
    newDeframer(1);

    feed(new byte[] {(byte) SINGLE_RESERVED});

    assertEquals(1, frames.size());
    assertArrayEquals(new byte[] {(byte) SINGLE_RESERVED}, frames.get(0));
  }

  @Test
  void reassemblesFrameDeliveredOneByteAtATime() {
    newDeframer(1);
    byte[] frame = variableFrame(0x73, 0x0a, 0x01, 0x06, 0x01, 0x14, 0x00, 0x65, 0x00);

    for (int i = 0; i < frame.length; i++) {
      feed(new byte[] {frame[i]});
      if (i < frame.length - 1) {
        assertEquals(0, frames.size(), "no frame should be emitted before the last byte");
      }
    }

    assertEquals(1, frames.size());
    assertArrayEquals(frame, frames.get(0));
  }

  @Test
  void reassemblesFrameDeliveredInTwoChunks() {
    newDeframer(1);
    byte[] frame = variableFrame(0x73, 0x01, 0x09, 0x06, 0x01, 0x0a, 0x00, 0x32, 0x01);

    int split = 5;
    feed(slice(frame, 0, split));
    assertEquals(0, frames.size());

    feed(slice(frame, split, frame.length));
    assertEquals(1, frames.size());
    assertArrayEquals(frame, frames.get(0));
  }

  @Test
  void emitsTwoFramesFromOneFeed() {
    newDeframer(1);
    byte[] first = variableFrame(0x73, 0x01, 0x01, 0x06, 0x01, 0x05, 0x00, 0x64, 0x01);
    byte[] second = fixedFrame(0x49, 0x01);

    feed(concat(first, second));

    assertEquals(2, frames.size());
    assertArrayEquals(first, frames.get(0));
    assertArrayEquals(second, frames.get(1));
  }

  @Test
  void emitsVariableThenSingleCharFromOneFeed() {
    newDeframer(1);
    byte[] variable = variableFrame(0x73, 0x01, 0x01, 0x06, 0x01, 0x05, 0x00, 0x64, 0x01);
    byte[] single = {(byte) SINGLE_ACK};

    feed(concat(variable, single));

    assertEquals(2, frames.size());
    assertArrayEquals(variable, frames.get(0));
    assertArrayEquals(single, frames.get(1));
  }

  @Test
  void resyncsPastLeadingGarbageByte() {
    newDeframer(1);
    byte[] frame = fixedFrame(0x49, 0x01);

    // A leading byte that is not any FT1.2 start octet must be discarded, then the real frame
    // found.
    feed(concat(new byte[] {(byte) 0xFF}, frame));

    assertEquals(1, frames.size());
    assertArrayEquals(frame, frames.get(0));
  }

  @Test
  void resyncsPastVariableHeaderWithMismatchedLength() {
    newDeframer(1);
    // 0x68 with L1 != L2 is a malformed variable header; the deframer discards one byte and
    // resyncs.
    byte[] malformedHeader = {(byte) START_VARIABLE, 0x05, 0x06, (byte) START_VARIABLE};
    byte[] frame = fixedFrame(0x49, 0x01);

    feed(concat(malformedHeader, frame));

    assertEquals(1, frames.size());
    assertArrayEquals(frame, frames.get(0));
  }

  @Test
  void leavesPartialVariableFrameBuffered() {
    newDeframer(1);
    byte[] frame = variableFrame(0x73, 0x01, 0x01, 0x06, 0x01, 0x05, 0x00, 0x64, 0x01);

    // Feed all but the final two bytes (CS, END): nothing should be emitted yet.
    feed(slice(frame, 0, frame.length - 2));
    assertEquals(0, frames.size());

    // Feed the remainder; now the complete frame is emitted.
    feed(slice(frame, frame.length - 2, frame.length));
    assertEquals(1, frames.size());
    assertArrayEquals(frame, frames.get(0));
  }

  @Test
  void leavesPartialVariableHeaderBuffered() {
    newDeframer(1);
    byte[] frame = variableFrame(0x73, 0x01, 0x01, 0x06, 0x01, 0x05, 0x00, 0x64, 0x01);

    // Fewer bytes than the 4-octet variable header: the deframer cannot yet size the frame.
    feed(new byte[] {(byte) START_VARIABLE, (byte) frame[1]});
    assertEquals(0, frames.size());

    feed(slice(frame, 2, frame.length));
    assertEquals(1, frames.size());
    assertArrayEquals(frame, frames.get(0));
  }

  // --- Frame builders --------------------------------------------------------------------------

  /**
   * Builds a raw variable-length FT1.2 frame {@code 0x68 L L 0x68 <userData> CS 0x16} where {@code
   * userData} is {@code control + linkAddress + asdu} and {@code CS} is the 8-bit arithmetic sum of
   * the user-data octets.
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
   * Builds a raw fixed-length FT1.2 frame {@code 0x10 <userData> CS 0x16} where {@code userData} is
   * {@code control + linkAddress} (so its length implies the configured link-address width).
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

  private static final int VARIABLE_OVERHEAD = 6; // 0x68 L L 0x68 ... CS 0x16
  private static final int FIXED_OVERHEAD = 3; // 0x10 ... CS 0x16

  private static byte[] slice(byte[] source, int from, int to) {
    byte[] out = new byte[to - from];
    System.arraycopy(source, from, out, 0, out.length);
    return out;
  }

  private static byte[] concat(byte[] a, byte[] b) {
    byte[] out = new byte[a.length + b.length];
    System.arraycopy(a, 0, out, 0, a.length);
    System.arraycopy(b, 0, out, a.length, b.length);
    return out;
  }
}
