package com.digitalpetri.iec60870.asdu.object;

import com.digitalpetri.iec60870.address.InformationObjectAddress;
import com.digitalpetri.iec60870.asdu.InformationObject;
import com.digitalpetri.iec60870.asdu.element.NormalizedValue;
import com.digitalpetri.iec60870.asdu.element.Qds;
import com.digitalpetri.iec60870.asdu.time.Cp24Time2a;
import io.netty.buffer.ByteBuf;
import java.util.Objects;

/**
 * M_ME_TA_1 (10) — measured value, normalized value, with a CP24Time2a time tag.
 *
 * <p>Reports a normalized measured value together with its quality descriptor and a three-octet
 * time tag. Because each measured value carries its own time tag, this type never appears as a
 * sequence of information elements (SQ = 0).
 *
 * @param address the information object address.
 * @param value the normalized measured value (NVA).
 * @param quality the quality descriptor (QDS).
 * @param time the CP24Time2a time tag of the event.
 */
public record MeasuredValueNormalizedWithCp24Time(
    InformationObjectAddress address, NormalizedValue value, Qds quality, Cp24Time2a time)
    implements InformationObject {

  /**
   * Validates that all required components are present.
   *
   * @param address the information object address.
   * @param value the normalized measured value (NVA).
   * @param quality the quality descriptor (QDS).
   * @param time the CP24Time2a time tag of the event.
   * @throws NullPointerException if {@code address}, {@code value}, {@code quality}, or {@code
   *     time} is {@code null}.
   */
  public MeasuredValueNormalizedWithCp24Time {
    Objects.requireNonNull(address, "address");
    Objects.requireNonNull(value, "value");
    Objects.requireNonNull(quality, "quality");
    Objects.requireNonNull(time, "time");
  }

  /**
   * Serde for the {@link MeasuredValueNormalizedWithCp24Time} information elements (after the IOA).
   */
  public static final class Serde {

    private Serde() {}

    /**
     * Encodes the NVA, QDS, and CP24Time2a elements into {@code buffer}.
     *
     * <p>Wire layout (Mode 1, least significant octet first), written after the IOA:
     *
     * <ul>
     *   <li>octets 1-2: NVA — raw signed 16-bit value, little-endian;
     *   <li>octet 3: QDS — OV(b1), BL(b5), SB(b6), NT(b7), IV(b8);
     *   <li>octets 4-6: CP24Time2a — milliseconds (octets 1-2, LE) and minute/RES1/IV (octet 3).
     * </ul>
     *
     * <p>Does not write the IOA and does not release the buffer.
     *
     * @param o the object whose elements are encoded.
     * @param buffer the caller-owned buffer to write into.
     */
    public static void encode(MeasuredValueNormalizedWithCp24Time o, ByteBuf buffer) {
      NormalizedValue.Serde.encode(o.value(), buffer);
      Qds.Serde.encode(o.quality(), buffer);
      Cp24Time2a.Serde.encode(o.time(), buffer);
    }

    /**
     * Decodes the NVA, QDS, and CP24Time2a elements (IOA already read) from {@code buffer}.
     *
     * <p>Does not release the buffer.
     *
     * @param address the information object address already read by the caller.
     * @param buffer the caller-owned buffer to read from.
     * @return the decoded measured value information.
     */
    public static MeasuredValueNormalizedWithCp24Time decode(
        InformationObjectAddress address, ByteBuf buffer) {
      NormalizedValue value = NormalizedValue.Serde.decode(buffer);
      Qds quality = Qds.Serde.decode(buffer);
      Cp24Time2a time = Cp24Time2a.Serde.decode(buffer);
      return new MeasuredValueNormalizedWithCp24Time(address, value, quality, time);
    }
  }
}
