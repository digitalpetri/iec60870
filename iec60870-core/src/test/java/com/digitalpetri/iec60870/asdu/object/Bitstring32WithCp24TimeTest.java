package com.digitalpetri.iec60870.asdu.object;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.digitalpetri.iec60870.address.InformationObjectAddress;
import com.digitalpetri.iec60870.asdu.element.Qds;
import com.digitalpetri.iec60870.asdu.time.Cp24Time2a;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

class Bitstring32WithCp24TimeTest {

  // Golden-byte derivation (Mode 1, least significant octet first), elements only (no IOA):
  //
  //   BSI         bits = 0xDEADBEEF, little-endian -> EF BE AD DE
  //   QDS         invalid = true (0x80), notTopical = true (0x40) -> 0xC0
  //   CP24Time2a  ms = 12345 (0x3039) little-endian -> 0x39 0x30
  //               minute = 42 (0x2A), genuine -> RES1 = 0, invalid = 0 -> 0x2A
  //
  // => EF BE AD DE C0 39 30 2A
  private static final String GOLDEN_HEX = "efbeaddec039302a";

  private static final InformationObjectAddress IOA = InformationObjectAddress.of(0x010203);

  private static Bitstring32WithCp24Time representative() {
    return new Bitstring32WithCp24Time(
        IOA,
        0xDEADBEEF,
        new Qds(false, false, false, true, true),
        new Cp24Time2a(12345, 42, false, true));
  }

  @Test
  void encodesToGoldenBytes() {
    ByteBuf buffer = Unpooled.buffer();
    try {
      Bitstring32WithCp24Time.Serde.encode(representative(), buffer);
      assertEquals(GOLDEN_HEX, ByteBufUtil.hexDump(buffer));
    } finally {
      buffer.release();
    }
  }

  @Test
  void roundTripsFromGoldenBytes() {
    ByteBuf buffer = Unpooled.wrappedBuffer(ByteBufUtil.decodeHexDump(GOLDEN_HEX));
    try {
      Bitstring32WithCp24Time decoded = Bitstring32WithCp24Time.Serde.decode(IOA, buffer);
      assertEquals(representative(), decoded);
      assertEquals(0, buffer.readableBytes());
    } finally {
      buffer.release();
    }
  }

  @Test
  void rejectsNullTime() {
    assertThrows(
        NullPointerException.class,
        () ->
            new Bitstring32WithCp24Time(IOA, 0, new Qds(false, false, false, false, false), null));
  }
}
