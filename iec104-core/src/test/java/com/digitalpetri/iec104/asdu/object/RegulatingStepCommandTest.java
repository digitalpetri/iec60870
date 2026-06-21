package com.digitalpetri.iec104.asdu.object;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.digitalpetri.iec104.address.InformationObjectAddress;
import com.digitalpetri.iec104.asdu.element.QualifierOfCommand;
import com.digitalpetri.iec104.asdu.element.StepCommandState;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

/**
 * Golden-byte and round-trip tests for {@link RegulatingStepCommand.Serde}, plus compact
 * constructor and qualifier validation.
 */
class RegulatingStepCommandTest {

  /** Reads all readable bytes of {@code buffer} into a fresh array without releasing the buffer. */
  private static byte[] bytes(ByteBuf buffer) {
    byte[] out = new byte[buffer.readableBytes()];
    buffer.getBytes(buffer.readerIndex(), out);
    return out;
  }

  @Test
  void encodesToGoldenByte() {
    // Representative instance: state = NEXT_STEP_HIGHER, QU = 1 (short pulse), S/E = select.
    //
    // RCO octet (one octet, bit 1 = LSB): S/E(b8) QU(b3..7) RCS(b1..2)
    //   RCS (NEXT_STEP_HIGHER = 2)     -> 0x02
    //   QU  (1 << 2)                   -> 0x04
    //   S/E (select = true, bit 8)     -> 0x80
    //   octet = 0x02 | 0x04 | 0x80 = 0x86
    RegulatingStepCommand object =
        new RegulatingStepCommand(
            InformationObjectAddress.of(0x010203),
            StepCommandState.NEXT_STEP_HIGHER,
            new QualifierOfCommand(1, true));

    byte[] golden = new byte[] {(byte) 0x86};

    ByteBuf buffer = Unpooled.buffer();
    try {
      RegulatingStepCommand.Serde.encode(object, buffer);

      assertEquals(1, buffer.readableBytes());
      assertArrayEquals(golden, bytes(buffer));
    } finally {
      buffer.release();
    }
  }

  @Test
  void roundTripsFromGoldenByte() {
    RegulatingStepCommand original =
        new RegulatingStepCommand(
            InformationObjectAddress.of(0x010203),
            StepCommandState.NEXT_STEP_HIGHER,
            new QualifierOfCommand(1, true));

    ByteBuf buffer = Unpooled.wrappedBuffer(new byte[] {(byte) 0x86});
    try {
      RegulatingStepCommand decoded =
          RegulatingStepCommand.Serde.decode(InformationObjectAddress.of(0x010203), buffer);

      assertEquals(original, decoded);
      assertEquals(0, buffer.readableBytes());
    } finally {
      buffer.release();
    }
  }

  @Test
  void rejectsNullState() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new RegulatingStepCommand(
                InformationObjectAddress.of(0x000000), null, new QualifierOfCommand(0, false)));
  }

  @Test
  void rejectsQualifierOutOfRange() {
    // QualifierOfCommand validates QU in [0, 31]; 32 must be rejected.
    assertThrows(IllegalArgumentException.class, () -> new QualifierOfCommand(32, false));
  }
}
