package com.digitalpetri.iec60870.asdu.object;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.digitalpetri.iec60870.address.InformationObjectAddress;
import com.digitalpetri.iec60870.asdu.element.FixedTestBitPattern;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

class TestCommandTest {

  // Octet-by-octet derivation from 7.3.4.5 (C_TS_NA_1, elements after the IOA):
  //   FBP (octets 1..2): standard test bit pattern 0x55AA, encoded least significant octet first.
  //     spec Fig. 76: octet 10 = 1010 1010 (0xAA), octet 11 = 0101 0101 (0x55)
  //     => octet 1 = 0xAA, octet 2 = 0x55
  // => golden bytes "aa55"
  private static final String GOLDEN_HEX = "aa55";

  // IOA is conventionally 0 for C_TS_NA_1.
  private static final InformationObjectAddress IOA = InformationObjectAddress.of(0);

  private static TestCommand sample() {
    return new TestCommand(IOA, FixedTestBitPattern.DEFAULT);
  }

  @Test
  void encodesGoldenBytes() {
    ByteBuf buffer = Unpooled.buffer();
    try {
      TestCommand.Serde.encode(sample(), buffer);
      assertEquals(GOLDEN_HEX, ByteBufUtil.hexDump(buffer));
    } finally {
      buffer.release();
    }
  }

  @Test
  void roundTrips() {
    ByteBuf buffer = Unpooled.wrappedBuffer(ByteBufUtil.decodeHexDump(GOLDEN_HEX));
    try {
      TestCommand decoded = TestCommand.Serde.decode(IOA, buffer);
      assertEquals(sample(), decoded);
      assertEquals(0, buffer.readableBytes());
    } finally {
      buffer.release();
    }
  }

  @Test
  void rejectsOutOfRangePattern() {
    assertThrows(IllegalArgumentException.class, () -> FixedTestBitPattern.of(0x1_0000));
  }

  @Test
  void rejectsNullComponent() {
    assertThrows(NullPointerException.class, () -> new TestCommand(IOA, null));
  }
}
