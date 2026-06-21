package com.digitalpetri.iec104.asdu.object;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.digitalpetri.iec104.address.InformationObjectAddress;
import com.digitalpetri.iec104.asdu.element.Qds;
import com.digitalpetri.iec104.asdu.time.Cp24Time2a;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

class MeasuredValueScaledWithCp24TimeTest {

  // Golden-byte derivation (Mode 1, least significant octet first), elements only (no IOA):
  //
  //   SVA         value = -1234 (0xFB2E as signed 16-bit) little-endian -> 0x2E 0xFB
  //   QDS         invalid = true (0x80), notTopical = true (0x40) -> 0xC0
  //   CP24Time2a  ms = 12345 (0x3039) little-endian -> 0x39 0x30
  //               minute = 42 (0x2A), genuine -> RES1 = 0, invalid = 0 -> 0x2A
  //
  // => 2E FB C0 39 30 2A
  private static final String GOLDEN_HEX = "2efbc039302a";

  private static final InformationObjectAddress IOA = InformationObjectAddress.of(0x010203);

  private static MeasuredValueScaledWithCp24Time representative() {
    return new MeasuredValueScaledWithCp24Time(
        IOA,
        (short) -1234,
        new Qds(false, false, false, true, true),
        new Cp24Time2a(12345, 42, false, true));
  }

  @Test
  void encodesToGoldenBytes() {
    ByteBuf buffer = Unpooled.buffer();
    try {
      MeasuredValueScaledWithCp24Time.Serde.encode(representative(), buffer);
      assertEquals(GOLDEN_HEX, ByteBufUtil.hexDump(buffer));
    } finally {
      buffer.release();
    }
  }

  @Test
  void roundTripsFromGoldenBytes() {
    ByteBuf buffer = Unpooled.wrappedBuffer(ByteBufUtil.decodeHexDump(GOLDEN_HEX));
    try {
      MeasuredValueScaledWithCp24Time decoded =
          MeasuredValueScaledWithCp24Time.Serde.decode(IOA, buffer);
      assertEquals(representative(), decoded);
      assertEquals(0, buffer.readableBytes());
    } finally {
      buffer.release();
    }
  }

  @Test
  void rejectsNullComponents() {
    Qds quality = new Qds(false, false, false, false, false);
    Cp24Time2a time = new Cp24Time2a(0, 0, false, true);
    assertThrows(
        IllegalArgumentException.class,
        () -> new MeasuredValueScaledWithCp24Time(null, (short) 0, quality, time));
    // CP24Time2a also rejects an out-of-range milliseconds value.
    assertThrows(IllegalArgumentException.class, () -> new Cp24Time2a(60000, 0, false, true));
  }
}
