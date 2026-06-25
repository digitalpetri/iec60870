package com.digitalpetri.iec60870.asdu.object;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.digitalpetri.iec60870.address.InformationObjectAddress;
import com.digitalpetri.iec60870.asdu.element.NormalizedValue;
import com.digitalpetri.iec60870.asdu.element.Qds;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

class MeasuredValueNormalizedTest {

  // Octet-by-octet derivation from 7.3.1.9 (M_ME_NA_1, elements after the IOA):
  //   NVA (octets 1..2): rawValue = 0x1234, encoded little-endian (low octet first)
  //     => octet 1 = 0x34, octet 2 = 0x12
  //   QDS (octet 3): invalid = true (IV b8 = 0x80), notTopical = true (NT b7 = 0x40),
  //     others clear => 0x80 | 0x40 = 0xC0
  // => golden bytes "3412c0"
  private static final String GOLDEN_HEX = "3412c0";

  private static final InformationObjectAddress IOA = InformationObjectAddress.of(0x010203);

  private static MeasuredValueNormalized sample() {
    return new MeasuredValueNormalized(
        IOA, new NormalizedValue((short) 0x1234), new Qds(false, false, false, true, true));
  }

  @Test
  void encodesGoldenBytes() {
    ByteBuf buffer = Unpooled.buffer();
    try {
      MeasuredValueNormalized.Serde.encode(sample(), buffer);
      assertEquals(GOLDEN_HEX, ByteBufUtil.hexDump(buffer));
    } finally {
      buffer.release();
    }
  }

  @Test
  void roundTrips() {
    ByteBuf buffer = Unpooled.wrappedBuffer(ByteBufUtil.decodeHexDump(GOLDEN_HEX));
    try {
      MeasuredValueNormalized decoded = MeasuredValueNormalized.Serde.decode(IOA, buffer);
      assertEquals(sample(), decoded);
      assertEquals(0, buffer.readableBytes());
    } finally {
      buffer.release();
    }
  }

  // Deliberate null to verify the constructor's null-check; passing null is the intended test
  // input.
  @SuppressWarnings("DataFlowIssue")
  @Test
  void rejectsNullComponent() {
    assertThrows(
        NullPointerException.class,
        () -> new MeasuredValueNormalized(IOA, null, new Qds(false, false, false, false, false)));
  }
}
