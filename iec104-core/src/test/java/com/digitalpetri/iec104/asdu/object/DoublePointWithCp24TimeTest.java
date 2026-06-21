package com.digitalpetri.iec104.asdu.object;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.digitalpetri.iec104.address.InformationObjectAddress;
import com.digitalpetri.iec104.asdu.element.DoublePointState;
import com.digitalpetri.iec104.asdu.element.Qds;
import com.digitalpetri.iec104.asdu.time.Cp24Time2a;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

class DoublePointWithCp24TimeTest {

  // Golden instance and its hand-computed wire bytes (elements only, no IOA):
  //
  // DIQ octet:
  //   DPI (bits 2..1) = ON(2)        -> 0b00000010 = 0x02
  //   BL  (bit 5)     = false        -> 0x00
  //   SB  (bit 6)     = false        -> 0x00
  //   NT  (bit 7)     = true         -> 0x40
  //   IV  (bit 8)     = false        -> 0x00
  //   => 0x42
  // CP24Time2a:
  //   milliseconds = 12345 = 0x3039  -> LE octets 0x39, 0x30
  //   octet 3: minute = 42 = 0x2A; genuine (RES1=0), not invalid -> 0x2A
  //   => 0x39, 0x30, 0x2A
  //
  // Full element bytes: 42 39 30 2A
  private static final String GOLDEN_HEX = "4239302a";

  private static DoublePointWithCp24Time goldenObject() {
    return new DoublePointWithCp24Time(
        InformationObjectAddress.of(0x010203),
        DoublePointState.ON,
        new Qds(false, false, false, true, false),
        new Cp24Time2a(12345, 42, false, true));
  }

  @Test
  void encodesToGoldenBytes() {
    ByteBuf buffer = Unpooled.buffer();
    try {
      DoublePointWithCp24Time.Serde.encode(goldenObject(), buffer);
      assertEquals(GOLDEN_HEX, ByteBufUtil.hexDump(buffer));
    } finally {
      buffer.release();
    }
  }

  @Test
  void decodesGoldenBytesAndRoundTrips() {
    ByteBuf buffer = Unpooled.wrappedBuffer(ByteBufUtil.decodeHexDump(GOLDEN_HEX));
    try {
      InformationObjectAddress address = InformationObjectAddress.of(0x010203);
      DoublePointWithCp24Time decoded = DoublePointWithCp24Time.Serde.decode(address, buffer);

      assertEquals(goldenObject(), decoded);
      assertEquals(0, buffer.readableBytes());
    } finally {
      buffer.release();
    }
  }

  @Test
  void rejectsQualityWithOverflowSet() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new DoublePointWithCp24Time(
                InformationObjectAddress.of(1),
                DoublePointState.OFF,
                new Qds(true, false, false, false, false),
                new Cp24Time2a(0, 0, false, true)));
  }
}
