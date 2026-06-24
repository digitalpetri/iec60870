package com.digitalpetri.iec60870.asdu.object;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.digitalpetri.iec60870.address.InformationObjectAddress;
import com.digitalpetri.iec60870.asdu.element.Qds;
import com.digitalpetri.iec60870.asdu.element.Vti;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

class StepPositionInformationTest {

  // Octet-by-octet derivation from 7.3.1.5 (M_ST_NA_1, elements after the IOA):
  //   VTI (octet 1): value = -5, transient = true
  //     value & 0x7F = (-5) & 0x7F = 0x7B ; TR bit 8 (0x80) set => 0x7B | 0x80 = 0xFB
  //   QDS (octet 2): invalid = true (IV b8 = 0x80), notTopical = true (NT b7 = 0x40),
  //     others clear => 0x80 | 0x40 = 0xC0
  // => golden bytes "fbc0"
  private static final String GOLDEN_HEX = "fbc0";

  private static final InformationObjectAddress IOA = InformationObjectAddress.of(0x010203);

  private static StepPositionInformation sample() {
    return new StepPositionInformation(
        IOA, new Vti(-5, true), new Qds(false, false, false, true, true));
  }

  @Test
  void encodesGoldenBytes() {
    ByteBuf buffer = Unpooled.buffer();
    try {
      StepPositionInformation.Serde.encode(sample(), buffer);
      assertEquals(GOLDEN_HEX, ByteBufUtil.hexDump(buffer));
    } finally {
      buffer.release();
    }
  }

  @Test
  void roundTrips() {
    ByteBuf buffer = Unpooled.wrappedBuffer(ByteBufUtil.decodeHexDump(GOLDEN_HEX));
    try {
      StepPositionInformation decoded = StepPositionInformation.Serde.decode(IOA, buffer);
      assertEquals(sample(), decoded);
      assertEquals(0, buffer.readableBytes());
    } finally {
      buffer.release();
    }
  }

  @Test
  void rejectsOutOfRangeVtiValue() {
    assertThrows(IllegalArgumentException.class, () -> new Vti(64, false));
  }
}
