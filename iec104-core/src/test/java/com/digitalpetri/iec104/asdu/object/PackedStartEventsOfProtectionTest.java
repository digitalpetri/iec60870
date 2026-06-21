package com.digitalpetri.iec104.asdu.object;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.digitalpetri.iec104.address.InformationObjectAddress;
import com.digitalpetri.iec104.asdu.element.Qdp;
import com.digitalpetri.iec104.asdu.element.Spe;
import com.digitalpetri.iec104.asdu.time.Cp16Time2a;
import com.digitalpetri.iec104.asdu.time.Cp24Time2a;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

class PackedStartEventsOfProtectionTest {

  // Golden-byte derivation (Mode 1, least significant octet first), elements only (no IOA):
  //
  //   SPE         generalStart=true (GS, 0x01), startL2=true (SL2, 0x04),
  //               startReverse=true (SRD, 0x20) -> 0x25
  //   QDP         elapsedTimeInvalid=true (EI, 0x08), invalid=true (IV, 0x80) -> 0x88
  //   CP16Time2a  ms = 4660 (0x1234) little-endian -> 0x34 0x12
  //   CP24Time2a  ms = 12345 (0x3039) little-endian -> 0x39 0x30
  //               minute = 42 (0x2A), genuine -> RES1 = 0, invalid = 0 -> 0x2A
  //
  // => 25 88 34 12 39 30 2A
  private static final String GOLDEN_HEX = "258834123930" + "2a";

  private static final InformationObjectAddress IOA = InformationObjectAddress.of(0x010203);

  private static PackedStartEventsOfProtection representative() {
    return new PackedStartEventsOfProtection(
        IOA,
        new Spe(true, false, true, false, false, true),
        new Qdp(true, false, false, false, true),
        new Cp16Time2a(4660),
        new Cp24Time2a(12345, 42, false, true));
  }

  @Test
  void encodesToGoldenBytes() {
    ByteBuf buffer = Unpooled.buffer();
    try {
      PackedStartEventsOfProtection.Serde.encode(representative(), buffer);
      assertEquals(GOLDEN_HEX, ByteBufUtil.hexDump(buffer));
    } finally {
      buffer.release();
    }
  }

  @Test
  void roundTripsFromGoldenBytes() {
    ByteBuf buffer = Unpooled.wrappedBuffer(ByteBufUtil.decodeHexDump(GOLDEN_HEX));
    try {
      PackedStartEventsOfProtection decoded =
          PackedStartEventsOfProtection.Serde.decode(IOA, buffer);
      assertEquals(representative(), decoded);
      assertEquals(0, buffer.readableBytes());
    } finally {
      buffer.release();
    }
  }

  @Test
  void rejectsOutOfRangeElapsedTime() {
    // CP16Time2a milliseconds 60000 is outside the range [0, 59999].
    assertThrows(IllegalArgumentException.class, () -> new Cp16Time2a(60000));
  }
}
