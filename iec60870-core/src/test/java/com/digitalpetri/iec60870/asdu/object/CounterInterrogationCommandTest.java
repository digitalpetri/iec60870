package com.digitalpetri.iec60870.asdu.object;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.digitalpetri.iec60870.address.InformationObjectAddress;
import com.digitalpetri.iec60870.asdu.element.FreezeMode;
import com.digitalpetri.iec60870.asdu.element.QualifierOfCounterInterrogation;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

class CounterInterrogationCommandTest {

  // Octet-by-octet derivation from 7.3.4.2 (C_CI_NA_1, elements after the IOA):
  //   QCC (octet 1): RQT in bits 1..6, FRZ in bits 7..8.
  //     request = 5 (general counter request) => bits 1..6 = 000101 = 0x05
  //     freeze  = FREEZE_WITH_RESET (value 2) => bits 7..8 = 10, shifted left 6 = 0x80
  //     octet   = 0x05 | 0x80 = 0x85
  // => golden bytes "85"
  private static final String GOLDEN_HEX = "85";

  private static final InformationObjectAddress IOA = InformationObjectAddress.of(0);

  private static CounterInterrogationCommand sample() {
    return new CounterInterrogationCommand(
        IOA, new QualifierOfCounterInterrogation(5, FreezeMode.FREEZE_WITH_RESET));
  }

  @Test
  void encodesGoldenBytes() {
    ByteBuf buffer = Unpooled.buffer();
    try {
      CounterInterrogationCommand.Serde.encode(sample(), buffer);
      assertEquals(GOLDEN_HEX, ByteBufUtil.hexDump(buffer));
    } finally {
      buffer.release();
    }
  }

  @Test
  void roundTrips() {
    ByteBuf buffer = Unpooled.wrappedBuffer(ByteBufUtil.decodeHexDump(GOLDEN_HEX));
    try {
      CounterInterrogationCommand decoded = CounterInterrogationCommand.Serde.decode(IOA, buffer);
      assertEquals(sample(), decoded);
      assertEquals(0, buffer.readableBytes());
    } finally {
      buffer.release();
    }
  }

  @Test
  void rejectsOutOfRangeRequest() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new QualifierOfCounterInterrogation(64, FreezeMode.READ));
  }

  @Test
  void rejectsNullComponent() {
    assertThrows(NullPointerException.class, () -> new CounterInterrogationCommand(IOA, null));
  }
}
