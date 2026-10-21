package com.digitalpetri.iec60870.asdu.time;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Golden-byte framing tests for {@link Cp56Time2a.Serde}, wire-field validation for the {@link
 * Cp56Time2a} compact constructor, and lenient handling of out-of-range fields.
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
  void constructorRejectsValuesThatDoNotFitTheWireFields() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new Cp56Time2a(65536, 0, 0, 1, 0, 1, 0, false, false, true));
    assertThrows(
        IllegalArgumentException.class,
        () -> new Cp56Time2a(0, 64, 0, 1, 0, 1, 0, false, false, true));
    assertThrows(
        IllegalArgumentException.class,
        () -> new Cp56Time2a(0, 0, 32, 1, 0, 1, 0, false, false, true));
    assertThrows(
        IllegalArgumentException.class,
        () -> new Cp56Time2a(0, 0, 0, 32, 0, 1, 0, false, false, true));
    assertThrows(
        IllegalArgumentException.class,
        () -> new Cp56Time2a(0, 0, 0, 1, 8, 1, 0, false, false, true));
    assertThrows(
        IllegalArgumentException.class,
        () -> new Cp56Time2a(0, 0, 0, 1, 0, 16, 0, false, false, true));
    assertThrows(
        IllegalArgumentException.class,
        () -> new Cp56Time2a(0, 0, 0, 1, 0, 1, 128, false, false, true));
    assertThrows(
        IllegalArgumentException.class,
        () -> new Cp56Time2a(-1, 0, 0, 1, 0, 1, 0, false, false, true));
  }

  @Test
  void outOfRangeFieldsAreKeptButNotWellFormed() {
    List<Cp56Time2a> malformed =
        List.of(
            new Cp56Time2a(60000, 0, 0, 1, 0, 1, 0, false, false, true), // milliseconds 60000
            new Cp56Time2a(0, 60, 0, 1, 0, 1, 0, false, false, true), // minute 60
            new Cp56Time2a(0, 0, 24, 1, 0, 1, 0, false, false, true), // hour 24
            new Cp56Time2a(0, 0, 0, 0, 0, 1, 0, false, false, true), // day 0
            new Cp56Time2a(0, 0, 0, 1, 0, 0, 0, false, false, true), // month 0
            new Cp56Time2a(0, 0, 0, 1, 0, 13, 0, false, false, true), // month 13
            new Cp56Time2a(0, 0, 0, 1, 0, 1, 100, false, false, true), // year 100
            new Cp56Time2a(0, 0, 0, 30, 0, 2, 26, false, false, true), // February 30
            new Cp56Time2a(0, 0, 0, 29, 0, 2, 25, false, false, true)); // February 29, 2025

    for (Cp56Time2a time : malformed) {
      assertFalse(time.isWellFormed(), time.toString());
      assertThrows(DateTimeException.class, () -> time.toInstant(ZoneOffset.UTC));
    }
  }

  @Test
  void leapDayIsWellFormed() {
    Cp56Time2a time = new Cp56Time2a(0, 0, 0, 29, 0, 2, 24, false, false, true);
    assertTrue(time.isWellFormed());
    assertEquals(Instant.parse("2024-02-29T00:00:00Z"), time.toInstant(ZoneOffset.UTC));
  }

  @Test
  void decodeKeepsAllZeroInvalidTimeTag() {
    // An unsynchronized device clock: all calendar fields zero, IV=1.
    ByteBuf buffer = Unpooled.buffer();
    try {
      buffer.writeBytes(new byte[] {0x00, 0x00, (byte) 0x80, 0x00, 0x00, 0x00, 0x00});
      Cp56Time2a decoded = Cp56Time2a.Serde.decode(buffer);

      assertEquals(new Cp56Time2a(0, 0, 0, 0, 0, 0, 0, true, false, true), decoded);
      assertFalse(decoded.isWellFormed());
    } finally {
      buffer.release();
    }
  }

  @Test
  void decodeKeepsEveryOutOfRangeFieldAndReencodesTheSameOctets() {
    // milliseconds 65535, minute 63, hour 31, day 31, month 15, year 127: every field at its wire
    // maximum and out of its specified range.
    byte[] wire = {(byte) 0xFF, (byte) 0xFF, 0x3F, 0x1F, 0x1F, 0x0F, 0x7F};
    ByteBuf buffer = Unpooled.buffer();
    try {
      buffer.writeBytes(wire);
      Cp56Time2a decoded = Cp56Time2a.Serde.decode(buffer);

      assertEquals(new Cp56Time2a(65535, 63, 31, 31, 0, 15, 127, false, false, true), decoded);
      assertFalse(decoded.isWellFormed());

      ByteBuf encoded = Unpooled.buffer();
      try {
        Cp56Time2a.Serde.encode(decoded, encoded);
        assertArrayEquals(wire, bytes(encoded));
      } finally {
        encoded.release();
      }
    } finally {
      buffer.release();
    }
  }

  @Test
  void wellFormednessIgnoresTheInvalidFlag() {
    Cp56Time2a time = new Cp56Time2a(45500, 30, 14, 20, 6, 6, 26, true, false, true);
    assertTrue(time.isWellFormed());
    assertEquals(Instant.parse("2026-06-20T14:30:45.500Z"), time.toInstant(ZoneOffset.UTC));
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
