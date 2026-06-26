package com.digitalpetri.iec60870.transport.tcp;

import com.digitalpetri.iec60870.AsduDecodeException;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import java.util.List;

/**
 * Frames complete IEC 60870-5-101 FT1.2 frames off a TCP byte stream, emitting one whole-frame
 * {@link ByteBuf} per complete frame.
 *
 * <p>This is the FT1.2 (101-over-TCP) peer of {@link Iec104FrameDecoder}: it carries the 101 link
 * layer over the same Netty octet transport the 104 path uses, so the transport stays
 * profile-agnostic. FT1.2 defines three frame shapes, distinguished by their leading start octet:
 *
 * <ul>
 *   <li><b>Single character</b> — the lone octet {@code 0xE5} (positive acknowledgement) or the
 *       reserved {@code 0xA2}; the frame is exactly one octet.
 *   <li><b>Fixed length</b> — {@code 0x10 | control | linkAddress | CS | 0x16}; the frame occupies
 *       {@code 4 + linkAddressLength} octets (start, control, checksum, and end octets plus the
 *       configured link-address width).
 *   <li><b>Variable length</b> — {@code 0x68 | L | L | 0x68 | userData | CS | 0x16}; the user-data
 *       length {@code L} is sent twice for integrity, and the whole frame occupies {@code 6 + L}
 *       octets (the two start octets, the two length octets, the checksum, and the end octet plus
 *       the {@code L} user-data octets).
 * </ul>
 *
 * <p>This is a hand-written {@link ByteToMessageDecoder} mirroring {@link Iec104FrameDecoder}'s
 * partial-read discipline: the decoder peeks at the start octet (and, for a variable frame, the
 * doubled length octets) without consuming, returns without advancing the reader index when fewer
 * than a whole frame is readable, and only then slices a single full frame with {@link
 * ByteBuf#readRetainedSlice(int)}. The emitted slice carries its own {@code +1} reference count;
 * the downstream auto-releasing {@link InboundFrameHandler} releases it after forwarding the frame.
 *
 * <p>It is pure octet framing and does <b>not</b> parse the frame: it trusts the start octet and,
 * for a variable frame, the doubled length field to size each frame, exactly as {@link
 * Iec104FrameDecoder} trusts the single length octet. The link control field, link address,
 * checksum, second start octet, and end octet are validated above this SPI by {@code Ft12Framer}.
 * Two framing-level structural errors are unrecoverable for a byte stream and therefore raise an
 * {@link AsduDecodeException} (which Netty propagates through {@code exceptionCaught}, closing the
 * channel, mirroring the 104 decoder): an unrecognized start octet, and a variable frame whose two
 * length octets disagree.
 *
 * <p>The FT1.2 octet constants are inlined here rather than referenced from the protocol-layer
 * {@code cs101} types, so this decoder stays free of any {@code cs101} import.
 */
public class Ft12FrameDecoder extends ByteToMessageDecoder {

  /** The single-character positive-acknowledgement octet ({@code 0xE5}); a one-octet frame. */
  private static final int SINGLE_ACK = 0xE5;

  /** The reserved single-character octet ({@code 0xA2}); a one-octet frame, rejected above. */
  private static final int SINGLE_RESERVED = 0xA2;

  /** The start octet of a fixed-length frame ({@code 0x10}). */
  private static final int START_FIXED = 0x10;

  /** The variable-length frame start octet, repeated as the second start ({@code 0x68}). */
  private static final int START_VARIABLE = 0x68;

  /**
   * The fixed-length frame overhead around the link address ({@code 4}): the start octet, the
   * one-octet control field, the checksum octet, and the end octet.
   */
  private static final int FIXED_FRAME_OVERHEAD = 4;

  /**
   * The variable-length frame overhead around the {@code L} user-data octets ({@code 6}): the two
   * start octets, the two length octets, the checksum octet, and the end octet.
   */
  private static final int VARIABLE_FRAME_OVERHEAD = 6;

  /**
   * The number of leading octets needed to size a variable-length frame ({@code 3}): the start
   * octet and the two doubled length octets.
   */
  private static final int VARIABLE_LENGTH_HEADER = 3;

  /** The FT1.2 link-address width in octets, used to size fixed-length frames. */
  private final int linkAddressLength;

  /**
   * Creates a decoder that sizes fixed-length frames using the given link-address width.
   *
   * @param linkAddressLength the FT1.2 link-address width in octets (in the range {@code 0..2}),
   *     used to size fixed-length frames.
   */
  public Ft12FrameDecoder(int linkAddressLength) {
    this.linkAddressLength = linkAddressLength;
  }

  /**
   * Frames as many complete FT1.2 frames as are fully buffered in {@code in}, emitting one
   * whole-frame {@link ByteBuf} for each.
   *
   * <p>The method consumes the accumulation buffer one full frame at a time. It returns without
   * consuming any octets when a complete frame is not yet available, allowing Netty to accumulate
   * more inbound data before invoking the decoder again.
   *
   * @param ctx the channel handler context.
   * @param in the cumulative inbound buffer positioned at the next start octet.
   * @param out the list to which whole-frame {@link ByteBuf}s are added.
   * @throws AsduDecodeException if the start octet is not one of {@code 0xE5}, {@code 0xA2}, {@code
   *     0x10}, or {@code 0x68}, or if a variable frame's two length octets disagree.
   */
  @Override
  protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
    if (!in.isReadable()) {
      return;
    }

    int startIndex = in.readerIndex();

    // Peek the start octet without consuming it; a bad start cannot be recovered from in a byte
    // stream.
    int start = in.getUnsignedByte(startIndex);

    int frameLength;
    if (start == SINGLE_ACK || start == SINGLE_RESERVED) {
      // Single-character frame: exactly one octet.
      frameLength = 1;
    } else if (start == START_FIXED) {
      // Fixed-length frame: start, control, link address, checksum, end.
      frameLength = FIXED_FRAME_OVERHEAD + linkAddressLength;
    } else if (start == START_VARIABLE) {
      // Variable-length frame: need the start and the two doubled length octets to size it.
      if (in.readableBytes() < VARIABLE_LENGTH_HEADER) {
        return;
      }
      int length1 = in.getUnsignedByte(startIndex + 1);
      int length2 = in.getUnsignedByte(startIndex + 2);
      if (length1 != length2) {
        throw new AsduDecodeException(
            String.format(
                "FT1.2 variable-frame length octets differ: L1=%d L2=%d", length1, length2));
      }
      frameLength = VARIABLE_FRAME_OVERHEAD + length1;
    } else {
      throw new AsduDecodeException(
          String.format(
              "invalid FT1.2 start octet: 0x%02X (expected 0xE5, 0xA2, 0x10, or 0x68)", start));
    }

    // Wait for the whole frame before consuming anything (partial-read handling).
    if (in.readableBytes() < frameLength) {
      return;
    }

    // Slice exactly one full frame and emit it. readRetainedSlice advances the reader index by
    // frameLength, consumes the frame from the accumulation buffer, and returns a slice with its
    // own +1 reference count, independent of the cumulative buffer. The downstream auto-releasing
    // InboundFrameHandler releases that reference after forwarding the frame to the listener,
    // balancing the retain here.
    out.add(in.readRetainedSlice(frameLength));
  }
}
