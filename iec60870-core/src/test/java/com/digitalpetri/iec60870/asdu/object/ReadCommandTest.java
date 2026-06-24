package com.digitalpetri.iec60870.asdu.object;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.digitalpetri.iec60870.address.InformationObjectAddress;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

class ReadCommandTest {

  // Octet-by-octet derivation from 7.3.4.3 (C_RD_NA_1, elements after the IOA):
  //   The read command carries no information elements; the IOA alone identifies the value.
  //   => zero element octets are emitted after the IOA.
  // => golden bytes "" (empty)
  private static final String GOLDEN_HEX = "";

  private static final InformationObjectAddress IOA = InformationObjectAddress.of(0x010203);

  private static ReadCommand sample() {
    return new ReadCommand(IOA);
  }

  @Test
  void encodesGoldenBytes() {
    ByteBuf buffer = Unpooled.buffer();
    try {
      ReadCommand.Serde.encode(sample(), buffer);
      assertEquals(GOLDEN_HEX, ByteBufUtil.hexDump(buffer));
      assertEquals(0, buffer.readableBytes());
    } finally {
      buffer.release();
    }
  }

  @Test
  void roundTrips() {
    ByteBuf buffer = Unpooled.buffer();
    try {
      ReadCommand decoded = ReadCommand.Serde.decode(IOA, buffer);
      assertEquals(sample(), decoded);
      assertEquals(0, buffer.readableBytes());
    } finally {
      buffer.release();
    }
  }

  @Test
  void rejectsNullComponent() {
    assertThrows(NullPointerException.class, () -> new ReadCommand(null));
  }
}
