package com.digitalpetri.iec104.asdu.object;

import com.digitalpetri.iec104.address.InformationObjectAddress;
import com.digitalpetri.iec104.asdu.InformationObject;
import com.digitalpetri.iec104.asdu.element.DoublePointState;
import com.digitalpetri.iec104.asdu.element.Qds;
import com.digitalpetri.iec104.asdu.time.Cp56Time2a;
import io.netty.buffer.ByteBuf;

/**
 * M_DP_TB_1 (31) — double-point information with a CP56Time2a time tag.
 *
 * <p>Reports the state of a double-point object together with its quality descriptor and a full
 * seven-octet binary time tag. Because each object carries an individual time tag, this type never
 * appears as a sequence of information elements (SQ = 1).
 *
 * @param address the information object address.
 * @param state the double-point state (DPI).
 * @param quality the quality descriptor; the overflow (OV) bit is not carried by DIQ and is always
 *     {@code false} on decode.
 * @param time the CP56Time2a time tag.
 */
public record DoublePointWithCp56Time(
    InformationObjectAddress address, DoublePointState state, Qds quality, Cp56Time2a time)
    implements InformationObject {

  /** Serde for the {@link DoublePointWithCp56Time} information elements (after the IOA). */
  public static final class Serde {

    private Serde() {}

    /**
     * Encodes the DIQ octet followed by the CP56Time2a time tag into {@code buffer}.
     *
     * <p>Wire layout (Mode 1, least significant octet first):
     *
     * <ul>
     *   <li>octet 1: DIQ — DPI(b1..2) = {@code state}, reserved bits 3..4 = 0, BL(b5), SB(b6),
     *       NT(b7), IV(b8); the OV bit defined by QDS is not present in DIQ and is not written.
     *   <li>octets 2..8: CP56Time2a, seven octets little-endian.
     * </ul>
     *
     * <p>Does not write the IOA and does not release the buffer.
     *
     * @param object the information object to encode.
     * @param buffer the caller-owned buffer to write into.
     */
    public static void encode(DoublePointWithCp56Time object, ByteBuf buffer) {
      Qds quality = object.quality();
      int octet = object.state().value() & 0x03;
      if (quality.blocked()) {
        octet |= 0x10;
      }
      if (quality.substituted()) {
        octet |= 0x20;
      }
      if (quality.notTopical()) {
        octet |= 0x40;
      }
      if (quality.invalid()) {
        octet |= 0x80;
      }
      buffer.writeByte(octet);

      Cp56Time2a.Serde.encode(object.time(), buffer);
    }

    /**
     * Decodes the DIQ octet and the CP56Time2a time tag from {@code buffer}, with the IOA already
     * read by the caller.
     *
     * <p>Reserved DIQ bits 3..4 are ignored. The decoded quality always reports {@code overflow ==
     * false} because the OV bit is not part of DIQ. Does not release the buffer.
     *
     * @param address the information object address read by the caller.
     * @param buffer the caller-owned buffer to read from.
     * @return the decoded information object.
     */
    public static DoublePointWithCp56Time decode(InformationObjectAddress address, ByteBuf buffer) {
      int octet = buffer.readUnsignedByte();
      DoublePointState state = DoublePointState.fromValue(octet & 0x03);
      Qds quality =
          new Qds(
              false,
              (octet & 0x10) != 0,
              (octet & 0x20) != 0,
              (octet & 0x40) != 0,
              (octet & 0x80) != 0);

      Cp56Time2a time = Cp56Time2a.Serde.decode(buffer);

      return new DoublePointWithCp56Time(address, state, quality, time);
    }
  }
}
