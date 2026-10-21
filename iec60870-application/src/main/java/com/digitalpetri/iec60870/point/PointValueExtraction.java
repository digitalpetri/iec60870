package com.digitalpetri.iec60870.point;

import com.digitalpetri.iec60870.asdu.InformationObject;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * The result of extracting a point value from a monitor information object.
 *
 * <p>Pairs the decoded {@link PointValue} with the object's {@link TimeTag}, when it carries one.
 * The time tag keeps the raw fields and their IV, SU, and RES1/GEN flags alongside the instant the
 * {@link TimePolicy} resolved.
 *
 * @param value the extracted point value, whose own {@link PointValue#timestamp()} mirrors {@link
 *     #timestamp()}.
 * @param timeTag the object's time tag, or empty for an untimed object.
 * @see MonitorMapping#extract(InformationObject, TimePolicy, Instant)
 */
public record PointValueExtraction(PointValue<?> value, Optional<TimeTag> timeTag) {

  /**
   * Validates the components.
   *
   * @param value the extracted point value.
   * @param timeTag the object's time tag, or empty for an untimed object.
   * @throws NullPointerException if {@code value} or {@code timeTag} is null.
   */
  public PointValueExtraction {
    Objects.requireNonNull(value, "value");
    Objects.requireNonNull(timeTag, "timeTag");
  }

  /**
   * Returns the acquisition timestamp: the time tag's resolved instant, unless the tag is marked
   * invalid (IV=1) or could not be resolved.
   *
   * @return the acquisition timestamp, or empty if there is no usable time tag.
   */
  public Optional<Instant> timestamp() {
    return timeTag.flatMap(TimeTag::timestamp);
  }
}
