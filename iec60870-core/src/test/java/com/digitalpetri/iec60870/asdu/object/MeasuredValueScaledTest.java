package com.digitalpetri.iec60870.asdu.object;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.digitalpetri.iec60870.address.InformationObjectAddress;
import com.digitalpetri.iec60870.asdu.element.Qds;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

class MeasuredValueScaledTest {

  // Octet-by-octet derivation from 7.3.1.11 (M_ME_NB_1, elements after the IOA):
  //   SVA (octets 1..2): value = 0x1234 (4660), little-endian => low octet 0x34, high octet 0x12
  //     => "3412"
  //   QDS (octet 3): invalid = true (IV b8 = 0x80), notTopical = true (NT b7 = 0x40),
  //     others clear => 0x80 | 0x40 = 0xC0 => "c0"
  // => golden bytes "3412c0"
  private static final String GOLDEN_HEX = "3412c0";

  private static final InformationObjectAddress IOA = InformationObjectAddress.of(0x010203);

  private static MeasuredValueScaled sample() {
    return new MeasuredValueScaled(IOA, (short) 0x1234, new Qds(false, false, false, true, true));
  }

  @Test
  void encodesGoldenBytes() {
    ByteBuf buffer = Unpooled.buffer();
    try {
      MeasuredValueScaled.Serde.encode(sample(), buffer);
      assertEquals(GOLDEN_HEX, ByteBufUtil.hexDump(buffer));
    } finally {
      buffer.release();
    }
  }

  @Test
  void roundTrips() {
    ByteBuf buffer = Unpooled.wrappedBuffer(ByteBufUtil.decodeHexDump(GOLDEN_HEX));
    try {
      MeasuredValueScaled decoded = MeasuredValueScaled.Serde.decode(IOA, buffer);
      assertEquals(sample(), decoded);
      assertEquals(0, buffer.readableBytes());
    } finally {
      buffer.release();
    }
  }
}
