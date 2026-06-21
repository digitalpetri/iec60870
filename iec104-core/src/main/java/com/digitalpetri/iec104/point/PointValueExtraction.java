package com.digitalpetri.iec104.point;

import com.digitalpetri.iec104.asdu.InformationObject;
import java.time.Instant;
import java.util.Optional;

/**
 * The result of extracting a point value from a monitor information object.
 *
 * <p>Pairs the decoded {@link PointValue} with the absolute timestamp recovered from the object's
 * time tag, when one is present. The timestamp is empty for untimed monitor objects and for objects
 * tagged with a CP24Time2a time tag, which carries only the milliseconds and minute of an event and
 * cannot be resolved to an absolute instant on its own.
 *
 * @param value the extracted point value, whose own {@link PointValue#timestamp()} mirrors {@code
 *     timestamp}.
 * @param timestamp the absolute acquisition timestamp, if the object carried a resolvable time tag.
 * @see MonitorMapping#extract(InformationObject)
 */
public record PointValueExtraction(PointValue<?> value, Optional<Instant> timestamp) {}
