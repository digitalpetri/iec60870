package com.digitalpetri.iec104.asdu.object;

import com.digitalpetri.iec104.address.InformationObjectAddress;
import com.digitalpetri.iec104.asdu.InformationObject;
import com.digitalpetri.iec104.asdu.element.Qds;
import com.digitalpetri.iec104.asdu.time.Cp24Time2a;
import io.netty.buffer.ByteBuf;
import java.util.Objects;

/**
 * M_ME_TC_1 (14) — measured value, short floating point number, with a CP24Time2a time tag.
 *
 * <p>Reports a measured process value as an IEEE STD 754 single-precision floating point number
 * together with its quality descriptor and a three-octet time tag. Because each measured value
 * carries its own time tag, this type never appears as a sequence of information elements (SQ = 0).
 *
 * @param address the information object address.
 * @param value the measured value as an IEEE STD 754 short floating point number (R32).
 * @param quality the quality descriptor (QDS).
 * @param time the CP24Time2a time tag of the measurement.
 */
public record MeasuredValueShortFloatWithCp24Time(
    InformationObjectAddress address, float value, Qds quality, Cp24Time2a time)
    implements InformationObject {

  /**
   * Validates that all required components are present.
   *
   * @param address the information object address.
   * @param value the measured value as an IEEE STD 754 short floating point number (R32).
   * @param quality the quality descriptor (QDS).
   * @param time the CP24Time2a time tag of the measurement.
   * @throws NullPointerException if {@code address}, {@code quality}, or {@code time} is {@code
   *     null}.
   */
  public MeasuredValueShortFloatWithCp24Time {
    Objects.requireNonNull(address, "address");
    Objects.requireNonNull(quality, "quality");
    Objects.requireNonNull(time, "time");
  }

  /**
   * Serde for the {@link MeasuredValueShortFloatWithCp24Time} information elements (after the IOA).
   */
  public static final class Serde {

    private Serde() {}

    /**
     * Encodes the R32 value, QDS, and CP24Time2a elements into {@code buffer}.
     *
     * <p>Wire layout (Mode 1, least significant octet first), written after the IOA:
     *
     * <ul>
     *   <li>octets 1-4: IEEE STD 754 short floating point number, little-endian;
     *   <li>octet 5: QDS — OV(b1), BL(b5), SB(b6), NT(b7), IV(b8);
     *   <li>octets 6-8: CP24Time2a — milliseconds (octets 1-2, LE) and minute/RES1/IV (octet 3).
     * </ul>
     *
     * <p>Does not write the IOA and does not release the buffer.
     *
     * @param o the object whose elements are encoded.
     * @param buffer the caller-owned buffer to write into.
     */
    public static void encode(MeasuredValueShortFloatWithCp24Time o, ByteBuf buffer) {
      buffer.writeFloatLE(o.value());
      Qds.Serde.encode(o.quality(), buffer);
      Cp24Time2a.Serde.encode(o.time(), buffer);
    }

    /**
     * Decodes the R32 value, QDS, and CP24Time2a elements (IOA already read) from {@code buffer}.
     *
     * <p>Does not release the buffer.
     *
     * @param address the information object address already read by the caller.
     * @param buffer the caller-owned buffer to read from.
     * @return the decoded measured value information.
     */
    public static MeasuredValueShortFloatWithCp24Time decode(
        InformationObjectAddress address, ByteBuf buffer) {
      float value = buffer.readFloatLE();
      Qds quality = Qds.Serde.decode(buffer);
      Cp24Time2a time = Cp24Time2a.Serde.decode(buffer);
      return new MeasuredValueShortFloatWithCp24Time(address, value, quality, time);
    }
  }
}
