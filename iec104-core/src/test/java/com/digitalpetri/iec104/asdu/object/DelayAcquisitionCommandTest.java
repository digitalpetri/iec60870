package com.digitalpetri.iec104.asdu.object;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.digitalpetri.iec104.address.InformationObjectAddress;
import com.digitalpetri.iec104.asdu.time.Cp16Time2a;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

class DelayAcquisitionCommandTest {

  // Octet-by-octet derivation from IEC 60870-5-101 §7.3.4.7 (C_CD_NA_1):
  //   The object carries only a CP16Time2a (two octets, no quality descriptor).
  //   CP16Time2a (milliseconds = 12345):
  //     octets 1-2: 12345 = 0x3039 -> LE -> 39 30
  private static final String GOLDEN_HEX = "3930";

  private static DelayAcquisitionCommand sample() {
    return new DelayAcquisitionCommand(InformationObjectAddress.of(0), new Cp16Time2a(12345));
  }

  @Test
  void encodesGoldenBytes() {
    ByteBuf buffer = Unpooled.buffer();
    try {
      DelayAcquisitionCommand.Serde.encode(sample(), buffer);
      assertEquals(GOLDEN_HEX, ByteBufUtil.hexDump(buffer));
    } finally {
      buffer.release();
    }
  }

  @Test
  void decodesAndRoundTrips() {
    ByteBuf buffer = Unpooled.wrappedBuffer(ByteBufUtil.decodeHexDump(GOLDEN_HEX));
    try {
      InformationObjectAddress address = InformationObjectAddress.of(0);
      DelayAcquisitionCommand decoded = DelayAcquisitionCommand.Serde.decode(address, buffer);

      assertEquals(sample(), decoded);
      assertEquals(0, buffer.readableBytes());
    } finally {
      buffer.release();
    }
  }

  @Test
  void rejectsOutOfRangeDelay() {
    // CP16Time2a validation propagates through record construction: 60000 ms exceeds 0..59999.
    assertThrows(
        IllegalArgumentException.class,
        () -> new DelayAcquisitionCommand(InformationObjectAddress.of(0), new Cp16Time2a(60000)));
  }
}
