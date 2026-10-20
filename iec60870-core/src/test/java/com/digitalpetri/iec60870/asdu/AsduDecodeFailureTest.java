package com.digitalpetri.iec60870.asdu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.digitalpetri.iec60870.AsduDecodeException;
import com.digitalpetri.iec60870.Iec60870Exception;
import com.digitalpetri.iec60870.ProtocolProfile;
import com.digitalpetri.iec60870.UnsupportedAsduTypeException;
import com.digitalpetri.iec60870.asdu.object.EventOfProtectionEquipmentWithCp56Time;
import com.digitalpetri.iec60870.asdu.object.SinglePointWithCp24Time;
import com.digitalpetri.iec60870.asdu.object.SinglePointWithCp56Time;
import com.digitalpetri.iec60870.asdu.time.Cp56Time2a;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.util.HexFormat;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Pins how {@link Asdu.Serde#decode(ProtocolProfile, ByteBuf)} treats the inputs from the
 * undecodable-ASDU trigger survey: content it cannot map onto the typed model fails only with
 * {@link AsduDecodeException} or {@link UnsupportedAsduTypeException}, and out-of-range time tags
 * decode with their fields kept.
 *
 * <p>Payloads use the IEC 104 default profile (COT 2, CA 2, IOA 3 octets).
 */
class AsduDecodeFailureTest {

  private static final ProtocolProfile PROFILE = ProtocolProfile.iec104Default();

  @Test
  void badInputFailsOnlyWithTypedDecodeExceptions() {
    Map<String, Class<? extends Iec60870Exception>> cases =
        Map.of(
            "88010300010064000001", UnsupportedAsduTypeException.class, // TypeID 136
            "29010300010064000001", UnsupportedAsduTypeException.class, // TypeID 41
            "780103000100640000010203", UnsupportedAsduTypeException.class, // F_FR_NA_1
            "01013000010064000001", AsduDecodeException.class, // COT 48
            "01010000010064000001", AsduDecodeException.class, // COT 0
            "018203000100ffffff0101", AsduDecodeException.class, // SQ=1 IOA overflow
            "0d02030001006400000000803f00", AsduDecodeException.class, // VSQ 2, one object
            "0d0103000100640000000000", AsduDecodeException.class, // float cut short
            "0101030001", AsduDecodeException.class); // truncated header
    for (Map.Entry<String, Class<? extends Iec60870Exception>> c : cases.entrySet()) {
      ByteBuf buffer = Unpooled.wrappedBuffer(HexFormat.of().parseHex(c.getKey()));
      try {
        assertThrows(c.getValue(), () -> Asdu.Serde.decode(PROFILE, buffer), c.getKey());
      } finally {
        buffer.release();
      }
    }
  }

  @Test
  void truncatedElementIsReportedAsDecodeExceptionWithTheOriginalCause() {
    ByteBuf buffer = Unpooled.wrappedBuffer(HexFormat.of().parseHex("0d0103000100640000000000"));
    try {
      AsduDecodeException e =
          assertThrows(AsduDecodeException.class, () -> Asdu.Serde.decode(PROFILE, buffer));
      assertInstanceOf(IndexOutOfBoundsException.class, e.getCause());
    } finally {
      buffer.release();
    }
  }

  @Test
  void outOfRangeTimeTagsDecodeWithTheirValues() {
    // M_SP_TB_1, SPI=1, CP56Time2a all zero with IV=1.
    Asdu cp56 = decode("1e01030001006400000100008000000000");
    var single = (SinglePointWithCp56Time) cp56.objects().get(0);
    assertTrue(single.on());
    assertEquals(new Cp56Time2a(0, 0, 0, 0, 0, 0, 0, true, false, true), single.time());

    // M_SP_TA_1, SPI=1, CP24Time2a minute 60 with IV=1.
    Asdu cp24 = decode("020103000100640000010000bc");
    var cp24Object = (SinglePointWithCp24Time) cp24.objects().get(0);
    assertEquals(60, cp24Object.time().minute());
    assertTrue(cp24Object.time().invalid());

    // M_EP_TD_1, SEP with EI=1, CP16Time2a elapsed 65535, CP56Time2a 2026-06-20 14:30:45.500.
    Asdu cp16 = decode("2601030001006400000affffbcb11e0ed4061a");
    var event = (EventOfProtectionEquipmentWithCp56Time) cp16.objects().get(0);
    assertEquals(65535, event.elapsedTime().milliseconds());
  }

  private static Asdu decode(String hex) {
    ByteBuf buffer = Unpooled.wrappedBuffer(HexFormat.of().parseHex(hex));
    try {
      return Asdu.Serde.decode(PROFILE, buffer);
    } finally {
      buffer.release();
    }
  }
}
