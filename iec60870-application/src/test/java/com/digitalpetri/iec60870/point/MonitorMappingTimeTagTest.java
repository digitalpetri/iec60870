package com.digitalpetri.iec60870.point;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.digitalpetri.iec60870.address.InformationObjectAddress;
import com.digitalpetri.iec60870.asdu.InformationObject;
import com.digitalpetri.iec60870.asdu.element.EventState;
import com.digitalpetri.iec60870.asdu.element.Qds;
import com.digitalpetri.iec60870.asdu.element.Sep;
import com.digitalpetri.iec60870.asdu.object.EventOfProtectionEquipment;
import com.digitalpetri.iec60870.asdu.object.EventOfProtectionEquipmentWithCp56Time;
import com.digitalpetri.iec60870.asdu.object.MeasuredValueShortFloat;
import com.digitalpetri.iec60870.asdu.object.SinglePointWithCp24Time;
import com.digitalpetri.iec60870.asdu.object.SinglePointWithCp56Time;
import com.digitalpetri.iec60870.asdu.time.Cp16Time2a;
import com.digitalpetri.iec60870.asdu.time.Cp24Time2a;
import com.digitalpetri.iec60870.asdu.time.Cp56Time2a;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class MonitorMappingTimeTagTest {

  private static final InformationObjectAddress IOA = InformationObjectAddress.of(100);
  private static final Qds GOOD = new Qds(false, false, false, false, false);
  private static final TimePolicy BERLIN = TimePolicy.of(ZoneId.of("Europe/Berlin"));
  private static final Instant RECEIVED = Instant.parse("2026-06-01T10:05:00Z");

  @Test
  void cp56TagResolvesInPolicyZone() {
    var local = new Cp56Time2a(0, 0, 14, 1, 1, 6, 26, false, true, true);

    PointValueExtraction e =
        MonitorMapping.extract(
                new SinglePointWithCp56Time(IOA, true, GOOD, local), BERLIN, RECEIVED)
            .orElseThrow();

    Instant expected = Instant.parse("2026-06-01T12:00:00Z");
    assertEquals(Optional.of(expected), e.timestamp());
    assertEquals(Optional.of(expected), e.value().timestamp());
    TimeTag tag = e.timeTag().orElseThrow();
    assertEquals(TimeTagStyle.CP56, tag.style());
    assertEquals(local, tag.cp56());
    assertNull(tag.cp24());
    assertTrue(tag.summerTime());
    assertFalse(tag.invalid());
    assertFalse(tag.substituted());
    assertFalse(tag.inferred());
  }

  @Test
  void flagsRemainVisibleOnTimeTag() {
    var clean = new Cp56Time2a(0, 0, 14, 1, 1, 6, 26, false, true, true);
    var flagged = new Cp56Time2a(0, 0, 14, 1, 1, 6, 26, true, false, false);

    PointValueExtraction a =
        MonitorMapping.extract(new SinglePointWithCp56Time(IOA, true, GOOD, clean)).orElseThrow();
    PointValueExtraction b =
        MonitorMapping.extract(new SinglePointWithCp56Time(IOA, true, GOOD, flagged)).orElseThrow();

    assertNotEquals(a, b);
    TimeTag tag = b.timeTag().orElseThrow();
    assertTrue(tag.invalid());
    assertFalse(tag.summerTime());
    assertTrue(tag.substituted());
    assertEquals(flagged, tag.cp56());
  }

  @Test
  void invalidTagKeepsInstantButHasNoTimestamp() {
    var invalid = new Cp56Time2a(0, 0, 14, 1, 1, 6, 26, true, true, true);

    PointValueExtraction e =
        MonitorMapping.extract(
                new SinglePointWithCp56Time(IOA, true, GOOD, invalid), BERLIN, RECEIVED)
            .orElseThrow();

    TimeTag tag = e.timeTag().orElseThrow();
    assertEquals(Optional.of(Instant.parse("2026-06-01T12:00:00Z")), tag.instant());
    assertEquals(Optional.empty(), tag.timestamp());
    assertEquals(Optional.empty(), e.timestamp());
    assertEquals(Optional.empty(), e.value().timestamp());
  }

  @Test
  void unresolvableTagHasRawFieldsAndNoInstant() {
    // February 29 of year 00 does not exist when years map to 2050..2149.
    var tag = new Cp56Time2a(0, 0, 0, 29, 0, 2, 0, false, false, true);

    PointValueExtraction e =
        MonitorMapping.extract(
                new SinglePointWithCp56Time(IOA, true, GOOD, tag),
                TimePolicy.utc().withBaseYear(2050),
                RECEIVED)
            .orElseThrow();

    assertEquals(tag, e.timeTag().orElseThrow().cp56());
    assertEquals(Optional.empty(), e.timeTag().orElseThrow().instant());
    assertEquals(Optional.empty(), e.timestamp());
  }

  @Test
  void cp24TagIsKeptWithoutInstantByDefault() {
    var raw = new Cp24Time2a(12_345, 17, false, false);

    PointValueExtraction e =
        MonitorMapping.extract(new SinglePointWithCp24Time(IOA, true, GOOD, raw)).orElseThrow();

    TimeTag tag = e.timeTag().orElseThrow();
    assertEquals(TimeTagStyle.CP24, tag.style());
    assertEquals(raw, tag.cp24());
    assertNull(tag.cp56());
    assertEquals(Optional.empty(), tag.instant());
    assertFalse(tag.inferred());
    assertTrue(tag.substituted());
    assertFalse(tag.summerTime());
    assertEquals(Optional.empty(), e.timestamp());
  }

  @Test
  void cp24TagIsCompletedWhenEnabled() {
    TimePolicy policy = TimePolicy.utc().withCompleteCp24(true);

    PointValueExtraction recent =
        MonitorMapping.extract(
                new SinglePointWithCp24Time(IOA, true, GOOD, new Cp24Time2a(0, 3, false, true)),
                policy,
                RECEIVED)
            .orElseThrow();
    PointValueExtraction older =
        MonitorMapping.extract(
                new SinglePointWithCp24Time(IOA, true, GOOD, new Cp24Time2a(0, 58, false, true)),
                policy,
                RECEIVED)
            .orElseThrow();

    assertEquals(Optional.of(Instant.parse("2026-06-01T10:03:00Z")), recent.timestamp());
    assertTrue(recent.timeTag().orElseThrow().inferred());
    assertEquals(Optional.of(Instant.parse("2026-06-01T09:58:00Z")), older.timestamp());
    assertTrue(older.timeTag().orElseThrow().inferred());
    assertEquals(older.timestamp(), older.value().timestamp());
  }

  @Test
  void untimedObjectHasNoTimeTag() {
    PointValueExtraction e =
        MonitorMapping.extract(new MeasuredValueShortFloat(IOA, 1.5f, GOOD)).orElseThrow();

    assertEquals(Optional.empty(), e.timeTag());
    assertEquals(Optional.empty(), e.timestamp());
  }

  @Test
  void timeTagReadsUnmappedProtectionEvents() {
    var cp56 = new Cp56Time2a(0, 0, 14, 1, 1, 6, 26, false, true, true);
    InformationObject withCp56 =
        new EventOfProtectionEquipmentWithCp56Time(
            IOA,
            new Sep(EventState.OFF, false, false, false, false, false),
            new Cp16Time2a(10),
            cp56);
    InformationObject withCp24 =
        new EventOfProtectionEquipment(
            IOA,
            new Sep(EventState.OFF, false, false, false, false, false),
            new Cp16Time2a(10),
            new Cp24Time2a(0, 3, false, true));

    assertEquals(Optional.empty(), MonitorMapping.extract(withCp56));
    assertEquals(
        Optional.of(Instant.parse("2026-06-01T12:00:00Z")),
        TimeTag.from(withCp56, BERLIN, RECEIVED).orElseThrow().instant());
    assertEquals(TimeTagStyle.CP24, TimeTag.from(withCp24, BERLIN, RECEIVED).orElseThrow().style());
    assertEquals(
        Optional.empty(),
        TimeTag.from(new MeasuredValueShortFloat(IOA, 1.5f, GOOD), BERLIN, RECEIVED));
  }

  @Test
  void timeTagRejectsInconsistentComponents() {
    var cp56 = new Cp56Time2a(0, 0, 0, 1, 0, 1, 26, false, false, true);
    var cp24 = new Cp24Time2a(0, 0, false, true);

    assertThrows(
        IllegalArgumentException.class,
        () -> new TimeTag(TimeTagStyle.NONE, cp56, null, Optional.empty(), false));
    assertThrows(
        IllegalArgumentException.class,
        () -> new TimeTag(TimeTagStyle.CP56, null, cp24, Optional.empty(), false));
    assertThrows(
        IllegalArgumentException.class,
        () -> new TimeTag(TimeTagStyle.CP56, cp56, cp24, Optional.empty(), false));
    assertThrows(
        IllegalArgumentException.class,
        () -> new TimeTag(TimeTagStyle.CP56, cp56, null, Optional.empty(), true));
    assertThrows(
        IllegalArgumentException.class,
        () -> new TimeTag(TimeTagStyle.CP24, null, null, Optional.empty(), false));
  }

  @Test
  void toMonitorObjectEncodesWithPolicy() {
    PointValue<Boolean> value =
        PointValue.single(true).withTimestamp(Instant.parse("2026-06-01T12:00:00Z"));

    InformationObject cp56 =
        MonitorMapping.toMonitorObject(
            PointType.SINGLE_POINT, IOA, value, TimeTagStyle.CP56, BERLIN);
    InformationObject utc =
        MonitorMapping.toMonitorObject(PointType.SINGLE_POINT, IOA, value, TimeTagStyle.CP56);

    Cp56Time2a local = assertInstanceOf(SinglePointWithCp56Time.class, cp56).time();
    assertEquals(14, local.hour());
    assertTrue(local.summerTime());
    assertEquals(
        new SinglePointWithCp56Time(
            IOA, true, GOOD, new Cp56Time2a(0, 0, 12, 1, 1, 6, 26, false, false, true)),
        utc);
  }
}
