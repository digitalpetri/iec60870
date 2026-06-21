package com.digitalpetri.iec104.asdu.object;

import com.digitalpetri.iec104.address.InformationObjectAddress;
import com.digitalpetri.iec104.asdu.InformationObject;
import com.digitalpetri.iec104.asdu.element.Qds;
import com.digitalpetri.iec104.asdu.time.Cp56Time2a;
import io.netty.buffer.ByteBuf;

/**
 * M_SP_TB_1 (30) — single-point information with a CP56Time2a time tag (IEC 60870-5-101 §7.3.1.22).
 *
 * <p>Each object carries a single-point status together with a quality descriptor (SIQ) and an
 * individual seven-octet time tag. Because every status carries its own time tag, this type never
 * appears as a sequence of information elements (SQ = 1).
 *
 * <p>The {@code on} status and the quality flags share a single SIQ octet; the {@link
 * Qds#overflow() overflow} flag is not part of SIQ and is therefore always {@code false} on a
 * decoded {@code quality}.
 *
 * @param address the information object address.
 * @param on the single-point status (SPI): {@code true} = ON, {@code false} = OFF.
 * @param quality the quality descriptor; the overflow flag is not represented by SIQ.
 * @param time the CP56Time2a time tag.
 */
public record SinglePointWithCp56Time(
    InformationObjectAddress address, boolean on, Qds quality, Cp56Time2a time)
    implements InformationObject {

  /** SPI — single-point status bit (bit 1). */
  private static final int SPI_MASK = 0x01;

  /** BL — blocked bit (bit 5). */
  private static final int BL_MASK = 0x10;

  /** SB — substituted bit (bit 6). */
  private static final int SB_MASK = 0x20;

  /** NT — not-topical bit (bit 7). */
  private static final int NT_MASK = 0x40;

  /** IV — invalid bit (bit 8). */
  private static final int IV_MASK = 0x80;

  /**
   * Serde for the {@link SinglePointWithCp56Time} information elements (after the IOA).
   *
   * <p>Wire layout (Mode 1, least significant octet first), eight octets total:
   *
   * <ul>
   *   <li>octet 1: SIQ — SPI(b1), BL(b5), SB(b6), NT(b7), IV(b8); reserved bits 2..4 are zero;
   *   <li>octets 2-8: CP56Time2a, as encoded by {@link Cp56Time2a.Serde}.
   * </ul>
   */
  public static final class Serde {

    private Serde() {}

    /**
     * Encodes the SIQ octet and CP56Time2a into {@code buffer}; does not write the IOA or release
     * the buffer.
     *
     * @param o the object to encode.
     * @param buffer the caller-owned buffer to write into.
     */
    public static void encode(SinglePointWithCp56Time o, ByteBuf buffer) {
      Qds q = o.quality();
      int octet = 0;
      if (o.on()) {
        octet |= SPI_MASK;
      }
      if (q.blocked()) {
        octet |= BL_MASK;
      }
      if (q.substituted()) {
        octet |= SB_MASK;
      }
      if (q.notTopical()) {
        octet |= NT_MASK;
      }
      if (q.invalid()) {
        octet |= IV_MASK;
      }
      buffer.writeByte(octet);

      Cp56Time2a.Serde.encode(o.time(), buffer);
    }

    /**
     * Decodes the elements (IOA already read) from {@code buffer}.
     *
     * <p>The decoded quality always has its overflow flag clear, since SIQ does not carry it.
     *
     * @param address the information object address already read by the caller.
     * @param buffer the caller-owned buffer positioned at the SIQ octet.
     * @return the decoded object.
     */
    public static SinglePointWithCp56Time decode(InformationObjectAddress address, ByteBuf buffer) {
      int octet = buffer.readUnsignedByte();
      boolean on = (octet & SPI_MASK) != 0;
      Qds quality =
          new Qds(
              false,
              (octet & BL_MASK) != 0,
              (octet & SB_MASK) != 0,
              (octet & NT_MASK) != 0,
              (octet & IV_MASK) != 0);

      Cp56Time2a time = Cp56Time2a.Serde.decode(buffer);

      return new SinglePointWithCp56Time(address, on, quality, time);
    }
  }
}
