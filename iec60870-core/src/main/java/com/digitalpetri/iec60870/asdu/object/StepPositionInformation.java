package com.digitalpetri.iec60870.asdu.object;

import com.digitalpetri.iec60870.address.InformationObjectAddress;
import com.digitalpetri.iec60870.asdu.InformationObject;
import com.digitalpetri.iec60870.asdu.element.Qds;
import com.digitalpetri.iec60870.asdu.element.Vti;
import io.netty.buffer.ByteBuf;
import java.util.Objects;

/**
 * M_ST_NA_1 (5) — step position information.
 *
 * <p>Reports a transformer tap position (or other step position) as a signed 7-bit value with a
 * transient-state indication, accompanied by a quality descriptor.
 *
 * @param address the information object address.
 * @param value the step position value with transient-state indication (VTI).
 * @param quality the quality descriptor (QDS).
 */
public record StepPositionInformation(InformationObjectAddress address, Vti value, Qds quality)
    implements InformationObject {

  /**
   * Validates that the required components are present.
   *
   * @param address the information object address.
   * @param value the step position value with transient-state indication (VTI).
   * @param quality the quality descriptor (QDS).
   * @throws NullPointerException if any component is null.
   */
  public StepPositionInformation {
    Objects.requireNonNull(address, "address");
    Objects.requireNonNull(value, "value");
    Objects.requireNonNull(quality, "quality");
  }

  /** Serde for the {@link StepPositionInformation} information elements (after the IOA). */
  public static final class Serde {

    private Serde() {}

    /**
     * Encodes the elements into {@code buffer}; does not write the IOA or release the buffer.
     *
     * <p>Wire layout (little-endian): octet 1 is the VTI (I7 value in bits 1..7, TR in bit 8),
     * octet 2 is the QDS (OV b1, BL b5, SB b6, NT b7, IV b8).
     *
     * @param o the step position information to encode.
     * @param buffer the caller-owned buffer to write into.
     */
    public static void encode(StepPositionInformation o, ByteBuf buffer) {
      Vti.Serde.encode(o.value(), buffer);
      Qds.Serde.encode(o.quality(), buffer);
    }

    /**
     * Decodes the elements (IOA already read) from {@code buffer}.
     *
     * <p>Reads the VTI octet followed by the QDS octet. Does not release the buffer.
     *
     * @param address the information object address already read by the caller.
     * @param buffer the caller-owned buffer to read from.
     * @return the decoded step position information.
     */
    public static StepPositionInformation decode(InformationObjectAddress address, ByteBuf buffer) {
      Vti value = Vti.Serde.decode(buffer);
      Qds quality = Qds.Serde.decode(buffer);
      return new StepPositionInformation(address, value, quality);
    }
  }
}
