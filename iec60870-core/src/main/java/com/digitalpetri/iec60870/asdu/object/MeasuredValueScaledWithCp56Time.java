package com.digitalpetri.iec60870.asdu.object;

import com.digitalpetri.iec60870.address.InformationObjectAddress;
import com.digitalpetri.iec60870.asdu.InformationObject;
import com.digitalpetri.iec60870.asdu.element.Qds;
import com.digitalpetri.iec60870.asdu.time.Cp56Time2a;
import io.netty.buffer.ByteBuf;

/**
 * M_ME_TE_1 (35) — measured value, scaled value, with a CP56Time2a time tag.
 *
 * <p>Carries a scaled measured value (SVA) as a signed 16-bit integer together with a quality
 * descriptor and a seven-octet absolute time tag. Each value carries its own time tag, so this type
 * is only transmitted as a sequence of information objects (SQ = 0). Used spontaneously or in
 * response to a read request.
 *
 * @param address the information object address.
 * @param value the scaled measured value (SVA, signed 16-bit integer).
 * @param quality the quality descriptor (QDS).
 * @param time the CP56Time2a time tag.
 */
public record MeasuredValueScaledWithCp56Time(
    InformationObjectAddress address, short value, Qds quality, Cp56Time2a time)
    implements InformationObject {

  /** Serde for the {@link MeasuredValueScaledWithCp56Time} information elements (after the IOA). */
  public static final class Serde {

    private Serde() {}

    /**
     * Encodes the SVA, QDS, and CP56Time2a elements into {@code buffer}.
     *
     * <p>Wire layout (Mode 1, least significant octet first): octets 1..2 = SVA (signed 16-bit
     * value, little-endian), octet 3 = QDS, octets 4..10 = CP56Time2a. The information object
     * address is not written here. Does not release the buffer.
     *
     * @param object the information object to encode.
     * @param buffer the caller-owned buffer to write into.
     */
    public static void encode(MeasuredValueScaledWithCp56Time object, ByteBuf buffer) {
      buffer.writeShortLE(object.value());
      Qds.Serde.encode(object.quality(), buffer);
      Cp56Time2a.Serde.encode(object.time(), buffer);
    }

    /**
     * Decodes the SVA, QDS, and CP56Time2a elements (information object address already read) from
     * {@code buffer}.
     *
     * <p>Does not release the buffer.
     *
     * @param address the information object address already read by the caller.
     * @param buffer the caller-owned buffer to read from.
     * @return the decoded information object.
     */
    public static MeasuredValueScaledWithCp56Time decode(
        InformationObjectAddress address, ByteBuf buffer) {
      short value = buffer.readShortLE();
      Qds quality = Qds.Serde.decode(buffer);
      Cp56Time2a time = Cp56Time2a.Serde.decode(buffer);
      return new MeasuredValueScaledWithCp56Time(address, value, quality, time);
    }
  }
}
