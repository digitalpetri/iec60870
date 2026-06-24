package com.digitalpetri.iec60870.asdu.object;

import com.digitalpetri.iec60870.address.InformationObjectAddress;
import com.digitalpetri.iec60870.asdu.InformationObject;
import com.digitalpetri.iec60870.asdu.element.Qds;
import com.digitalpetri.iec60870.asdu.time.Cp24Time2a;
import io.netty.buffer.ByteBuf;
import java.util.Objects;

/**
 * M_ME_TB_1 (12) — measured value, scaled value with a CP24Time2a time tag.
 *
 * <p>Reports a scaled measured value (SVA, a signed 16-bit integer) together with its quality
 * descriptor and a three-octet time tag. Because each measured value carries its own time tag, this
 * type never appears as a sequence of information elements (SQ = 0).
 *
 * @param address the information object address.
 * @param value the scaled measured value (SVA, signed 16-bit integer).
 * @param quality the quality descriptor (QDS).
 * @param time the CP24Time2a time tag of the event.
 */
public record MeasuredValueScaledWithCp24Time(
    InformationObjectAddress address, short value, Qds quality, Cp24Time2a time)
    implements InformationObject {

  /**
   * Validates that all required reference components are present.
   *
   * @param address the information object address.
   * @param value the scaled measured value (SVA, signed 16-bit integer).
   * @param quality the quality descriptor (QDS).
   * @param time the CP24Time2a time tag of the event.
   * @throws NullPointerException if {@code address}, {@code quality}, or {@code time} is {@code
   *     null}.
   */
  public MeasuredValueScaledWithCp24Time {
    Objects.requireNonNull(address, "address");
    Objects.requireNonNull(quality, "quality");
    Objects.requireNonNull(time, "time");
  }

  /** Serde for the {@link MeasuredValueScaledWithCp24Time} information elements (after the IOA). */
  public static final class Serde {

    private Serde() {}

    /**
     * Encodes the SVA, QDS, and CP24Time2a elements into {@code buffer}.
     *
     * <p>Wire layout (Mode 1, least significant octet first), written after the IOA:
     *
     * <ul>
     *   <li>octets 1-2: SVA — scaled value as a signed 16-bit integer, little-endian;
     *   <li>octet 3: QDS — OV(b1), BL(b5), SB(b6), NT(b7), IV(b8);
     *   <li>octets 4-6: CP24Time2a — milliseconds (octets 1-2, LE) and minute/RES1/IV (octet 3).
     * </ul>
     *
     * <p>Does not write the IOA and does not release the buffer.
     *
     * @param o the object whose elements are encoded.
     * @param buffer the caller-owned buffer to write into.
     */
    public static void encode(MeasuredValueScaledWithCp24Time o, ByteBuf buffer) {
      buffer.writeShortLE(o.value());
      Qds.Serde.encode(o.quality(), buffer);
      Cp24Time2a.Serde.encode(o.time(), buffer);
    }

    /**
     * Decodes the SVA, QDS, and CP24Time2a elements (IOA already read) from {@code buffer}.
     *
     * <p>Does not release the buffer.
     *
     * @param address the information object address already read by the caller.
     * @param buffer the caller-owned buffer to read from.
     * @return the decoded scaled measured value with time tag.
     */
    public static MeasuredValueScaledWithCp24Time decode(
        InformationObjectAddress address, ByteBuf buffer) {
      short value = buffer.readShortLE();
      Qds quality = Qds.Serde.decode(buffer);
      Cp24Time2a time = Cp24Time2a.Serde.decode(buffer);
      return new MeasuredValueScaledWithCp24Time(address, value, quality, time);
    }
  }
}
