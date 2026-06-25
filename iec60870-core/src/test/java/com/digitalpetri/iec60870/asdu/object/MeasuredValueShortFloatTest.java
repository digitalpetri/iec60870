package com.digitalpetri.iec60870.asdu.object;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.digitalpetri.iec60870.address.InformationObjectAddress;
import com.digitalpetri.iec60870.asdu.element.Qds;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

class MeasuredValueShortFloatTest {

  // value = 1.0f => IEEE STD 754 = 0x3F800000; QDS with invalid set only.
  // Octet breakdown (least significant octet first):
  //   R32 (4 octets, 0x3F800000 LE): 00 00 80 3f
  //   QDS (1 octet): IV(b8)=1 => 0x80
  // => golden hex: 000080 3f 80 => "0000803f80"
  private static final String GOLDEN = "0000803f80";

  @Test
  void encodeProducesGoldenBytes() {
    MeasuredValueShortFloat o =
        new MeasuredValueShortFloat(
            InformationObjectAddress.of(1), 1.0f, new Qds(false, false, false, false, true));

    ByteBuf buffer = Unpooled.buffer();
    try {
      MeasuredValueShortFloat.Serde.encode(o, buffer);
      assertEquals(GOLDEN, ByteBufUtil.hexDump(buffer));
    } finally {
      buffer.release();
    }
  }

  @Test
  void decodeRoundTrip() {
    MeasuredValueShortFloat original =
        new MeasuredValueShortFloat(
            InformationObjectAddress.of(1), 1.0f, new Qds(false, false, false, false, true));

    ByteBuf buffer = Unpooled.wrappedBuffer(ByteBufUtil.decodeHexDump(GOLDEN));
    try {
      MeasuredValueShortFloat decoded =
          MeasuredValueShortFloat.Serde.decode(InformationObjectAddress.of(1), buffer);
      assertEquals(original, decoded);
      assertEquals(0, buffer.readableBytes());
    } finally {
      buffer.release();
    }
  }
}
