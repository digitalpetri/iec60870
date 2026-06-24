package com.digitalpetri.iec60870.asdu.object;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.digitalpetri.iec60870.address.InformationObjectAddress;
import com.digitalpetri.iec60870.asdu.element.Qds;
import com.digitalpetri.iec60870.asdu.time.Cp56Time2a;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

class SinglePointWithCp56TimeTest {

  // Octet-by-octet derivation from IEC 60870-5-101 §7.3.1.22 (M_SP_TB_1):
  //   SIQ octet: SPI(b1)=on=1, IV(b8)=invalid=1 -> 0x81
  //   CP56Time2a (ms=12345, min=30, hour=10, dom=9, dow=3, month=6, year=24, genuine):
  //     octets 1-2: 12345 = 0x3039 LE -> 39 30
  //     octet 3:    minute 30 = 0x1E (genuine -> RES1=0, invalid=0) -> 1E
  //     octet 4:    hour 10 = 0x0A (no summer time) -> 0A
  //     octet 5:    dayOfMonth 9 | (dayOfWeek 3 << 5 = 0x60) -> 69
  //     octet 6:    month 6 -> 06
  //     octet 7:    year 24 = 0x18 -> 18
  private static final String GOLDEN_HEX = "8139301e0a690618";

  private static SinglePointWithCp56Time sample() {
    Qds quality = new Qds(false, false, false, false, true);
    Cp56Time2a time = new Cp56Time2a(12345, 30, 10, 9, 3, 6, 24, false, false, true);
    return new SinglePointWithCp56Time(InformationObjectAddress.of(0x010203), true, quality, time);
  }

  @Test
  void encodesGoldenBytes() {
    ByteBuf buffer = Unpooled.buffer();
    try {
      SinglePointWithCp56Time.Serde.encode(sample(), buffer);
      assertEquals(GOLDEN_HEX, ByteBufUtil.hexDump(buffer));
    } finally {
      buffer.release();
    }
  }

  @Test
  void decodesAndRoundTrips() {
    ByteBuf buffer = Unpooled.wrappedBuffer(ByteBufUtil.decodeHexDump(GOLDEN_HEX));
    try {
      InformationObjectAddress address = InformationObjectAddress.of(0x010203);
      SinglePointWithCp56Time decoded = SinglePointWithCp56Time.Serde.decode(address, buffer);

      assertEquals(sample(), decoded);
      assertEquals(0, buffer.readableBytes());
    } finally {
      buffer.release();
    }
  }

  @Test
  void rejectsOutOfRangeTime() {
    // CP56Time2a validation propagates through the record construction: minute 60 is invalid.
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new SinglePointWithCp56Time(
                InformationObjectAddress.of(0),
                true,
                new Qds(false, false, false, false, false),
                new Cp56Time2a(0, 60, 0, 1, 0, 1, 0, false, false, true)));
  }
}
