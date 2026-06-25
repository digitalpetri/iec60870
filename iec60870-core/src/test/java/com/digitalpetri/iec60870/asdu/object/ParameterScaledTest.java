package com.digitalpetri.iec60870.asdu.object;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.digitalpetri.iec60870.address.InformationObjectAddress;
import com.digitalpetri.iec60870.asdu.element.QualifierOfParameter;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

class ParameterScaledTest {

  // Octet-by-octet derivation from 7.3.5.2 (P_ME_NB_1, elements after the IOA):
  //   SVA (octets 1..2): value = 0x1234 (4660), little-endian => low octet 0x34, high octet 0x12
  //     => "3412"
  //   QPM (octet 3): KPA = 0x01 threshold value (bits 1..6), LPC = false (b7), POP = false (b8)
  //     => 0x01 => "01"
  // => golden bytes "341201"
  private static final String GOLDEN_HEX = "341201";

  private static final InformationObjectAddress IOA = InformationObjectAddress.of(0x010203);

  private static ParameterScaled sample() {
    return new ParameterScaled(IOA, (short) 0x1234, new QualifierOfParameter(1, false, false));
  }

  @Test
  void encodesGoldenBytes() {
    ByteBuf buffer = Unpooled.buffer();
    try {
      ParameterScaled.Serde.encode(sample(), buffer);
      assertEquals(GOLDEN_HEX, ByteBufUtil.hexDump(buffer));
    } finally {
      buffer.release();
    }
  }

  @Test
  void roundTrips() {
    ByteBuf buffer = Unpooled.wrappedBuffer(ByteBufUtil.decodeHexDump(GOLDEN_HEX));
    try {
      ParameterScaled decoded = ParameterScaled.Serde.decode(IOA, buffer);
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
