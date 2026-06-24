package com.digitalpetri.iec60870.asdu.object;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.digitalpetri.iec60870.address.InformationObjectAddress;
import com.digitalpetri.iec60870.asdu.element.QualifierOfResetProcess;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

class ResetProcessCommandTest {

  // Octet-by-octet derivation from 7.3.4.6 (C_RP_NA_1, elements after the IOA):
  //   QRP (octet 1): general reset of the process = value 1 = 0x01
  // => golden bytes "01"
  private static final String GOLDEN_HEX = "01";

  private static final InformationObjectAddress IOA = InformationObjectAddress.of(0x000000);

  private static ResetProcessCommand sample() {
    return new ResetProcessCommand(IOA, QualifierOfResetProcess.GENERAL);
  }

  @Test
  void encodesGoldenBytes() {
    ByteBuf buffer = Unpooled.buffer();
    try {
      ResetProcessCommand.Serde.encode(sample(), buffer);
      assertEquals(GOLDEN_HEX, ByteBufUtil.hexDump(buffer));
    } finally {
      buffer.release();
    }
  }

  @Test
  void roundTrips() {
    ByteBuf buffer = Unpooled.wrappedBuffer(ByteBufUtil.decodeHexDump(GOLDEN_HEX));
    try {
      ResetProcessCommand decoded = ResetProcessCommand.Serde.decode(IOA, buffer);
      assertEquals(sample(), decoded);
      assertEquals(0, buffer.readableBytes());
    } finally {
      buffer.release();
    }
  }

  @Test
  void rejectsNullComponent() {
    assertThrows(NullPointerException.class, () -> new ResetProcessCommand(IOA, null));
  }

  @Test
  void rejectsOutOfRangeQualifier() {
    assertThrows(IllegalArgumentException.class, () -> QualifierOfResetProcess.of(256));
  }
}
