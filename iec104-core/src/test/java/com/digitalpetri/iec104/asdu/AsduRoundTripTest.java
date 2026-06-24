package com.digitalpetri.iec104.asdu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.digitalpetri.iec104.AsduDecodeException;
import com.digitalpetri.iec104.ProtocolProfile;
import com.digitalpetri.iec104.address.CommonAddress;
import com.digitalpetri.iec104.address.InformationObjectAddress;
import com.digitalpetri.iec104.address.OriginatorAddress;
import com.digitalpetri.iec104.asdu.element.BinaryCounterReading;
import com.digitalpetri.iec104.asdu.element.DoublePointState;
import com.digitalpetri.iec104.asdu.element.Qds;
import com.digitalpetri.iec104.asdu.element.QualifierOfCommand;
import com.digitalpetri.iec104.asdu.element.QualifierOfInterrogation;
import com.digitalpetri.iec104.asdu.element.QualifierOfSetpoint;
import com.digitalpetri.iec104.asdu.object.ClockSynchronizationCommand;
import com.digitalpetri.iec104.asdu.object.DoublePointInformation;
import com.digitalpetri.iec104.asdu.object.IntegratedTotals;
import com.digitalpetri.iec104.asdu.object.InterrogationCommand;
import com.digitalpetri.iec104.asdu.object.MeasuredValueScaled;
import com.digitalpetri.iec104.asdu.object.MeasuredValueShortFloatWithCp56Time;
import com.digitalpetri.iec104.asdu.object.SetpointShortFloat;
import com.digitalpetri.iec104.asdu.object.SingleCommand;
import com.digitalpetri.iec104.asdu.object.SinglePointInformation;
import com.digitalpetri.iec104.asdu.time.Cp56Time2a;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Full-ASDU golden-byte and round-trip coverage for {@link Asdu.Serde} through the {@linkplain
 * InformationObjectCodecs#standard() standard} codec registry, exercising one representative type
 * from every information-object family.
 *
 * <p>All bytes assume the standard IEC 60870-5-104 profile {@link ProtocolProfile#iec104Default()}:
 * a 2-octet cause of transmission (with originator address), a 2-octet common address, and a
 * 3-octet information object address, all little-endian (Mode 1). With that profile the data unit
 * identifier is eight octets:
 *
 * <pre>
 *   octet 1   : type identification
 *   octet 2   : variable structure qualifier = (SQ ? 0x80 : 0) | (count &amp; 0x7F)
 *   octet 3   : cause of transmission        = (T ? 0x80 : 0) | (P/N ? 0x40 : 0) | (cause &amp; 0x3F)
 *   octet 4   : originator address
 *   octets 5-6: common address (little-endian)
 * </pre>
 *
 * followed by the objects (each preceded by a 3-octet little-endian information object address for
 * SQ = 0; a single leading address for SQ = 1).
 */
class AsduRoundTripTest {

  private static final ProtocolProfile PROFILE = ProtocolProfile.iec104Default();

  /** Common address 10 used across the fixtures; little-endian octets {@code 0a 00}. */
  private static final CommonAddress CA = CommonAddress.of(10);

  private static String encode(Asdu asdu) {
    ByteBuf buffer = Unpooled.buffer();
    try {
      Asdu.Serde.encode(asdu, PROFILE, buffer);
      return ByteBufUtil.hexDump(buffer);
    } finally {
      buffer.release();
    }
  }

  private static Asdu roundTrip(Asdu asdu) {
    ByteBuf buffer = Unpooled.buffer();
    try {
      Asdu.Serde.encode(asdu, PROFILE, buffer);
      Asdu decoded = Asdu.Serde.decode(PROFILE, buffer);
      assertEquals(0, buffer.readableBytes(), "buffer fully consumed");
      return decoded;
    } finally {
      buffer.release();
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Golden-byte assertions (hand-computed, octet-by-octet).
  // ---------------------------------------------------------------------------------------------

  @Test
  void singlePointGoldenBytes() {
    // M_SP_NA_1 (1), SQ = 0, one object, cause SPONTANEOUS (3), originator 5, common address 10.
    // DUI:
    //   type            = 1                                  => 01
    //   VSQ             = SQ(0) | count(1)                   => 01
    //   COT             = T(0) | P/N(0) | cause SPONTANEOUS(3) => 03
    //   originator      = 5                                  => 05
    //   common address  = 10 = 0x000A, LE                    => 0a 00
    // Object:
    //   IOA             = 100 = 0x000064, 3 octets LE        => 64 00 00
    //   SIQ             = SPI(on, b1=0x01) | NT(b7=0x40) | IV(b8=0x80) => c1
    // => 010103050a00640000c1
    Asdu asdu =
        new Asdu(
            AsduType.M_SP_NA_1,
            false,
            Cause.SPONTANEOUS,
            false,
            false,
            OriginatorAddress.of(5),
            CA,
            List.of(
                new SinglePointInformation(
                    InformationObjectAddress.of(100),
                    true,
                    new Qds(false, false, false, true, true))));

    assertEquals("010103050a00640000c1", encode(asdu));
  }

  @Test
  void interrogationCommandGoldenBytes() {
    // C_IC_NA_1 (100), SQ = 0, one object, cause ACTIVATION (6), originator 0, common address 10.
    // DUI:
    //   type            = 100 = 0x64                         => 64
    //   VSQ             = SQ(0) | count(1)                   => 01
    //   COT             = cause ACTIVATION(6)                => 06
    //   originator      = 0                                  => 00
    //   common address  = 10 = 0x000A, LE                    => 0a 00
    // Object:
    //   IOA             = 0, 3 octets LE                     => 00 00 00
    //   QOI             = STATION = 20 = 0x14                => 14
    // => 640106000a0000000014
    Asdu asdu =
        new Asdu(
            AsduType.C_IC_NA_1,
            false,
            Cause.ACTIVATION,
            false,
            false,
            OriginatorAddress.none(),
            CA,
            List.of(
                new InterrogationCommand(
                    InformationObjectAddress.of(0), QualifierOfInterrogation.STATION)));

    assertEquals("640106000a0000000014", encode(asdu));
  }

  // ---------------------------------------------------------------------------------------------
  // Round-trip coverage spanning every family.
  // ---------------------------------------------------------------------------------------------

  @Test
  void singlePointRoundTrips() {
    Asdu asdu =
        new Asdu(
            AsduType.M_SP_NA_1,
            false,
            Cause.SPONTANEOUS,
            false,
            false,
            OriginatorAddress.of(5),
            CA,
            List.of(
                new SinglePointInformation(
                    InformationObjectAddress.of(100),
                    true,
                    new Qds(false, false, false, true, true))));

    assertEquals(asdu, roundTrip(asdu));
  }

  @Test
  void doublePointMultipleObjectsRoundTrips() {
    // M_DP_NA_1 (3) with SQ = 0 and several individually-addressed objects.
    Asdu asdu =
        new Asdu(
            AsduType.M_DP_NA_1,
            false,
            Cause.INTERROGATED_BY_STATION,
            false,
            false,
            OriginatorAddress.of(1),
            CA,
            List.of(
                new DoublePointInformation(
                    InformationObjectAddress.of(200),
                    DoublePointState.ON,
                    new Qds(false, false, false, false, false)),
                new DoublePointInformation(
                    InformationObjectAddress.of(350),
                    DoublePointState.OFF,
                    new Qds(false, true, false, false, true)),
                new DoublePointInformation(
                    InformationObjectAddress.of(999),
                    DoublePointState.INDETERMINATE,
                    new Qds(false, false, true, true, false))));

    Asdu decoded = roundTrip(asdu);
    assertEquals(asdu, decoded);
    assertEquals(3, decoded.objects().size());
  }

  @Test
  void measuredValueScaledSequenceRoundTrips() {
    // M_ME_NB_1 (11) with SQ = 1: a single leading IOA followed by three element groups whose
    // decoded addresses are ioa, ioa + 1, ioa + 2.
    InformationObjectAddress ioa = InformationObjectAddress.of(500);
    Asdu asdu =
        new Asdu(
            AsduType.M_ME_NB_1,
            true,
            Cause.PERIODIC,
            false,
            false,
            OriginatorAddress.of(2),
            CA,
            List.of(
                new MeasuredValueScaled(
                    ioa, (short) 0x1111, new Qds(false, false, false, false, false)),
                new MeasuredValueScaled(
                    InformationObjectAddress.of(501),
                    (short) -2,
                    new Qds(true, false, false, false, false)),
                new MeasuredValueScaled(
                    InformationObjectAddress.of(502),
                    (short) 0x7FFF,
                    new Qds(false, false, false, false, true))));

    Asdu decoded = roundTrip(asdu);
    assertEquals(asdu, decoded);

    // SQ = 1 addressing: the leading IOA plus consecutive ascending offsets.
    assertEquals(3, decoded.objects().size());
    assertEquals(InformationObjectAddress.of(500), decoded.objects().get(0).address());
    assertEquals(InformationObjectAddress.of(501), decoded.objects().get(1).address());
    assertEquals(InformationObjectAddress.of(502), decoded.objects().get(2).address());
  }

  @Test
  void measuredValueShortFloatWithCp56TimeRoundTrips() {
    // M_ME_TF_1 (36): R32 + QDS + CP56Time2a, SQ = 0.
    Cp56Time2a time = new Cp56Time2a(12345, 30, 14, 20, 5, 6, 26, false, false, true);
    Asdu asdu =
        new Asdu(
            AsduType.M_ME_TF_1,
            false,
            Cause.SPONTANEOUS,
            false,
            false,
            OriginatorAddress.of(7),
            CA,
            List.of(
                new MeasuredValueShortFloatWithCp56Time(
                    InformationObjectAddress.of(1000),
                    3.14159f,
                    new Qds(false, false, false, false, false),
                    time)));

    assertEquals(asdu, roundTrip(asdu));
  }

  @Test
  void integratedTotalsRoundTrips() {
    // M_IT_NA_1 (15): BCR.
    Asdu asdu =
        new Asdu(
            AsduType.M_IT_NA_1,
            false,
            Cause.REQUESTED_BY_GENERAL_COUNTER,
            false,
            false,
            OriginatorAddress.of(3),
            CA,
            List.of(
                new IntegratedTotals(
                    InformationObjectAddress.of(700),
                    new BinaryCounterReading(123456789, 7, true, false, false))));

    assertEquals(asdu, roundTrip(asdu));
  }

  @Test
  void singleCommandRoundTrips() {
    // C_SC_NA_1 (45): SCO.
    Asdu asdu =
        new Asdu(
            AsduType.C_SC_NA_1,
            false,
            Cause.ACTIVATION,
            false,
            false,
            OriginatorAddress.of(4),
            CA,
            List.of(
                new SingleCommand(
                    InformationObjectAddress.of(2000), true, new QualifierOfCommand(1, true))));

    assertEquals(asdu, roundTrip(asdu));
  }

  @Test
  void setpointShortFloatRoundTrips() {
    // C_SE_NC_1 (50): R32 + QOS.
    Asdu asdu =
        new Asdu(
            AsduType.C_SE_NC_1,
            false,
            Cause.ACTIVATION,
            false,
            false,
            OriginatorAddress.of(8),
            CA,
            List.of(
                new SetpointShortFloat(
                    InformationObjectAddress.of(3000),
                    -123.5f,
                    new QualifierOfSetpoint(0, false))));

    assertEquals(asdu, roundTrip(asdu));
  }

  @Test
  void interrogationCommandRoundTrips() {
    Asdu asdu =
        new Asdu(
            AsduType.C_IC_NA_1,
            false,
            Cause.ACTIVATION,
            false,
            false,
            OriginatorAddress.none(),
            CA,
            List.of(
                new InterrogationCommand(
                    InformationObjectAddress.of(0), QualifierOfInterrogation.STATION)));

    assertEquals(asdu, roundTrip(asdu));
  }

  @Test
  void sequenceAddressingOverflowThrowsAsduDecodeException() {
    // SQ = 1 I-frame body for M_SP_NA_1 (typeId 1) whose leading IOA is 0x00FF_FFFF (the maximum
    // representable address). With count = 2 the synthetic address for the second element group
    // would be 0x0100_0000, past the 3-octet ceiling; decode must reject it as a decode failure
    // rather than letting InformationObjectAddress.of throw IllegalArgumentException.
    ByteBuf buffer = Unpooled.buffer();
    try {
      buffer.writeByte(AsduType.M_SP_NA_1.typeId()); // type = 1
      buffer.writeByte(0x80 | 2); // VSQ: SQ = 1, count = 2
      buffer.writeByte(Cause.SPONTANEOUS.value()); // COT
      buffer.writeByte(0); // originator address
      buffer.writeByte(0x0A); // common address LE octet 1
      buffer.writeByte(0x00); // common address LE octet 2
      buffer.writeByte(0xFF); // leading IOA LE octet 1
      buffer.writeByte(0xFF); // leading IOA LE octet 2
      buffer.writeByte(0xFF); // leading IOA LE octet 3 -> 0x00FF_FFFF
      buffer.writeByte(0x01); // SIQ for element group 0
      buffer.writeByte(0x01); // SIQ for element group 1

      assertThrows(AsduDecodeException.class, () -> Asdu.Serde.decode(PROFILE, buffer));
    } finally {
      buffer.release();
    }
  }

  @Test
  void clockSynchronizationCommandRoundTrips() {
    // C_CS_NA_1 (103): CP56Time2a.
    Cp56Time2a time = new Cp56Time2a(59999, 59, 23, 31, 7, 12, 99, false, true, false);
    Asdu asdu =
        new Asdu(
            AsduType.C_CS_NA_1,
            false,
            Cause.ACTIVATION,
            false,
            false,
            OriginatorAddress.none(),
            CA,
            List.of(new ClockSynchronizationCommand(InformationObjectAddress.of(0), time)));

    assertEquals(asdu, roundTrip(asdu));
  }
}
