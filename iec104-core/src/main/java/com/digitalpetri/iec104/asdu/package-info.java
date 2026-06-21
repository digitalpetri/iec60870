/**
 * Application Service Data Unit (ASDU) model and codec for IEC 60870-5-104.
 *
 * <p>This package owns the application-layer envelope that every IEC 104 message carries above the
 * APCI control field. {@link com.digitalpetri.iec104.asdu.Asdu} is the central record: it pairs the
 * data unit identifier (type, variable structure qualifier, cause of transmission, common address,
 * and optional originator address) with the list of typed {@link
 * com.digitalpetri.iec104.asdu.InformationObject}s it conveys.
 *
 * <h2>Data flow</h2>
 *
 * <p>On the wire an ASDU is a {@code TYPE IDENTIFICATION} octet, a {@code VARIABLE STRUCTURE
 * QUALIFIER} octet, a one- or two-octet cause of transmission, the common address, and then the
 * information objects. {@link com.digitalpetri.iec104.asdu.Asdu.Serde} encodes and decodes this
 * envelope, delegating per-type element framing to an {@link
 * com.digitalpetri.iec104.asdu.InformationObjectCodec} resolved from an {@link
 * com.digitalpetri.iec104.asdu.InformationObjectCodecRegistry}. The information object address
 * (IOA) framing is handled centrally by {@code Asdu.Serde}; each codec only reads and writes the
 * elements that follow the IOA.
 *
 * <h2>Wire conventions</h2>
 *
 * <p>All multi-octet fields use Mode 1 (least significant octet first). Field widths come from the
 * active {@link com.digitalpetri.iec104.ProtocolProfile}: the cause-of-transmission length selects
 * whether the originator address octet is present, and the common-address and IOA lengths select
 * how many octets those fields occupy.
 *
 * <h2>Enumerations</h2>
 *
 * <p>{@link com.digitalpetri.iec104.asdu.AsduType} enumerates every type identification defined in
 * the IEC 60870-5-101/104 tables; {@link com.digitalpetri.iec104.asdu.AsduType#supported()} reports
 * whether a typed information object record exists for that type. {@link
 * com.digitalpetri.iec104.asdu.Cause} enumerates the standard-range causes of transmission.
 *
 * <h2>Boundaries</h2>
 *
 * <p>Netty {@code ByteBuf} appears only inside the nested {@code Serde} classes of this package and
 * the address package. High-level types ({@code Asdu}, addresses, enumerations) never expose it.
 */
@org.jspecify.annotations.NullMarked
package com.digitalpetri.iec104.asdu;
