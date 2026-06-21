package com.digitalpetri.iec104.asdu.object;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.digitalpetri.iec104.address.InformationObjectAddress;
import com.digitalpetri.iec104.asdu.element.DoubleCommandState;
import com.digitalpetri.iec104.asdu.element.QualifierOfCommand;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

/**
 * Golden-byte and round-trip tests for {@link DoubleCommand.Serde}, plus compact constructor
 * validation.
 */
class DoubleCommandTest {

  /** Reads all readable bytes of {@code buffer} into a fresh array without releasing the buffer. */
  private static byte[] bytes(ByteBuf buffer) {
    byte[] out = new byte[buffer.readableBytes()];
    buffer.getBytes(buffer.readerIndex(), out);
    return out;
  }

  @Test
  void encodesToGoldenByte() {
    // Representative instance: state = ON, qualifier QU = 1 (short pulse), select = true.
    //
    // DCO octet (one octet, bit 1 = LSB): S/E QU(5 bits) DCS(2 bits)
    //   DCS (state ON = 2)              -> 0x02
    //   QU  (1 << 2)                    -> 0x04
    //   S/E (select=true, bit 8)        -> 0x80
    //   octet = 0x02 | 0x04 | 0x80 = 0x86
    DoubleCommand object =
        new DoubleCommand(
            InformationObjectAddress.of(0x010203),
            DoubleCommandState.ON,
            new QualifierOfCommand(1, true));

    byte[] golden = new byte[] {(byte) 0x86};

    ByteBuf buffer = Unpooled.buffer();
    try {
      DoubleCommand.Serde.encode(object, buffer);

      assertEquals(1, buffer.readableBytes());
      assertArrayEquals(golden, bytes(buffer));
    } finally {
      buffer.release();
    }
  }

  @Test
  void roundTripsFromGoldenByte() {
    DoubleCommand original =
        new DoubleCommand(
            InformationObjectAddress.of(0x010203),
            DoubleCommandState.ON,
            new QualifierOfCommand(1, true));

    ByteBuf buffer = Unpooled.wrappedBuffer(new byte[] {(byte) 0x86});
    try {
      DoubleCommand decoded =
          DoubleCommand.Serde.decode(InformationObjectAddress.of(0x010203), buffer);

      assertEquals(original, decoded);
      assertEquals(0, buffer.readableBytes());
    } finally {
      buffer.release();
    }
  }

  @Test
  void rejectsQualifierOutOfRange() {
    // QualifierOfCommand rejects a QU field above 31.
    assertThrows(IllegalArgumentException.class, () -> new QualifierOfCommand(32, false));
  }

  @Test
  void rejectsNullState() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new DoubleCommand(
                InformationObjectAddress.of(0x000000), null, new QualifierOfCommand(0, false)));
  }
}
