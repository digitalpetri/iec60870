package com.digitalpetri.iec60870.asdu.object;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.digitalpetri.iec60870.address.InformationObjectAddress;
import com.digitalpetri.iec60870.asdu.element.BinaryCounterReading;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

class IntegratedTotalsTest {

  @Test
  void encodeProducesGoldenBytes() {
    // BCR value = 0x12345678; sequenceNumber = 5; carry = true; adjusted = false; invalid = true.
    // Octet breakdown (least significant octet first):
    //   Value (4 octets, 0x12345678 LE): 78 56 34 12
    //   Status (1 octet): SQ(b1..5)=5 (0x05) | CY(b6)=1 (0x20) | IV(b8)=1 (0x80) => 0xA5
    // => golden hex: 78 56 34 12 a5 == "78563412a5"
    IntegratedTotals o =
        new IntegratedTotals(
            InformationObjectAddress.of(1),
            new BinaryCounterReading(0x12345678, 5, true, false, true));

    ByteBuf buffer = Unpooled.buffer();
    try {
      IntegratedTotals.Serde.encode(o, buffer);
      assertEquals("78563412a5", ByteBufUtil.hexDump(buffer));
    } finally {
      buffer.release();
    }
  }

  @Test
  void decodeRoundTrip() {
    IntegratedTotals original =
        new IntegratedTotals(
            InformationObjectAddress.of(1),
            new BinaryCounterReading(0x12345678, 5, true, false, true));

    ByteBuf buffer = Unpooled.wrappedBuffer(ByteBufUtil.decodeHexDump("78563412a5"));
    try {
      IntegratedTotals decoded =
          IntegratedTotals.Serde.decode(InformationObjectAddress.of(1), buffer);
      assertEquals(original, decoded);
      assertEquals(0, buffer.readableBytes());
    } finally {
      buffer.release();
    }
  }

  @Test
  void rejectsOutOfRangeSequenceNumber() {
    assertThrows(
        IllegalArgumentException.class, () -> new BinaryCounterReading(0, 32, false, false, false));
  }
}
