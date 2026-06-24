package com.digitalpetri.iec60870.asdu.object;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.digitalpetri.iec60870.address.InformationObjectAddress;
import com.digitalpetri.iec60870.asdu.element.NormalizedValue;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

class MeasuredValueNormalizedNoQualityTest {

  // Octet-by-octet derivation from 7.3.1.21 (M_ME_ND_1, elements after the IOA):
  //   NVA (octets 1..2): rawValue = 0x1234, encoded little-endian (low octet first)
  //     => octet 1 = 0x34, octet 2 = 0x12
  //   No quality (QDS) octet follows.
  // => golden bytes "3412"
  private static final String GOLDEN_HEX = "3412";

  private static final InformationObjectAddress IOA = InformationObjectAddress.of(0x010203);

  private static MeasuredValueNormalizedNoQuality sample() {
    return new MeasuredValueNormalizedNoQuality(IOA, new NormalizedValue((short) 0x1234));
  }

  @Test
  void encodesGoldenBytes() {
    ByteBuf buffer = Unpooled.buffer();
    try {
      MeasuredValueNormalizedNoQuality.Serde.encode(sample(), buffer);
      assertEquals(GOLDEN_HEX, ByteBufUtil.hexDump(buffer));
    } finally {
      buffer.release();
    }
  }

  @Test
  void roundTrips() {
    ByteBuf buffer = Unpooled.wrappedBuffer(ByteBufUtil.decodeHexDump(GOLDEN_HEX));
    try {
      MeasuredValueNormalizedNoQuality decoded =
          MeasuredValueNormalizedNoQuality.Serde.decode(IOA, buffer);
      assertEquals(sample(), decoded);
      assertEquals(0, buffer.readableBytes());
    } finally {
      buffer.release();
    }
  }

  @Test
  void rejectsNullComponent() {
    assertThrows(NullPointerException.class, () -> new MeasuredValueNormalizedNoQuality(IOA, null));
  }
}
