package com.digitalpetri.iec60870.asdu.object;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.digitalpetri.iec60870.address.InformationObjectAddress;
import com.digitalpetri.iec60870.asdu.element.DoublePointState;
import com.digitalpetri.iec60870.asdu.element.Qds;
import com.digitalpetri.iec60870.asdu.time.Cp56Time2a;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

/** Golden-byte framing and round-trip tests for {@link DoublePointWithCp56Time.Serde}. */
class DoublePointWithCp56TimeTest {

  /** Reads all readable bytes of {@code buffer} into a fresh array without releasing the buffer. */
  private static byte[] bytes(ByteBuf buffer) {
    byte[] out = new byte[buffer.readableBytes()];
    buffer.getBytes(buffer.readerIndex(), out);
    return out;
  }

  @Test
  void encodesToGoldenBytes() {
    // Representative instance: state ON, quality substituted + invalid (blocked/notTopical clear),
    // time 2026-06-20 (Saturday) 14:30:45.500, valid, standard time, genuine.
    //
    // Octet breakdown (Mode 1, least significant octet first):
    //   octet 1 (DIQ): DPI(ON = 2) = 0x02
    //                | BL(false) 0x00 | SB(true) 0x20 | NT(false) 0x00 | IV(true) 0x80
    //                = 0xA2
    //   octets 2..8 (CP56Time2a):
    //     milliseconds = 45 * 1000 + 500 = 45500 = 0xB1BC -> LE: BC B1
    //     octet3 = minute(30=0x1E) | (genuine? 0 : 0x40) | (invalid? 0x80 : 0) = 0x1E
    //     octet4 = hour(14=0x0E) | (summerTime? 0x80 : 0) = 0x0E
    //     octet5 = dayOfMonth(20=0x14) | (dayOfWeek(6) << 5 = 0xC0) = 0xD4
    //     octet6 = month(6) = 0x06
    //     octet7 = year(26 = 2026-2000) = 0x1A
    InformationObjectAddress address = InformationObjectAddress.of(0x010203);
    Cp56Time2a time = new Cp56Time2a(45500, 30, 14, 20, 6, 6, 26, false, false, true);
    Qds quality = new Qds(false, false, true, false, true);
    DoublePointWithCp56Time object =
        new DoublePointWithCp56Time(address, DoublePointState.ON, quality, time);

    byte[] golden = {(byte) 0xA2, (byte) 0xBC, (byte) 0xB1, 0x1E, 0x0E, (byte) 0xD4, 0x06, 0x1A};

    ByteBuf buffer = Unpooled.buffer();
    try {
      DoublePointWithCp56Time.Serde.encode(object, buffer);

      assertEquals(8, buffer.readableBytes());
      assertArrayEquals(golden, bytes(buffer));

      // Round-trip: decode the golden bytes (IOA supplied by the caller) and confirm equality and
      // full consumption of the buffer.
      DoublePointWithCp56Time decoded =
          DoublePointWithCp56Time.Serde.decode(InformationObjectAddress.of(0x010203), buffer);
      assertEquals(object, decoded);
      assertEquals(0, buffer.readableBytes());
    } finally {
      buffer.release();
    }
  }

  @Test
  void roundTripsIndeterminateStateWithAllQualityFlags() {
    // octet 1 (DIQ): DPI(INDETERMINATE = 3) = 0x03 | BL 0x10 | SB 0x20 | NT 0x40 | IV 0x80 = 0xF3
    InformationObjectAddress address = InformationObjectAddress.of(7);
    Cp56Time2a time = new Cp56Time2a(0, 0, 0, 1, 0, 1, 0, true, true, false);
    Qds quality = new Qds(false, true, true, true, true);
    DoublePointWithCp56Time object =
        new DoublePointWithCp56Time(address, DoublePointState.INDETERMINATE, quality, time);

    ByteBuf buffer = Unpooled.buffer();
    try {
      DoublePointWithCp56Time.Serde.encode(object, buffer);

      assertEquals(0xF3, buffer.getUnsignedByte(buffer.readerIndex()));

      DoublePointWithCp56Time decoded = DoublePointWithCp56Time.Serde.decode(address, buffer);
      assertEquals(object, decoded);
      assertEquals(0, buffer.readableBytes());
    } finally {
      buffer.release();
    }
  }
}
