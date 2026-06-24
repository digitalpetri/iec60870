package com.digitalpetri.iec60870.asdu.object;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.digitalpetri.iec60870.address.InformationObjectAddress;
import com.digitalpetri.iec60870.asdu.element.NormalizedValue;
import com.digitalpetri.iec60870.asdu.element.QualifierOfSetpoint;
import com.digitalpetri.iec60870.asdu.time.Cp56Time2a;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

class SetpointNormalizedWithCp56TimeTest {

  /**
   * Golden bytes for the representative instance below.
   *
   * <p>Octet-by-octet derivation (Mode 1, least significant octet first):
   *
   * <ul>
   *   <li>NVA: raw value 16384 = 0x4000 LE = <b>00 40</b>
   *   <li>QOS: QL 5 = 0x05 | S/E select (0x80) = <b>85</b>
   *   <li>CP56Time2a milliseconds 12345 = 0x3039 LE = <b>39 30</b>
   *   <li>CP56Time2a octet 3: minute 30 = 0x1E (genuine, valid) = <b>1E</b>
   *   <li>CP56Time2a octet 4: hour 10 = 0x0A (no summer time) = <b>0A</b>
   *   <li>CP56Time2a octet 5: dayOfMonth 9 | (dayOfWeek 2 &lt;&lt; 5) = 0x09 | 0x40 = <b>49</b>
   *   <li>CP56Time2a octet 6: month 6 = <b>06</b>
   *   <li>CP56Time2a octet 7: year 20 = 0x14 = <b>14</b>
   * </ul>
   */
  private static final String GOLDEN_HEX = "00408539301E0A490614";

  private static SetpointNormalizedWithCp56Time representative() {
    return new SetpointNormalizedWithCp56Time(
        InformationObjectAddress.of(100),
        new NormalizedValue((short) 16384),
        new QualifierOfSetpoint(5, true),
        new Cp56Time2a(12345, 30, 10, 9, 2, 6, 20, false, false, true));
  }

  @Test
  void encodeProducesGoldenBytes() {
    ByteBuf buffer = Unpooled.buffer();
    try {
      SetpointNormalizedWithCp56Time.Serde.encode(representative(), buffer);
      assertEquals(GOLDEN_HEX, ByteBufUtil.hexDump(buffer).toUpperCase());
    } finally {
      buffer.release();
    }
  }

  @Test
  void decodeRoundTripsAndConsumesBuffer() {
    ByteBuf buffer = Unpooled.wrappedBuffer(ByteBufUtil.decodeHexDump(GOLDEN_HEX));
    try {
      SetpointNormalizedWithCp56Time decoded =
          SetpointNormalizedWithCp56Time.Serde.decode(InformationObjectAddress.of(100), buffer);
      assertEquals(representative(), decoded);
      assertEquals(0, buffer.readableBytes());
    } finally {
      buffer.release();
    }
  }

  @Test
  void qualifierOutOfRangeIsRejected() {
    assertThrows(IllegalArgumentException.class, () -> new QualifierOfSetpoint(128, false));
  }
}
