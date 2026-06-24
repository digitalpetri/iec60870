package com.digitalpetri.iec60870.examples;

import com.digitalpetri.iec60870.ProtocolProfile;
import com.digitalpetri.iec60870.address.CommonAddress;
import com.digitalpetri.iec60870.address.InformationObjectAddress;
import com.digitalpetri.iec60870.address.OriginatorAddress;
import com.digitalpetri.iec60870.asdu.Asdu;
import com.digitalpetri.iec60870.asdu.AsduType;
import com.digitalpetri.iec60870.asdu.Cause;
import com.digitalpetri.iec60870.asdu.element.Qds;
import com.digitalpetri.iec60870.asdu.element.QualifierOfInterrogation;
import com.digitalpetri.iec60870.asdu.object.InterrogationCommand;
import com.digitalpetri.iec60870.asdu.object.MeasuredValueScaled;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import java.util.List;

/**
 * Demonstrates the raw protocol layer without the client/server facade: build an {@link Asdu} by
 * hand from {@link AsduType}, {@link Cause}, and {@code asdu.object} records, encode it to bytes
 * with {@link Asdu.Serde}, print the hex, then decode it back and print the round-tripped object.
 *
 * <p>This is the layer the high-level client and server use internally, exposed for conformance
 * work and for private TypeIDs. Two ASDUs are demonstrated: a {@code C_IC_NA_1} station
 * interrogation (control direction) and an {@code M_ME_NB_1} scaled measured value (monitor
 * direction).
 *
 * <p>The example uses the standard IEC 104 profile ({@link ProtocolProfile#iec104Default()}), which
 * selects the field widths used on the wire (two-octet cause of transmission and common address,
 * three-octet information object address).
 */
public final class RawAsduExample {

  private static final ProtocolProfile PROFILE = ProtocolProfile.iec104Default();

  private RawAsduExample() {}

  /**
   * Builds, encodes, prints, and decodes a station interrogation and a scaled measured value.
   *
   * @param args ignored.
   */
  public static void main(String[] args) {
    roundTrip("C_IC_NA_1 station interrogation", buildInterrogation());
    roundTrip("M_ME_NB_1 scaled measured value", buildMeasuredValue());
  }

  /** Builds a {@code C_IC_NA_1} station (global) interrogation for common address 1. */
  private static Asdu buildInterrogation() {
    InterrogationCommand object =
        new InterrogationCommand(InformationObjectAddress.of(0), QualifierOfInterrogation.STATION);

    return new Asdu(
        AsduType.C_IC_NA_1,
        false,
        Cause.ACTIVATION,
        false,
        false,
        OriginatorAddress.none(),
        CommonAddress.of(1),
        List.of(object));
  }

  /** Builds an {@code M_ME_NB_1} scaled measured value at IOA 200 with good quality. */
  private static Asdu buildMeasuredValue() {
    Qds goodQuality = new Qds(false, false, false, false, false);
    MeasuredValueScaled object =
        new MeasuredValueScaled(InformationObjectAddress.of(200), (short) 1234, goodQuality);

    return new Asdu(
        AsduType.M_ME_NB_1,
        false,
        Cause.SPONTANEOUS,
        false,
        false,
        OriginatorAddress.none(),
        CommonAddress.of(1),
        List.of(object));
  }

  /**
   * Encodes the ASDU to a buffer, prints its hex, decodes it back, and prints the result.
   *
   * @param label a human-readable label for the ASDU.
   * @param asdu the ASDU to round-trip.
   */
  private static void roundTrip(String label, Asdu asdu) {
    ByteBuf buffer = Unpooled.buffer();
    try {
      Asdu.Serde.encode(asdu, PROFILE, buffer);
      System.out.println(label);
      System.out.println("  built:   " + asdu);
      System.out.println("  encoded: " + ByteBufUtil.hexDump(buffer));

      Asdu decoded = Asdu.Serde.decode(PROFILE, buffer);
      System.out.println("  decoded: " + decoded);
      System.out.println("  objects: " + decoded.objects());
      System.out.println();
    } finally {
      buffer.release();
    }
  }
}
