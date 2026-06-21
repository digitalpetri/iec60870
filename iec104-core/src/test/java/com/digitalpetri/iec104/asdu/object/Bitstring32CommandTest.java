package com.digitalpetri.iec104.asdu.object;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.digitalpetri.iec104.address.InformationObjectAddress;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

class Bitstring32CommandTest {

  @Test
  void encodeProducesGoldenBytes() {
    // BSI = 0x12345678.
    // Octet breakdown (least significant octet first):
    //   BSI (4 octets, 0x12345678 LE): 78 56 34 12
    // => golden hex: 78563412
    Bitstring32Command o = new Bitstring32Command(InformationObjectAddress.of(1), 0x12345678);

    ByteBuf buffer = Unpooled.buffer();
    try {
      Bitstring32Command.Serde.encode(o, buffer);
      assertEquals("78563412", ByteBufUtil.hexDump(buffer));
    } finally {
      buffer.release();
    }
  }

  @Test
  void decodeRoundTrip() {
    Bitstring32Command original =
        new Bitstring32Command(InformationObjectAddress.of(1), 0x12345678);

    ByteBuf buffer = Unpooled.wrappedBuffer(ByteBufUtil.decodeHexDump("78563412"));
    try {
      Bitstring32Command decoded =
          Bitstring32Command.Serde.decode(InformationObjectAddress.of(1), buffer);
      assertEquals(original, decoded);
      assertEquals(0, buffer.readableBytes());
    } finally {
      buffer.release();
    }
  }
}
