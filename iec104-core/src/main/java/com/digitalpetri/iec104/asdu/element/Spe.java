package com.digitalpetri.iec104.asdu.element;

import io.netty.buffer.ByteBuf;

/**
 * SPE — start events of protection equipment (IEC 60870-5-101 §7.2.6.11).
 *
 * <p>A bit string flagging which start-of-operation events the protection equipment has detected.
 * Reserved bits 7..8 are ignored on decode and written as zero on encode.
 *
 * @param generalStart whether a general start of operation occurred (GS).
 * @param startL1 whether a start of operation occurred on phase L1 (SL1).
 * @param startL2 whether a start of operation occurred on phase L2 (SL2).
 * @param startL3 whether a start of operation occurred on phase L3 (SL3).
 * @param startIE whether a start of operation occurred on earth current (SIE).
 * @param startReverse whether a start of operation occurred in the reverse direction (SRD).
 */
public record Spe(
    boolean generalStart,
    boolean startL1,
    boolean startL2,
    boolean startL3,
    boolean startIE,
    boolean startReverse) {

  /** GS — general-start bit (bit 1). */
  private static final int GS_MASK = 0x01;

  /** SL1 — start-of-operation phase L1 bit (bit 2). */
  private static final int SL1_MASK = 0x02;

  /** SL2 — start-of-operation phase L2 bit (bit 3). */
  private static final int SL2_MASK = 0x04;

  /** SL3 — start-of-operation phase L3 bit (bit 4). */
  private static final int SL3_MASK = 0x08;

  /** SIE — start-of-operation earth-current bit (bit 5). */
  private static final int SIE_MASK = 0x10;

  /** SRD — start-of-operation reverse-direction bit (bit 6). */
  private static final int SRD_MASK = 0x20;

  /** Serde for the {@link Spe} element, encoded as a single octet. */
  public static final class Serde {

    private Serde() {}

    /**
     * Encodes the start-event flags as one octet into {@code buffer}.
     *
     * <p>Wire layout (bit 1 = least significant bit): GS(b1), SL1(b2), SL2(b3), SL3(b4), SIE(b5),
     * SRD(b6); reserved bits 7..8 are written as zero. Does not release the buffer.
     *
     * @param spe the element to encode.
     * @param buffer the caller-owned buffer to write into.
     */
    public static void encode(Spe spe, ByteBuf buffer) {
      int b = 0;
      if (spe.generalStart()) {
        b |= GS_MASK;
      }
      if (spe.startL1()) {
        b |= SL1_MASK;
      }
      if (spe.startL2()) {
        b |= SL2_MASK;
      }
      if (spe.startL3()) {
        b |= SL3_MASK;
      }
      if (spe.startIE()) {
        b |= SIE_MASK;
      }
      if (spe.startReverse()) {
        b |= SRD_MASK;
      }
      buffer.writeByte(b);
    }

    /**
     * Decodes one octet from {@code buffer} into a {@link Spe}.
     *
     * <p>Reserved bits 7..8 are ignored. Does not release the buffer.
     *
     * @param buffer the caller-owned buffer to read from.
     * @return the decoded element.
     */
    public static Spe decode(ByteBuf buffer) {
      int b = buffer.readUnsignedByte();
      return new Spe(
          (b & GS_MASK) != 0,
          (b & SL1_MASK) != 0,
          (b & SL2_MASK) != 0,
          (b & SL3_MASK) != 0,
          (b & SIE_MASK) != 0,
          (b & SRD_MASK) != 0);
    }
  }
}
