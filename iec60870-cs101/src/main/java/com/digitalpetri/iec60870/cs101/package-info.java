/**
 * FT1.2 link layer for the IEC 60870-5-101 serial profile.
 *
 * <p>IEC 60870-5-101 is the serial sibling of IEC 60870-5-104: it carries the identical ASDU
 * application layer over an FT1.2 link layer instead of the 104 APCI over TCP. This package owns
 * the genuinely-101 link/session code, the peer of the {@code iec60870-cs104} APCI session:
 *
 * <ul>
 *   <li>{@code Ft12LinkLayer} — the FT1.2 link state machine that {@code implements} the core
 *       {@link com.digitalpetri.iec60870.session.Session} SPI, the peer of {@code ApciSession}. It
 *       owns the FCB stop-and-wait flow control, link-reset bring-up, and the
 *       confirm/repeat/link-state timers.
 *   <li>{@link com.digitalpetri.iec60870.cs101.Ft12Frame} — the three FT1.2 frame shapes
 *       (fixed-length, variable-length, single character) and the {@link
 *       com.digitalpetri.iec60870.cs101.LinkControlField} one-octet control field.
 *   <li>{@link com.digitalpetri.iec60870.cs101.Ft12Framer} — the {@code Ft12Frame}&lt;-&gt;{@code
 *       ByteBuf} bridge, the peer of {@code ApduFramer}.
 *   <li>{@link com.digitalpetri.iec60870.cs101.LinkSettings} — the neutral {@link
 *       com.digitalpetri.iec60870.SessionSettings} for the link layer (link mode, link address, and
 *       timers), the peer of {@code ApciSettings}.
 *   <li>{@code Cs101Binding} — the assembly point that wires an {@code Ft12LinkLayer} to a core
 *       octet transport handle, the peer of {@code Cs104Binding}.
 * </ul>
 *
 * <h2>Wire format</h2>
 *
 * <p>An FT1.2 variable-length frame carries one ASDU: a {@code 0x68} start octet, a length octet
 * sent twice, a second {@code 0x68}, the user data (a one-octet control field, an optional 0/1/2
 * octet link address, and the ASDU), an arithmetic checksum, and a {@code 0x16} end octet. A
 * fixed-length frame ({@code 0x10} start) carries the control field and link address but no ASDU,
 * and the single character {@code 0xE5} is a short positive acknowledgement. Unlike the 104 APCI,
 * there is no explicit control-information header: the length octet counts the user-data octets and
 * the ASDU length is implied.
 *
 * <h2>Dependencies</h2>
 *
 * <p>This module depends only on {@code iec60870-core} (plus {@code netty-buffer} for the {@code
 * ByteBuf} codec boundary). It imports nothing from {@code iec60870-transport-serial}, {@code
 * iec60870-transport-tcp}, or {@code iec60870-application}; any octet transport can be bound to a
 * 101 link layer through {@code Cs101Binding}.
 */
@org.jspecify.annotations.NullMarked
package com.digitalpetri.iec60870.cs101;
