package com.digitalpetri.iec60870.asdu.object;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.digitalpetri.iec60870.address.InformationObjectAddress;
import com.digitalpetri.iec60870.asdu.element.QualifierOfCommand;
import com.digitalpetri.iec60870.asdu.time.Cp56Time2a;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

class SingleCommandWithCp56TimeTest {

  // Octet-by-octet derivation from IEC 60870-5-104 §8.1 (C_SC_TA_1):
  //   SCO octet: SCS(b1)=on=1, QU(b3..7)=1 (short pulse) -> 0x04, S/E(b8)=select=1 -> 0x80
  //     => 0x01 | 0x04 | 0x80 = 0x85
  //   CP56Time2a (ms=12345, min=30, hour=10, dom=9, dow=3, month=6, year=24, genuine):
  //     octets 1-2: 12345 = 0x3039 LE -> 39 30
  //     octet 3:    minute 30 = 0x1E (genuine -> RES1=0, invalid=0) -> 1E
  //     octet 4:    hour 10 = 0x0A (no summer time) -> 0A
  //     octet 5:    dayOfMonth 9 | (dayOfWeek 3 << 5 = 0x60) -> 69
  //     octet 6:    month 6 -> 06
  //     octet 7:    year 24 = 0x18 -> 18
  private static final String GOLDEN_HEX = "8539301e0a690618";

  private static SingleCommandWithCp56Time sample() {
    QualifierOfCommand qualifier = new QualifierOfCommand(1, true);
    Cp56Time2a time = new Cp56Time2a(12345, 30, 10, 9, 3, 6, 24, false, false, true);
    return new SingleCommandWithCp56Time(
        InformationObjectAddress.of(0x010203), true, qualifier, time);
  }

  @Test
  void encodesGoldenBytes() {
    ByteBuf buffer = Unpooled.buffer();
    try {
      SingleCommandWithCp56Time.Serde.encode(sample(), buffer);
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
      SingleCommandWithCp56Time decoded = SingleCommandWithCp56Time.Serde.decode(address, buffer);

      assertEquals(sample(), decoded);
      assertEquals(0, buffer.readableBytes());
    } finally {
      buffer.release();
    }
  }

  @Test
  void rejectsOutOfRangeQualifier() {
    // QualifierOfCommand validation propagates through the record construction: QU 32 is invalid.
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new SingleCommandWithCp56Time(
                InformationObjectAddress.of(0),
                true,
                new QualifierOfCommand(32, false),
                new Cp56Time2a(0, 0, 0, 1, 0, 1, 0, false, false, true)));
  }
}
