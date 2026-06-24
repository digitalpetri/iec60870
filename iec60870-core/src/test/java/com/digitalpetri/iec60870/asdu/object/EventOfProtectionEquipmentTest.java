package com.digitalpetri.iec60870.asdu.object;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.digitalpetri.iec60870.address.InformationObjectAddress;
import com.digitalpetri.iec60870.asdu.element.EventState;
import com.digitalpetri.iec60870.asdu.element.Sep;
import com.digitalpetri.iec60870.asdu.time.Cp16Time2a;
import com.digitalpetri.iec60870.asdu.time.Cp24Time2a;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

class EventOfProtectionEquipmentTest {

  // Golden-byte derivation (Mode 1, least significant octet first), elements only (no IOA):
  //
  //   SEP         state = ON (0x02), invalid = true (0x80), notTopical = true (0x40)
  //               0x02 | 0x80 | 0x40 -> 0xC2
  //   CP16Time2a  elapsed ms = 12345 (0x3039) little-endian -> 0x39 0x30
  //   CP24Time2a  ms = 23456 (0x5BA0) little-endian -> 0xA0 0x5B
  //               minute = 42 (0x2A), genuine -> RES1 = 0, invalid = 0 -> 0x2A
  //
  // => C2 39 30 A0 5B 2A
  private static final String GOLDEN_HEX = "c23930a05b2a";

  private static final InformationObjectAddress IOA = InformationObjectAddress.of(0x010203);

  private static EventOfProtectionEquipment representative() {
    return new EventOfProtectionEquipment(
        IOA,
        new Sep(EventState.ON, false, false, false, true, true),
        new Cp16Time2a(12345),
        new Cp24Time2a(23456, 42, false, true));
  }

  @Test
  void encodesToGoldenBytes() {
    ByteBuf buffer = Unpooled.buffer();
    try {
      EventOfProtectionEquipment.Serde.encode(representative(), buffer);
      assertEquals(GOLDEN_HEX, ByteBufUtil.hexDump(buffer));
    } finally {
      buffer.release();
    }
  }

  @Test
  void roundTripsFromGoldenBytes() {
    ByteBuf buffer = Unpooled.wrappedBuffer(ByteBufUtil.decodeHexDump(GOLDEN_HEX));
    try {
      EventOfProtectionEquipment decoded = EventOfProtectionEquipment.Serde.decode(IOA, buffer);
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
