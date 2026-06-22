package com.digitalpetri.iec104.point;

import com.digitalpetri.iec104.asdu.element.BinaryCounterReading;
import com.digitalpetri.iec104.asdu.element.DoublePointState;
import com.digitalpetri.iec104.asdu.element.NormalizedValue;
import com.digitalpetri.iec104.asdu.element.Vti;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * A logical point value: a {@link PointType}, a typed value paired with its {@link Quality}, and an
 * optional acquisition timestamp.
 *
 * <p>The type parameter {@code T} is the natural Java representation of the value for the point's
 * {@link PointType}: {@link Boolean} for single-point, {@link DoublePointState} for double-point,
 * {@link Vti} for step position, {@link Integer} for a 32-bit bit string, {@link Short} for a
 * scaled value, {@link NormalizedValue} for a normalized value, {@link Float} for a short
 * floating-point value, and {@link BinaryCounterReading} for integrated totals.
 *
 * <p>Use the static factories to create instances with good quality and no timestamp, then refine
 * them with {@link #withQuality(Quality)} and {@link #withTimestamp(Instant)}:
 *
 * <pre>{@code
 * PointValue<Float> v = PointValue.shortFloat(12.5f)
 *     .withQuality(Quality.good())
 *     .withTimestamp(Instant.now());
 * }</pre>
 *
 * @param <T> the natural Java type of the value.
 * @param type the point type, which determines the natural Java type {@code T} of {@code value}.
 * @param value the point value.
 * @param quality the quality of the value.
 * @param timestamp the optional acquisition timestamp.
 */
public record PointValue<T>(PointType type, T value, Quality quality, Optional<Instant> timestamp) {

  /**
   * Validates the components of a point value.
   *
   * @param type the point type, which determines the natural Java type {@code T} of {@code value}.
   * @param value the point value.
   * @param quality the quality of the value.
   * @param timestamp the optional acquisition timestamp.
   * @throws NullPointerException if {@code type}, {@code value}, {@code quality}, or {@code
   *     timestamp} is null.
   * @throws IllegalArgumentException if {@code value} is not an instance of {@link
   *     PointType#valueClass() type.valueClass()}.
   */
  public PointValue {
    Objects.requireNonNull(type, "type");
    Objects.requireNonNull(value, "value");
    Objects.requireNonNull(quality, "quality");
    Objects.requireNonNull(timestamp, "timestamp");
    if (!type.valueClass().isInstance(value)) {
      throw new IllegalArgumentException(
          "value of type "
              + value.getClass().getName()
              + " is not valid for point type "
              + type
              + " (expected "
              + type.valueClass().getName()
              + ")");
    }
  }

  /**
   * Creates a single-point value with good quality and no timestamp.
   *
   * @param on the single-point state; {@code true} for ON, {@code false} for OFF.
   * @return the point value.
   */
  public static PointValue<Boolean> single(boolean on) {
    return new PointValue<>(PointType.SINGLE_POINT, on, Quality.good(), Optional.empty());
  }

  /**
   * Creates a double-point value with good quality and no timestamp.
   *
   * @param state the double-point state.
   * @return the point value.
   */
  public static PointValue<DoublePointState> doublePoint(DoublePointState state) {
    return new PointValue<>(PointType.DOUBLE_POINT, state, Quality.good(), Optional.empty());
  }

  /**
   * Creates a step-position value with good quality and no timestamp.
   *
   * @param value the value-with-transient-state indication.
   * @return the point value.
   */
  public static PointValue<Vti> stepPosition(Vti value) {
    return new PointValue<>(PointType.STEP_POSITION, value, Quality.good(), Optional.empty());
  }

  /**
   * Creates a 32-bit bit-string value with good quality and no timestamp.
   *
   * @param bits the 32 raw bits.
   * @return the point value.
   */
  public static PointValue<Integer> bitstring(int bits) {
    return new PointValue<>(PointType.BITSTRING32, bits, Quality.good(), Optional.empty());
  }

  /**
   * Creates a scaled (signed 16-bit) measured value with good quality and no timestamp.
   *
   * @param value the scaled value.
   * @return the point value.
   */
  public static PointValue<Short> scaled(short value) {
    return new PointValue<>(PointType.SCALED, value, Quality.good(), Optional.empty());
  }

  /**
   * Creates a normalized measured value with good quality and no timestamp.
   *
   * @param value the normalized value.
   * @return the point value.
   */
  public static PointValue<NormalizedValue> normalized(NormalizedValue value) {
    return new PointValue<>(PointType.NORMALIZED, value, Quality.good(), Optional.empty());
  }

  /**
   * Creates a short floating-point measured value with good quality and no timestamp.
   *
   * @param value the short floating-point value.
   * @return the point value.
   */
  public static PointValue<Float> shortFloat(float value) {
    return new PointValue<>(PointType.SHORT_FLOAT, value, Quality.good(), Optional.empty());
  }

  /**
   * Creates an integrated-totals (binary counter) value with good quality and no timestamp.
   *
   * @param counter the binary counter reading.
   * @return the point value.
   */
  public static PointValue<BinaryCounterReading> counter(BinaryCounterReading counter) {
    return new PointValue<>(PointType.INTEGRATED_TOTALS, counter, Quality.good(), Optional.empty());
  }

  /**
   * Returns a copy of this point value with the given quality.
   *
   * @param quality the new quality.
   * @return a point value equal to this one but with the given quality.
   */
  public PointValue<T> withQuality(Quality quality) {
    return new PointValue<>(type, value, quality, timestamp);
  }

  /**
   * Returns a copy of this point value with the given timestamp.
   *
   * @param timestamp the acquisition timestamp.
   * @return a point value equal to this one but carrying the given timestamp.
   */
  public PointValue<T> withTimestamp(Instant timestamp) {
    return new PointValue<>(type, value, quality, Optional.of(timestamp));
  }
}
