/**
 * Typed IEC 60870-5-104 information-object records and their wire codecs.
 *
 * <p>Each type in this package models one standard TypeID's information object: a single,
 * addressable payload composed of the reusable information elements from {@code
 * com.digitalpetri.iec104.asdu.element} (quality and value descriptors, command and setpoint
 * qualifiers) together with an {@link com.digitalpetri.iec104.address.InformationObjectAddress}.
 * Every type is an immutable {@code record} that validates its components in the compact
 * constructor and carries a nested {@code Serde} responsible for the little-endian (Mode 1) wire
 * encoding and decoding of that object's elements following the IOA.
 *
 * <h2>Wire conventions</h2>
 *
 * <p>All octets are written and read least-significant-octet-first. Each {@code Serde.encode}
 * writes into a caller-owned {@link io.netty.buffer.ByteBuf} without releasing it and does not
 * write the IOA; each {@code Serde.decode} reads from a caller-owned buffer with the IOA already
 * read by the caller and does not release the buffer. Buffer allocation and release remain the
 * caller's responsibility.
 *
 * <h2>Boundaries</h2>
 *
 * <p>The {@link io.netty.buffer.ByteBuf} type appears only inside the nested {@code Serde} classes;
 * the object records themselves never expose Netty types.
 */
@NullMarked
package com.digitalpetri.iec104.asdu.object;

import org.jspecify.annotations.NullMarked;
