package com.digitalpetri.iec60870.transport.serial;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;

/**
 * Incremental, length-based FT1.2 deframer that turns a byte stream into whole frames.
 *
 * <p>Bytes arrive in arbitrary chunks through {@link #feed(ByteBuf)}; this deframer accumulates
 * them and, as soon as a complete frame is buffered, hands a fresh whole-frame {@link ByteBuf} to
 * the {@code onFrame} consumer supplied at construction. Framing is purely length-based — derived
 * from the FT1.2 start octet and (for variable frames) the doubled length field — and never relies
 * on inter-character idle timing.
 *
 * <p><b>Buffer ownership.</b> Each delivered frame is owned by the deframer: the consumer must read
 * it synchronously and must not retain or release it, because the deframer releases it as soon as
 * the consumer returns. This mirrors the transport-owns-and-releases contract of {@code
 * TransportListener.onFrame}. The {@code chunk} passed to {@link #feed(ByteBuf)} is left to the
 * caller to release; the deframer only reads from it.
 *
 * <p>This class is hardware-independent and holds no serial-port reference, so it can be
 * unit-tested without a serial port. It is not thread-safe: drive it from a single thread (the
 * transport's reader thread). When the owning connection closes, call {@link #reset()} to release
 * the internal accumulation buffer; any partially-buffered frame is discarded.
 */
class Ft12Deframer {

  private static final int START_VARIABLE = 0x68;
  private static final int START_FIXED = 0x10;
  private static final int SINGLE_ACK = 0xE5;
  private static final int SINGLE_RESERVED = 0xA2;

  /** Minimum bytes needed to read a variable frame's header: {@code 0x68 L1 L2 0x68}. */
  private static final int VARIABLE_HEADER_LENGTH = 4;

  /** Variable frame overhead around the {@code L} user-data octets: start, L, L, start, CS, END. */
  private static final int VARIABLE_FRAME_OVERHEAD = 6;

  private final int linkAddressLength;
  private final ByteBufAllocator alloc;
  private final Consumer<ByteBuf> onFrame;

  private @Nullable ByteBuf accumulation;

  /**
   * Creates a deframer.
   *
   * @param linkAddressLength the FT1.2 link-address width in octets (0 to 2), used to size
   *     fixed-length frames.
   * @param alloc the allocator used for the internal accumulation buffer and each emitted frame.
   * @param onFrame invoked with each complete frame; the frame is owned by the deframer and
   *     released once the consumer returns.
   */
  Ft12Deframer(int linkAddressLength, ByteBufAllocator alloc, Consumer<ByteBuf> onFrame) {
    this.linkAddressLength = linkAddressLength;
    this.alloc = alloc;
    this.onFrame = onFrame;
  }

  /**
   * Appends the readable bytes of {@code chunk} and emits every complete frame now buffered.
   *
   * <p>The caller retains ownership of {@code chunk}: this method only reads its bytes and does not
   * release it. Any trailing partial frame is kept for the next call.
   *
   * @param chunk freshly received bytes; consumed but not released.
   */
  void feed(ByteBuf chunk) {
    ByteBuf acc = accumulation;
    if (acc == null) {
      acc = alloc.buffer();
      accumulation = acc;
    }

    acc.writeBytes(chunk);
    extractFrames(acc);

    if (acc.isReadable()) {
      acc.discardReadBytes();
    } else {
      acc.clear();
    }
  }

  /** Releases the accumulation buffer and discards any partially-buffered frame. */
  void reset() {
    ByteBuf acc = accumulation;
    if (acc != null) {
      acc.release();
      accumulation = null;
    }
  }

  private void extractFrames(ByteBuf acc) {
    while (acc.isReadable()) {
      int start = acc.getUnsignedByte(acc.readerIndex());

      if (start == SINGLE_ACK || start == SINGLE_RESERVED) {
        // Single-character frame: deliver the lone octet. A 0xA2 is handed up as-is so the link
        // layer can recognize and reject the reserved single char.
        emit(acc, 1);
      } else if (start == START_FIXED) {
        int total = 4 + linkAddressLength;
        if (acc.readableBytes() < total) {
          return;
        }
        emit(acc, total);
      } else if (start == START_VARIABLE) {
        if (acc.readableBytes() < VARIABLE_HEADER_LENGTH) {
          return;
        }
        int readerIndex = acc.readerIndex();
        int l1 = acc.getUnsignedByte(readerIndex + 1);
        int l2 = acc.getUnsignedByte(readerIndex + 2);
        int secondStart = acc.getUnsignedByte(readerIndex + 3);
        if (l1 != l2 || secondStart != START_VARIABLE) {
          acc.skipBytes(1); // malformed header; resync on the next octet
          continue;
        }
        int total = VARIABLE_FRAME_OVERHEAD + l1;
        if (acc.readableBytes() < total) {
          return;
        }
        emit(acc, total);
      } else {
        acc.skipBytes(1); // unknown leading octet; resync on the next octet
      }
    }
  }

  private void emit(ByteBuf acc, int length) {
    ByteBuf frame = alloc.buffer(length);
    try {
      acc.readBytes(frame, length);
      onFrame.accept(frame);
    } finally {
      frame.release();
    }
  }
}
