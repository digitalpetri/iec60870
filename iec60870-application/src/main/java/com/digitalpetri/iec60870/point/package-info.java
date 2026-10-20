/**
 * Logical point model and the bridge between it and the wire-level monitor information objects.
 *
 * <p>This package describes a station's process data in caller-friendly terms, independent of the
 * many type identifications that the protocol uses to carry the same logical value. The central
 * types are {@link com.digitalpetri.iec60870.point.PointValue} (a typed value with {@link
 * com.digitalpetri.iec60870.point.Quality} and an optional timestamp), {@link
 * com.digitalpetri.iec60870.point.PointType} (the logical kind of a point and the monitor TypeID
 * family used to carry it), and {@link com.digitalpetri.iec60870.point.PointCapability} (how a
 * point may be interacted with).
 *
 * <h2>Monitor bridge</h2>
 *
 * <p>{@link com.digitalpetri.iec60870.point.MonitorMapping} translates in both directions between
 * the {@code com.digitalpetri.iec60870.asdu.object} monitor records and the point model. The client
 * uses it to turn received monitor objects into {@code (PointAddress, PointValue, TimeTag)}
 * updates; the server uses it to build the correct monitor record when publishing a point value.
 *
 * <h2>Time tags</h2>
 *
 * <p>IEC 60870-5 time tags carry wall-clock fields with no time zone. {@link
 * com.digitalpetri.iec60870.point.TimePolicy} names the zone of the peer's clock and the rules for
 * the summer-time bit, the day of week, the two-digit year, and CP24Time2a completion. It is the
 * single conversion path for both directions: the client resolves received tags and encodes command
 * and clock-sync tags with it, and the server encodes published tags with it. The default, {@link
 * com.digitalpetri.iec60870.point.TimePolicy#utc()}, treats every tag as UTC.
 *
 * <p>A received tag surfaces as a {@link com.digitalpetri.iec60870.point.TimeTag}, which keeps the
 * raw CP56Time2a or CP24Time2a fields and their IV, SU, and RES1/GEN flags next to the resolved
 * instant. The convenience {@code timestamp()} accessors on point values, extractions, and client
 * events drop the instant of a tag marked invalid. CP24Time2a tags carry only the minute and
 * milliseconds, so their instant is empty unless the policy completes them from the receive time.
 * Untimed monitor objects carry no time tag.
 *
 * <h2>Boundaries</h2>
 *
 * <p>This package contains no Netty types. Encoding and decoding of the wire bytes remains the
 * responsibility of the {@code Serde} classes nested in the {@code asdu} packages; the bridge here
 * only maps between immutable domain records.
 */
@NullMarked
package com.digitalpetri.iec60870.point;

import org.jspecify.annotations.NullMarked;
