package com.digitalpetri.iec60870.asdu.object;

import com.digitalpetri.iec60870.address.InformationObjectAddress;
import com.digitalpetri.iec60870.asdu.InformationObject;
import com.digitalpetri.iec60870.asdu.element.Qds;
import com.digitalpetri.iec60870.asdu.time.Cp56Time2a;
import io.netty.buffer.ByteBuf;
import java.util.Objects;

/**
 * M_ME_TF_1 (36) — measured value, short floating point number, with a CP56Time2a time tag (IEC
 * 60870-5-101 §7.3.1.28).
 *
 * <p>Carries an IEEE STD 754 single-precision (32-bit) short floating point process value (R32)
 * together with a quality descriptor and a seven-octet absolute time tag. Each value carries its
 * own time tag, so this type is only transmitted as a sequence of information objects (SQ = 0).
 * Used spontaneously or in response to a read request.
 *
 * @param address the information object address.
 * @param value the measured value as an IEEE STD 754 short floating point number (R32).
 * @param quality the quality descriptor (QDS).
 * @param time the CP56Time2a time tag.
 */
public record MeasuredValueShortFloatWithCp56Time(
    InformationObjectAddress address, float value, Qds quality, Cp56Time2a time)
    implements InformationObject {

  /**
   * Validates the components of a {@code MeasuredValueShortFloatWithCp56Time}.
   *
   * @param address the information object address.
   * @param value the measured value as an IEEE STD 754 short floating point number (R32).
   * @param quality the quality descriptor (QDS).
   * @param time the CP56Time2a time tag.
   * @throws NullPointerException if {@code address}, {@code quality}, or {@code time} is {@code
   *     null}.
   */
  public MeasuredValueShortFloatWithCp56Time {
    Objects.requireNonNull(address, "address");
    Objects.requireNonNull(quality, "quality");
    Objects.requireNonNull(time, "time");
  }

  /**
   * Serde for the {@link MeasuredValueShortFloatWithCp56Time} information elements (after the IOA).
   */
  public static final class Serde {

    private Serde() {}

    /**
     * Encodes the R32 value, QDS, and CP56Time2a elements into {@code buffer}.
     *
     * <p>Wire layout (Mode 1, least significant octet first): octets 1..4 = IEEE STD 754 short
     * float (R32, little-endian), octet 5 = QDS, octets 6..12 = CP56Time2a. The information object
     * address is not written here. Does not release the buffer.
     *
     * @param object the information object to encode.
     * @param buffer the caller-owned buffer to write into.
     */
    public static void encode(MeasuredValueShortFloatWithCp56Time object, ByteBuf buffer) {
      buffer.writeFloatLE(object.value());
      Qds.Serde.encode(object.quality(), buffer);
      Cp56Time2a.Serde.encode(object.time(), buffer);
    }

    /**
     * Decodes the R32 value, QDS, and CP56Time2a elements (information object address already read)
     * from {@code buffer}.
     *
     * <p>Reads a 4-octet little-endian IEEE STD 754 short float, then a 1-octet QDS, then a 7-octet
     * CP56Time2a. Does not release the buffer.
     *
     * @param address the information object address already read by the caller.
     * @param buffer the caller-owned buffer to read from.
     * @return the decoded information object.
     */
    public static MeasuredValueShortFloatWithCp56Time decode(
        InformationObjectAddress address, ByteBuf buffer) {
      float value = buffer.readFloatLE();
      Qds quality = Qds.Serde.decode(buffer);
      Cp56Time2a time = Cp56Time2a.Serde.decode(buffer);
      return new MeasuredValueShortFloatWithCp56Time(address, value, quality, time);
    }
  }
}
