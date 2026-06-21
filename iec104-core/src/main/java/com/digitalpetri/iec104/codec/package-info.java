/**
 * Raw-codec extensibility for ASDU information objects.
 *
 * <p>This package owns the registry surfaces that map an {@link
 * com.digitalpetri.iec104.asdu.AsduType} to a per-type {@link
 * com.digitalpetri.iec104.asdu.InformationObjectCodec}. The standard, built-in codecs for the
 * supported TypeIDs are defined elsewhere (in {@code
 * com.digitalpetri.iec104.asdu.InformationObjectCodecs}, added during the integration phase); the
 * registries here exist so callers can plug in codecs for private or uncommon TypeIDs that the
 * library does not model with a typed {@code .asdu.object} record.
 *
 * <h2>Entry points</h2>
 *
 * <p>{@link com.digitalpetri.iec104.codec.TypeCodecRegistry} is the lookup contract: register a
 * codec for an {@code AsduType}, then resolve it later during encode/decode dispatch. {@link
 * com.digitalpetri.iec104.codec.MutableTypeCodecRegistry} is a thread-safe, mutable default
 * implementation suitable for accumulating user extensions at startup and consulting them at
 * runtime.
 *
 * <h2>Extension points</h2>
 *
 * <p>Use a {@code TypeCodecRegistry} to extend the wire-level coverage of the library without
 * modifying the built-in codec tables. Codecs registered here take part in raw-layer encode/decode
 * of the corresponding {@code AsduType}; the typed high-level model in {@code
 * com.digitalpetri.iec104.asdu} remains the authority for the supported TypeIDs.
 */
@org.jspecify.annotations.NullMarked
package com.digitalpetri.iec104.codec;
