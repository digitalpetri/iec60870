package com.digitalpetri.iec60870.asdu.object;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.digitalpetri.iec60870.address.InformationObjectAddress;
import com.digitalpetri.iec60870.asdu.element.QualifierOfParameterActivation;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

class ParameterActivationTest {

  // Octet-by-octet derivation from 101 §7.3.5.4 (P_AC_NA_1, elements after the IOA):
  //   QPA (octet 1): qualifier value = 0x03 (act/deact of persistent cyclic transmission), UI8.
  //     => octet 1 = 0x03
  // => golden bytes "03"
  private static final String GOLDEN_HEX = "03";

  private static final InformationObjectAddress IOA = InformationObjectAddress.of(0x010203);

  private static ParameterActivation sample() {
    return new ParameterActivation(IOA, QualifierOfParameterActivation.of(0x03));
  }

  @Test
  void encodesGoldenBytes() {
    ByteBuf buffer = Unpooled.buffer();
    try {
      ParameterActivation.Serde.encode(sample(), buffer);
      assertEquals(GOLDEN_HEX, ByteBufUtil.hexDump(buffer));
    } finally {
      buffer.release();
    }
  }

  @Test
  void roundTrips() {
    ByteBuf buffer = Unpooled.wrappedBuffer(ByteBufUtil.decodeHexDump(GOLDEN_HEX));
    try {
      ParameterActivation decoded = ParameterActivation.Serde.decode(IOA, buffer);
      assertEquals(sample(), decoded);
      assertEquals(0, buffer.readableBytes());
    } finally {
      buffer.release();
    }
  }

  @Test
  void rejectsOutOfRangeQualifierValue() {
    assertThrows(IllegalArgumentException.class, () -> QualifierOfParameterActivation.of(256));
  }

  @Test
  void rejectsNullComponent() {
    assertThrows(NullPointerException.class, () -> new ParameterActivation(IOA, null));
  }
}
