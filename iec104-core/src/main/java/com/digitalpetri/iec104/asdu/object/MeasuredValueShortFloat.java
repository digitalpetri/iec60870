package com.digitalpetri.iec104.asdu.object;

import com.digitalpetri.iec104.address.InformationObjectAddress;
import com.digitalpetri.iec104.asdu.InformationObject;
import com.digitalpetri.iec104.asdu.element.Qds;
import io.netty.buffer.ByteBuf;

/**
 * M_ME_NC_1 (13) — measured value, short floating point number (IEC 60870-5-101 §7.3.1.13).
 *
 * <p>Carries an IEEE STD 754 single-precision (32-bit) short floating point process value (R32)
 * together with its quality descriptor (QDS).
 *
 * @param address the information object address.
 * @param value the measured value as an IEEE STD 754 short floating point number (R32).
 * @param quality the quality descriptor (QDS).
 */
public record MeasuredValueShortFloat(InformationObjectAddress address, float value, Qds quality)
    implements InformationObject {

  /** Serde for the {@link MeasuredValueShortFloat} information elements (after the IOA). */
  public static final class Serde {

    private Serde() {}

    /**
     * Encodes the R32 value and QDS into {@code buffer}; does not write the IOA or release the
     * buffer.
     *
     * <p>Wire layout (least significant octet first): IEEE STD 754 short float (4 octets,
     * little-endian) followed by QDS (1 octet).
     *
     * @param o the information object to encode.
     * @param buffer the caller-owned buffer to write into.
     */
    public static void encode(MeasuredValueShortFloat o, ByteBuf buffer) {
      buffer.writeFloatLE(o.value());
      Qds.Serde.encode(o.quality(), buffer);
    }

    /**
     * Decodes the elements (IOA already read) from {@code buffer}.
     *
     * <p>Reads a 4-octet little-endian IEEE STD 754 short float followed by a 1-octet QDS.
     *
     * @param address the information object address already read by the caller.
     * @param buffer the caller-owned buffer to read from.
     * @return the decoded {@link MeasuredValueShortFloat}.
     */
    public static MeasuredValueShortFloat decode(InformationObjectAddress address, ByteBuf buffer) {
      float value = buffer.readFloatLE();
      Qds quality = Qds.Serde.decode(buffer);
      return new MeasuredValueShortFloat(address, value, quality);
    }
  }
}
