/**
 * Netty-backed TCP/TLS transport for the IEC 60870-5-104 protocol.
 *
 * <p>This package adapts the transport-neutral abstractions in {@code com.digitalpetri.iec104} to a
 * concrete Netty pipeline. The two reusable channel handlers in this package form the {@link
 * io.netty.buffer.ByteBuf}-to-{@link com.digitalpetri.iec104.apci.Apdu} boundary:
 *
 * <ul>
 *   <li>{@link com.digitalpetri.iec104.transport.tcp.Iec104FrameDecoder} reads length-delimited
 *       APDU frames off the wire and emits decoded {@link com.digitalpetri.iec104.apci.Apdu}
 *       messages.
 *   <li>{@link com.digitalpetri.iec104.transport.tcp.Iec104FrameEncoder} serializes outbound {@link
 *       com.digitalpetri.iec104.apci.Apdu} messages back onto the wire.
 * </ul>
 *
 * <p>Each frame on the wire is {@code [0x68][length][length octets]}, where the one-octet length
 * counts the four APCI control octets plus any ASDU body and excludes the start and length octets
 * themselves. The codec handlers are intentionally pure and stateless beyond Netty's accumulation
 * buffer: they perform framing and {@code Apdu.Serde} translation only and do not implement any
 * protocol state machine. Transports add their own inbound handler downstream of the decoder to
 * drive the APCI session.
 *
 * <p>Netty runtime types ({@code Channel}, {@code EventLoopGroup}, {@code SslHandler}, {@code
 * Bootstrap}, {@code ByteBuf}, {@code ChannelHandler}) are confined to this module and never appear
 * in a core protocol signature.
 */
@org.jspecify.annotations.NullMarked
package com.digitalpetri.iec104.transport.tcp;
