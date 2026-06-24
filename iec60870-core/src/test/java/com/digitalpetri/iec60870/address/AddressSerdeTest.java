package com.digitalpetri.iec60870.address;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

/**
 * Framing tests for {@link CommonAddress.Serde} and {@link InformationObjectAddress.Serde}: the
 * little-endian width conventions, golden octets, and address-range validation.
 */
class AddressSerdeTest {

  /** Reads all readable bytes of {@code buffer} into a fresh array without releasing the buffer. */
  private static byte[] bytes(ByteBuf buffer) {
    byte[] out = new byte[buffer.readableBytes()];
    buffer.getBytes(buffer.readerIndex(), out);
    return out;
  }

  @Test
  void commonAddressRoundTripsTwoOctetsLittleEndian() {
    // 0x1234 -> octets 34 12 (least significant first).
    CommonAddress address = CommonAddress.of(0x1234);

    ByteBuf buffer = Unpooled.buffer();
    try {
      CommonAddress.Serde.encode(address, 2, buffer);

      assertArrayEquals(new byte[] {0x34, 0x12}, bytes(buffer));

      CommonAddress decoded = CommonAddress.Serde.decode(2, buffer);
      assertEquals(address, decoded);
      assertEquals(0, buffer.readableBytes());
    } finally {
      buffer.release();
    }
  }

  @Test
  void commonAddressBroadcastGoldenBytes() {
    // 0xFFFF (global address) -> FF FF.
    CommonAddress address = CommonAddress.of(0xFFFF);

    ByteBuf buffer = Unpooled.buffer();
    try {
      CommonAddress.Serde.encode(address, 2, buffer);
      assertArrayEquals(new byte[] {(byte) 0xFF, (byte) 0xFF}, bytes(buffer));
      assertEquals(address, CommonAddress.Serde.decode(2, buffer));
    } finally {
      buffer.release();
    }
  }

  @Test
  void informationObjectAddressRoundTripsThreeOctetsLittleEndian() {
    // 0x123456 -> octets 56 34 12 (least significant first).
    InformationObjectAddress address = InformationObjectAddress.of(0x123456);

    ByteBuf buffer = Unpooled.buffer();
    try {
      InformationObjectAddress.Serde.encode(address, 3, buffer);

      assertArrayEquals(new byte[] {0x56, 0x34, 0x12}, bytes(buffer));

      InformationObjectAddress decoded = InformationObjectAddress.Serde.decode(3, buffer);
      assertEquals(address, decoded);
      assertEquals(0, buffer.readableBytes());
    } finally {
      buffer.release();
    }
  }

  @Test
  void informationObjectAddressMaxValueGoldenBytes() {
    // 0x00FF_FFFF (largest valid IOA) -> FF FF FF.
    InformationObjectAddress address = InformationObjectAddress.of(0x00FF_FFFFL);

    ByteBuf buffer = Unpooled.buffer();
    try {
      InformationObjectAddress.Serde.encode(address, 3, buffer);
      assertArrayEquals(new byte[] {(byte) 0xFF, (byte) 0xFF, (byte) 0xFF}, bytes(buffer));
      assertEquals(address, InformationObjectAddress.Serde.decode(3, buffer));
    } finally {
      buffer.release();
    }
  }

  @Test
  void informationObjectAddressRejectsValueAboveLimit() {
    // One past 0x00FF_FFFF must be rejected by the factory.
    assertThrows(IllegalArgumentException.class, () -> InformationObjectAddress.of(0x0100_0000L));
  }
}
