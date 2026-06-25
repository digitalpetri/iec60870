package com.digitalpetri.iec60870.asdu.object;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.digitalpetri.iec60870.address.InformationObjectAddress;
import com.digitalpetri.iec60870.asdu.element.QualifierOfInterrogation;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

class InterrogationCommandTest {

  // Octet-by-octet derivation from 7.3.4.1 (C_IC_NA_1, elements after the IOA):
  //   QOI (octet 1): global station interrogation = value 20 = 0x14
  // => golden bytes "14"
  private static final String GOLDEN_HEX = "14";

  private static final InformationObjectAddress IOA = InformationObjectAddress.of(0x000000);

  private static InterrogationCommand sample() {
    return new InterrogationCommand(IOA, QualifierOfInterrogation.STATION);
  }

  @Test
  void encodesGoldenBytes() {
    ByteBuf buffer = Unpooled.buffer();
    try {
      InterrogationCommand.Serde.encode(sample(), buffer);
      assertEquals(GOLDEN_HEX, ByteBufUtil.hexDump(buffer));
    } finally {
      buffer.release();
    }
  }

  @Test
  void roundTrips() {
    ByteBuf buffer = Unpooled.wrappedBuffer(ByteBufUtil.decodeHexDump(GOLDEN_HEX));
    try {
      InterrogationCommand decoded = InterrogationCommand.Serde.decode(IOA, buffer);
      assertEquals(sample(), decoded);
      assertEquals(0, buffer.readableBytes());
    } finally {
      buffer.release();
    }
  }

  @Test
  void rejectsNullComponent() {
    // Deliberate null input to verify the @NotNull constructor rejects it.
    //noinspection ConstantConditions
    assertThrows(NullPointerException.class, () -> new InterrogationCommand(IOA, null));
  }
}
