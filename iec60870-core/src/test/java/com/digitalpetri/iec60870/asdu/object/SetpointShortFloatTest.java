package com.digitalpetri.iec60870.asdu.object;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.digitalpetri.iec60870.address.InformationObjectAddress;
import com.digitalpetri.iec60870.asdu.element.QualifierOfSetpoint;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

class SetpointShortFloatTest {

  // Octet-by-octet derivation from 7.3.2.6 (C_SE_NC_1, elements after the IOA):
  //   R32 (octets 1..4): value = 1.0f, IEEE STD 754 single precision = 0x3F800000,
  //     little-endian (least significant octet first) => "0000803f"
  //   QOS (octet 5): QL = 0x05 (bits 1..7), select = true (S/E b8 = 0x80) => 0x80 | 0x05 = 0x85
  //     => "85"
  // => golden bytes "0000803f85"
  private static final String GOLDEN_HEX = "0000803f85";

  private static final InformationObjectAddress IOA = InformationObjectAddress.of(0x010203);

  private static SetpointShortFloat sample() {
    return new SetpointShortFloat(IOA, 1.0f, new QualifierOfSetpoint(0x05, true));
  }

  @Test
  void encodesGoldenBytes() {
    ByteBuf buffer = Unpooled.buffer();
    try {
      SetpointShortFloat.Serde.encode(sample(), buffer);
      assertEquals(GOLDEN_HEX, ByteBufUtil.hexDump(buffer));
    } finally {
      buffer.release();
    }
  }

  @Test
  void roundTrips() {
    ByteBuf buffer = Unpooled.wrappedBuffer(ByteBufUtil.decodeHexDump(GOLDEN_HEX));
    try {
      SetpointShortFloat decoded = SetpointShortFloat.Serde.decode(IOA, buffer);
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
