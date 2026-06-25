package com.digitalpetri.iec60870.asdu.object;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.digitalpetri.iec60870.address.InformationObjectAddress;
import com.digitalpetri.iec60870.asdu.element.QualifierOfSetpoint;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

class SetpointScaledTest {

  // Octet-by-octet derivation from 7.3.2.5 (C_SE_NB_1, elements after the IOA):
  //   SVA (octets 1..2): value = 0x1234 (4660), little-endian => low octet 0x34, high octet 0x12
  //     => "3412"
  //   QOS (octet 3): QL = 0x05 (bits 1..7), select = true (S/E b8 = 0x80) => 0x80 | 0x05 = 0x85
  //     => "85"
  // => golden bytes "341285"
  private static final String GOLDEN_HEX = "341285";

  private static final InformationObjectAddress IOA = InformationObjectAddress.of(0x010203);

  private static SetpointScaled sample() {
    return new SetpointScaled(IOA, (short) 0x1234, new QualifierOfSetpoint(0x05, true));
  }

  @Test
  void encodesGoldenBytes() {
    ByteBuf buffer = Unpooled.buffer();
    try {
      SetpointScaled.Serde.encode(sample(), buffer);
      assertEquals(GOLDEN_HEX, ByteBufUtil.hexDump(buffer));
    } finally {
      buffer.release();
    }
  }

  @Test
  void roundTrips() {
    ByteBuf buffer = Unpooled.wrappedBuffer(ByteBufUtil.decodeHexDump(GOLDEN_HEX));
    try {
      SetpointScaled decoded = SetpointScaled.Serde.decode(IOA, buffer);
      assertEquals(sample(), decoded);
      assertEquals(0, buffer.readableBytes());
    } finally {
      buffer.release();
    }
  }

  @Test
  void rejectsOutOfRangeQualifier() {
    assertThrows(IllegalArgumentException.class, () -> new QualifierOfSetpoint(128, false));
  }
}
