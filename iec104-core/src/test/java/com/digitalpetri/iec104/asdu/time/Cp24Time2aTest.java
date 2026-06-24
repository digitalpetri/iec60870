package com.digitalpetri.iec104.asdu.time;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.digitalpetri.iec104.AsduDecodeException;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

/**
 * Round-trip and malformed-wire-data tests for {@link Cp24Time2a.Serde} and range validation for
 * the {@link Cp24Time2a} compact constructor.
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
  void decodeRejectsMinuteOutOfRange() {
    // octet3 bits 6..1 carry the minute; values 60..63 (0x3C..0x3F) are out of range and must
    // surface as AsduDecodeException rather than IllegalArgumentException from the constructor.
    for (int minute = 0x3C; minute <= 0x3F; minute++) {
      ByteBuf buffer = Unpooled.buffer();
      try {
        buffer.writeShortLE(0); // milliseconds = 0
        buffer.writeByte(minute); // octet3: minute = 60..63
        assertThrows(AsduDecodeException.class, () -> Cp24Time2a.Serde.decode(buffer));
      } finally {
        buffer.release();
      }
    }
  }

  @Test
  void constructorRejectsMinuteOutOfRange() {
    assertThrows(IllegalArgumentException.class, () -> new Cp24Time2a(0, 60, false, true));
  }
}
