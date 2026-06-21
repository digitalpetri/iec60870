package com.digitalpetri.iec104.asdu.object;

import com.digitalpetri.iec104.address.InformationObjectAddress;
import com.digitalpetri.iec104.asdu.InformationObject;
import com.digitalpetri.iec104.asdu.element.Qds;
import com.digitalpetri.iec104.asdu.element.Scd;
import io.netty.buffer.ByteBuf;

/**
 * M_PS_NA_1 (20) — packed single-point information with status change detection (IEC 60870-5-101
 * §7.3.1.20).
 *
 * <p>Carries 16 single-point status bits together with 16 corresponding change-detection bits (SCD)
 * and a shared quality descriptor (QDS). The information object address identifies the least
 * significant status bit; the remaining bits follow at incrementing addresses.
 *
 * <p>Per the specification, the overflow (OV) bit of the QDS is not used by this type and is always
 * zero.
 *
 * @param address the information object address.
 * @param statusChange the 16 status bits and 16 change-detection bits (SCD).
 * @param quality the quality descriptor (QDS) shared by all status bits.
 */
public record PackedSinglePointWithStatusChange(
    InformationObjectAddress address, Scd statusChange, Qds quality) implements InformationObject {

  /**
   * Serde for the {@link PackedSinglePointWithStatusChange} information elements (after the IOA).
   */
  public static final class Serde {

    private Serde() {}

    /**
     * Encodes the SCD and QDS into {@code buffer}; does not write the IOA or release the buffer.
     *
     * <p>Wire layout (least significant octet first): SCD (4 octets, status bits in the low half
     * and change-detection bits in the high half, little-endian) followed by QDS (1 octet).
     *
     * @param o the information object to encode.
     * @param buffer the caller-owned buffer to write into.
     */
    public static void encode(PackedSinglePointWithStatusChange o, ByteBuf buffer) {
      Scd.Serde.encode(o.statusChange(), buffer);
      Qds.Serde.encode(o.quality(), buffer);
    }

    /**
     * Decodes the elements (IOA already read) from {@code buffer}.
     *
     * <p>Reads a 4-octet little-endian SCD followed by a 1-octet QDS.
     *
     * @param address the information object address already read by the caller.
     * @param buffer the caller-owned buffer to read from.
     * @return the decoded {@link PackedSinglePointWithStatusChange}.
     */
    public static PackedSinglePointWithStatusChange decode(
        InformationObjectAddress address, ByteBuf buffer) {
      Scd statusChange = Scd.Serde.decode(buffer);
      Qds quality = Qds.Serde.decode(buffer);
      return new PackedSinglePointWithStatusChange(address, statusChange, quality);
    }
  }
}
