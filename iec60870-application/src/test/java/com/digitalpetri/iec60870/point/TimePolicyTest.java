package com.digitalpetri.iec60870.point;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.digitalpetri.iec60870.asdu.time.Cp24Time2a;
import com.digitalpetri.iec60870.asdu.time.Cp56Time2a;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class TimePolicyTest {

  private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");

  /** A valid, genuine CP56Time2a tag with day-of-week unused. */
  private static Cp56Time2a tag(
      int year, int month, int day, int hour, int minute, int ms, boolean su) {
    return new Cp56Time2a(ms, minute, hour, day, 0, month, year, false, su, true);
  }

  @Test
  void localSummerTimeResolvesToUtc() {
    TimePolicy policy = TimePolicy.of(BERLIN);

    assertEquals(
        Optional.of(Instant.parse("2026-06-01T12:00:00Z")),
        policy.toInstant(tag(26, 6, 1, 14, 0, 0, true)));
  }

  @Test
  void summerTimeBitPicksOffsetInRepeatedHour() {
    TimePolicy policy = TimePolicy.of(BERLIN);

    assertEquals(
        Optional.of(Instant.parse("2026-10-25T00:30:00Z")),
        policy.toInstant(tag(26, 10, 25, 2, 30, 0, true)));
    assertEquals(
        Optional.of(Instant.parse("2026-10-25T01:30:00Z")),
        policy.toInstant(tag(26, 10, 25, 2, 30, 0, false)));
  }

  @Test
  void withoutHonorSummerTimeRepeatedHourTakesEarlierOffset() {
    TimePolicy policy = TimePolicy.of(BERLIN).withHonorSummerTime(false);

    assertEquals(
        Optional.of(Instant.parse("2026-10-25T00:30:00Z")),
        policy.toInstant(tag(26, 10, 25, 2, 30, 0, false)));
  }

  @Test
  void summerTimeBitOutsideOverlapDoesNotOverrideZoneRules() {
    TimePolicy policy = TimePolicy.of(BERLIN);

    // January is standard time in Berlin; a stray SU=1 does not shift the result.
    assertEquals(
        Optional.of(Instant.parse("2026-01-15T12:00:00Z")),
        policy.toInstant(tag(26, 1, 15, 13, 0, 0, true)));
  }

  @Test
  void skippedHourShiftsForward() {
    TimePolicy policy = TimePolicy.of(BERLIN);

    // 02:30 does not exist on 2026-03-29 in Berlin; it shifts to 03:30 CEST.
    assertEquals(
        Optional.of(Instant.parse("2026-03-29T01:30:00Z")),
        policy.toInstant(tag(26, 3, 29, 2, 30, 0, false)));
  }

  @Test
  void fixedOffsetIgnoresSeason() {
    TimePolicy policy = TimePolicy.of(ZoneOffset.ofHours(1));

    assertEquals(
        Optional.of(Instant.parse("2026-01-15T12:00:00Z")),
        policy.toInstant(tag(26, 1, 15, 13, 0, 0, false)));
    assertEquals(
        Optional.of(Instant.parse("2026-07-15T12:00:00Z")),
        policy.toInstant(tag(26, 7, 15, 13, 0, 0, false)));
  }

  @Test
  void flagsDoNotChangeResolvedInstant() {
    TimePolicy policy = TimePolicy.utc();
    Cp56Time2a clean = new Cp56Time2a(1234, 5, 6, 7, 0, 8, 26, false, false, true);
    Cp56Time2a flagged = new Cp56Time2a(1234, 5, 6, 7, 3, 8, 26, true, false, false);

    assertEquals(policy.toInstant(clean), policy.toInstant(flagged));
    assertEquals(Optional.of(Instant.parse("2026-08-07T06:05:01.234Z")), policy.toInstant(clean));
  }

  @Test
  void defaultCenturyIs2000() {
    assertEquals(
        Optional.of(Instant.parse("2090-01-01T00:00:00Z")),
        TimePolicy.utc().toInstant(tag(90, 1, 1, 0, 0, 0, false)));
  }

  @Test
  void baseYearMapsTwoDigitYears() {
    TimePolicy policy = TimePolicy.utc().withBaseYear(1990);

    assertEquals(
        Optional.of(Instant.parse("1995-03-04T00:00:00Z")),
        policy.toInstant(tag(95, 3, 4, 0, 0, 0, false)));
    assertEquals(
        Optional.of(Instant.parse("2005-03-04T00:00:00Z")),
        policy.toInstant(tag(5, 3, 4, 0, 0, 0, false)));
    assertEquals(
        Optional.of(Instant.parse("1990-03-04T00:00:00Z")),
        policy.toInstant(tag(90, 3, 4, 0, 0, 0, false)));
    assertEquals(
        Optional.of(Instant.parse("2089-03-04T00:00:00Z")),
        policy.toInstant(tag(89, 3, 4, 0, 0, 0, false)));
  }

  @Test
  void baseYearBoundsEncoding() {
    TimePolicy policy = TimePolicy.utc().withBaseYear(1990);

    assertEquals(95, policy.toCp56(Instant.parse("1995-03-04T00:00:00Z")).year());
    assertEquals(5, policy.toCp56(Instant.parse("2005-03-04T00:00:00Z")).year());
    assertEquals(90, policy.toCp56(Instant.parse("1990-01-01T00:00:00Z")).year());
    assertEquals(89, policy.toCp56(Instant.parse("2089-12-31T23:59:59.999Z")).year());
    assertThrows(
        IllegalArgumentException.class,
        () -> policy.toCp56(Instant.parse("1989-12-31T23:59:59.999Z")));
    assertThrows(
        IllegalArgumentException.class, () -> policy.toCp56(Instant.parse("2090-01-01T00:00:00Z")));
  }

  @Test
  void dateMissingFromMappedCenturyDoesNotResolve() {
    // February 29 of year 00 is valid for 2000 but not for 2100.
    TimePolicy policy = TimePolicy.utc().withBaseYear(2050);

    assertEquals(Optional.empty(), policy.toInstant(tag(0, 2, 29, 0, 0, 0, false)));
  }

  @Test
  void rejectsUnsupportedBaseYear() {
    assertThrows(
        IllegalArgumentException.class, () -> TimePolicy.utc().withBaseYear(Integer.MAX_VALUE));
  }

  @Test
  void encodesWallClockInZoneWithSummerTime() {
    TimePolicy policy = TimePolicy.of(BERLIN);

    Cp56Time2a summer = policy.toCp56(Instant.parse("2026-06-01T12:00:00.250Z"));
    assertEquals(new Cp56Time2a(250, 0, 14, 1, 1, 6, 26, false, true, true), summer);

    Cp56Time2a winter = policy.toCp56(Instant.parse("2026-01-15T12:00:00Z"));
    assertEquals(new Cp56Time2a(0, 0, 13, 15, 4, 1, 26, false, false, true), winter);
  }

  @Test
  void encodingHonorsSummerTimeAndDayOfWeekSettings() {
    TimePolicy policy = TimePolicy.of(BERLIN).withSetSummerTime(false).withSendDayOfWeek(false);

    Cp56Time2a summer = policy.toCp56(Instant.parse("2026-06-01T12:00:00Z"));
    assertEquals(new Cp56Time2a(0, 0, 14, 1, 0, 6, 26, false, false, true), summer);
  }

  @Test
  void encodedTimeRoundTrips() {
    TimePolicy policy = TimePolicy.of(BERLIN);
    for (String text :
        List.of(
            "2026-06-01T12:00:00Z",
            "2026-10-25T00:30:00Z",
            "2026-10-25T01:30:00Z",
            "2026-03-29T01:30:00Z")) {
      Instant instant = Instant.parse(text);
      assertEquals(Optional.of(instant), policy.toInstant(policy.toCp56(instant)), text);
    }
  }

  @Test
  void utcEncodingMatchesFixedOffsetEncoding() {
    for (String text :
        List.of(
            "2000-01-01T00:00:00Z",
            "2024-02-29T23:59:59.999Z",
            "2026-06-01T12:00:00Z",
            "2026-10-25T01:30:00.123Z",
            "2099-12-31T23:59:59.999Z")) {
      Instant instant = Instant.parse(text);
      assertEquals(Cp56Time2a.from(instant, ZoneOffset.UTC), TimePolicy.utc().toCp56(instant));
    }
  }

  @Test
  void utcEncodingRejectsSameYearsAsFixedOffsetEncoding() {
    Instant before = Instant.parse("1999-12-31T23:59:59Z");
    Instant after = Instant.parse("2100-01-01T00:00:00Z");

    assertThrows(IllegalArgumentException.class, () -> Cp56Time2a.from(before, ZoneOffset.UTC));
    assertThrows(IllegalArgumentException.class, () -> TimePolicy.utc().toCp56(before));
    assertThrows(IllegalArgumentException.class, () -> Cp56Time2a.from(after, ZoneOffset.UTC));
    assertThrows(IllegalArgumentException.class, () -> TimePolicy.utc().toCp56(after));
  }

  @Test
  void cp24CompletionIsOptIn() {
    Instant receivedAt = Instant.parse("2026-06-01T10:05:00Z");

    assertFalse(TimePolicy.utc().completeCp24());
    assertEquals(
        Optional.empty(), TimePolicy.utc().complete(new Cp24Time2a(0, 3, false, true), receivedAt));
  }

  @Test
  void cp24CompletesWithinPastHour() {
    TimePolicy policy = TimePolicy.utc().withCompleteCp24(true);
    Instant receivedAt = Instant.parse("2026-06-01T10:05:00Z");

    assertEquals(
        Optional.of(Instant.parse("2026-06-01T10:03:00Z")),
        policy.complete(new Cp24Time2a(0, 3, false, true), receivedAt));
    assertEquals(
        Optional.of(Instant.parse("2026-06-01T09:58:00Z")),
        policy.complete(new Cp24Time2a(0, 58, false, true), receivedAt));
    // A peer clock slightly ahead stays in the current hour.
    assertEquals(
        Optional.of(Instant.parse("2026-06-01T10:05:30.500Z")),
        policy.complete(new Cp24Time2a(30_500, 5, false, true), receivedAt));
  }

  @Test
  void cp24CompletesAcrossDayBoundary() {
    TimePolicy policy = TimePolicy.utc().withCompleteCp24(true);

    assertEquals(
        Optional.of(Instant.parse("2026-05-31T23:59:59.999Z")),
        policy.complete(
            new Cp24Time2a(59_999, 59, false, true), Instant.parse("2026-06-01T00:00:10Z")));
  }

  @Test
  void cp24CompletionUsesZoneMinute() {
    TimePolicy policy = TimePolicy.of(ZoneOffset.ofHoursMinutes(5, 30)).withCompleteCp24(true);
    // 10:05Z is 15:35 local.
    Instant receivedAt = Instant.parse("2026-06-01T10:05:00Z");

    assertEquals(
        Optional.of(Instant.parse("2026-06-01T10:03:00Z")),
        policy.complete(new Cp24Time2a(0, 33, false, true), receivedAt));
    assertEquals(
        Optional.of(Instant.parse("2026-06-01T09:20:00Z")),
        policy.complete(new Cp24Time2a(0, 50, false, true), receivedAt));
  }

  @Test
  void cp24CompletionReachesNextHourWithinAllowance() {
    TimePolicy policy = TimePolicy.utc().withCompleteCp24(true);

    assertEquals(
        Optional.of(Instant.parse("2026-06-01T11:00:00Z")),
        policy.complete(new Cp24Time2a(0, 0, false, true), Instant.parse("2026-06-01T10:59:30Z")));
    assertEquals(
        Optional.of(Instant.parse("2026-06-02T00:00:15Z")),
        policy.complete(
            new Cp24Time2a(15_000, 0, false, true), Instant.parse("2026-06-01T23:59:45Z")));
    // Beyond the allowance the previous hour wins.
    assertEquals(
        Optional.of(Instant.parse("2026-06-01T10:01:00Z")),
        policy.complete(new Cp24Time2a(0, 1, false, true), Instant.parse("2026-06-01T10:59:30Z")));
  }

  @Test
  void cp24CompletionKeepsMinuteAcrossHalfHourTransition() {
    // Lord Howe moves from +10:30 to +11:00 at 02:00 local on 2026-10-04 (15:30Z). 15:35Z is
    // 02:35 local; a minute-55 tag is 01:55 local (+10:30), ten minutes earlier.
    TimePolicy policy = TimePolicy.of(ZoneId.of("Australia/Lord_Howe")).withCompleteCp24(true);

    assertEquals(
        Optional.of(Instant.parse("2026-10-03T15:25:00Z")),
        policy.complete(new Cp24Time2a(0, 55, false, true), Instant.parse("2026-10-03T15:35:00Z")));
  }

  @Test
  void cp24CompletionAcrossTwoHourRollback() {
    // Troll moves from +02:00 to +00:00 at 01:00Z on 2026-10-25 (03:00 local back to 01:00).
    // 00:59:30Z is 02:59:30 local; a minute-55 tag is 02:55 local (+02:00), 4.5 minutes earlier.
    TimePolicy policy = TimePolicy.of(ZoneId.of("Antarctica/Troll")).withCompleteCp24(true);

    assertEquals(
        Optional.of(Instant.parse("2026-10-25T00:55:00Z")),
        policy.complete(new Cp24Time2a(0, 55, false, true), Instant.parse("2026-10-25T00:59:30Z")));
  }

  @Test
  void cp24CompletionAcrossSkippedDay() {
    // Apia moved from -10:00 to +14:00 at 10:00Z on 2011-12-30, skipping December 30 local.
    // A minute-55 tag received at 10:05Z is 23:55 local on December 29 (-10:00), at 09:55Z.
    TimePolicy policy = TimePolicy.of(ZoneId.of("Pacific/Apia")).withCompleteCp24(true);

    assertEquals(
        Optional.of(Instant.parse("2011-12-30T09:55:00Z")),
        policy.complete(new Cp24Time2a(0, 55, false, true), Instant.parse("2011-12-30T10:05:00Z")));
    // A minute-3 tag is 00:03 local on December 31 (+14:00), at 10:03Z.
    assertEquals(
        Optional.of(Instant.parse("2011-12-30T10:03:00Z")),
        policy.complete(new Cp24Time2a(0, 3, false, true), Instant.parse("2011-12-30T10:05:00Z")));
  }

  @Test
  void cp24CompletionInRepeatedHourPicksLatestPastInstant() {
    TimePolicy policy = TimePolicy.of(BERLIN).withCompleteCp24(true);
    // 01:10Z is 02:10 CET, the second pass of the repeated hour on 2026-10-25.
    Instant receivedAt = Instant.parse("2026-10-25T01:10:00Z");

    // 02:05 occurs in both passes; the later one (01:05Z, CET) is five minutes ago.
    assertEquals(
        Optional.of(Instant.parse("2026-10-25T01:05:00Z")),
        policy.complete(new Cp24Time2a(0, 5, false, true), receivedAt));
    // 02:50 CET is in the future, so the first pass (00:50Z, CEST) is the event.
    assertEquals(
        Optional.of(Instant.parse("2026-10-25T00:50:00Z")),
        policy.complete(new Cp24Time2a(0, 50, false, true), receivedAt));
  }

  @Test
  void cp24EncodingUsesZoneMinute() {
    TimePolicy policy = TimePolicy.of(ZoneOffset.ofHoursMinutes(5, 30));

    assertEquals(
        new Cp24Time2a(12_345, 33, false, true),
        policy.toCp24(Instant.parse("2026-06-01T10:03:12.345Z")));
    assertEquals(
        new Cp24Time2a(12_345, 3, false, true),
        TimePolicy.utc().toCp24(Instant.parse("2026-06-01T10:03:12.345Z")));
  }

  @Test
  void factoriesSetDocumentedDefaults() {
    TimePolicy policy = TimePolicy.of(BERLIN);

    assertEquals(BERLIN, policy.zone());
    assertTrue(policy.honorSummerTime());
    assertTrue(policy.setSummerTime());
    assertTrue(policy.sendDayOfWeek());
    assertEquals(2000, policy.baseYear());
    assertFalse(policy.completeCp24());
    assertEquals(ZoneOffset.UTC, TimePolicy.utc().zone());
  }
}
