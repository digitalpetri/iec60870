package com.digitalpetri.iec104.asdu.object;

import com.digitalpetri.iec104.address.InformationObjectAddress;
import com.digitalpetri.iec104.asdu.InformationObject;
import com.digitalpetri.iec104.asdu.element.Qds;
import com.digitalpetri.iec104.asdu.time.Cp24Time2a;
import io.netty.buffer.ByteBuf;

/**
 * M_SP_TA_1 (2) — single-point information with a CP24Time2a time tag (IEC 60870-5-101 §7.3.1.2).
 *
 * <p>Carries a single-point state together with its quality descriptor packed into one SIQ octet,
 * followed by the three-octet CP24Time2a time tag marking when the state was acquired. Because each
 * point carries its own time tag, this type is only ever transmitted with the variable structure
 * qualifier sequence bit clear (SQ = 0).
 *
 * @param address the information object address.
 * @param on the single-point state (SPI): {@code true} for the ON state, {@code false} for OFF.
 * @param quality the quality descriptor; the SIQ octet conveys only the BL, SB, NT, and IV bits
 *     (the OV bit is not defined for single-point information and is always encoded as zero).
 * @param time the CP24Time2a time tag.
 */
public record SinglePointWithCp24Time(
    InformationObjectAddress address, boolean on, Qds quality, Cp24Time2a time)
    implements InformationObject {

  /** SPI — single-point information bit (bit 1) of the SIQ octet. */
  private static final int SPI_MASK = 0x01;

  /** BL — blocked bit (bit 5) of the SIQ octet. */
  private static final int BL_MASK = 0x10;

  /** SB — substituted bit (bit 6) of the SIQ octet. */
  private static final int SB_MASK = 0x20;

  /** NT — not-topical bit (bit 7) of the SIQ octet. */
  private static final int NT_MASK = 0x40;

  /** IV — invalid bit (bit 8) of the SIQ octet. */
  private static final int IV_MASK = 0x80;

  /** Serde for the {@link SinglePointWithCp24Time} information elements (after the IOA). */
  public static final class Serde {

    private Serde() {}

    /**
     * Encodes the SIQ octet and CP24Time2a time tag into {@code buffer}; does not write the IOA or
     * release the buffer.
     *
     * <p>Wire layout (Mode 1, least significant octet first):
     *
     * <ul>
     *   <li>octet 1: SIQ — bit 1 = SPI (single-point state), bits 2..4 reserved (zero), bit 5 = BL,
     *       bit 6 = SB, bit 7 = NT, bit 8 = IV;
     *   <li>octets 2-4: CP24Time2a time tag (see {@link Cp24Time2a.Serde}).
     * </ul>
     *
     * @param o the information object to encode.
     * @param buffer the destination buffer to write the elements into.
     */
    public static void encode(SinglePointWithCp24Time o, ByteBuf buffer) {
      int siq = 0;
      if (o.on()) {
        siq |= SPI_MASK;
      }
      if (o.quality().blocked()) {
        siq |= BL_MASK;
      }
      if (o.quality().substituted()) {
        siq |= SB_MASK;
      }
      if (o.quality().notTopical()) {
        siq |= NT_MASK;
      }
      if (o.quality().invalid()) {
        siq |= IV_MASK;
      }
      buffer.writeByte(siq);

      Cp24Time2a.Serde.encode(o.time(), buffer);
    }

    /**
     * Decodes the SIQ octet and CP24Time2a time tag (IOA already read) from {@code buffer}; does
     * not release the buffer.
     *
     * @param address the information object address already read by the caller.
     * @param buffer the source buffer positioned at the SIQ octet.
     * @return the decoded information object.
     */
    public static SinglePointWithCp24Time decode(InformationObjectAddress address, ByteBuf buffer) {
      int siq = buffer.readUnsignedByte();
      boolean on = (siq & SPI_MASK) != 0;
      Qds quality =
          new Qds(
              false,
              (siq & BL_MASK) != 0,
              (siq & SB_MASK) != 0,
              (siq & NT_MASK) != 0,
              (siq & IV_MASK) != 0);

      Cp24Time2a time = Cp24Time2a.Serde.decode(buffer);

      return new SinglePointWithCp24Time(address, on, quality, time);
    }
  }
}
