/**
 * Assembly point for the IEC 60870-5-101 stack over a serial port.
 *
 * <p>This package hosts the user-facing builders that wire a complete 101 protocol stack together
 * from three independently published layers: the octet transport (the jSerialComm-backed serial
 * transport), the FT1.2 link layer, and the high-level application facade. It is the sole point
 * where those three layers' dependencies converge for the serial medium.
 *
 * <ul>
 *   <li>{@code SerialIec101Client} / {@code SerialIec101Server} assemble the 101 stack: the serial
 *       octet transport, the FT1.2 link layer and its binding, and the high-level client / server
 *       facade.
 * </ul>
 *
 * <p>Nothing in this package frames octets or runs a protocol state machine; it only selects and
 * wires the pieces. The octet transport classes live in their own module and depend on the core SPI
 * only; the link-layer and application types are pulled in here, where assembly legitimately
 * couples them.
 */
@org.jspecify.annotations.NullMarked
package com.digitalpetri.iec60870.serial;
