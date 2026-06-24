package com.digitalpetri.iec60870.transport.tcp;

import com.digitalpetri.iec60870.AsduDecodeException;
import com.digitalpetri.iec60870.ProtocolProfile;
import com.digitalpetri.iec60870.apci.Apdu;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import java.util.List;

/**
 * Frames length-delimited IEC 60870-5-104 APDUs off the wire and decodes each into an {@link Apdu}.
 *
 * <p>Every APDU on the wire is {@code [0x68][length][length octets]}: a fixed {@code 0x68} START
 * octet, a single length octet, and then exactly {@code length} body octets (the four APCI control
 * octets plus any ASDU body). A complete frame therefore occupies {@code 2 + length} octets.
 *
 * <p>This is a hand-written {@link ByteToMessageDecoder} rather than a {@link
 * io.netty.handler.codec.LengthFieldBasedFrameDecoder}. The hand-written form is preferred here
 * because it makes START-octet validation and partial-read handling explicit and keeps the framing
 * decision co-located with the {@link Apdu.Serde#decode(ProtocolProfile, ByteBuf) decode} call.
 *
 * <p>Partial reads are handled by leaving the accumulation buffer untouched until a whole frame is
 * available: the decoder peeks at the length octet without consuming, returns if fewer than {@code
 * 2 + length} octets are readable, and only then slices and decodes a single full APDU. A bad START
 * octet (anything other than {@code 0x68}) is unrecoverable for a byte stream, so an {@link
 * AsduDecodeException} is raised; Netty propagates it through {@code exceptionCaught} and the
 * transport closes the channel.
 *
 * <p>This handler is pure framing plus {@code Apdu.Serde} translation. It carries no protocol state
 * machine; transports add their own inbound handler downstream to drive the APCI session.
 */
public class Iec104FrameDecoder extends ByteToMessageDecoder {

  /** The fixed START octet ({@code 0x68}) that delimits the beginning of every APDU. */
  private static final int START_OCTET = Apdu.START_OCTET;

  /** The number of octets consumed by the START octet and the length octet ({@code 2}). */
  private static final int HEADER_LENGTH = 2;

  private final ProtocolProfile profile;

  /**
   * Creates a frame decoder that decodes APDUs using the given protocol profile.
   *
   * @param profile the protocol profile that governs ASDU field widths during decode.
   */
  public Iec104FrameDecoder(ProtocolProfile profile) {
    this.profile = profile;
  }

  /**
   * Frames and decodes as many complete APDUs as are fully buffered in {@code in}.
   *
   * <p>The method consumes the accumulation buffer one full frame at a time. It returns without
   * consuming any octets when a complete frame is not yet available, allowing Netty to accumulate
   * more inbound data before invoking the decoder again.
   *
   * @param ctx the channel handler context.
   * @param in the cumulative inbound buffer positioned at the next START octet.
   * @param out the list to which decoded {@link Apdu} messages are added.
   * @throws AsduDecodeException if the START octet is not {@code 0x68} or the framed APDU is
   *     malformed.
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

    // Slice exactly one full APDU and hand it to the core decoder. readSlice advances the reader
    // index by frameLength, consuming the frame from the accumulation buffer. The slice shares the
    // accumulation buffer's memory and is consumed synchronously here, so no retain/release is
    // required.
    ByteBuf frame = in.readSlice(frameLength);

    out.add(Apdu.Serde.decode(profile, frame));
  }
}
