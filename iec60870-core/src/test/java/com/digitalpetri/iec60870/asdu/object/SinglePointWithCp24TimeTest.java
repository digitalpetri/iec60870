package com.digitalpetri.iec60870.asdu.object;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.digitalpetri.iec60870.address.InformationObjectAddress;
import com.digitalpetri.iec60870.asdu.element.Qds;
import com.digitalpetri.iec60870.asdu.time.Cp24Time2a;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

class SinglePointWithCp24TimeTest {

  // Golden instance:
  //   on        = true                (SPI bit set)
  //   quality   = blocked only        (BL bit set; SB/NT/IV clear; OV not defined => 0)
  //   time      = ms=12345, min=42, invalid=false, genuine=true
  //
  // Octet breakdown (Mode 1, least significant octet first):
  //   octet 1 : SIQ  = SPI(0x01) | BL(0x10)            = 0x11
  //   octet 2 : CP24 ms low  (12345 = 0x3039, LE)      = 0x39
  //   octet 3 : CP24 ms high                           = 0x30
  //   octet 4 : CP24 octet3 = min 42 (0x2A), IV=0, GEN genuine=0 => 0x2A
  // => 11 39 30 2A
  private static final byte[] GOLDEN = {0x11, 0x39, 0x30, 0x2A};

  private static final InformationObjectAddress IOA = InformationObjectAddress.of(0x000010);

  private static SinglePointWithCp24Time newGolden() {
    return new SinglePointWithCp24Time(
        IOA,
        true,
        new Qds(false, true, false, false, false),
        new Cp24Time2a(12345, 42, false, true));
  }

  @Test
  void encodeProducesGoldenBytes() {
    ByteBuf buffer = Unpooled.buffer();
    try {
      SinglePointWithCp24Time.Serde.encode(newGolden(), buffer);

      byte[] actual = new byte[buffer.readableBytes()];
      buffer.readBytes(actual);
      assertArrayEquals(GOLDEN, actual);
    } finally {
      buffer.release();
    }
  }

  @Test
  void decodeRoundTripsGoldenBytes() {
    ByteBuf buffer = Unpooled.wrappedBuffer(GOLDEN);
    try {
      SinglePointWithCp24Time decoded = SinglePointWithCp24Time.Serde.decode(IOA, buffer);

      assertEquals(newGolden(), decoded);
      assertEquals(0, buffer.readableBytes());
    } finally {
      buffer.release();
    }
  }

  @Test
  void compactConstructorRejectsOutOfRangeTime() {
    // The CP24Time2a component validates its own ranges; minute 60 is out of range.
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new SinglePointWithCp24Time(
                IOA,
                true,
                new Qds(false, false, false, false, false),
                new Cp24Time2a(0, 60, false, true)));
  }
}
