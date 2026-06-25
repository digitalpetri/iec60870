/**
 * Root package of the IEC 60870-5-104 protocol library.
 *
 * <p>This package owns the cross-cutting building blocks shared by the rest of the library: the
 * exception hierarchy rooted at {@link com.digitalpetri.iec60870.Iec60870Exception}; the immutable
 * configuration holders {@link com.digitalpetri.iec60870.ProtocolProfile} and {@link
 * com.digitalpetri.iec60870.TlsOptions}; the neutral {@link
 * com.digitalpetri.iec60870.SessionSettings} handle that the client and server configurations
 * carry; and the {@link com.digitalpetri.iec60870.OutboundQueuePolicy} that governs a full outbound
 * session queue.
 *
 * <h2>Configuration</h2>
 *
 * <p>{@link com.digitalpetri.iec60870.ProtocolProfile} fixes the station-wide field widths (cause
 * of transmission, common address, and information object address octet counts) plus the maximum
 * ASDU length. The cs104 {@code ApciSettings} fixes the APCI flow-control parameters (the {@code k}
 * and {@code w} window sizes and the {@code t0}-{@code t3} timeouts). {@link
 * com.digitalpetri.iec60870.TlsOptions} carries the TLS material the transport layer applies when
 * securing a connection.
 *
 * <h2>Errors</h2>
 *
 * <p>All library-specific failures extend {@link com.digitalpetri.iec60870.Iec60870Exception}, an
 * unchecked exception. Malformed wire data surfaces as {@link
 * com.digitalpetri.iec60870.AsduDecodeException} during decoding, while bad local arguments are
 * rejected with {@link java.lang.IllegalArgumentException} from compact constructors and factories.
 *
 * <h2>Wire conventions</h2>
 *
 * <p>Every multi-octet field is encoded least-significant octet first (Mode 1, little-endian).
 * Unsigned wire values cross public boundaries as jOOU wrappers ({@code org.joou.UByte}/{@code
 * org.joou.UShort}/{@code org.joou.UInteger}); the signed carrier used to read and write them lives
 * only inside the nested {@code Serde} classes.
 */
@NullMarked
package com.digitalpetri.iec60870;

import org.jspecify.annotations.NullMarked;
