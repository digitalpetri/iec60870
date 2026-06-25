package com.digitalpetri.iec60870.point;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.digitalpetri.iec60870.asdu.element.BinaryCounterReading;
import com.digitalpetri.iec60870.asdu.element.DoublePointState;
import com.digitalpetri.iec60870.asdu.element.NormalizedValue;
import com.digitalpetri.iec60870.asdu.element.Vti;
import org.junit.jupiter.api.Test;

/** Tests the {@link PointType#valueClass()} mapping from point type to natural Java value class. */
class PointTypeTest {

  @Test
  void valueClassMapsEachType() {
    assertEquals(Boolean.class, PointType.SINGLE_POINT.valueClass());
    assertEquals(DoublePointState.class, PointType.DOUBLE_POINT.valueClass());
    assertEquals(Vti.class, PointType.STEP_POSITION.valueClass());
    assertEquals(Integer.class, PointType.BITSTRING32.valueClass());
    assertEquals(NormalizedValue.class, PointType.NORMALIZED.valueClass());
    assertEquals(Short.class, PointType.SCALED.valueClass());
    assertEquals(Float.class, PointType.SHORT_FLOAT.valueClass());
    assertEquals(BinaryCounterReading.class, PointType.INTEGRATED_TOTALS.valueClass());
  }
}
