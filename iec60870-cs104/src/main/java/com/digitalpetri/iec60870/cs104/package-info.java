/**
 * Application Protocol Control Information (APCI) and Application Protocol Data Unit (APDU) framing
 * for the IEC 60870-5-104 TCP profile.
 *
 * <p>Every IEC 104 message on the wire is an {@link com.digitalpetri.iec60870.cs104.Apdu}: a
 * four-octet APCI control field, optionally followed by an ASDU body. The APCI is delimited by a
 * {@code 0x68} start octet and a one-octet length, and its {@link
 * com.digitalpetri.iec60870.cs104.ControlField} carries one of three formats:
 *
 * <ul>
 *   <li>{@link com.digitalpetri.iec60870.cs104.ControlField.TypeI} — numbered information transfer;
 *       the only format that carries an ASDU. It pairs a send sequence number N(S) with a receive
 *       sequence number N(R).
 *   <li>{@link com.digitalpetri.iec60870.cs104.ControlField.TypeS} — numbered supervisory
 *       acknowledge; carries only a receive sequence number N(R) to acknowledge received I-format
 *       APDUs.
 *   <li>{@link com.digitalpetri.iec60870.cs104.ControlField.TypeU} — unnumbered control function
 *       (STARTDT, STOPDT, or TESTFR), modeled by {@link com.digitalpetri.iec60870.cs104.UFunction}.
 * </ul>
 *
 * <h2>Wire format</h2>
 *
 * <p>All sequence numbers are 15-bit values in the range {@code 0..32767} and are shifted left by
 * one bit on the wire, because the least significant bit of the first control octet is the I/S/U
 * format flag. The APDU length octet counts the four control octets plus the ASDU body; it excludes
 * the {@code 0x68} start octet and the length octet itself.
 *
 * <h2>Serialization</h2>
 *
 * <p>Wire encoding and decoding live in the nested {@code Serde} classes ({@link
 * com.digitalpetri.iec60870.cs104.ControlField.Serde} and {@link
 * com.digitalpetri.iec60870.cs104.Apdu.Serde}). Those classes write into and read from a
 * caller-owned Netty buffer and never allocate or release it. APDU decode assumes a full APDU is
 * already present in the buffer; length-based framing is performed by the transport layer.
 */
@org.jspecify.annotations.NullMarked
package com.digitalpetri.iec60870.cs104;
