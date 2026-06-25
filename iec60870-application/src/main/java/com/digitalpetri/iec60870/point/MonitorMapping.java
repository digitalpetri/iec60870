package com.digitalpetri.iec60870.point;

import com.digitalpetri.iec60870.address.InformationObjectAddress;
import com.digitalpetri.iec60870.asdu.InformationObject;
import com.digitalpetri.iec60870.asdu.element.BinaryCounterReading;
import com.digitalpetri.iec60870.asdu.element.DoublePointState;
import com.digitalpetri.iec60870.asdu.element.NormalizedValue;
import com.digitalpetri.iec60870.asdu.element.Qds;
import com.digitalpetri.iec60870.asdu.element.Vti;
import com.digitalpetri.iec60870.asdu.object.Bitstring32;
import com.digitalpetri.iec60870.asdu.object.Bitstring32WithCp24Time;
import com.digitalpetri.iec60870.asdu.object.Bitstring32WithCp56Time;
import com.digitalpetri.iec60870.asdu.object.DoublePointInformation;
import com.digitalpetri.iec60870.asdu.object.DoublePointWithCp24Time;
import com.digitalpetri.iec60870.asdu.object.DoublePointWithCp56Time;
import com.digitalpetri.iec60870.asdu.object.IntegratedTotals;
import com.digitalpetri.iec60870.asdu.object.IntegratedTotalsWithCp24Time;
import com.digitalpetri.iec60870.asdu.object.IntegratedTotalsWithCp56Time;
import com.digitalpetri.iec60870.asdu.object.MeasuredValueNormalized;
import com.digitalpetri.iec60870.asdu.object.MeasuredValueNormalizedNoQuality;
import com.digitalpetri.iec60870.asdu.object.MeasuredValueNormalizedWithCp24Time;
import com.digitalpetri.iec60870.asdu.object.MeasuredValueNormalizedWithCp56Time;
import com.digitalpetri.iec60870.asdu.object.MeasuredValueScaled;
import com.digitalpetri.iec60870.asdu.object.MeasuredValueScaledWithCp24Time;
import com.digitalpetri.iec60870.asdu.object.MeasuredValueScaledWithCp56Time;
import com.digitalpetri.iec60870.asdu.object.MeasuredValueShortFloat;
import com.digitalpetri.iec60870.asdu.object.MeasuredValueShortFloatWithCp24Time;
import com.digitalpetri.iec60870.asdu.object.MeasuredValueShortFloatWithCp56Time;
import com.digitalpetri.iec60870.asdu.object.SinglePointInformation;
import com.digitalpetri.iec60870.asdu.object.SinglePointWithCp24Time;
import com.digitalpetri.iec60870.asdu.object.SinglePointWithCp56Time;
import com.digitalpetri.iec60870.asdu.object.StepPositionInformation;
import com.digitalpetri.iec60870.asdu.object.StepPositionWithCp24Time;
import com.digitalpetri.iec60870.asdu.object.StepPositionWithCp56Time;
import com.digitalpetri.iec60870.asdu.time.Cp24Time2a;
import com.digitalpetri.iec60870.asdu.time.Cp56Time2a;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Bidirectional bridge between the wire-level monitor information objects in {@code
 * com.digitalpetri.iec60870.asdu.object} and the point model ({@link PointType}, {@link
 * PointValue}, and an optional acquisition {@link Instant}).
 *
 * <p>Inbound, {@link #typeOf(InformationObject)} classifies a monitor object into its {@link
 * PointType} family and {@link #extract(InformationObject)} recovers its value, quality, and
 * timestamp. Outbound, {@link #toMonitorObject(PointType, InformationObjectAddress, PointValue,
 * TimeTagStyle)} builds the monitor record for a chosen time-tag style.
 *
 * <p>The bridge covers the common monitor families — single-point, double-point, step position,
 * 32-bit bit string, normalized / scaled / short-float measured values, and integrated totals — in
 * their untimed, CP24Time2a, and CP56Time2a forms. The normalized family additionally accepts the
 * no-quality variant (M_ME_ND_1) on extraction. Inbound objects that are not monitor objects (for
 * example commands, packed-event, or parameter types) yield {@link Optional#empty()} from {@link
 * #extract}.
 *
 * <h2>Time tags</h2>
 *
 * <p>CP56Time2a time tags are resolved to an {@link Instant} at {@link ZoneOffset#UTC}. CP24Time2a
 * time tags carry only the milliseconds and minute of an event and cannot be resolved to an
 * absolute instant on their own, so extraction omits the timestamp for CP24 variants while still
 * recovering the value and quality. Untimed objects carry no timestamp.
 *
 * <p>Example:
 *
 * <pre>{@code
 * Optional<PointValueExtraction> result = MonitorMapping.extract(monitorObject);
 * result.ifPresent(e -> handle(e.value(), e.timestamp()));
 * }</pre>
 */
public final class MonitorMapping {

  /** The zone offset used to resolve CP56Time2a time tags to an {@link Instant}. */
  private static final ZoneOffset TIME_ZONE = ZoneOffset.UTC;

  private MonitorMapping() {}

  /**
   * Classifies a monitor information object into its {@link PointType} family.
   *
   * @param monitorObject the monitor information object to classify.
   * @return the point type family of {@code monitorObject}.
   * @throws NullPointerException if {@code monitorObject} is {@code null}.
   * @throws IllegalArgumentException if {@code monitorObject} is not a supported monitor type.
   */
  public static PointType typeOf(InformationObject monitorObject) {
    Objects.requireNonNull(monitorObject, "monitorObject");
    if (monitorObject instanceof SinglePointInformation
        || monitorObject instanceof SinglePointWithCp24Time
        || monitorObject instanceof SinglePointWithCp56Time) {
      return PointType.SINGLE_POINT;
    }
    if (monitorObject instanceof DoublePointInformation
        || monitorObject instanceof DoublePointWithCp24Time
        || monitorObject instanceof DoublePointWithCp56Time) {
      return PointType.DOUBLE_POINT;
    }
    if (monitorObject instanceof StepPositionInformation
        || monitorObject instanceof StepPositionWithCp24Time
        || monitorObject instanceof StepPositionWithCp56Time) {
      return PointType.STEP_POSITION;
    }
    if (monitorObject instanceof Bitstring32
        || monitorObject instanceof Bitstring32WithCp24Time
        || monitorObject instanceof Bitstring32WithCp56Time) {
      return PointType.BITSTRING32;
    }
    if (monitorObject instanceof MeasuredValueNormalized
        || monitorObject instanceof MeasuredValueNormalizedNoQuality
        || monitorObject instanceof MeasuredValueNormalizedWithCp24Time
        || monitorObject instanceof MeasuredValueNormalizedWithCp56Time) {
      return PointType.NORMALIZED;
    }
    if (monitorObject instanceof MeasuredValueScaled
        || monitorObject instanceof MeasuredValueScaledWithCp24Time
        || monitorObject instanceof MeasuredValueScaledWithCp56Time) {
      return PointType.SCALED;
    }
    if (monitorObject instanceof MeasuredValueShortFloat
        || monitorObject instanceof MeasuredValueShortFloatWithCp24Time
        || monitorObject instanceof MeasuredValueShortFloatWithCp56Time) {
      return PointType.SHORT_FLOAT;
    }
    if (monitorObject instanceof IntegratedTotals
        || monitorObject instanceof IntegratedTotalsWithCp24Time
        || monitorObject instanceof IntegratedTotalsWithCp56Time) {
      return PointType.INTEGRATED_TOTALS;
    }
    throw new IllegalArgumentException(
        "not a supported monitor object: " + monitorObject.getClass().getName());
  }

  /**
   * Extracts the value, quality, and timestamp carried by a monitor information object.
   *
   * <p>The returned {@link PointValueExtraction#value()} carries the same timestamp as {@link
   * PointValueExtraction#timestamp()} so that either accessor may be used. The timestamp is present
   * only for CP56Time2a-tagged objects; CP24Time2a-tagged and untimed objects yield an empty
   * timestamp.
   *
   * @param monitorObject the monitor information object to extract from.
   * @return the extracted value and timestamp, or {@link Optional#empty()} if {@code monitorObject}
   *     is not a supported monitor type.
   */
  public static Optional<PointValueExtraction> extract(InformationObject monitorObject) {
    // Single-point
    if (monitorObject instanceof SinglePointInformation o) {
      return Optional.of(extraction(PointValue.single(o.on()), o.quality(), null));
    }
    if (monitorObject instanceof SinglePointWithCp24Time o) {
      return Optional.of(extraction(PointValue.single(o.on()), o.quality(), null));
    }
    if (monitorObject instanceof SinglePointWithCp56Time o) {
      return Optional.of(extraction(PointValue.single(o.on()), o.quality(), instant(o.time())));
    }

    // Double-point
    if (monitorObject instanceof DoublePointInformation o) {
      return Optional.of(extraction(PointValue.doublePoint(o.state()), o.quality(), null));
    }
    if (monitorObject instanceof DoublePointWithCp24Time o) {
      return Optional.of(extraction(PointValue.doublePoint(o.state()), o.quality(), null));
    }
    if (monitorObject instanceof DoublePointWithCp56Time o) {
      return Optional.of(
          extraction(PointValue.doublePoint(o.state()), o.quality(), instant(o.time())));
    }

    // Step position
    if (monitorObject instanceof StepPositionInformation o) {
      return Optional.of(extraction(PointValue.stepPosition(o.value()), o.quality(), null));
    }
    if (monitorObject instanceof StepPositionWithCp24Time o) {
      return Optional.of(extraction(PointValue.stepPosition(o.value()), o.quality(), null));
    }
    if (monitorObject instanceof StepPositionWithCp56Time o) {
      return Optional.of(
          extraction(PointValue.stepPosition(o.value()), o.quality(), instant(o.time())));
    }

    // Bit string
    if (monitorObject instanceof Bitstring32 o) {
      return Optional.of(extraction(PointValue.bitstring(o.bits()), o.quality(), null));
    }
    if (monitorObject instanceof Bitstring32WithCp24Time o) {
      return Optional.of(extraction(PointValue.bitstring(o.bits()), o.quality(), null));
    }
    if (monitorObject instanceof Bitstring32WithCp56Time o) {
      return Optional.of(
          extraction(PointValue.bitstring(o.bits()), o.quality(), instant(o.time())));
    }

    // Normalized
    if (monitorObject instanceof MeasuredValueNormalized o) {
      return Optional.of(extraction(PointValue.normalized(o.value()), o.quality(), null));
    }
    if (monitorObject instanceof MeasuredValueNormalizedNoQuality o) {
      Qds good = new Qds(false, false, false, false, false);
      return Optional.of(extraction(PointValue.normalized(o.value()), good, null));
    }
    if (monitorObject instanceof MeasuredValueNormalizedWithCp24Time o) {
      return Optional.of(extraction(PointValue.normalized(o.value()), o.quality(), null));
    }
    if (monitorObject instanceof MeasuredValueNormalizedWithCp56Time o) {
      return Optional.of(
          extraction(PointValue.normalized(o.value()), o.quality(), instant(o.time())));
    }

    // Scaled
    if (monitorObject instanceof MeasuredValueScaled o) {
      return Optional.of(extraction(PointValue.scaled(o.value()), o.quality(), null));
    }
    if (monitorObject instanceof MeasuredValueScaledWithCp24Time o) {
      return Optional.of(extraction(PointValue.scaled(o.value()), o.quality(), null));
    }
    if (monitorObject instanceof MeasuredValueScaledWithCp56Time o) {
      return Optional.of(extraction(PointValue.scaled(o.value()), o.quality(), instant(o.time())));
    }

    // Short float
    if (monitorObject instanceof MeasuredValueShortFloat o) {
      return Optional.of(extraction(PointValue.shortFloat(o.value()), o.quality(), null));
    }
    if (monitorObject instanceof MeasuredValueShortFloatWithCp24Time o) {
      return Optional.of(extraction(PointValue.shortFloat(o.value()), o.quality(), null));
    }
    if (monitorObject instanceof MeasuredValueShortFloatWithCp56Time o) {
      return Optional.of(
          extraction(PointValue.shortFloat(o.value()), o.quality(), instant(o.time())));
    }

    // Integrated totals
    if (monitorObject instanceof IntegratedTotals o) {
      return Optional.of(counterExtraction(o.counter(), null));
    }
    if (monitorObject instanceof IntegratedTotalsWithCp24Time o) {
      return Optional.of(counterExtraction(o.counter(), null));
    }
    if (monitorObject instanceof IntegratedTotalsWithCp56Time o) {
      return Optional.of(counterExtraction(o.counter(), instant(o.time())));
    }

    return Optional.empty();
  }

  /**
   * Builds the monitor information object that carries the given point value at the given address
   * and time-tag style.
   *
   * <p>The concrete record is selected from {@code type} and {@code style}. For {@link
   * TimeTagStyle#CP24} and {@link TimeTagStyle#CP56} a time tag is required: the value's {@link
   * PointValue#timestamp()} is used when present, otherwise the current time. CP24Time2a retains
   * only the milliseconds and minute of that instant.
   *
   * <p>The value's runtime type must match {@code type} (for example {@link Boolean} for {@link
   * PointType#SINGLE_POINT}); see {@link PointValue} for the per-type Java representation.
   *
   * <p>For {@link PointType#INTEGRATED_TOTALS} the wire records carry no {@link Qds} descriptor, so
   * only the invalid flag of the value's quality is representable: it is OR-ed into the binary
   * counter reading's own invalid (IV) bit, and the remaining quality flags are dropped.
   *
   * <pre>{@code
   * InformationObject o = MonitorMapping.toMonitorObject(
   *     PointType.SHORT_FLOAT, ioa, PointValue.shortFloat(12.5f), TimeTagStyle.CP56);
   * }</pre>
   *
   * @param type the logical point type.
   * @param ioa the information object address of the produced object.
   * @param value the point value to encode; its runtime type must match {@code type}.
   * @param style the time-tag style of the produced object.
   * @return the monitor information object.
   * @throws NullPointerException if {@code type}, {@code ioa}, {@code value}, or {@code style} is
   *     {@code null}.
   * @throws IllegalArgumentException if the value's runtime type does not match {@code type}.
   */
  public static InformationObject toMonitorObject(
      PointType type, InformationObjectAddress ioa, PointValue<?> value, TimeTagStyle style) {

    Objects.requireNonNull(type, "type");
    Objects.requireNonNull(ioa, "ioa");
    Objects.requireNonNull(value, "value");
    Objects.requireNonNull(style, "style");

    Qds qds = value.quality().toQds();

    return switch (type) {
      case SINGLE_POINT -> {
        boolean on = as(value.value(), Boolean.class, type);
        yield switch (style) {
          case NONE -> new SinglePointInformation(ioa, on, qds);
          case CP24 -> new SinglePointWithCp24Time(ioa, on, qds, cp24(value));
          case CP56 -> new SinglePointWithCp56Time(ioa, on, qds, cp56(value));
        };
      }
      case DOUBLE_POINT -> {
        DoublePointState state = as(value.value(), DoublePointState.class, type);
        yield switch (style) {
          case NONE -> new DoublePointInformation(ioa, state, qds);
          case CP24 -> new DoublePointWithCp24Time(ioa, state, qds, cp24(value));
          case CP56 -> new DoublePointWithCp56Time(ioa, state, qds, cp56(value));
        };
      }
      case STEP_POSITION -> {
        Vti vti = as(value.value(), Vti.class, type);
        yield switch (style) {
          case NONE -> new StepPositionInformation(ioa, vti, qds);
          case CP24 -> new StepPositionWithCp24Time(ioa, vti, qds, cp24(value));
          case CP56 -> new StepPositionWithCp56Time(ioa, vti, qds, cp56(value));
        };
      }
      case BITSTRING32 -> {
        int bits = as(value.value(), Integer.class, type);
        yield switch (style) {
          case NONE -> new Bitstring32(ioa, bits, qds);
          case CP24 -> new Bitstring32WithCp24Time(ioa, bits, qds, cp24(value));
          case CP56 -> new Bitstring32WithCp56Time(ioa, bits, qds, cp56(value));
        };
      }
      case NORMALIZED -> {
        NormalizedValue nva = as(value.value(), NormalizedValue.class, type);
        yield switch (style) {
          case NONE -> new MeasuredValueNormalized(ioa, nva, qds);
          case CP24 -> new MeasuredValueNormalizedWithCp24Time(ioa, nva, qds, cp24(value));
          case CP56 -> new MeasuredValueNormalizedWithCp56Time(ioa, nva, qds, cp56(value));
        };
      }
      case SCALED -> {
        short scaled = as(value.value(), Short.class, type);
        yield switch (style) {
          case NONE -> new MeasuredValueScaled(ioa, scaled, qds);
          case CP24 -> new MeasuredValueScaledWithCp24Time(ioa, scaled, qds, cp24(value));
          case CP56 -> new MeasuredValueScaledWithCp56Time(ioa, scaled, qds, cp56(value));
        };
      }
      case SHORT_FLOAT -> {
        float f = as(value.value(), Float.class, type);
        yield switch (style) {
          case NONE -> new MeasuredValueShortFloat(ioa, f, qds);
          case CP24 -> new MeasuredValueShortFloatWithCp24Time(ioa, f, qds, cp24(value));
          case CP56 -> new MeasuredValueShortFloatWithCp56Time(ioa, f, qds, cp56(value));
        };
      }
      case INTEGRATED_TOTALS -> {
        BinaryCounterReading raw = as(value.value(), BinaryCounterReading.class, type);
        // The OR keeps the IV bit set when either source is invalid; in this guarded branch
        // value.quality().invalid() is necessarily true, but spelling out the union is intentional,
        // self-documenting, and yields the correct value.
        //noinspection ConstantValue
        BinaryCounterReading bcr =
            raw.invalid() == value.quality().invalid()
                ? raw
                : new BinaryCounterReading(
                    raw.value(),
                    raw.sequenceNumber(),
                    raw.carry(),
                    raw.adjusted(),
                    raw.invalid() || value.quality().invalid());
        yield switch (style) {
          case NONE -> new IntegratedTotals(ioa, bcr);
          case CP24 -> new IntegratedTotalsWithCp24Time(ioa, bcr, cp24(value));
          case CP56 -> new IntegratedTotalsWithCp56Time(ioa, bcr, cp56(value));
        };
      }
    };
  }

  /**
   * Builds an extraction result for a value carrying a {@link Qds} quality descriptor.
   *
   * @param value the point value (with good quality and no timestamp).
   * @param qds the wire quality descriptor to apply.
   * @param timestamp the resolved timestamp, or {@code null} if absent.
   * @return the extraction result.
   */
  private static <T> PointValueExtraction extraction(
      PointValue<T> value, Qds qds, @Nullable Instant timestamp) {
    PointValue<T> withQuality = value.withQuality(Quality.fromQds(qds));
    PointValue<T> finalValue = applyTimestamp(withQuality, timestamp);
    return new PointValueExtraction(finalValue, Optional.ofNullable(timestamp));
  }

  /**
   * Builds an extraction result for an integrated-totals value, mapping the counter's invalid flag
   * onto the point quality.
   *
   * @param counter the binary counter reading.
   * @param timestamp the resolved timestamp, or {@code null} if absent.
   * @return the extraction result.
   */
  private static PointValueExtraction counterExtraction(
      BinaryCounterReading counter, @Nullable Instant timestamp) {
    Quality quality = Quality.good().withInvalid(counter.invalid());
    PointValue<BinaryCounterReading> withQuality = PointValue.counter(counter).withQuality(quality);
    PointValue<BinaryCounterReading> finalValue = applyTimestamp(withQuality, timestamp);
    return new PointValueExtraction(finalValue, Optional.ofNullable(timestamp));
  }

  /**
   * Returns {@code value} with the given timestamp applied, or unchanged when the timestamp is
   * absent.
   *
   * @param value the point value.
   * @param timestamp the timestamp to apply, or {@code null} to leave the value unchanged.
   * @param <T> the value type.
   * @return the value with the timestamp applied, if present.
   */
  private static <T> PointValue<T> applyTimestamp(
      PointValue<T> value, @Nullable Instant timestamp) {
    return timestamp != null ? value.withTimestamp(timestamp) : value;
  }

  /**
   * Resolves a CP56Time2a time tag to an {@link Instant} at {@link ZoneOffset#UTC}.
   *
   * @param time the time tag.
   * @return the resolved instant.
   */
  private static Instant instant(Cp56Time2a time) {
    return time.toInstant(TIME_ZONE);
  }

  /**
   * Returns the CP56Time2a time tag for an outbound value, using its timestamp when present and the
   * current time otherwise.
   *
   * @param value the point value being encoded.
   * @return the time tag.
   */
  private static Cp56Time2a cp56(PointValue<?> value) {
    Instant instant = value.timestamp().orElseGet(Instant::now);
    return Cp56Time2a.from(instant, TIME_ZONE);
  }

  /**
   * Returns the CP24Time2a time tag for an outbound value, retaining only the milliseconds and
   * minute of its timestamp (or of the current time when none is present).
   *
   * @param value the point value being encoded.
   * @return the time tag.
   */
  private static Cp24Time2a cp24(PointValue<?> value) {
    Instant instant = value.timestamp().orElseGet(Instant::now);
    Cp56Time2a full = Cp56Time2a.from(instant, TIME_ZONE);
    return new Cp24Time2a(full.milliseconds(), full.minute(), false, true);
  }

  /**
   * Casts a point value's payload to the expected Java type for a point type.
   *
   * @param value the raw point value payload.
   * @param expected the expected Java class.
   * @param type the point type the payload must match.
   * @param <T> the expected Java type.
   * @return the payload cast to {@code expected}.
   * @throws IllegalArgumentException if {@code value} is not an instance of {@code expected}.
   */
  private static <T> T as(@Nullable Object value, Class<T> expected, PointType type) {
    if (!expected.isInstance(value)) {
      throw new IllegalArgumentException(
          "value for "
              + type
              + " must be a "
              + expected.getSimpleName()
              + " but was "
              + (value == null ? "null" : value.getClass().getName()));
    }
    return expected.cast(value);
  }
}
