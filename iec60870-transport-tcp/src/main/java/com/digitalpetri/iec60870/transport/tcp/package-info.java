/**
 * Netty-backed TCP/TLS transport for the IEC 60870-5-104 protocol, with an optional 101-over-TCP
 * variant.
 *
 * <p>This package adapts the octet-shaped transport SPI in {@code
 * com.digitalpetri.iec60870.transport} to a concrete Netty pipeline. The transport speaks only in
 * complete, length-delimited frame {@link io.netty.buffer.ByteBuf}s; translating a frame to and
 * from an {@code Apdu} (104) or {@code Ft12Frame} (101) happens above the SPI (via {@code
 * ApduFramer} / {@code Ft12Framer}), not here.
 *
 * <ul>
 *   <li>{@link com.digitalpetri.iec60870.transport.tcp.Iec104FrameDecoder} reads length-delimited
 *       104 APDUs off the wire and emits one whole-frame {@link io.netty.buffer.ByteBuf} per
 *       complete frame; it is the default frame decoder.
 *   <li>{@link com.digitalpetri.iec60870.transport.tcp.Ft12FrameDecoder} is its FT1.2 peer for the
 *       optional 101-over-TCP variant, framing complete FT1.2 frames off the same byte stream.
 *   <li>{@link com.digitalpetri.iec60870.transport.tcp.InboundFrameHandler} is the terminal handler
 *       that forwards each frame to the registered {@code TransportListener}.
 * </ul>
 *
 * <p>The 104 path is assembled by the {@code TcpIec104Client} / {@code TcpIec104Server} builders
 * and is the default: the transport configs leave the frame-decoder factory unset, so {@code
 * Iec104FrameDecoder} is installed. The optional 101-over-TCP path is assembled by the {@code
 * TcpIec101Client} / {@code TcpIec101Server} builders, which set the transport config's
 * frame-decoder factory to supply an {@code Ft12FrameDecoder} and delegate the link-layer wiring to
 * {@code Cs101Binding}; nothing else in the pipeline changes.
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
