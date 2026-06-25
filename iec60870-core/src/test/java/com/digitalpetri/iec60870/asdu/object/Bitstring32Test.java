package com.digitalpetri.iec60870.asdu.object;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.digitalpetri.iec60870.address.InformationObjectAddress;
import com.digitalpetri.iec60870.asdu.element.Qds;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

class Bitstring32Test {

  @Test
  void encodeProducesGoldenBytes() {
    // BSI = 0x12345678; QDS with invalid set only.
    // Octet breakdown (least significant octet first):
    //   BSI (4 octets, 0x12345678 LE): 78 56 34 12
    //   QDS (1 octet): IV(b8)=1 => 0x80
    // => golden hex: 7856341280
    Bitstring32 o =
        new Bitstring32(
            InformationObjectAddress.of(1), 0x12345678, new Qds(false, false, false, false, true));

    ByteBuf buffer = Unpooled.buffer();
    try {
      Bitstring32.Serde.encode(o, buffer);
      assertEquals("7856341280", ByteBufUtil.hexDump(buffer));
    } finally {
      buffer.release();
    }
  }

  @Test
  void decodeRoundTrip() {
    Bitstring32 original =
        new Bitstring32(
            InformationObjectAddress.of(1), 0x12345678, new Qds(false, false, false, false, true));

    ByteBuf buffer = Unpooled.wrappedBuffer(ByteBufUtil.decodeHexDump("7856341280"));
    try {
      Bitstring32 decoded = Bitstring32.Serde.decode(InformationObjectAddress.of(1), buffer);
      assertEquals(original, decoded);
      assertEquals(0, buffer.readableBytes());
    } finally {
      buffer.release();
    }
  }
}
