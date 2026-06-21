package com.digitalpetri.iec104.asdu.object;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.digitalpetri.iec104.address.InformationObjectAddress;
import com.digitalpetri.iec104.asdu.element.Qds;
import com.digitalpetri.iec104.asdu.time.Cp56Time2a;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

class MeasuredValueShortFloatWithCp56TimeTest {

  // Golden-byte derivation (Mode 1, least significant octet first), elements only (no IOA):
  //
  //   R32         value = -1234.5f => IEEE STD 754 = 0xC49A5000, little-endian -> 00 50 9A C4
  //   QDS         invalid = true (0x80), notTopical = true (0x40) -> 0xC0
  //   CP56Time2a:
  //     ms = 12345 (0x3039) little-endian               -> 39 30
  //     octet3 = minute 42 (0x2A), genuine (RES1=0), valid -> 0x2A
  //     octet4 = hour 13 (0x0D), summerTime=false        -> 0x0D
  //     octet5 = dayOfMonth 5 (0x05) | dayOfWeek 3 << 5  -> 0x05 | 0x60 = 0x65
  //     octet6 = month 6 (0x06)                          -> 0x06
  //     octet7 = year 24 (0x18)                          -> 0x18
  //
  // => 00 50 9A C4 C0 39 30 2A 0D 65 06 18
  private static final String GOLDEN_HEX = "00509ac4c039302a0d650618";

  private static final InformationObjectAddress IOA = InformationObjectAddress.of(0x010203);

  private static MeasuredValueShortFloatWithCp56Time representative() {
    return new MeasuredValueShortFloatWithCp56Time(
        IOA,
        -1234.5f,
        new Qds(false, false, false, true, true),
        new Cp56Time2a(12345, 42, 13, 5, 3, 6, 24, false, false, true));
  }

  @Test
  void encodesToGoldenBytes() {
    ByteBuf buffer = Unpooled.buffer();
    try {
      MeasuredValueShortFloatWithCp56Time.Serde.encode(representative(), buffer);
      assertEquals(GOLDEN_HEX, ByteBufUtil.hexDump(buffer));
    } finally {
      buffer.release();
    }
  }

  @Test
  void roundTripsFromGoldenBytes() {
    ByteBuf buffer = Unpooled.wrappedBuffer(ByteBufUtil.decodeHexDump(GOLDEN_HEX));
    try {
      MeasuredValueShortFloatWithCp56Time decoded =
          MeasuredValueShortFloatWithCp56Time.Serde.decode(IOA, buffer);
      assertEquals(representative(), decoded);
      assertEquals(0, buffer.readableBytes());
    } finally {
      buffer.release();
    }
  }

  @Test
  void rejectsNullComponents() {
    Qds quality = new Qds(false, false, false, false, false);
    Cp56Time2a time = new Cp56Time2a(0, 0, 0, 1, 0, 1, 0, false, false, true);
    assertThrows(
        IllegalArgumentException.class,
        () -> new MeasuredValueShortFloatWithCp56Time(null, 0.0f, quality, time));
    // CP56Time2a also rejects an out-of-range milliseconds value.
    assertThrows(
        IllegalArgumentException.class,
        () -> new Cp56Time2a(60000, 0, 0, 1, 0, 1, 0, false, false, true));
  }
}
