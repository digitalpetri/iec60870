package com.digitalpetri.iec104.asdu.object;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.digitalpetri.iec104.address.InformationObjectAddress;
import com.digitalpetri.iec104.asdu.element.Qds;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

class SinglePointInformationTest {

  @Test
  void encodesGoldenBytes() {
    // SIQ octet (IEC 60870-5-101 §7.2.6.1): IV NT SB BL 0 0 0 SPI.
    //   SPI = 1  -> 0x01 (on)
    //   BL  = 0
    //   SB  = 0
    //   NT  = 1  -> 0x40 (not topical)
    //   IV  = 1  -> 0x80 (invalid)
    // octet = 0x01 | 0x40 | 0x80 = 0xC1
    SinglePointInformation o =
        new SinglePointInformation(
            InformationObjectAddress.of(0x000123), true, new Qds(false, false, false, true, true));

    ByteBuf buffer = Unpooled.buffer();
    try {
      SinglePointInformation.Serde.encode(o, buffer);
      assertEquals("c1", ByteBufUtil.hexDump(buffer));
    } finally {
      buffer.release();
    }
  }

  @Test
  void roundTripsFromGoldenBytes() {
    SinglePointInformation original =
        new SinglePointInformation(
            InformationObjectAddress.of(0x000123), true, new Qds(false, false, false, true, true));

    ByteBuf buffer = Unpooled.wrappedBuffer(new byte[] {(byte) 0xC1});
    try {
      SinglePointInformation decoded =
          SinglePointInformation.Serde.decode(InformationObjectAddress.of(0x000123), buffer);
      assertEquals(original, decoded);
      assertEquals(0, buffer.readableBytes());
    } finally {
      buffer.release();
    }
  }

  @Test
  void overflowBitIsNeverEncodedAndDecodesFalse() {
    // The SIQ octet has no overflow bit; an overflow=true input must not leak onto the wire,
    // and a decoded value always reports overflow=false.
    SinglePointInformation o =
        new SinglePointInformation(
            InformationObjectAddress.of(1), false, new Qds(true, false, false, false, false));

    ByteBuf buffer = Unpooled.buffer();
    try {
      SinglePointInformation.Serde.encode(o, buffer);
      assertEquals("00", ByteBufUtil.hexDump(buffer));

      SinglePointInformation decoded =
          SinglePointInformation.Serde.decode(InformationObjectAddress.of(1), buffer);
      assertFalse(decoded.quality().overflow());
    } finally {
      buffer.release();
    }
  }
}
