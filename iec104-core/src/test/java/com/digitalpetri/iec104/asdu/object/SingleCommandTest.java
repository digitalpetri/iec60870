package com.digitalpetri.iec104.asdu.object;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.digitalpetri.iec104.address.InformationObjectAddress;
import com.digitalpetri.iec104.asdu.element.QualifierOfCommand;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

class SingleCommandTest {

  @Test
  void encodesGoldenBytes() {
    // SCO octet (IEC 60870-5-101 §7.2.6.15): S/E QU QU QU QU QU 0 SCS.
    //   SCS = 1            -> 0x01 (command ON)
    //   QU  = 1 (short pulse) in bits 3..7 -> (1 << 2) = 0x04
    //   S/E = 1 (select)   -> 0x80
    // octet = 0x01 | 0x04 | 0x80 = 0x85
    SingleCommand o =
        new SingleCommand(
            InformationObjectAddress.of(0x000123), true, new QualifierOfCommand(1, true));

    ByteBuf buffer = Unpooled.buffer();
    try {
      SingleCommand.Serde.encode(o, buffer);
      assertEquals("85", ByteBufUtil.hexDump(buffer));
    } finally {
      buffer.release();
    }
  }

  @Test
  void roundTripsFromGoldenBytes() {
    SingleCommand original =
        new SingleCommand(
            InformationObjectAddress.of(0x000123), true, new QualifierOfCommand(1, true));

    ByteBuf buffer = Unpooled.wrappedBuffer(new byte[] {(byte) 0x85});
    try {
      SingleCommand decoded =
          SingleCommand.Serde.decode(InformationObjectAddress.of(0x000123), buffer);
      assertEquals(original, decoded);
      assertEquals(0, buffer.readableBytes());
    } finally {
      buffer.release();
    }
  }

  @Test
  void rejectsOutOfRangeQualifier() {
    // QU is a five-bit field (0..31); 32 must be rejected by QualifierOfCommand's compact ctor.
    assertThrows(IllegalArgumentException.class, () -> new QualifierOfCommand(32, false));
  }
}
