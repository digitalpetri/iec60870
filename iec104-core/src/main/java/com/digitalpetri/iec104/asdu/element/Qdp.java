package com.digitalpetri.iec104.asdu.element;

import io.netty.buffer.ByteBuf;

/**
 * QDP — quality descriptor for events of protection equipment (IEC 60870-5-101 §7.2.6.4).
 *
 * <p>Carries an elapsed-time-invalid bit in addition to the standard BL/SB/NT/IV quality bits.
 * Reserved bits are ignored on decode and written as zero on encode.
 *
 * @param elapsedTimeInvalid whether the elapsed time could not be correctly acquired (EI).
 * @param blocked whether the value is blocked for transmission (BL).
 * @param substituted whether the value was substituted by an operator or automatic source (SB).
 * @param notTopical whether the value was not refreshed within the expected interval (NT).
 * @param invalid whether the event could not be correctly acquired (IV).
 */
public record Qdp(
    boolean elapsedTimeInvalid,
    boolean blocked,
    boolean substituted,
    boolean notTopical,
    boolean invalid) {

  /** EI — elapsed-time-invalid bit (bit 4). */
  private static final int EI_MASK = 0x08;

  /** BL — blocked bit (bit 5). */
  private static final int BL_MASK = 0x10;

  /** SB — substituted bit (bit 6). */
  private static final int SB_MASK = 0x20;

  /** NT — not-topical bit (bit 7). */
  private static final int NT_MASK = 0x40;

  /** IV — invalid bit (bit 8). */
  private static final int IV_MASK = 0x80;

  /** Serde for the {@link Qdp} quality descriptor, encoded as a single octet. */
  public static final class Serde {

    private Serde() {}

    /**
     * Encodes the quality descriptor as one octet into {@code buffer}.
     *
     * <p>Wire layout (bit 1 = least significant bit): EI(b4), BL(b5), SB(b6), NT(b7), IV(b8);
     * reserved bits 1..3 are written as zero. Does not release the buffer.
     *
     * @param qdp the quality descriptor to encode.
     * @param buffer the caller-owned buffer to write into.
     */
    public static void encode(Qdp qdp, ByteBuf buffer) {
      int b = 0;
      if (qdp.elapsedTimeInvalid()) {
        b |= EI_MASK;
      }
      if (qdp.blocked()) {
        b |= BL_MASK;
      }
      if (qdp.substituted()) {
        b |= SB_MASK;
      }
      if (qdp.notTopical()) {
        b |= NT_MASK;
      }
      if (qdp.invalid()) {
        b |= IV_MASK;
      }
      buffer.writeByte(b);
    }

    /**
     * Decodes one octet from {@code buffer} into a {@link Qdp}.
     *
     * <p>Reserved bits 1..3 are ignored. Does not release the buffer.
     *
     * @param buffer the caller-owned buffer to read from.
     * @return the decoded quality descriptor.
     */
    public static Qdp decode(ByteBuf buffer) {
      int b = buffer.readUnsignedByte();
      return new Qdp(
          (b & EI_MASK) != 0,
          (b & BL_MASK) != 0,
          (b & SB_MASK) != 0,
          (b & NT_MASK) != 0,
          (b & IV_MASK) != 0);
    }
  }
}
