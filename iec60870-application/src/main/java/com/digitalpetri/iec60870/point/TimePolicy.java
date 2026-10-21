package com.digitalpetri.iec60870.point;

import com.digitalpetri.iec60870.asdu.time.Cp24Time2a;
import com.digitalpetri.iec60870.asdu.time.Cp56Time2a;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.Year;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.zone.ZoneOffsetTransition;
import java.time.zone.ZoneRules;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * The rules that convert CP56Time2a and CP24Time2a time tags to and from {@link Instant}.
 *
 * <p>IEC 60870-5 time tags carry wall-clock fields with no time zone. Many outstations run on UTC,
 * but others run on local time or a fixed offset. A time policy names the zone the peer's clock
 * uses and how to treat the summer-time (SU) bit, the day-of-week field, the two-digit year, and
 * the minute-only CP24Time2a tag. The client applies its policy to received monitor objects, to
 * command time tags, and to clock synchronization; the server applies its policy to every time tag
 * it publishes.
 *
 * <pre>{@code
 * ClientConfig config = ClientConfig.builder()
 *     .timePolicy(TimePolicy.of(ZoneId.of("Europe/Berlin")))
 *     .build();
 * }</pre>
 *
 * <h2>Receive rules</h2>
 *
 * <p>{@link #toInstant(Cp56Time2a)} maps the two-digit year to {@code baseYear..baseYear+99} and
 * resolves the local date-time in {@code zone}. When the local time falls in an overlap (the
 * repeated hour when summer time ends) and {@code honorSummerTime} is set, SU=1 picks the earlier
 * (summer) offset and SU=0 the later (standard) one; otherwise the earlier offset wins. A local
 * time in a gap (the skipped hour when summer time starts) shifts forward by the length of the gap.
 * Outside overlaps the zone rules decide, and an SU bit that disagrees with them stays visible on
 * {@link TimeTag#summerTime()}. The IV, RES1/GEN, and day-of-week fields do not affect the result.
 *
 * <p>{@link #complete(Cp24Time2a, Instant)} infers the missing hour and date of a CP24Time2a tag
 * from the receive time, when {@code completeCp24} is set.
 *
 * <h2>Send rules</h2>
 *
 * <p>{@link #toCp56(Instant)} writes the wall-clock fields in {@code zone}, sets SU when {@code
 * setSummerTime} is set and the zone rules say summer time is in effect, and writes day-of-week
 * {@code 0} ("not used") when {@code sendDayOfWeek} is clear. Encoded tags are always valid (IV=0)
 * and genuine (RES1/GEN=0).
 *
 * <p>{@link #utc()} reproduces the fixed UTC behavior of earlier releases byte for byte, and is the
 * default for both client and server.
 *
 * @param zone the zone of the peer's clock, for example {@link ZoneOffset#UTC}, {@code
 *     ZoneOffset.ofHours(1)}, or {@code ZoneId.of("Europe/Berlin")}.
 * @param honorSummerTime on receive, whether the SU bit chooses the offset for a local time that
 *     falls in a summer-time overlap.
 * @param setSummerTime on send, whether to set the SU bit when the zone rules say summer time is in
 *     effect.
 * @param sendDayOfWeek on send, whether to fill in the day-of-week field; {@code false} writes
 *     {@code 0}.
 * @param baseYear the first year of the century that two-digit years map to; years map to {@code
 *     baseYear..baseYear+99}.
 * @param completeCp24 on receive, whether to infer the hour and date of a CP24Time2a tag from the
 *     receive time.
 */
public record TimePolicy(
    ZoneId zone,
    boolean honorSummerTime,
    boolean setSummerTime,
    boolean sendDayOfWeek,
    int baseYear,
    boolean completeCp24) {

  /**
   * How far past the receive time a completed CP24Time2a tag may fall before it is moved back one
   * hour, to absorb a peer clock running slightly ahead of this one.
   */
  private static final Duration CP24_FUTURE_ALLOWANCE = Duration.ofMinutes(1);

  /**
   * Validates the components.
   *
   * @param zone the zone of the peer's clock.
   * @param honorSummerTime on receive, whether the SU bit chooses the offset for a local time that
   *     falls in a summer-time overlap.
   * @param setSummerTime on send, whether to set the SU bit when the zone rules say summer time is
   *     in effect.
   * @param sendDayOfWeek on send, whether to fill in the day-of-week field; {@code false} writes
   *     {@code 0}.
   * @param baseYear the first year of the century that two-digit years map to.
   * @param completeCp24 on receive, whether to infer the hour and date of a CP24Time2a tag from the
   *     receive time.
   * @throws NullPointerException if {@code zone} is null.
   * @throws IllegalArgumentException if {@code baseYear..baseYear+99} is not a range of supported
   *     years.
   */
  public TimePolicy {
    Objects.requireNonNull(zone, "zone");
    if (baseYear < Year.MIN_VALUE || baseYear > Year.MAX_VALUE - 99) {
      throw new IllegalArgumentException("baseYear out of range: " + baseYear);
    }
  }

  /**
   * Returns the default policy: UTC, two-digit years in 2000..2099, day-of-week sent, and no CP24
   * completion.
   *
   * <p>UTC has no summer time, so the SU bit is never set on send and never changes a received
   * time.
   *
   * @return the UTC policy.
   */
  public static TimePolicy utc() {
    return of(ZoneOffset.UTC);
  }

  /**
   * Returns a policy for a peer whose clock runs in {@code zone}.
   *
   * <p>The policy honors and sets the SU bit, sends the day of week, maps two-digit years to
   * 2000..2099, and does not complete CP24Time2a tags. Use the {@code with*} methods to change
   * those settings.
   *
   * @param zone the zone of the peer's clock.
   * @return the policy.
   * @throws NullPointerException if {@code zone} is null.
   */
  public static TimePolicy of(ZoneId zone) {
    return new TimePolicy(zone, true, true, true, 2000, false);
  }

  /**
   * Returns a copy of this policy with the given receive-side summer-time handling.
   *
   * @param honorSummerTime whether the SU bit chooses the offset in a summer-time overlap.
   * @return the updated policy.
   */
  public TimePolicy withHonorSummerTime(boolean honorSummerTime) {
    return new TimePolicy(
        zone, honorSummerTime, setSummerTime, sendDayOfWeek, baseYear, completeCp24);
  }

  /**
   * Returns a copy of this policy with the given send-side summer-time handling.
   *
   * @param setSummerTime whether to set the SU bit when summer time is in effect.
   * @return the updated policy.
   */
  public TimePolicy withSetSummerTime(boolean setSummerTime) {
    return new TimePolicy(
        zone, honorSummerTime, setSummerTime, sendDayOfWeek, baseYear, completeCp24);
  }

  /**
   * Returns a copy of this policy with the given day-of-week handling.
   *
   * @param sendDayOfWeek whether to fill in the day-of-week field; {@code false} writes {@code 0}.
   * @return the updated policy.
   */
  public TimePolicy withSendDayOfWeek(boolean sendDayOfWeek) {
    return new TimePolicy(
        zone, honorSummerTime, setSummerTime, sendDayOfWeek, baseYear, completeCp24);
  }

  /**
   * Returns a copy of this policy with the given century.
   *
   * <p>For example, {@code withBaseYear(1990)} maps year {@code 95} to 1995 and year {@code 5} to
   * 2005.
   *
   * @param baseYear the first year of the century that two-digit years map to.
   * @return the updated policy.
   * @throws IllegalArgumentException if {@code baseYear..baseYear+99} is not a range of supported
   *     years.
   */
  public TimePolicy withBaseYear(int baseYear) {
    return new TimePolicy(
        zone, honorSummerTime, setSummerTime, sendDayOfWeek, baseYear, completeCp24);
  }

  /**
   * Returns a copy of this policy with the given CP24Time2a completion setting.
   *
   * @param completeCp24 whether to infer the hour and date of a CP24Time2a tag from the receive
   *     time.
   * @return the updated policy.
   */
  public TimePolicy withCompleteCp24(boolean completeCp24) {
    return new TimePolicy(
        zone, honorSummerTime, setSummerTime, sendDayOfWeek, baseYear, completeCp24);
  }

  /**
   * Resolves a CP56Time2a time tag to an instant.
   *
   * <p>The result is present whenever the fields name a real date-time, including for a tag marked
   * invalid (IV=1). It is empty when the tag is not {@linkplain Cp56Time2a#isWellFormed()
   * well-formed} (for example the all-zero tag of a device whose clock was never set, or February
   * 30) or when the fields do not form a calendar date in the mapped century, for example February
   * 29 in a year that is not a leap year. This method never throws for a received tag.
   *
   * @param time the time tag.
   * @return the resolved instant, or empty if the fields cannot be resolved.
   * @throws NullPointerException if {@code time} is null.
   */
  public Optional<Instant> toInstant(Cp56Time2a time) {
    Objects.requireNonNull(time, "time");
    if (!time.isWellFormed()) {
      return Optional.empty();
    }
    LocalDateTime local;
    try {
      local =
          LocalDateTime.of(
              fullYear(time.year()),
              time.month(),
              time.dayOfMonth(),
              time.hour(),
              time.minute(),
              time.milliseconds() / 1000,
              (time.milliseconds() % 1000) * 1_000_000);
    } catch (DateTimeException e) {
      return Optional.empty();
    }
    // With no preferred offset, an overlap resolves to the earlier (summer) offset and a gap
    // shifts forward.
    ZonedDateTime resolved = ZonedDateTime.ofLocal(local, zone, null);
    if (honorSummerTime && !time.summerTime()) {
      resolved = resolved.withLaterOffsetAtOverlap();
    }
    return Optional.of(resolved.toInstant());
  }

  /**
   * Infers the instant of a CP24Time2a time tag from the time it was received.
   *
   * <p>A CP24Time2a tag carries only the minute and milliseconds of the event's wall-clock time in
   * {@code zone}. The result is the latest instant, no more than a minute after the receive time
   * and no more than an hour before it, whose wall-clock time has that minute and millisecond. The
   * result is correct only when the tag reaches this station less than an hour after the event, so
   * completion is opt-in through {@code completeCp24}.
   *
   * @param time the time tag.
   * @param receivedAt the time the carrying ASDU was received.
   * @return the inferred instant, or empty if {@code completeCp24} is clear, the tag is not
   *     {@linkplain Cp24Time2a#isWellFormed() well-formed}, or no instant in that window has the
   *     tag's minute and milliseconds.
   * @throws NullPointerException if {@code time} or {@code receivedAt} is null.
   */
  public Optional<Instant> complete(Cp24Time2a time, Instant receivedAt) {
    Objects.requireNonNull(time, "time");
    Objects.requireNonNull(receivedAt, "receivedAt");
    if (!completeCp24 || !time.isWellFormed()) {
      return Optional.empty();
    }
    Instant limit = receivedAt.plus(CP24_FUTURE_ALLOWANCE);
    Instant windowStart = receivedAt.minus(Duration.ofHours(1));
    ZoneRules rules = zone.getRules();

    // Collect every offset in effect during the window, then search each one separately. Within a
    // fixed offset, stepping back whole hours keeps the tag's minute, so this holds across offset
    // changes of any size, including ones that are not whole hours or that skip a day.
    Set<ZoneOffset> offsets = new LinkedHashSet<>();
    offsets.add(rules.getOffset(limit));
    offsets.add(rules.getOffset(windowStart));
    ZoneOffsetTransition transition = rules.previousTransition(limit);
    while (transition != null && !transition.getInstant().isBefore(windowStart)) {
      offsets.add(transition.getOffsetBefore());
      offsets.add(transition.getOffsetAfter());
      transition = rules.previousTransition(transition.getInstant());
    }

    Instant latest = null;
    for (ZoneOffset offset : offsets) {
      OffsetDateTime candidate =
          limit
              .atOffset(offset)
              .withMinute(time.minute())
              .withSecond(time.milliseconds() / 1000)
              .withNano((time.milliseconds() % 1000) * 1_000_000);
      if (candidate.toInstant().isAfter(limit)) {
        candidate = candidate.minusHours(1);
      }
      // The latest candidate under this offset may fall where another offset applies; step back
      // until one names a real local time or the window ends.
      while (!candidate.toInstant().isBefore(windowStart)) {
        Instant instant = candidate.toInstant();
        if (rules.getOffset(instant).equals(offset)) {
          if (latest == null || instant.isAfter(latest)) {
            latest = instant;
          }
          break;
        }
        candidate = candidate.minusHours(1);
      }
    }
    return Optional.ofNullable(latest);
  }

  /**
   * Encodes an instant as a CP56Time2a time tag.
   *
   * @param instant the instant to encode.
   * @return the time tag, valid and genuine, with SU and day-of-week set according to this policy.
   * @throws NullPointerException if {@code instant} is null.
   * @throws IllegalArgumentException if the wall-clock year in {@code zone} is outside {@code
   *     baseYear..baseYear+99}.
   */
  public Cp56Time2a toCp56(Instant instant) {
    Objects.requireNonNull(instant, "instant");
    ZonedDateTime local = instant.atZone(zone);

    int year = local.getYear();
    if (year < baseYear || year > baseYear + 99) {
      throw new IllegalArgumentException(
          "year out of representable century [" + baseYear + ", " + (baseYear + 99) + "]: " + year);
    }

    return new Cp56Time2a(
        local.getSecond() * 1000 + local.getNano() / 1_000_000,
        local.getMinute(),
        local.getHour(),
        local.getDayOfMonth(),
        sendDayOfWeek ? local.getDayOfWeek().getValue() : 0,
        local.getMonthValue(),
        Math.floorMod(year, 100),
        false,
        setSummerTime && zone.getRules().isDaylightSavings(instant),
        true);
  }

  /**
   * Encodes an instant as a CP24Time2a time tag, keeping only the minute and milliseconds of its
   * wall-clock time in {@code zone}.
   *
   * @param instant the instant to encode.
   * @return the time tag, valid and genuine.
   * @throws NullPointerException if {@code instant} is null.
   */
  public Cp24Time2a toCp24(Instant instant) {
    Objects.requireNonNull(instant, "instant");
    ZonedDateTime local = instant.atZone(zone);
    return new Cp24Time2a(
        local.getSecond() * 1000 + local.getNano() / 1_000_000, local.getMinute(), false, true);
  }

  private int fullYear(int twoDigitYear) {
    return baseYear + Math.floorMod(twoDigitYear - baseYear, 100);
  }
}
