package com.digitalpetri.iec104.asdu.object;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.digitalpetri.iec104.address.InformationObjectAddress;
import com.digitalpetri.iec104.asdu.element.NormalizedValue;
import com.digitalpetri.iec104.asdu.element.QualifierOfSetpoint;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

class SetpointNormalizedTest {

  // Octet-by-octet derivation from 7.3.2.4 (C_SE_NA_1, elements after the IOA):
  //   NVA (octets 1..2): rawValue = 0x4000, encoded little-endian (low octet first)
  //     => octet 1 = 0x00, octet 2 = 0x40
  //   QOS (octet 3): QL = 0x05 (b1..7), select = true (S/E b8 = 0x80)
  //     => 0x05 | 0x80 = 0x85
  // => golden bytes "004085"
  private static final String GOLDEN_HEX = "004085";

  private static final InformationObjectAddress IOA = InformationObjectAddress.of(0x010203);

  private static SetpointNormalized sample() {
    return new SetpointNormalized(
        IOA, new NormalizedValue((short) 0x4000), new QualifierOfSetpoint(5, true));
  }

  @Test
  void encodesGoldenBytes() {
    ByteBuf buffer = Unpooled.buffer();
    try {
      SetpointNormalized.Serde.encode(sample(), buffer);
      assertEquals(GOLDEN_HEX, ByteBufUtil.hexDump(buffer));
    } finally {
      buffer.release();
    }
  }

  @Test
  void roundTrips() {
    ByteBuf buffer = Unpooled.wrappedBuffer(ByteBufUtil.decodeHexDump(GOLDEN_HEX));
    try {
      SetpointNormalized decoded = SetpointNormalized.Serde.decode(IOA, buffer);
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

  @Test
  void rejectsNullComponent() {
    assertThrows(
        NullPointerException.class,
        () -> new SetpointNormalized(IOA, null, new QualifierOfSetpoint(0, false)));
  }
}
