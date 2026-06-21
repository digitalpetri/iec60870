/**
 * Logical point model and the bridge between it and the wire-level monitor information objects.
 *
 * <p>This package describes a station's process data in caller-friendly terms, independent of the
 * many type identifications that the protocol uses to carry the same logical value. The central
 * types are {@link com.digitalpetri.iec104.point.PointValue} (a typed value with {@link
 * com.digitalpetri.iec104.point.Quality} and an optional timestamp), {@link
 * com.digitalpetri.iec104.point.PointType} (the logical kind of a point and the monitor TypeID
 * family used to carry it), and {@link com.digitalpetri.iec104.point.PointCapability} (how a point
 * may be interacted with).
 *
 * <h2>Monitor bridge</h2>
 *
 * <p>{@link com.digitalpetri.iec104.point.MonitorMapping} translates in both directions between the
 * {@code com.digitalpetri.iec104.asdu.object} monitor records and the point model. The client uses
 * it to turn received monitor objects into {@code (PointAddress, PointValue, timestamp)} updates;
 * the server uses it to build the correct monitor record when publishing a point value.
 *
 * <h2>Time tags</h2>
 *
 * <p>CP56Time2a time tags carry a full calendar date and are surfaced as a {@link
 * java.time.Instant} (interpreted at UTC). CP24Time2a time tags carry only the milliseconds and
 * minute of an event and cannot be resolved to an absolute instant on their own, so the bridge
 * omits the timestamp for CP24 variants while still extracting the value and quality. Untimed
 * monitor objects carry no timestamp.
 *
 * <h2>Boundaries</h2>
 *
 * <p>This package contains no Netty types. Encoding and decoding of the wire bytes remains the
 * responsibility of the {@code Serde} classes nested in the {@code asdu} packages; the bridge here
 * only maps between immutable domain records.
 */
@NullMarked
package com.digitalpetri.iec104.point;

import org.jspecify.annotations.NullMarked;
