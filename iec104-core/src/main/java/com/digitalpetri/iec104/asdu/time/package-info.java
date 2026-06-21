/**
 * Binary time-tag information elements defined by IEC 60870-5-4 §6.8 and IEC 60870-5-101 §7.2.6.
 *
 * <p>This package provides the three binary time formats used to time-stamp information objects and
 * to express elapsed durations: {@link com.digitalpetri.iec104.asdu.time.Cp56Time2a} (seven octets,
 * full calendar date and time), {@link com.digitalpetri.iec104.asdu.time.Cp24Time2a} (three octets,
 * minute-of-hour and milliseconds), and {@link com.digitalpetri.iec104.asdu.time.Cp16Time2a} (two
 * octets, elapsed time in milliseconds).
 *
 * <p>Each type is an immutable {@code record} whose components carry the decoded calendar fields.
 * The reserved bits and flag bits described by the specification are surfaced as boolean components
 * ({@code invalid}, {@code summerTime}, {@code genuine}). On the wire every multi-octet field is
 * encoded least significant octet first (Mode 1, little-endian); the bit-level layout for each
 * octet is documented on the nested {@code Serde} of each type.
 *
 * <h2>Conversions</h2>
 *
 * {@link com.digitalpetri.iec104.asdu.time.Cp56Time2a} offers {@link
 * com.digitalpetri.iec104.asdu.time.Cp56Time2a#from(java.time.Instant, java.time.ZoneOffset)} and
 * {@link com.digitalpetri.iec104.asdu.time.Cp56Time2a#toInstant(java.time.ZoneOffset)} to bridge
 * between the wire representation and {@link java.time.Instant}. Because CP56Time2a has only a
 * two-digit year and no century field, conversions to {@code Instant} interpret the year against
 * the 2000..2099 century.
 *
 * <h2>Ownership</h2>
 *
 * These elements are consumed by the per-TypeID records in {@code
 * com.digitalpetri.iec104.asdu.object} (for example the CP56Time2a-tagged measured-value and
 * command types). Encoding and decoding of the surrounding ASDU framing (variable structure
 * qualifier, information object address) is the responsibility of {@code Asdu.Serde}, not of this
 * package.
 */
@org.jspecify.annotations.NullMarked
package com.digitalpetri.iec104.asdu.time;
