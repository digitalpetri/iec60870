package com.digitalpetri.iec60870.asdu.time;

import com.digitalpetri.iec60870.AsduDecodeException;
import io.netty.buffer.ByteBuf;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * CP56Time2a — seven octet binary time, as defined by IEC 60870-5-4 §6.8 and IEC 60870-5-101
 * §7.2.6.18.
 *
 * <p>This element carries a full calendar date and time of day with millisecond resolution. It is
 * used to time-tag information objects and to convey clock-synchronization commands. In addition to
 * the calendar fields it carries an {@code invalid} flag (IV), a {@code summerTime} flag (SU), and
 * a {@code genuine} flag derived from the RES1/GEN bit.
 *
 * <p>The {@code genuine} component indicates whether the time tag was added by the acquiring device
 * (genuine time) or substituted by intermediate equipment or the controlling station (substituted
 * time). It maps to the RES1/GEN bit such that {@code genuine == true} corresponds to a RES1 bit of
 * {@code 0} (GEN&lt;0&gt; = genuine time).
 *
 * <p>The {@code dayOfWeek} component is optional on the wire: a value of {@code 0} means "not
 * used". It is not required to reconstruct an instant and is ignored by {@link
 * #toInstant(ZoneOffset)}.
 *
 * <p>Because the year is a two-digit field with no century, conversions to and from {@link Instant}
 * interpret the year against the 2000..2099 century.
 *
 * @param milliseconds the milliseconds within the minute, in the range {@code 0..59999}.
 * @param minute the minute of the hour, in the range {@code 0..59}.
 * @param hour the hour of the day, in the range {@code 0..23}.
 * @param dayOfMonth the day of the month, in the range {@code 1..31}.
 * @param dayOfWeek the day of the week ({@code 1}=Monday .. {@code 7}=Sunday), or {@code 0} if not
 *     used, in the range {@code 0..7}.
 * @param month the month of the year, in the range {@code 1..12}.
 * @param year the two-digit year, in the range {@code 0..99}.
 * @param invalid {@code true} if the time tag is marked invalid (IV bit set).
 * @param summerTime {@code true} if the time tag indicates summer time / daylight saving time (SU
 *     bit set).
 * @param genuine {@code true} if the time tag is genuine (RES1/GEN bit clear), {@code false} if
 *     substituted.
 */
public record Cp56Time2a(
    int milliseconds,
    int minute,
    int hour,
    int dayOfMonth,
    int dayOfWeek,
    int month,
    int year,
    boolean invalid,
    boolean summerTime,
    boolean genuine) {

  /**
   * Validates the component ranges of a {@code Cp56Time2a}.
   *
   * @param milliseconds the milliseconds within the minute, in the range {@code 0..59999}.
   * @param minute the minute of the hour, in the range {@code 0..59}.
   * @param hour the hour of the day, in the range {@code 0..23}.
   * @param dayOfMonth the day of the month, in the range {@code 1..31}.
   * @param dayOfWeek the day of the week ({@code 1}=Monday .. {@code 7}=Sunday), or {@code 0} if
   *     not used, in the range {@code 0..7}.
   * @param month the month of the year, in the range {@code 1..12}.
   * @param year the two-digit year, in the range {@code 0..99}.
   * @param invalid {@code true} if the time tag is marked invalid (IV bit set).
   * @param summerTime {@code true} if the time tag indicates summer time / daylight saving time (SU
   *     bit set).
   * @param genuine {@code true} if the time tag is genuine (RES1/GEN bit clear), {@code false} if
   *     substituted.
   * @throws IllegalArgumentException if any calendar component is outside its specified range:
   *     {@code milliseconds} {@code 0..59999}, {@code minute} {@code 0..59}, {@code hour} {@code
   *     0..23}, {@code dayOfMonth} {@code 1..31}, {@code dayOfWeek} {@code 0..7}, {@code month}
   *     {@code 1..12}, or {@code year} {@code 0..99}; or if the {@code dayOfMonth}/{@code month}
   *     combination is not a valid calendar date (such as February 31).
   */
  public Cp56Time2a {
    if (milliseconds < 0 || milliseconds > 59999) {
      throw new IllegalArgumentException("milliseconds out of range [0, 59999]: " + milliseconds);
    }
    if (minute < 0 || minute > 59) {
      throw new IllegalArgumentException("minute out of range [0, 59]: " + minute);
    }
    if (hour < 0 || hour > 23) {
      throw new IllegalArgumentException("hour out of range [0, 23]: " + hour);
    }
    if (dayOfMonth < 1 || dayOfMonth > 31) {
      throw new IllegalArgumentException("dayOfMonth out of range [1, 31]: " + dayOfMonth);
    }
    if (dayOfWeek < 0 || dayOfWeek > 7) {
      throw new IllegalArgumentException("dayOfWeek out of range [0, 7]: " + dayOfWeek);
    }
    if (month < 1 || month > 12) {
      throw new IllegalArgumentException("month out of range [1, 12]: " + month);
    }
    if (year < 0 || year > 99) {
      throw new IllegalArgumentException("year out of range [0, 99]: " + year);
    }
    // Day and month are independent wire fields, so calendar-impossible dates (e.g. February 31)
    // are peer-reachable. Reject them here so toInstant never throws for a constructed instance.
    int seconds = milliseconds / 1000;
    int nanos = (milliseconds % 1000) * 1_000_000;
    try {
      // Called for its DateTimeException side effect: validates the calendar date; result unused.
      //noinspection ResultOfMethodCallIgnored
      LocalDateTime.of(2000 + year, month, dayOfMonth, hour, minute, seconds, nanos);
    } catch (DateTimeException e) {
      throw new IllegalArgumentException("invalid CP56Time2a calendar date: " + e.getMessage());
    }
  }

  /**
   * Creates a {@code Cp56Time2a} from an {@link Instant} interpreted at the given zone offset.
   *
   * <p>The instant is converted to a wall-clock date and time using {@code offset}, then split into
   * the CP56Time2a calendar fields. The seconds and milliseconds of the instant are combined into
   * the {@code milliseconds} field ({@code seconds * 1000 + millis}). The {@code dayOfWeek} field
   * is populated from the calendar date ({@code 1}=Monday .. {@code 7}=Sunday). The resulting time
   * tag is marked valid, genuine, and with the {@code summerTime} flag clear.
   *
   * <p>Only the two low-order digits of the year are retained; the year must be within the
   * 2000..2099 century so that it round-trips through {@link #toInstant(ZoneOffset)}.
   *
   * @param instant the instant to convert.
   * @param offset the zone offset used to derive the wall-clock fields.
   * @return the time tag corresponding to {@code instant} at {@code offset}.
   * @throws IllegalArgumentException if the wall-clock year is outside the 2000..2099 century.
   */
  public static Cp56Time2a from(Instant instant, ZoneOffset offset) {
    LocalDateTime dateTime = LocalDateTime.ofInstant(instant, offset);

    int fullYear = dateTime.getYear();
    if (fullYear < 2000 || fullYear > 2099) {
      throw new IllegalArgumentException(
          "year out of representable century [2000, 2099]: " + fullYear);
    }

    int ms = dateTime.getSecond() * 1000 + dateTime.getNano() / 1_000_000;

    return new Cp56Time2a(
        ms,
        dateTime.getMinute(),
        dateTime.getHour(),
        dateTime.getDayOfMonth(),
        dateTime.getDayOfWeek().getValue(),
        dateTime.getMonthValue(),
        fullYear - 2000,
        false,
        false,
        true);
  }

  /**
   * Converts this time tag to an {@link Instant} at the given zone offset.
   *
   * <p>The two-digit {@code year} is interpreted against the 2000..2099 century. The {@code
   * milliseconds} field is split back into seconds and milliseconds of the minute. The {@code
   * dayOfWeek}, {@code invalid}, {@code summerTime}, and {@code genuine} components do not affect
   * the result.
   *
   * @param offset the zone offset at which the calendar fields are interpreted.
   * @return the instant represented by this time tag at {@code offset}.
   */
  public Instant toInstant(ZoneOffset offset) {
    int seconds = milliseconds / 1000;
    int nanos = (milliseconds % 1000) * 1_000_000;

    LocalDateTime dateTime =
        LocalDateTime.of(2000 + year, month, dayOfMonth, hour, minute, seconds, nanos);

    return dateTime.toInstant(offset);
  }

  /**
   * Encoder and decoder for the seven-octet CP56Time2a wire representation.
   *
   * <p>Wire layout (Mode 1, least significant octet first):
   *
   * <ul>
   *   <li>octets 1-2: milliseconds as an unsigned 16-bit integer, little-endian, range {@code
   *       0..59999};
   *   <li>octet 3: bit 8 = IV (invalid), bit 7 = RES1/GEN ({@code 0} = genuine, {@code 1} =
   *       substituted), bits 6..1 = minutes ({@code 0..59});
   *   <li>octet 4: bit 8 = SU (summer time), bits 7..6 = RES2, bits 5..1 = hours ({@code 0..23});
   *   <li>octet 5: bits 8..6 = day of week ({@code 0..7}), bits 5..1 = day of month ({@code
   *       1..31});
   *   <li>octet 6: bits 8..5 = RES3, bits 4..1 = months ({@code 1..12});
   *   <li>octet 7: bit 8 = RES4, bits 7..1 = years ({@code 0..99}).
   * </ul>
   *
   * <p>Reserved bits (RES2, RES3, RES4) are written as {@code 0} on encode and ignored on decode.
   */
  public static final class Serde {

    private Serde() {}

    /**
     * Encodes {@code time} into {@code buffer} as seven octets.
     *
     * <p>The buffer is owned by the caller; this method writes into it and never releases it.
     *
     * @param time the time element to encode.
     * @param buffer the destination buffer to write the seven octets into.
     */
    public static void encode(Cp56Time2a time, ByteBuf buffer) {
      buffer.writeShortLE(time.milliseconds());

      int octet3 = time.minute() & 0x3F;
      if (!time.genuine()) {
        octet3 |= 0x40;
      }
      if (time.invalid()) {
        octet3 |= 0x80;
      }
      buffer.writeByte(octet3);

      int octet4 = time.hour() & 0x1F;
      if (time.summerTime()) {
        octet4 |= 0x80;
      }
      buffer.writeByte(octet4);

      int octet5 = (time.dayOfMonth() & 0x1F) | ((time.dayOfWeek() & 0x07) << 5);
      buffer.writeByte(octet5);

      buffer.writeByte(time.month() & 0x0F);

      buffer.writeByte(time.year() & 0x7F);
    }

    /**
     * Decodes a {@code Cp56Time2a} from the next seven octets of {@code buffer}.
     *
     * <p>The buffer is owned by the caller; this method reads from it and never releases it.
     * Reserved bits are ignored. Decoded calendar fields are validated against their specified
     * ranges.
     *
     * @param buffer the source buffer positioned at the first octet of the time element.
     * @return the decoded time element.
     * @throws AsduDecodeException if any decoded calendar component is outside its specified range.
     */
    public static Cp56Time2a decode(ByteBuf buffer) {
      int milliseconds = buffer.readUnsignedShortLE();

      int octet3 = buffer.readUnsignedByte();
      int minute = octet3 & 0x3F;
      boolean genuine = (octet3 & 0x40) == 0;
      boolean invalid = (octet3 & 0x80) != 0;

      int octet4 = buffer.readUnsignedByte();
      int hour = octet4 & 0x1F;
      boolean summerTime = (octet4 & 0x80) != 0;

      int octet5 = buffer.readUnsignedByte();
      int dayOfMonth = octet5 & 0x1F;
      int dayOfWeek = (octet5 >> 5) & 0x07;

      int octet6 = buffer.readUnsignedByte();
      int month = octet6 & 0x0F;

      int octet7 = buffer.readUnsignedByte();
      int year = octet7 & 0x7F;

      try {
        return new Cp56Time2a(
            milliseconds,
            minute,
            hour,
            dayOfMonth,
            dayOfWeek,
            month,
            year,
            invalid,
            summerTime,
            genuine);
      } catch (IllegalArgumentException e) {
        throw new AsduDecodeException("malformed CP56Time2a: " + e.getMessage());
      }
    }
  }
}
