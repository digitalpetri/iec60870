package com.digitalpetri.iec60870.asdu.object;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.digitalpetri.iec60870.address.InformationObjectAddress;
import com.digitalpetri.iec60870.asdu.element.QualifierOfCommand;
import com.digitalpetri.iec60870.asdu.element.StepCommandState;
import com.digitalpetri.iec60870.asdu.time.Cp56Time2a;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

/** Golden-byte framing and round-trip tests for {@link RegulatingStepCommandWithCp56Time.Serde}. */
class RegulatingStepCommandWithCp56TimeTest {

  /** Reads all readable bytes of {@code buffer} into a fresh array without releasing the buffer. */
  private static byte[] bytes(ByteBuf buffer) {
    byte[] out = new byte[buffer.readableBytes()];
    buffer.getBytes(buffer.readerIndex(), out);
    return out;
  }

  @Test
  void encodesToGoldenBytes() {
    // Representative instance: RCS NEXT_STEP_HIGHER, QU = 3 (persistent output), S/E = select,
    // time 2026-06-20 (Saturday) 14:30:45.500, valid, standard time, genuine.
    //
    // Octet breakdown (Mode 1, least significant octet first):
    //   octet 1 (RCO): RCS(NEXT_STEP_HIGHER = 2) = 0x02
    //                | QU(3) << 2 = 0x0C
    //                | S/E(select) = 0x80
    //                = 0x8E
    //   octets 2..8 (CP56Time2a):
    //     milliseconds = 45 * 1000 + 500 = 45500 = 0xB1BC -> LE: BC B1
    //     octet3 = minute(30=0x1E) | (genuine? 0 : 0x40) | (invalid? 0x80 : 0) = 0x1E
    //     octet4 = hour(14=0x0E) | (summerTime? 0x80 : 0) = 0x0E
    //     octet5 = dayOfMonth(20=0x14) | (dayOfWeek(6) << 5 = 0xC0) = 0xD4
    //     octet6 = month(6) = 0x06
    //     octet7 = year(26 = 2026-2000) = 0x1A
    InformationObjectAddress address = InformationObjectAddress.of(0x010203);
    Cp56Time2a time = new Cp56Time2a(45500, 30, 14, 20, 6, 6, 26, false, false, true);
    QualifierOfCommand qualifier = new QualifierOfCommand(3, true);
    RegulatingStepCommandWithCp56Time object =
        new RegulatingStepCommandWithCp56Time(
            address, StepCommandState.NEXT_STEP_HIGHER, qualifier, time);

    byte[] golden = {(byte) 0x8E, (byte) 0xBC, (byte) 0xB1, 0x1E, 0x0E, (byte) 0xD4, 0x06, 0x1A};

    ByteBuf buffer = Unpooled.buffer();
    try {
      RegulatingStepCommandWithCp56Time.Serde.encode(object, buffer);

      assertEquals(8, buffer.readableBytes());
      assertArrayEquals(golden, bytes(buffer));

      // Round-trip: decode the golden bytes (IOA supplied by the caller) and confirm equality and
      // full consumption of the buffer.
      RegulatingStepCommandWithCp56Time decoded =
          RegulatingStepCommandWithCp56Time.Serde.decode(
              InformationObjectAddress.of(0x010203), buffer);
      assertEquals(object, decoded);
      assertEquals(0, buffer.readableBytes());
    } finally {
      buffer.release();
    }
  }

  @Test
  void roundTripsExecuteWithLowerStep() {
    // octet 1 (RCO): RCS(NEXT_STEP_LOWER = 1) = 0x01 | QU(1) << 2 = 0x04 | S/E(execute) = 0x00
    //              = 0x05
    InformationObjectAddress address = InformationObjectAddress.of(42);
    Cp56Time2a time = new Cp56Time2a(0, 0, 0, 1, 0, 1, 0, true, true, false);
    QualifierOfCommand qualifier = new QualifierOfCommand(1, false);
    RegulatingStepCommandWithCp56Time object =
        new RegulatingStepCommandWithCp56Time(
            address, StepCommandState.NEXT_STEP_LOWER, qualifier, time);

    ByteBuf buffer = Unpooled.buffer();
    try {
      RegulatingStepCommandWithCp56Time.Serde.encode(object, buffer);

      assertEquals(0x05, buffer.getUnsignedByte(buffer.readerIndex()));

      RegulatingStepCommandWithCp56Time decoded =
          RegulatingStepCommandWithCp56Time.Serde.decode(address, buffer);
      assertEquals(object, decoded);
      assertEquals(0, buffer.readableBytes());
    } finally {
      buffer.release();
    }
  }

  @Test
  void rejectsQualifierOutOfRange() {
    // QU is a five-bit field; 32 exceeds the maximum of 31 and must be rejected.
    assertThrows(IllegalArgumentException.class, () -> new QualifierOfCommand(32, false));
  }
}
