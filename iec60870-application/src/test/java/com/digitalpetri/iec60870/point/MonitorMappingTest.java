package com.digitalpetri.iec60870.point;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.digitalpetri.iec60870.address.InformationObjectAddress;
import com.digitalpetri.iec60870.asdu.InformationObject;
import com.digitalpetri.iec60870.asdu.element.BinaryCounterReading;
import com.digitalpetri.iec60870.asdu.element.Qds;
import com.digitalpetri.iec60870.asdu.object.IntegratedTotals;
import com.digitalpetri.iec60870.asdu.object.IntegratedTotalsWithCp24Time;
import com.digitalpetri.iec60870.asdu.object.IntegratedTotalsWithCp56Time;
import com.digitalpetri.iec60870.asdu.object.SinglePointWithCp56Time;
import com.digitalpetri.iec60870.asdu.time.Cp56Time2a;
import org.junit.jupiter.api.Test;

class MonitorMappingTest {

  private static final InformationObjectAddress IOA = InformationObjectAddress.of(1);

  @Test
  void integratedTotalsPropagatesInvalidQualityIntoBcrInvalidBit() {
    PointValue<BinaryCounterReading> value =
        PointValue.counter(new BinaryCounterReading(7, 0, false, false, false))
            .withQuality(Quality.invalidQuality());

    InformationObject object =
        MonitorMapping.toMonitorObject(PointType.INTEGRATED_TOTALS, IOA, value, TimeTagStyle.NONE);

    IntegratedTotals totals = assertInstanceOf(IntegratedTotals.class, object);
    assertTrue(totals.counter().invalid());
    assertEquals(7, totals.counter().value());
  }

  @Test
  void integratedTotalsGoodQualityLeavesBcrInvalidBitClear() {
    PointValue<BinaryCounterReading> value =
        PointValue.counter(new BinaryCounterReading(42, 3, true, true, false))
            .withQuality(Quality.good());

    InformationObject object =
        MonitorMapping.toMonitorObject(PointType.INTEGRATED_TOTALS, IOA, value, TimeTagStyle.NONE);

    IntegratedTotals totals = assertInstanceOf(IntegratedTotals.class, object);
    assertFalse(totals.counter().invalid());
    assertEquals(42, totals.counter().value());
    assertEquals(3, totals.counter().sequenceNumber());
    assertTrue(totals.counter().carry());
    assertTrue(totals.counter().adjusted());
  }

  @Test
  void integratedTotalsPreservesAlreadyInvalidBcrWithGoodQuality() {
    PointValue<BinaryCounterReading> value =
        PointValue.counter(new BinaryCounterReading(5, 0, false, false, true))
            .withQuality(Quality.good());

    InformationObject object =
        MonitorMapping.toMonitorObject(PointType.INTEGRATED_TOTALS, IOA, value, TimeTagStyle.NONE);

    IntegratedTotals totals = assertInstanceOf(IntegratedTotals.class, object);
    assertTrue(totals.counter().invalid());
  }

  @Test
  void integratedTotalsCp24PropagatesInvalidQuality() {
    PointValue<BinaryCounterReading> value =
        PointValue.counter(new BinaryCounterReading(7, 0, false, false, false))
            .withQuality(Quality.invalidQuality());

    InformationObject object =
        MonitorMapping.toMonitorObject(PointType.INTEGRATED_TOTALS, IOA, value, TimeTagStyle.CP24);

    IntegratedTotalsWithCp24Time totals =
        assertInstanceOf(IntegratedTotalsWithCp24Time.class, object);
    assertTrue(totals.counter().invalid());
  }

  @Test
  void integratedTotalsCp56PropagatesInvalidQuality() {
    PointValue<BinaryCounterReading> value =
        PointValue.counter(new BinaryCounterReading(7, 0, false, false, false))
            .withQuality(Quality.invalidQuality());

    InformationObject object =
        MonitorMapping.toMonitorObject(PointType.INTEGRATED_TOTALS, IOA, value, TimeTagStyle.CP56);

    IntegratedTotalsWithCp56Time totals =
        assertInstanceOf(IntegratedTotalsWithCp56Time.class, object);
    assertTrue(totals.counter().invalid());
  }

  @Test
  void malformedCp56TimeTagKeepsTheValueWithoutATimestamp() {
    // An unsynchronized device clock: all calendar fields zero, IV=1.
    Cp56Time2a unset = new Cp56Time2a(0, 0, 0, 0, 0, 0, 0, true, false, true);
    InformationObject object =
        new SinglePointWithCp56Time(IOA, true, new Qds(false, false, false, false, false), unset);

    PointValueExtraction extraction = MonitorMapping.extract(object).orElseThrow();

    assertEquals(Boolean.TRUE, extraction.value().value());
    assertTrue(extraction.timestamp().isEmpty());
    assertTrue(extraction.value().timestamp().isEmpty());
  }
}
