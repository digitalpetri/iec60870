package com.digitalpetri.iec60870.asdu.object;

import com.digitalpetri.iec60870.address.InformationObjectAddress;
import com.digitalpetri.iec60870.asdu.InformationObject;
import com.digitalpetri.iec60870.asdu.element.NormalizedValue;
import io.netty.buffer.ByteBuf;
import java.util.Objects;

/**
 * M_ME_ND_1 (21) — measured value, normalized value without quality descriptor.
 *
 * <p>Reports a measured value as a signed 16-bit normalized fraction (NVA). Unlike {@link
 * MeasuredValueNormalized} (M_ME_NA_1), this type carries no quality descriptor (QDS) octet.
 *
 * @param address the information object address.
 * @param value the normalized measured value (NVA).
 */
public record MeasuredValueNormalizedNoQuality(
    InformationObjectAddress address, NormalizedValue value) implements InformationObject {

  /**
   * Validates that the required components are present.
   *
   * @param address the information object address.
   * @param value the normalized measured value (NVA).
   * @throws NullPointerException if any component is null.
   */
  public MeasuredValueNormalizedNoQuality {
    Objects.requireNonNull(address, "address");
    Objects.requireNonNull(value, "value");
  }

  /**
   * Serde for the {@link MeasuredValueNormalizedNoQuality} information elements (after the IOA).
   */
  public static final class Serde {

    private Serde() {}

    /**
     * Encodes the elements into {@code buffer}; does not write the IOA or release the buffer.
     *
     * <p>Wire layout (little-endian): octets 1..2 are the NVA (I16, low octet first). No quality
     * octet follows.
     *
     * @param o the measured value to encode.
     * @param buffer the caller-owned buffer to write into.
     */
    public static void encode(MeasuredValueNormalizedNoQuality o, ByteBuf buffer) {
      NormalizedValue.Serde.encode(o.value(), buffer);
    }

    /**
     * Decodes the elements (IOA already read) from {@code buffer}.
     *
     * <p>Reads the two NVA octets. Does not release the buffer.
     *
     * @param address the information object address already read by the caller.
     * @param buffer the caller-owned buffer to read from.
     * @return the decoded measured value.
     */
    public static MeasuredValueNormalizedNoQuality decode(
        InformationObjectAddress address, ByteBuf buffer) {
      NormalizedValue value = NormalizedValue.Serde.decode(buffer);
      return new MeasuredValueNormalizedNoQuality(address, value);
    }
  }
}
