/**
 * Assembly point for the IEC 60870-5-104 stack over TCP, with an optional 101-over-TCP variant.
 *
 * <p>This package hosts the user-facing builders that wire a complete protocol stack together from
 * three independently published layers: the octet transport (the Netty-backed TCP/TLS transport),
 * the link layer (the 104 APCI session or the FT1.2 link layer), and the high-level application
 * facade. It is the sole point where those three layers' dependencies converge for the TCP medium.
 *
 * <ul>
 *   <li>{@code TcpIec104Client} / {@code TcpIec104Server} assemble the default 104 stack: the Netty
 *       octet transport, the 104 APCI session and its binding, and the high-level client / server
 *       facade.
 *   <li>{@code TcpIec101Client} / {@code TcpIec101Server} assemble the optional 101-over-TCP stack:
 *       the same octet transport configured for FT1.2 framing, the FT1.2 link layer and its
 *       binding, and the same high-level facade.
 * </ul>
 *
 * <p>Nothing in this package frames octets or runs a protocol state machine; it only selects and
 * wires the pieces. The octet transport classes live in their own module and depend on the core SPI
 * only; the link-layer and application types are pulled in here, where assembly legitimately
 * couples them.
 */
@org.jspecify.annotations.NullMarked
package com.digitalpetri.iec60870.tcp;
