package com.digitalpetri.iec60870.asdu.object;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.digitalpetri.iec60870.address.InformationObjectAddress;
import com.digitalpetri.iec60870.asdu.element.BinaryCounterReading;
import com.digitalpetri.iec60870.asdu.time.Cp24Time2a;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

class IntegratedTotalsWithCp24TimeTest {

  // Golden-byte derivation (Mode 1, least significant octet first), elements only (no IOA):
  //
  //   BCR         value = 0x12345678, little-endian -> 78 56 34 12
  //               status octet: sequenceNumber = 5 (0x05, bits 1..5),
  //                             carry = true (CY b6 = 0x20), adjusted = false,
  //                             invalid = true (IV b8 = 0x80) -> 0x05 | 0x20 | 0x80 = 0xA5
  //   CP24Time2a  ms = 12345 (0x3039) little-endian -> 0x39 0x30
  //               minute = 42 (0x2A), genuine -> RES1 = 0, invalid = 0 -> 0x2A
  //
  // => 78 56 34 12 A5 39 30 2A
  private static final String GOLDEN_HEX = "78563412a539302a";

  private static final InformationObjectAddress IOA = InformationObjectAddress.of(0x010203);

  private static IntegratedTotalsWithCp24Time representative() {
    return new IntegratedTotalsWithCp24Time(
        IOA,
        new BinaryCounterReading(0x12345678, 5, true, false, true),
        new Cp24Time2a(12345, 42, false, true));
  }

  @Test
  void encodesToGoldenBytes() {
    ByteBuf buffer = Unpooled.buffer();
    try {
      IntegratedTotalsWithCp24Time.Serde.encode(representative(), buffer);
      assertEquals(GOLDEN_HEX, ByteBufUtil.hexDump(buffer));
    } finally {
      buffer.release();
    }
  }

  @Test
  void roundTripsFromGoldenBytes() {
    ByteBuf buffer = Unpooled.wrappedBuffer(ByteBufUtil.decodeHexDump(GOLDEN_HEX));
    try {
      IntegratedTotalsWithCp24Time decoded = IntegratedTotalsWithCp24Time.Serde.decode(IOA, buffer);
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
            new IntegratedTotalsWithCp24Time(
                IOA, new BinaryCounterReading(0, 0, false, false, false), null));
  }

  @Test
  void rejectsSequenceNumberOutOfRange() {
    assertThrows(
        IllegalArgumentException.class, () -> new BinaryCounterReading(0, 32, false, false, false));
  }
}
