package com.digitalpetri.iec104.asdu.object;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.digitalpetri.iec104.address.InformationObjectAddress;
import com.digitalpetri.iec104.asdu.element.Qds;
import com.digitalpetri.iec104.asdu.element.Scd;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

class PackedSinglePointWithStatusChangeTest {

  @Test
  void encodeProducesGoldenBytes() {
    // SCD: statusBits = 0x000B, changeDetectedBits = 0x0007.
    // QDS: invalid (IV, b8) set only.
    // Octet breakdown (least significant octet first):
    //   SCD (4 octets): word = ST(low 16) | CD(high 16) = 0x0007_000B
    //     => LE bytes: 0B 00 07 00
    //   QDS (1 octet): IV(b8)=1 => 0x80
    // => golden hex: 0b00070080
    PackedSinglePointWithStatusChange o =
        new PackedSinglePointWithStatusChange(
            InformationObjectAddress.of(1),
            new Scd(0x000B, 0x0007),
            new Qds(false, false, false, false, true));

    ByteBuf buffer = Unpooled.buffer();
    try {
      PackedSinglePointWithStatusChange.Serde.encode(o, buffer);
      assertEquals("0b00070080", ByteBufUtil.hexDump(buffer));
    } finally {
      buffer.release();
    }
  }

  @Test
  void decodeRoundTrip() {
    PackedSinglePointWithStatusChange original =
        new PackedSinglePointWithStatusChange(
            InformationObjectAddress.of(1),
            new Scd(0x000B, 0x0007),
            new Qds(false, false, false, false, true));

    ByteBuf buffer = Unpooled.wrappedBuffer(ByteBufUtil.decodeHexDump("0b00070080"));
    try {
      PackedSinglePointWithStatusChange decoded =
          PackedSinglePointWithStatusChange.Serde.decode(InformationObjectAddress.of(1), buffer);
      assertEquals(original, decoded);
      assertEquals(0, buffer.readableBytes());
    } finally {
      buffer.release();
    }
  }
}
