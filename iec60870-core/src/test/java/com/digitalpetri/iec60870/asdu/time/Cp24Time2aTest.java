package com.digitalpetri.iec60870.asdu.time;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

/**
 * Round-trip and out-of-range wire-data tests for {@link Cp24Time2a.Serde} and {@link
 * Cp16Time2a.Serde}, and wire-field validation for their compact constructors.
 */
class Cp24Time2aTest {

  @Test
  void roundTrips() {
    // milliseconds = 45 * 1000 + 500 = 45500, minute = 30, valid, genuine.
    Cp24Time2a time = new Cp24Time2a(45500, 30, false, true);

    ByteBuf buffer = Unpooled.buffer();
    try {
      Cp24Time2a.Serde.encode(time, buffer);
      assertEquals(3, buffer.readableBytes());

      Cp24Time2a decoded = Cp24Time2a.Serde.decode(buffer);
      assertEquals(time, decoded);
      assertEquals(0, buffer.readableBytes());
    } finally {
      buffer.release();
    }
  }

  @Test
  void decodeKeepsOutOfRangeFields() {
    // octet3 bits 6..1 carry the minute; values 60..63 (0x3C..0x3F) are out of range but decode
    // keeps them, so the information object that carries the tag is still delivered.
    for (int minute = 0x3C; minute <= 0x3F; minute++) {
      ByteBuf buffer = Unpooled.buffer();
      try {
        buffer.writeShortLE(65535);
        buffer.writeByte(0x80 | minute); // IV=1
        Cp24Time2a decoded = Cp24Time2a.Serde.decode(buffer);

        assertEquals(new Cp24Time2a(65535, minute, true, true), decoded);
        assertFalse(decoded.isWellFormed());
      } finally {
        buffer.release();
      }
    }
  }

  @Test
  void wellFormedChecksSpecifiedRanges() {
    assertTrue(new Cp24Time2a(59999, 59, true, true).isWellFormed());
    assertFalse(new Cp24Time2a(60000, 0, false, true).isWellFormed());
    assertFalse(new Cp24Time2a(0, 60, false, true).isWellFormed());
  }

  @Test
  void constructorRejectsValuesThatDoNotFitTheWireFields() {
    assertThrows(IllegalArgumentException.class, () -> new Cp24Time2a(0, 64, false, true));
    assertThrows(IllegalArgumentException.class, () -> new Cp24Time2a(65536, 0, false, true));
    assertThrows(IllegalArgumentException.class, () -> new Cp24Time2a(-1, 0, false, true));
  }

  @Test
  void cp16KeepsOutOfRangeElapsedTime() {
    ByteBuf buffer = Unpooled.buffer();
    try {
      buffer.writeShortLE(65535);
      Cp16Time2a decoded = Cp16Time2a.Serde.decode(buffer);

      assertEquals(new Cp16Time2a(65535), decoded);
      assertFalse(decoded.isWellFormed());
      assertTrue(new Cp16Time2a(59999).isWellFormed());
      assertThrows(IllegalArgumentException.class, () -> new Cp16Time2a(65536));
    } finally {
      buffer.release();
    }
  }
}
