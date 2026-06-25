/**
 * Reusable IEC 60870-5-104 information elements and qualifiers shared across ASDU object types.
 *
 * <p>The types in this package model the small, fixed-width quality descriptors, value descriptors,
 * protection-equipment descriptors, and command/setpoint qualifiers defined in IEC 60870-5-101
 * §7.2.6. Each element is an immutable {@code record} that validates its components in the compact
 * constructor and carries a nested {@code Serde} responsible for the little-endian (Mode 1) wire
 * encoding and decoding of that element.
 *
 * <h2>Wire conventions</h2>
 *
 * <p>All octets are written and read least-significant-octet-first. Bit positions follow the spec
 * numbering where bit 1 is the least significant bit. Each {@code Serde.encode} writes into a
 * caller-owned {@link io.netty.buffer.ByteBuf} without releasing it, and each {@code Serde.decode}
 * reads from a caller-owned buffer without releasing it; buffer allocation and release remain the
 * caller's responsibility.
 *
 * <h2>Boundaries</h2>
 *
 * <p>Object records in {@code com.digitalpetri.iec60870.asdu.object} compose these elements to form
 * the payload of a typed information object. The {@link io.netty.buffer.ByteBuf} type appears only
 * inside the nested {@code Serde} classes; the element records themselves never expose Netty types.
 */
@NullMarked
package com.digitalpetri.iec60870.asdu.element;

import org.jspecify.annotations.NullMarked;
