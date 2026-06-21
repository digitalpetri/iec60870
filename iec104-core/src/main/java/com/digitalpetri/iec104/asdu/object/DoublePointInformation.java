package com.digitalpetri.iec104.asdu.object;

import com.digitalpetri.iec104.address.InformationObjectAddress;
import com.digitalpetri.iec104.asdu.InformationObject;
import com.digitalpetri.iec104.asdu.element.DoublePointState;
import com.digitalpetri.iec104.asdu.element.Qds;
import io.netty.buffer.ByteBuf;

/**
 * M_DP_NA_1 (3) — double-point information without time tag (IEC 60870-5-101 §7.3.1.3).
 *
 * <p>Carries a single double-point status value together with its quality descriptor, packed into
 * one DIQ octet (§7.2.6.2). The double-point state occupies the two least significant bits; the
 * remaining quality bits (BL, SB, NT, IV) share the same octet.
 *
 * @param address the information object address.
 * @param state the double-point state (DPI).
 * @param quality the quality descriptor; the overflow (OV) bit is not part of DIQ and is ignored.
 */
public record DoublePointInformation(
    InformationObjectAddress address, DoublePointState state, Qds quality)
    implements InformationObject {

  /** DPI — double-point state bits (bits 1..2). */
  private static final int DPI_MASK = 0x03;

  /** BL — blocked bit (bit 5). */
  private static final int BL_MASK = 0x10;

  /** SB — substituted bit (bit 6). */
  private static final int SB_MASK = 0x20;

  /** NT — not-topical bit (bit 7). */
  private static final int NT_MASK = 0x40;

  /** IV — invalid bit (bit 8). */
  private static final int IV_MASK = 0x80;

  /**
   * Validates that the address, state, and quality are present.
   *
   * @param address the information object address.
   * @param state the double-point state (DPI).
   * @param quality the quality descriptor; the overflow (OV) bit is not part of DIQ and is ignored.
   * @throws IllegalArgumentException if {@code address}, {@code state}, or {@code quality} is null.
   */
  public DoublePointInformation {
    if (address == null) {
      throw new IllegalArgumentException("address must not be null");
    }
    if (state == null) {
      throw new IllegalArgumentException("state must not be null");
    }
    if (quality == null) {
      throw new IllegalArgumentException("quality must not be null");
    }
  }

  /** Serde for the {@link DoublePointInformation} information elements (after the IOA). */
  public static final class Serde {

    private Serde() {}

    /**
     * Encodes the DIQ octet into {@code buffer}; does not write the IOA or release the buffer.
     *
     * <p>Wire layout (one octet, bit 1 = least significant bit): DPI(b1..2), BL(b5), SB(b6),
     * NT(b7), IV(b8). Reserved bits 3..4 are written as zero; the QDS overflow bit is not part of
     * DIQ and is not written.
     *
     * @param o the double-point information to encode.
     * @param buffer the caller-owned buffer to write into.
     */
    public static void encode(DoublePointInformation o, ByteBuf buffer) {
      int octet = o.state().value() & DPI_MASK;
      Qds quality = o.quality();
      if (quality.blocked()) {
        octet |= BL_MASK;
      }
      if (quality.substituted()) {
        octet |= SB_MASK;
      }
      if (quality.notTopical()) {
        octet |= NT_MASK;
      }
      if (quality.invalid()) {
        octet |= IV_MASK;
      }
      buffer.writeByte(octet);
    }

    /**
     * Decodes the DIQ octet from {@code buffer} (IOA already read); does not release the buffer.
     *
     * <p>The decoded quality descriptor always reports {@code overflow == false}, since the
     * overflow bit is not carried by DIQ.
     *
     * @param address the information object address read by the caller.
     * @param buffer the caller-owned buffer to read from.
     * @return the decoded double-point information.
     */
    public static DoublePointInformation decode(InformationObjectAddress address, ByteBuf buffer) {
      int octet = buffer.readUnsignedByte();
      DoublePointState state = DoublePointState.fromValue(octet & DPI_MASK);
      Qds quality =
          new Qds(
              false,
              (octet & BL_MASK) != 0,
              (octet & SB_MASK) != 0,
              (octet & NT_MASK) != 0,
              (octet & IV_MASK) != 0);
      return new DoublePointInformation(address, state, quality);
    }
  }
}
