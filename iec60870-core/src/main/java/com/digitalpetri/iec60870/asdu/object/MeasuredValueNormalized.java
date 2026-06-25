package com.digitalpetri.iec60870.asdu.object;

import com.digitalpetri.iec60870.address.InformationObjectAddress;
import com.digitalpetri.iec60870.asdu.InformationObject;
import com.digitalpetri.iec60870.asdu.element.NormalizedValue;
import com.digitalpetri.iec60870.asdu.element.Qds;
import io.netty.buffer.ByteBuf;
import java.util.Objects;

/**
 * M_ME_NA_1 (9) — measured value, normalized value.
 *
 * <p>Reports a measured value as a signed 16-bit normalized fraction (NVA) accompanied by a quality
 * descriptor (QDS).
 *
 * @param address the information object address.
 * @param value the normalized measured value (NVA).
 * @param quality the quality descriptor (QDS).
 */
public record MeasuredValueNormalized(
    InformationObjectAddress address, NormalizedValue value, Qds quality)
    implements InformationObject {

  /**
   * Validates that the required components are present.
   *
   * @param address the information object address.
   * @param value the normalized measured value (NVA).
   * @param quality the quality descriptor (QDS).
   * @throws NullPointerException if any component is null.
   */
  public MeasuredValueNormalized {
    Objects.requireNonNull(address, "address");
    Objects.requireNonNull(value, "value");
    Objects.requireNonNull(quality, "quality");
  }

  /** Serde for the {@link MeasuredValueNormalized} information elements (after the IOA). */
  public static final class Serde {

    private Serde() {}

    /**
     * Encodes the elements into {@code buffer}; does not write the IOA or release the buffer.
     *
     * <p>Wire layout (little-endian): octets 1..2 are the NVA (I16, low octet first), octet 3 is
     * the QDS (OV b1, BL b5, SB b6, NT b7, IV b8).
     *
     * @param o the measured value to encode.
     * @param buffer the caller-owned buffer to write into.
     */
    public static void encode(MeasuredValueNormalized o, ByteBuf buffer) {
      NormalizedValue.Serde.encode(o.value(), buffer);
      Qds.Serde.encode(o.quality(), buffer);
    }

    /**
     * Decodes the elements (IOA already read) from {@code buffer}.
     *
     * <p>Reads the two NVA octets followed by the QDS octet. Does not release the buffer.
     *
     * @param address the information object address already read by the caller.
     * @param buffer the caller-owned buffer to read from.
     * @return the decoded measured value.
     */
    public static MeasuredValueNormalized decode(InformationObjectAddress address, ByteBuf buffer) {
      NormalizedValue value = NormalizedValue.Serde.decode(buffer);
      Qds quality = Qds.Serde.decode(buffer);
      return new MeasuredValueNormalized(address, value, quality);
    }
  }
}
