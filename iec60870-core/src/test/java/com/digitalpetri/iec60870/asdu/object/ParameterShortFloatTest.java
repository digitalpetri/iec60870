package com.digitalpetri.iec60870.asdu.object;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.digitalpetri.iec60870.address.InformationObjectAddress;
import com.digitalpetri.iec60870.asdu.element.QualifierOfParameter;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

class ParameterShortFloatTest {

  // Octet-by-octet derivation from 7.3.5.3 (P_ME_NC_1, elements after the IOA):
  //   R32 (octets 1..4): value = 1.0f, IEEE STD 754 single precision = 0x3F800000,
  //     little-endian (least significant octet first) => "0000803f"
  //   QPM (octet 5): KPA = 0x01 threshold value (bits 1..6), LPC = false (b7 = 0),
  //     POP = false (b8 = 0) => 0x01 => "01"
  // => golden bytes "0000803f01"
  private static final String GOLDEN_HEX = "0000803f01";

  private static final InformationObjectAddress IOA = InformationObjectAddress.of(0x010203);

  private static ParameterShortFloat sample() {
    return new ParameterShortFloat(IOA, 1.0f, new QualifierOfParameter(0x01, false, false));
  }

  @Test
  void encodesGoldenBytes() {
    ByteBuf buffer = Unpooled.buffer();
    try {
      ParameterShortFloat.Serde.encode(sample(), buffer);
      assertEquals(GOLDEN_HEX, ByteBufUtil.hexDump(buffer));
    } finally {
      buffer.release();
    }
  }

  @Test
  void roundTrips() {
    ByteBuf buffer = Unpooled.wrappedBuffer(ByteBufUtil.decodeHexDump(GOLDEN_HEX));
    try {
      ParameterShortFloat decoded = ParameterShortFloat.Serde.decode(IOA, buffer);
      assertEquals(sample(), decoded);
      assertEquals(0, buffer.readableBytes());
    } finally {
      buffer.release();
    }
  }

  @Test
  void rejectsOutOfRangeQualifier() {
    assertThrows(IllegalArgumentException.class, () -> new QualifierOfParameter(64, false, false));
  }
}
