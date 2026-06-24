package com.digitalpetri.iec60870.asdu.object;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.digitalpetri.iec60870.address.InformationObjectAddress;
import com.digitalpetri.iec60870.asdu.element.Oci;
import com.digitalpetri.iec60870.asdu.element.Qdp;
import com.digitalpetri.iec60870.asdu.time.Cp16Time2a;
import com.digitalpetri.iec60870.asdu.time.Cp24Time2a;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

class PackedOutputCircuitInfoTest {

  // Golden-byte derivation (Mode 1, least significant octet first), elements only (no IOA):
  //
  //   OCI         generalCommand = true (GC b1 = 0x01), commandL2 = true (CL2 b3 = 0x04) -> 0x05
  //   QDP         invalid = true (0x80), notTopical = true (0x40) -> 0xC0
  //   CP16Time2a  ms = 12345 (0x3039) little-endian -> 0x39 0x30
  //   CP24Time2a  ms = 4660 (0x1234) little-endian -> 0x34 0x12
  //               minute = 42 (0x2A), genuine -> RES1 = 0, invalid = 0 -> 0x2A
  //
  // => 05 C0 39 30 34 12 2A
  private static final String GOLDEN_HEX = "05c0393034122a";

  private static final InformationObjectAddress IOA = InformationObjectAddress.of(0x010203);

  private static PackedOutputCircuitInfo representative() {
    return new PackedOutputCircuitInfo(
        IOA,
        new Oci(true, false, true, false),
        new Qdp(false, false, false, true, true),
        new Cp16Time2a(12345),
        new Cp24Time2a(4660, 42, false, true));
  }

  @Test
  void encodesToGoldenBytes() {
    ByteBuf buffer = Unpooled.buffer();
    try {
      PackedOutputCircuitInfo.Serde.encode(representative(), buffer);
      assertEquals(GOLDEN_HEX, ByteBufUtil.hexDump(buffer));
    } finally {
      buffer.release();
    }
  }

  @Test
  void roundTripsFromGoldenBytes() {
    ByteBuf buffer = Unpooled.wrappedBuffer(ByteBufUtil.decodeHexDump(GOLDEN_HEX));
    try {
      PackedOutputCircuitInfo decoded = PackedOutputCircuitInfo.Serde.decode(IOA, buffer);
      assertEquals(representative(), decoded);
      assertEquals(0, buffer.readableBytes());
    } finally {
      buffer.release();
    }
  }

  @Test
  void rejectsOutOfRangeElapsedTime() {
    // CP16Time2a milliseconds 60000 is outside the valid range [0, 59999].
    assertThrows(IllegalArgumentException.class, () -> new Cp16Time2a(60000));
  }
}
