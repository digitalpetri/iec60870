package com.digitalpetri.iec104.asdu.object;

import com.digitalpetri.iec104.address.InformationObjectAddress;
import com.digitalpetri.iec104.asdu.InformationObject;
import com.digitalpetri.iec104.asdu.element.Qds;
import io.netty.buffer.ByteBuf;
import java.util.Objects;

/**
 * M_ME_NB_1 (11) — measured value, scaled value.
 *
 * <p>Reports a measured value as a signed 16-bit scaled value (SVA) accompanied by a quality
 * descriptor.
 *
 * @param address the information object address.
 * @param value the scaled measured value (SVA, signed 16-bit).
 * @param quality the quality descriptor (QDS).
 */
public record MeasuredValueScaled(InformationObjectAddress address, short value, Qds quality)
    implements InformationObject {

  /**
   * Validates that the required components are present.
   *
   * @param address the information object address.
   * @param value the scaled measured value (SVA, signed 16-bit).
   * @param quality the quality descriptor (QDS).
   * @throws NullPointerException if any component is null.
   */
  public MeasuredValueScaled {
    Objects.requireNonNull(address, "address");
    Objects.requireNonNull(quality, "quality");
  }

  /** Serde for the {@link MeasuredValueScaled} information elements (after the IOA). */
  public static final class Serde {

    private Serde() {}

    /**
     * Encodes the elements into {@code buffer}; does not write the IOA or release the buffer.
     *
     * <p>Wire layout (little-endian): octets 1..2 are the SVA scaled value (signed 16-bit, least
     * significant octet first), octet 3 is the QDS (OV b1, BL b5, SB b6, NT b7, IV b8).
     *
     * @param o the measured value to encode.
     * @param buffer the caller-owned buffer to write into.
     */
    public static void encode(MeasuredValueScaled o, ByteBuf buffer) {
      buffer.writeShortLE(o.value());
      Qds.Serde.encode(o.quality(), buffer);
    }

    /**
     * Decodes the elements (IOA already read) from {@code buffer}.
     *
     * <p>Reads the two-octet SVA scaled value followed by the QDS octet. Does not release the
     * buffer.
     *
     * @param address the information object address already read by the caller.
     * @param buffer the caller-owned buffer to read from.
     * @return the decoded measured value.
     */
    public static MeasuredValueScaled decode(InformationObjectAddress address, ByteBuf buffer) {
      short value = buffer.readShortLE();
      Qds quality = Qds.Serde.decode(buffer);
      return new MeasuredValueScaled(address, value, quality);
    }
  }
}
