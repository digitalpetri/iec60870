package com.digitalpetri.iec104.asdu.object;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.digitalpetri.iec104.address.InformationObjectAddress;
import com.digitalpetri.iec104.asdu.element.DoublePointState;
import com.digitalpetri.iec104.asdu.element.Qds;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

/**
 * Golden-byte and round-trip tests for {@link DoublePointInformation.Serde}, plus compact
 * constructor validation.
 */
class DoublePointInformationTest {

  /** Reads all readable bytes of {@code buffer} into a fresh array without releasing the buffer. */
  private static byte[] bytes(ByteBuf buffer) {
    byte[] out = new byte[buffer.readableBytes()];
    buffer.getBytes(buffer.readerIndex(), out);
    return out;
  }

  @Test
  void encodesToGoldenByte() {
    // Representative instance: state = ON, quality = substituted + invalid.
    //
    // DIQ octet (one octet, bit 1 = LSB): IV NT SB BL 0 0 DPI
    //   DPI (state ON = 2)  -> 0x02
    //   BL  (blocked=false) -> 0x00
    //   SB  (substituted)   -> 0x20
    //   NT  (notTopical=false) -> 0x00
    //   IV  (invalid)       -> 0x80
    //   octet = 0x02 | 0x20 | 0x80 = 0xA2
    Qds quality = new Qds(false, false, true, false, true);
    DoublePointInformation object =
        new DoublePointInformation(
            InformationObjectAddress.of(0x010203), DoublePointState.ON, quality);

    byte[] golden = new byte[] {(byte) 0xA2};

    ByteBuf buffer = Unpooled.buffer();
    try {
      DoublePointInformation.Serde.encode(object, buffer);

      assertEquals(1, buffer.readableBytes());
      assertArrayEquals(golden, bytes(buffer));
    } finally {
      buffer.release();
    }
  }

  @Test
  void roundTripsFromGoldenByte() {
    Qds quality = new Qds(false, false, true, false, true);
    DoublePointInformation original =
        new DoublePointInformation(
            InformationObjectAddress.of(0x010203), DoublePointState.ON, quality);

    ByteBuf buffer = Unpooled.wrappedBuffer(new byte[] {(byte) 0xA2});
    try {
      DoublePointInformation decoded =
          DoublePointInformation.Serde.decode(InformationObjectAddress.of(0x010203), buffer);

      assertEquals(original, decoded);
      assertEquals(0, buffer.readableBytes());
    } finally {
      buffer.release();
    }
  }

  @Test
  void rejectsNullState() {
    Qds quality = new Qds(false, false, false, false, false);
    assertThrows(
        IllegalArgumentException.class,
        () -> new DoublePointInformation(InformationObjectAddress.of(0x000000), null, quality));
  }
}
