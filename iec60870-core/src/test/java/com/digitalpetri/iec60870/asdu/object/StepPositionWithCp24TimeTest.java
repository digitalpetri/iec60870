package com.digitalpetri.iec60870.asdu.object;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.digitalpetri.iec60870.address.InformationObjectAddress;
import com.digitalpetri.iec60870.asdu.element.Qds;
import com.digitalpetri.iec60870.asdu.element.Vti;
import com.digitalpetri.iec60870.asdu.time.Cp24Time2a;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

class StepPositionWithCp24TimeTest {

  // Golden-byte derivation (Mode 1, least significant octet first), elements only (no IOA):
  //
  //   VTI         value = -5, transient = true
  //               (-5 & 0x7F) = 0x7B, set TR (0x80) -> 0xFB
  //   QDS         invalid = true (0x80), notTopical = true (0x40) -> 0xC0
  //   CP24Time2a  ms = 12345 (0x3039) little-endian -> 0x39 0x30
  //               minute = 42 (0x2A), genuine -> RES1 = 0, invalid = 0 -> 0x2A
  //
  // => FB C0 39 30 2A
  private static final String GOLDEN_HEX = "fbc039302a";

  private static final InformationObjectAddress IOA = InformationObjectAddress.of(0x010203);

  private static StepPositionWithCp24Time representative() {
    return new StepPositionWithCp24Time(
        IOA,
        new Vti(-5, true),
        new Qds(false, false, false, true, true),
        new Cp24Time2a(12345, 42, false, true));
  }

  @Test
  void encodesToGoldenBytes() {
    ByteBuf buffer = Unpooled.buffer();
    try {
      StepPositionWithCp24Time.Serde.encode(representative(), buffer);
      assertEquals(GOLDEN_HEX, ByteBufUtil.hexDump(buffer));
    } finally {
      buffer.release();
    }
  }

  @Test
  void roundTripsFromGoldenBytes() {
    ByteBuf buffer = Unpooled.wrappedBuffer(ByteBufUtil.decodeHexDump(GOLDEN_HEX));
    try {
      StepPositionWithCp24Time decoded = StepPositionWithCp24Time.Serde.decode(IOA, buffer);
      assertEquals(representative(), decoded);
      assertEquals(0, buffer.readableBytes());
    } finally {
      buffer.release();
    }
  }

  @Test
  void rejectsOutOfRangeVtiValue() {
    // VTI value 64 is outside the signed 7-bit range [-64, 63].
    assertThrows(IllegalArgumentException.class, () -> new Vti(64, false));
  }
}
