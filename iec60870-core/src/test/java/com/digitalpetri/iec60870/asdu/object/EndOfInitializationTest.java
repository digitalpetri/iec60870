package com.digitalpetri.iec60870.asdu.object;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.digitalpetri.iec60870.address.InformationObjectAddress;
import com.digitalpetri.iec60870.asdu.element.CauseOfInitialization;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

class EndOfInitializationTest {

  // Octet-by-octet derivation from 7.3.3.1 (M_EI_NA_1, elements after the IOA):
  //   COI (octet 1): cause value 0x02 (remote reset) in bits 1..7,
  //     after-parameter-change flag = true sets bit 8 (0x80).
  //     => octet 1 = 0x02 | 0x80 = 0x82
  // => golden bytes "82"
  private static final String GOLDEN_HEX = "82";

  // IOA is conventionally 0 for M_EI_NA_1.
  private static final InformationObjectAddress IOA = InformationObjectAddress.of(0);

  private static EndOfInitialization sample() {
    return new EndOfInitialization(IOA, new CauseOfInitialization(0x02, true));
  }

  @Test
  void encodesGoldenBytes() {
    ByteBuf buffer = Unpooled.buffer();
    try {
      EndOfInitialization.Serde.encode(sample(), buffer);
      assertEquals(GOLDEN_HEX, ByteBufUtil.hexDump(buffer));
    } finally {
      buffer.release();
    }
  }

  @Test
  void roundTrips() {
    ByteBuf buffer = Unpooled.wrappedBuffer(ByteBufUtil.decodeHexDump(GOLDEN_HEX));
    try {
      EndOfInitialization decoded = EndOfInitialization.Serde.decode(IOA, buffer);
      assertEquals(sample(), decoded);
      assertEquals(0, buffer.readableBytes());
    } finally {
      buffer.release();
    }
  }

  @Test
  void rejectsOutOfRangeCauseValue() {
    assertThrows(IllegalArgumentException.class, () -> new CauseOfInitialization(128, false));
  }

  @Test
  void rejectsNullComponent() {
    // Deliberate negative test: null is the intended input to verify the @NotNull contract throws.
    //noinspection DataFlowIssue
    assertThrows(NullPointerException.class, () -> new EndOfInitialization(IOA, null));
  }
}
