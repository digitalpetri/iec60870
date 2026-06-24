package com.digitalpetri.iec60870.asdu.object;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.digitalpetri.iec60870.address.InformationObjectAddress;
import com.digitalpetri.iec60870.asdu.element.NormalizedValue;
import com.digitalpetri.iec60870.asdu.element.QualifierOfParameter;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

class ParameterNormalizedTest {

  // Octet-by-octet derivation from 7.3.5.1 (P_ME_NA_1, elements after the IOA):
  //   NVA (octets 1..2): rawValue = 0x4000, encoded little-endian (low octet first)
  //     => octet 1 = 0x00, octet 2 = 0x40
  //   QPM (octet 3): KPA = 0x01 threshold value (b1..6), LPC = false (b7),
  //     POP = true => bit 8 = 0x80
  //     => 0x01 | 0x80 = 0x81
  // => golden bytes "004081"
  private static final String GOLDEN_HEX = "004081";

  private static final InformationObjectAddress IOA = InformationObjectAddress.of(0x010203);

  private static ParameterNormalized sample() {
    return new ParameterNormalized(
        IOA, new NormalizedValue((short) 0x4000), new QualifierOfParameter(1, false, true));
  }

  @Test
  void encodesGoldenBytes() {
    ByteBuf buffer = Unpooled.buffer();
    try {
      ParameterNormalized.Serde.encode(sample(), buffer);
      assertEquals(GOLDEN_HEX, ByteBufUtil.hexDump(buffer));
    } finally {
      buffer.release();
    }
  }

  @Test
  void roundTrips() {
    ByteBuf buffer = Unpooled.wrappedBuffer(ByteBufUtil.decodeHexDump(GOLDEN_HEX));
    try {
      ParameterNormalized decoded = ParameterNormalized.Serde.decode(IOA, buffer);
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

  @Test
  void rejectsNullComponent() {
    assertThrows(
        NullPointerException.class,
        () -> new ParameterNormalized(IOA, null, new QualifierOfParameter(1, false, false)));
  }
}
