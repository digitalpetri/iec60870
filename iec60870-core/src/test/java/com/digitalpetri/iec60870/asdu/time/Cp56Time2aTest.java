package com.digitalpetri.iec60870.asdu.time;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.digitalpetri.iec60870.AsduDecodeException;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/**
 * Golden-byte framing tests for {@link Cp56Time2a.Serde} and range validation for the {@link
 * Cp56Time2a} compact constructor.
 */
class Cp56Time2aTest {

  /** Reads all readable bytes of {@code buffer} into a fresh array without releasing the buffer. */
  private static byte[] bytes(ByteBuf buffer) {
    byte[] out = new byte[buffer.readableBytes()];
    buffer.getBytes(buffer.readerIndex(), out);
    return out;
  }

  @Test
  void encodesKnownTimestampToGoldenBytes() {
    // Known timestamp: 2026-06-20 (Saturday) 14:30:45.500, valid, standard time, genuine.
    //
    // Octet breakdown (Mode 1, least significant octet first):
    //   milliseconds = 45 * 1000 + 500 = 45500 = 0xB1BC -> LE: BC B1
    //   octet3 = minute(30=0x1E) | (genuine? 0 : 0x40) | (invalid? 0x80 : 0)
    //          = 0x1E | 0x00 | 0x00 = 0x1E
    //   octet4 = hour(14=0x0E) | (summerTime? 0x80 : 0) = 0x0E
    //   octet5 = dayOfMonth(20=0x14) | (dayOfWeek(6) << 5 = 0xC0) = 0xD4
    //   octet6 = month(6) = 0x06
    //   octet7 = year(26 = 2026-2000) = 0x1A
    Cp56Time2a time = new Cp56Time2a(45500, 30, 14, 20, 6, 6, 26, false, false, true);

    byte[] golden = new byte[] {(byte) 0xBC, (byte) 0xB1, 0x1E, 0x0E, (byte) 0xD4, 0x06, 0x1A};

    ByteBuf buffer = Unpooled.buffer();
    try {
      Cp56Time2a.Serde.encode(time, buffer);

      assertEquals(7, buffer.readableBytes());
      assertArrayEquals(golden, bytes(buffer));

      Cp56Time2a decoded = Cp56Time2a.Serde.decode(buffer);
      assertEquals(time, decoded);
      assertEquals(0, buffer.readableBytes());
    } finally {
      buffer.release();
    }
  }

  @Test
  void encodesInvalidSummerTimeSubstitutedFlags() {
    // Same calendar fields but invalid=true, summerTime=true, genuine=false (substituted).
    //   octet3 = 0x1E | 0x40 (substituted) | 0x80 (invalid) = 0xDE
    //   octet4 = 0x0E | 0x80 (summer time) = 0x8E
    Cp56Time2a time = new Cp56Time2a(45500, 30, 14, 20, 6, 6, 26, true, true, false);

    byte[] golden =
        new byte[] {(byte) 0xBC, (byte) 0xB1, (byte) 0xDE, (byte) 0x8E, (byte) 0xD4, 0x06, 0x1A};

    ByteBuf buffer = Unpooled.buffer();
    try {
      Cp56Time2a.Serde.encode(time, buffer);
      assertArrayEquals(golden, bytes(buffer));
      assertEquals(time, Cp56Time2a.Serde.decode(buffer));
    } finally {
      buffer.release();
    }
  }

  @Test
  void rejectsMillisecondsOutOfRange() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new Cp56Time2a(60000, 0, 0, 1, 0, 1, 0, false, false, true));
  }

  @Test
  void rejectsHourOutOfRange() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new Cp56Time2a(0, 0, 24, 1, 0, 1, 0, false, false, true));
  }

  @Test
  void rejectsMonthOutOfRange() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new Cp56Time2a(0, 0, 0, 1, 0, 13, 0, false, false, true));
  }

  @Test
  void decodeRejectsCalendarImpossibleDate() {
    // Day 31, month 2 (February 31): each field is independently in range but the combination is
    // not a valid calendar date. Decode must surface this as AsduDecodeException.
    //   milliseconds = 0 -> LE: 00 00
    //   octet3 = minute(0) -> 0x00
    //   octet4 = hour(0) -> 0x00
    //   octet5 = dayOfMonth(31 = 0x1F) | (dayOfWeek(0) << 5) = 0x1F
    //   octet6 = month(2) = 0x02
    //   octet7 = year(0) = 0x00
    ByteBuf buffer = Unpooled.buffer();
    try {
      buffer.writeBytes(new byte[] {0x00, 0x00, 0x00, 0x00, 0x1F, 0x02, 0x00});
      assertThrows(AsduDecodeException.class, () -> Cp56Time2a.Serde.decode(buffer));
    } finally {
      buffer.release();
    }
  }

  @Test
  void constructorRejectsCalendarImpossibleDate() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new Cp56Time2a(0, 0, 0, 31, 0, 2, 0, false, false, true));
  }

  @Test
  void validDateRoundTripsAndConvertsToInstant() {
    // 2026-06-20 14:30:45.500 UTC.
    Cp56Time2a time = new Cp56Time2a(45500, 30, 14, 20, 6, 6, 26, false, false, true);

    ByteBuf buffer = Unpooled.buffer();
    try {
      Cp56Time2a.Serde.encode(time, buffer);
      Cp56Time2a decoded = Cp56Time2a.Serde.decode(buffer);
      assertEquals(time, decoded);

      Instant instant = decoded.toInstant(ZoneOffset.UTC);
      assertEquals(Instant.parse("2026-06-20T14:30:45.500Z"), instant);
    } finally {
      buffer.release();
    }
  }
}
