package com.digitalpetri.iec60870.transport.tcp;

import com.digitalpetri.iec60870.AsduDecodeException;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import java.util.List;

/**
 * Frames length-delimited IEC 60870-5-104 APDUs off the wire, emitting one whole-frame {@link
 * ByteBuf} per complete frame.
 *
 * <p>Every APDU on the wire is {@code [0x68][length][length octets]}: a fixed {@code 0x68} START
 * octet, a single length octet, and then exactly {@code length} body octets (the four APCI control
 * octets plus any ASDU body). A complete frame therefore occupies {@code 2 + length} octets.
 *
 * <p>This is a hand-written {@link ByteToMessageDecoder} rather than a {@link
 * io.netty.handler.codec.LengthFieldBasedFrameDecoder}. The hand-written form is preferred here
 * because it makes START-octet validation and partial-read handling explicit.
 *
 * <p>Partial reads are handled by leaving the accumulation buffer untouched until a whole frame is
 * available: the decoder peeks at the length octet without consuming, returns if fewer than {@code
 * 2 + length} octets are readable, and only then slices a single full frame. A bad START octet
 * (anything other than {@code 0x68}) is unrecoverable for a byte stream, so an {@link
 * AsduDecodeException} is raised; Netty propagates it through {@code exceptionCaught} and the
 * transport closes the channel.
 *
 * <p>This handler is pure octet framing: it does <b>not</b> parse the APDU. Each emitted frame is a
 * complete, length-delimited {@link ByteBuf} the protocol layer above decodes (via {@code
 * ApduFramer}). It carries no protocol state machine and is profile-agnostic; transports add their
 * own inbound handler downstream to forward each frame to the {@code TransportListener}.
 */
public class Iec104FrameDecoder extends ByteToMessageDecoder {

  /**
   * The fixed START octet ({@code 0x68}) that delimits the beginning of every APDU.
   *
   * <p>This is pure octet framing, so the constant is inlined here rather than referenced from the
   * protocol-layer {@code Apdu} type — this decoder stays free of any cs104 import.
   */
  private static final int START_OCTET = 0x68;

  /** The number of octets consumed by the START octet and the length octet ({@code 2}). */
  private static final int HEADER_LENGTH = 2;

  /**
   * Frames as many complete APDUs as are fully buffered in {@code in}, emitting one whole-frame
   * {@link ByteBuf} for each.
   *
   * <p>The method consumes the accumulation buffer one full frame at a time. It returns without
   * consuming any octets when a complete frame is not yet available, allowing Netty to accumulate
   * more inbound data before invoking the decoder again.
   *
   * @param ctx the channel handler context.
   * @param in the cumulative inbound buffer positioned at the next START octet.
   * @param out the list to which whole-frame {@link ByteBuf}s are added.
   * @throws AsduDecodeException if the START octet is not {@code 0x68}.
   */
  @Override
  protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
    // Need at least the START and length octets to know the frame size.
    if (in.readableBytes() < HEADER_LENGTH) {
      return;
    }

    int startIndex = in.readerIndex();

    // Validate the START octet without consuming it; a bad START is unrecoverable for a byte
    // stream.
    int start = in.getUnsignedByte(startIndex);
    if (start != START_OCTET) {
      throw new AsduDecodeException(
          String.format("invalid START octet: 0x%02X (expected 0x68)", start));
    }

    // Peek the length octet; the full frame is HEADER_LENGTH + length octets.
    int length = in.getUnsignedByte(startIndex + 1);
    int frameLength = HEADER_LENGTH + length;

    // Wait for the whole frame before consuming anything (partial-read handling).
    if (in.readableBytes() < frameLength) {
      return;
    }

    // Slice exactly one full frame and emit it. readRetainedSlice advances the reader index by
    // frameLength (consuming the frame from the accumulation buffer) and returns a slice with its
    // own +1 reference count, independent of the cumulative buffer. The downstream auto-releasing
    // InboundFrameHandler releases that reference after forwarding the frame to the listener,
    // balancing the retain here.
    out.add(in.readRetainedSlice(frameLength));
  }
}
