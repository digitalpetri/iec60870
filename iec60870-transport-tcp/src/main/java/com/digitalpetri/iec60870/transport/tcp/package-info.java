/**
 * Netty-backed TCP/TLS transport for the IEC 60870-5-104 protocol.
 *
 * <p>This package adapts the octet-shaped transport SPI in {@code
 * com.digitalpetri.iec60870.transport} to a concrete Netty pipeline. The transport speaks only in
 * complete, length-delimited frame {@link io.netty.buffer.ByteBuf}s; translating a frame to and
 * from an {@code Apdu} happens above the SPI (via {@code ApduFramer}), not here.
 *
 * <ul>
 *   <li>{@link com.digitalpetri.iec60870.transport.tcp.Iec104FrameDecoder} reads length-delimited
 *       frames off the wire and emits one whole-frame {@link io.netty.buffer.ByteBuf} per complete
 *       frame.
 *   <li>{@link com.digitalpetri.iec60870.transport.tcp.InboundFrameHandler} is the terminal handler
 *       that forwards each frame to the registered {@code TransportListener}.
 * </ul>
 *
 * <p>Outbound is a raw {@link io.netty.buffer.ByteBuf} write: the protocol layer above frames each
 * APDU into a complete length-delimited buffer and the transport writes-and-flushes it, so no
 * outbound encoder handler exists. Each frame on the wire is {@code [0x68][length][length octets]},
 * where the one-octet length counts the four APCI control octets plus any ASDU body and excludes
 * the start and length octets themselves. The decoder is intentionally pure and stateless beyond
 * Netty's accumulation buffer: it performs octet framing only, is profile-agnostic, and implements
 * no protocol state machine.
 *
 * <p>Netty runtime types ({@code Channel}, {@code EventLoopGroup}, {@code SslHandler}, {@code
 * Bootstrap}, {@code ChannelHandler}) are confined to this module and never appear in a core
 * signature. The one exception is {@link io.netty.buffer.ByteBuf}, which is the sanctioned
 * codec-boundary type on the octet transport SPI itself.
 */
@org.jspecify.annotations.NullMarked
package com.digitalpetri.iec60870.transport.tcp;
