package com.digitalpetri.iec104.asdu.object;

import com.digitalpetri.iec104.address.InformationObjectAddress;
import com.digitalpetri.iec104.asdu.InformationObject;
import com.digitalpetri.iec104.asdu.element.Qds;
import io.netty.buffer.ByteBuf;

/**
 * M_SP_NA_1 (1) — single-point information without time tag.
 *
 * <p>Carries a single binary state ({@code on}) together with a quality descriptor. The state and
 * quality share a single SIQ octet on the wire (IEC 60870-5-101 §7.2.6.1).
 *
 * @param address the information object address.
 * @param on the single-point state (SPI); {@code true} for ON, {@code false} for OFF.
 * @param quality the quality descriptor; the SIQ octet has no overflow bit, so {@link
 *     Qds#overflow()} is always {@code false} for a decoded value and is not encoded.
 */
public record SinglePointInformation(InformationObjectAddress address, boolean on, Qds quality)
    implements InformationObject {

  /** SPI — single-point information state bit (bit 1). */
  private static final int SPI_MASK = 0x01;

  /** BL — blocked quality bit (bit 5). */
  private static final int BL_MASK = 0x10;

  /** SB — substituted quality bit (bit 6). */
  private static final int SB_MASK = 0x20;

  /** NT — not-topical quality bit (bit 7). */
  private static final int NT_MASK = 0x40;

  /** IV — invalid quality bit (bit 8). */
  private static final int IV_MASK = 0x80;

  /** Serde for the {@link SinglePointInformation} information elements (after the IOA). */
  public static final class Serde {

    private Serde() {}

    /**
     * Encodes the SIQ octet into {@code buffer}; does not write the IOA or release the buffer.
     *
     * <p>Wire layout — one octet (bit 1 = least significant bit): {@code IV NT SB BL 0 0 0 SPI}.
     * The SPI bit carries the state and bits 5..8 carry the blocked, substituted, not-topical, and
     * invalid quality flags. The QDS overflow bit is undefined for SIQ and is written as zero.
     *
     * @param o the single-point information object to encode.
     * @param buffer the caller-owned buffer to write into.
     */
    public static void encode(SinglePointInformation o, ByteBuf buffer) {
      Qds q = o.quality();
      int octet = (o.on() ? SPI_MASK : 0);
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
    }

    /**
     * Decodes the SIQ octet (IOA already read) from {@code buffer}; does not release the buffer.
     *
     * <p>The SPI bit yields {@code on}; bits 5..8 yield the blocked, substituted, not-topical, and
     * invalid quality flags. The QDS overflow bit is not present in SIQ and decodes as {@code
     * false}.
     *
     * @param address the information object address already read by the caller.
     * @param buffer the caller-owned buffer to read from.
     * @return the decoded single-point information object.
     */
    public static SinglePointInformation decode(InformationObjectAddress address, ByteBuf buffer) {
      int octet = buffer.readUnsignedByte();
      boolean on = (octet & SPI_MASK) != 0;
      Qds quality =
          new Qds(
              false,
              (octet & BL_MASK) != 0,
              (octet & SB_MASK) != 0,
              (octet & NT_MASK) != 0,
              (octet & IV_MASK) != 0);
      return new SinglePointInformation(address, on, quality);
    }
  }
}
