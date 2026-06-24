package com.digitalpetri.iec60870.apci;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.digitalpetri.iec60870.ProtocolProfile;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

/**
 * Golden-byte framing tests for {@link Apdu.Serde} over control-only (U-format and S-format) APDUs.
 *
 * <p>These frames carry no ASDU, so encoding and decoding need no information object codecs: the
 * {@link Apdu.Serde} overloads that omit a codec registry are exercised end to end.
 */
class ApduTest {

  private static final ProtocolProfile PROFILE = ProtocolProfile.iec104Default();

  /** Reads all readable bytes of {@code buffer} into a fresh array without releasing the buffer. */
  private static byte[] bytes(ByteBuf buffer) {
    byte[] out = new byte[buffer.readableBytes()];
    buffer.getBytes(buffer.readerIndex(), out);
    return out;
  }

  @Test
  void encodesStartDtActivationFrame() {
    // STARTDT act, control-only:
    //   octet1 = START   = 0x68
    //   octet2 = length  = 0x04 (four control octets, no ASDU body)
    //   octets3..6       = control field 07 00 00 00 (STARTDT act)
    Apdu apdu = new Apdu(new ControlField.TypeU(UFunction.STARTDT_ACT), null);

    byte[] golden = new byte[] {0x68, 0x04, 0x07, 0x00, 0x00, 0x00};

    ByteBuf buffer = Unpooled.buffer();
    try {
      Apdu.Serde.encode(apdu, PROFILE, buffer);

      assertArrayEquals(golden, bytes(buffer));

      Apdu decoded = Apdu.Serde.decode(PROFILE, buffer);
      assertEquals(apdu, decoded);
      assertNull(decoded.asdu());
      assertEquals(0, buffer.readableBytes());
    } finally {
      buffer.release();
    }
  }

  @Test
  void encodesTestFrActivationFrame() {
    // TESTFR act, control-only -> 68 04 43 00 00 00.
    Apdu apdu = new Apdu(new ControlField.TypeU(UFunction.TESTFR_ACT), null);

    byte[] golden = new byte[] {0x68, 0x04, 0x43, 0x00, 0x00, 0x00};

    ByteBuf buffer = Unpooled.buffer();
    try {
      Apdu.Serde.encode(apdu, PROFILE, buffer);
      assertArrayEquals(golden, bytes(buffer));
      assertEquals(apdu, Apdu.Serde.decode(PROFILE, buffer));
    } finally {
      buffer.release();
    }
  }

  @Test
  void encodesSFormatFrame() {
    // S-format acknowledge, N(R)=5, control-only:
    //   68 04 01 00 0A 00  (octet3=0x01 marks S-format, octet5 = (5 << 1) = 0x0A)
    Apdu apdu = new Apdu(new ControlField.TypeS(5), null);

    byte[] golden = new byte[] {0x68, 0x04, 0x01, 0x00, 0x0A, 0x00};

    ByteBuf buffer = Unpooled.buffer();
    try {
      Apdu.Serde.encode(apdu, PROFILE, buffer);

      assertArrayEquals(golden, bytes(buffer));

      Apdu decoded = Apdu.Serde.decode(PROFILE, buffer);
      assertEquals(apdu, decoded);
      assertNull(decoded.asdu());
      assertEquals(0, buffer.readableBytes());
    } finally {
      buffer.release();
    }
  }
}
