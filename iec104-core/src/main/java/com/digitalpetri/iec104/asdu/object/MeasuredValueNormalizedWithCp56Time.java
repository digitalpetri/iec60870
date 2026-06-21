package com.digitalpetri.iec104.asdu.object;

import com.digitalpetri.iec104.address.InformationObjectAddress;
import com.digitalpetri.iec104.asdu.InformationObject;
import com.digitalpetri.iec104.asdu.element.NormalizedValue;
import com.digitalpetri.iec104.asdu.element.Qds;
import com.digitalpetri.iec104.asdu.time.Cp56Time2a;
import io.netty.buffer.ByteBuf;

/**
 * M_ME_TD_1 (34) — measured value, normalized value, with a CP56Time2a time tag.
 *
 * <p>Carries a normalized (fractional) measured value together with a quality descriptor and a
 * seven-octet absolute time tag. Reported spontaneously or in response to a request; because each
 * value carries its own time tag, this type is only transmitted as a sequence of information
 * objects (SQ = 0).
 *
 * @param address the information object address.
 * @param value the normalized measured value (NVA).
 * @param quality the quality descriptor (QDS).
 * @param time the CP56Time2a time tag.
 */
public record MeasuredValueNormalizedWithCp56Time(
    InformationObjectAddress address, NormalizedValue value, Qds quality, Cp56Time2a time)
    implements InformationObject {

  /**
   * Serde for the {@link MeasuredValueNormalizedWithCp56Time} information elements (after the IOA).
   */
  public static final class Serde {

    private Serde() {}

    /**
     * Encodes the NVA, QDS, and CP56Time2a elements into {@code buffer}.
     *
     * <p>Wire layout (Mode 1, least significant octet first): octets 1..2 = NVA (signed 16-bit
     * normalized value, little-endian), octet 3 = QDS, octets 4..10 = CP56Time2a. The information
     * object address is not written here. Does not release the buffer.
     *
     * @param object the information object to encode.
     * @param buffer the caller-owned buffer to write into.
     */
    public static void encode(MeasuredValueNormalizedWithCp56Time object, ByteBuf buffer) {
      NormalizedValue.Serde.encode(object.value(), buffer);
      Qds.Serde.encode(object.quality(), buffer);
      Cp56Time2a.Serde.encode(object.time(), buffer);
    }

    /**
     * Decodes the NVA, QDS, and CP56Time2a elements (information object address already read) from
     * {@code buffer}.
     *
     * <p>Does not release the buffer.
     *
     * @param address the information object address already read by the caller.
     * @param buffer the caller-owned buffer to read from.
     * @return the decoded information object.
     */
    public static MeasuredValueNormalizedWithCp56Time decode(
        InformationObjectAddress address, ByteBuf buffer) {
      NormalizedValue value = NormalizedValue.Serde.decode(buffer);
      Qds quality = Qds.Serde.decode(buffer);
      Cp56Time2a time = Cp56Time2a.Serde.decode(buffer);
      return new MeasuredValueNormalizedWithCp56Time(address, value, quality, time);
    }
  }
}
