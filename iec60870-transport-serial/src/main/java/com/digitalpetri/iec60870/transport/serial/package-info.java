/**
 * Serial (RS-232 / RS-485) octet transport for the IEC 60870-5-101 FT1.2 link layer.
 *
 * <p>This module realizes the core octet transport SPI ({@link
 * com.digitalpetri.iec60870.transport.ClientTransport}, {@link
 * com.digitalpetri.iec60870.transport.ServerTransport}, and {@link
 * com.digitalpetri.iec60870.transport.ServerTransportConnection}) over a serial port, the peer of
 * the Netty-backed {@code iec60870-transport-tcp}. It exchanges complete, length-delimited FT1.2
 * frames as Netty {@code ByteBuf}s and performs incremental, length-based deframing of the inbound
 * byte stream; it never measures inter-character idle time.
 *
 * <h2>Runtime boundaries</h2>
 *
 * <p>The octet classes depend on {@code iec60870-core} only. They import nothing from {@code
 * iec60870-cs101} or {@code iec60870-application}, and the underlying {@code
 * com.fazecast.jSerialComm} driver is confined entirely to this package: no {@code com.fazecast}
 * type appears in any public API signature or escapes into another module. The FT1.2 framing detail
 * this transport needs (the configured link-address length, used to size fixed-length frames) is
 * supplied as a plain integer, so the transport stays protocol-agnostic and could serve any FT1.2
 * link layer.
 *
 * <h2>Threading</h2>
 *
 * <p>A dedicated reader thread does semi-blocking bulk reads into a reusable buffer and feeds the
 * incremental deframer, delivering each assembled frame to the registered {@link
 * com.digitalpetri.iec60870.transport.TransportListener}. Sends write, flush, and release one whole
 * frame on the calling thread.
 */
@org.jspecify.annotations.NullMarked
package com.digitalpetri.iec60870.transport.serial;
