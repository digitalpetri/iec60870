/**
 * Addressing types used by the IEC 60870-5-104 data unit identifier and information objects.
 *
 * <p>{@link com.digitalpetri.iec60870.address.CommonAddress} identifies a station (the common
 * address of an ASDU). {@link com.digitalpetri.iec60870.address.InformationObjectAddress}
 * identifies a single information object within a station. {@link
 * com.digitalpetri.iec60870.address.OriginatorAddress} optionally identifies the originator of a
 * control-direction message and is present only when the cause-of-transmission field is two octets
 * wide. {@link com.digitalpetri.iec60870.address.PointAddress} pairs a common address with an
 * information object address to globally identify a point.
 *
 * <h2>Wire format</h2>
 *
 * <p>The common address and information object address are encoded least-significant octet first
 * (little-endian) over a width fixed by the {@link com.digitalpetri.iec60870.ProtocolProfile}.
 * Their nested {@code Serde} classes take that width as an explicit parameter; the addresses
 * themselves carry the full unsigned value as a jOOU wrapper and do not know the width.
 *
 * <h2>Validation</h2>
 *
 * <p>Each record validates its value range in its compact constructor and factory methods, throwing
 * {@link java.lang.IllegalArgumentException} for out-of-range arguments. Decoders throw {@link
 * com.digitalpetri.iec60870.AsduDecodeException} when the wire data cannot yield a valid address.
 */
@NullMarked
package com.digitalpetri.iec60870.address;

import org.jspecify.annotations.NullMarked;
