package com.digitalpetri.iec104.asdu.object;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.digitalpetri.iec104.address.InformationObjectAddress;
import com.digitalpetri.iec104.asdu.element.NormalizedValue;
import com.digitalpetri.iec104.asdu.element.Qds;
import com.digitalpetri.iec104.asdu.time.Cp24Time2a;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

class MeasuredValueNormalizedWithCp24TimeTest {

  // Golden-byte derivation (Mode 1, least significant octet first), elements only (no IOA):
  //
  //   NVA         rawValue = 16384 (0x4000) little-endian -> 0x00 0x40
  //   QDS         invalid = true (0x80), notTopical = true (0x40) -> 0xC0
  //   CP24Time2a  ms = 12345 (0x3039) little-endian -> 0x39 0x30
  //               minute = 42 (0x2A), genuine -> RES1 = 0, invalid = 0 -> 0x2A
  //
  // => 00 40 C0 39 30 2A
  private static final String GOLDEN_HEX = "0040c039302a";

  private static final InformationObjectAddress IOA = InformationObjectAddress.of(0x010203);

  private static MeasuredValueNormalizedWithCp24Time representative() {
    return new MeasuredValueNormalizedWithCp24Time(
        IOA,
        new NormalizedValue((short) 16384),
        new Qds(false, false, false, true, true),
        new Cp24Time2a(12345, 42, false, true));
  }

  @Test
  void encodesToGoldenBytes() {
    ByteBuf buffer = Unpooled.buffer();
    try {
      MeasuredValueNormalizedWithCp24Time.Serde.encode(representative(), buffer);
      assertEquals(GOLDEN_HEX, ByteBufUtil.hexDump(buffer));
    } finally {
      buffer.release();
    }
  }

  @Test
  void roundTripsFromGoldenBytes() {
    ByteBuf buffer = Unpooled.wrappedBuffer(ByteBufUtil.decodeHexDump(GOLDEN_HEX));
    try {
      MeasuredValueNormalizedWithCp24Time decoded =
          MeasuredValueNormalizedWithCp24Time.Serde.decode(IOA, buffer);
      assertEquals(representative(), decoded);
      assertEquals(0, buffer.readableBytes());
    } finally {
      buffer.release();
    }
  }

  @Test
  void rejectsNullComponent() {
    // The compact constructor rejects a null quality descriptor.
    assertThrows(
        NullPointerException.class,
        () ->
            new MeasuredValueNormalizedWithCp24Time(
                IOA, new NormalizedValue((short) 0), null, new Cp24Time2a(0, 0, false, true)));
  }
}
