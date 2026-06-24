package com.digitalpetri.iec60870.transport.tcp;

import com.digitalpetri.iec60870.ProtocolProfile;
import com.digitalpetri.iec60870.apci.Apdu;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;

/**
 * Serializes outbound {@link Apdu} messages onto the wire.
 *
 * <p>Each APDU is written as {@code [0x68][length][length octets]} by delegating to {@link
 * Apdu.Serde#encode(Apdu, ProtocolProfile, ByteBuf)}, which emits the START octet, the length
 * octet, the four APCI control octets, and (for I-format APDUs) the ASDU body. The encoder writes
 * into the Netty-supplied output buffer and never releases it; Netty owns the lifecycle of that
 * buffer.
 *
 * <p>This handler is pure {@code Apdu.Serde} translation and carries no protocol state machine; it
 * is the outbound half of the {@code ByteBuf}-to-{@link Apdu} boundary and is reusable by any
 * transport.
 */
public class Iec104FrameEncoder extends MessageToByteEncoder<Apdu> {

  private final ProtocolProfile profile;

  /**
   * Creates a frame encoder that encodes APDUs using the given protocol profile.
   *
   * @param profile the protocol profile that governs ASDU field widths during encode.
   */
  public Iec104FrameEncoder(ProtocolProfile profile) {
    super(Apdu.class);

    this.profile = profile;
  }

  /**
   * Encodes a single outbound APDU into {@code out}.
   *
   * @param ctx the channel handler context.
   * @param apdu the APDU to encode.
   * @param out the Netty-owned destination buffer; the encoded APDU is appended and the buffer is
   *     not released.
   */
  @Override
  protected void encode(ChannelHandlerContext ctx, Apdu apdu, ByteBuf out) {
    Apdu.Serde.encode(apdu, profile, out);
  }
}
