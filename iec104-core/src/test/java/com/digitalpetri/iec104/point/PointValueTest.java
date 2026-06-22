package com.digitalpetri.iec104.point;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.digitalpetri.iec104.asdu.element.BinaryCounterReading;
import com.digitalpetri.iec104.asdu.element.DoublePointState;
import com.digitalpetri.iec104.asdu.element.NormalizedValue;
import com.digitalpetri.iec104.asdu.element.Vti;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Tests that {@link PointValue} carries and validates its {@link PointType}. */
class PointValueTest {

  @Test
  void factoriesSetTheMatchingType() {
    assertEquals(PointType.SINGLE_POINT, PointValue.single(true).type());
    assertEquals(PointType.DOUBLE_POINT, PointValue.doublePoint(DoublePointState.ON).type());
    assertEquals(PointType.STEP_POSITION, PointValue.stepPosition(new Vti(3, false)).type());
    assertEquals(PointType.BITSTRING32, PointValue.bitstring(0x1234).type());
    assertEquals(PointType.NORMALIZED, PointValue.normalized(NormalizedValue.of(0.5)).type());
    assertEquals(PointType.SCALED, PointValue.scaled((short) 7).type());
    assertEquals(PointType.SHORT_FLOAT, PointValue.shortFloat(1.5f).type());
    assertEquals(
        PointType.INTEGRATED_TOTALS,
        PointValue.counter(new BinaryCounterReading(100, 0, false, false, false)).type());
  }

  @Test
  void constructorRejectsValueOfWrongClassForType() {
    // A String is not valid for SCALED, which expects a Short.
    assertThrows(
        IllegalArgumentException.class,
        () -> new PointValue<>(PointType.SCALED, "nope", Quality.good(), Optional.empty()));
  }

  @Test
  void constructorRejectsNullType() {
    assertThrows(
        NullPointerException.class,
        () -> new PointValue<>(null, (short) 7, Quality.good(), Optional.empty()));
  }

  @Test
  void withQualityPreservesType() {
    PointValue<Short> updated = PointValue.scaled((short) 7).withQuality(Quality.invalidQuality());
    assertEquals(PointType.SCALED, updated.type());
    assertEquals(Quality.invalidQuality(), updated.quality());
  }

  @Test
  void withTimestampPreservesType() {
    Instant when = Instant.ofEpochSecond(1_700_000_000L);
    PointValue<Short> updated = PointValue.scaled((short) 7).withTimestamp(when);
    assertEquals(PointType.SCALED, updated.type());
    assertEquals(Optional.of(when), updated.timestamp());
  }
}
