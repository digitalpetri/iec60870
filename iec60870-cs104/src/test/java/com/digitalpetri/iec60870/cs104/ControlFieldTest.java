package com.digitalpetri.iec60870.cs104;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

/**
 * Golden-octet framing tests for {@link ControlField.Serde}: the four control octets of the I, S,
 * and U formats and their decode round-trips.
 */
class ControlFieldTest {

  /** Reads all readable bytes of {@code buffer} into a fresh array without releasing the buffer. */
  private static byte[] bytes(ByteBuf buffer) {
    byte[] out = new byte[buffer.readableBytes()];
    buffer.getBytes(buffer.readerIndex(), out);
    return out;
  }

  /** Encodes {@code control}, asserts the golden octets, and asserts a decode round-trip. */
  private static void assertControlField(ControlField control, byte[] golden) {
    ByteBuf buffer = Unpooled.buffer();
    try {
      ControlField.Serde.encode(control, buffer);

      assertEquals(4, buffer.readableBytes());
      assertArrayEquals(golden, bytes(buffer));

      ControlField decoded = ControlField.Serde.decode(buffer);
      assertEquals(control, decoded);
      assertEquals(0, buffer.readableBytes());
    } finally {
      buffer.release();
    }
  }

  @Test
  void encodesIFormatSequenceNumbers() {
    // N(S)=1, N(R)=2:
    //   octet1 = (1 << 1) & 0xFE = 0x02 (bit1 = 0 marks I-format)
    //   octet2 = (1 >> 7)        = 0x00
    //   octet3 = (2 << 1) & 0xFE = 0x04
    //   octet4 = (2 >> 7)        = 0x00
    assertControlField(new ControlField.TypeI(1, 2), new byte[] {0x02, 0x00, 0x04, 0x00});
  }

  @Test
  void encodesIFormatLargeSequenceNumbersSpanningTwoOctets() {
    // N(S)=200, N(R)=0:
    //   200 = 0b11001000; (200 << 1) = 0b1_10010000 -> octet1 = 0x90, octet2 = 0x01.
    assertControlField(new ControlField.TypeI(200, 0), new byte[] {(byte) 0x90, 0x01, 0x00, 0x00});
  }

  @Test
  void encodesSFormatReceiveSequenceNumber() {
    // S-format: octet1 = 0x01, octet2 = 0x00, N(R)=5 -> octet3 = (5 << 1) = 0x0A, octet4 = 0x00.
    assertControlField(new ControlField.TypeS(5), new byte[] {0x01, 0x00, 0x0A, 0x00});
  }

  @Test
  void encodesUFormatStartDtActivation() {
    // STARTDT act -> 07 00 00 00.
    assertControlField(
        new ControlField.TypeU(UFunction.STARTDT_ACT), new byte[] {0x07, 0x00, 0x00, 0x00});
  }

  @Test
  void encodesUFormatStartDtConfirmation() {
    // STARTDT con -> 0B 00 00 00.
    assertControlField(
        new ControlField.TypeU(UFunction.STARTDT_CON), new byte[] {0x0B, 0x00, 0x00, 0x00});
  }

  @Test
  void encodesUFormatStopDtActivation() {
    // STOPDT act -> 13 00 00 00.
    assertControlField(
        new ControlField.TypeU(UFunction.STOPDT_ACT), new byte[] {0x13, 0x00, 0x00, 0x00});
  }

  @Test
  void encodesUFormatTestFrActivation() {
    // TESTFR act -> 43 00 00 00.
    assertControlField(
        new ControlField.TypeU(UFunction.TESTFR_ACT), new byte[] {0x43, 0x00, 0x00, 0x00});
  }
}
