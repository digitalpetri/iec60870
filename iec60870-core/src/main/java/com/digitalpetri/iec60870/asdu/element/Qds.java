package com.digitalpetri.iec60870.asdu.element;

import io.netty.buffer.ByteBuf;

/**
 * QDS — quality descriptor (IEC 60870-5-101 §7.2.6.3).
 *
 * <p>Five independently settable quality bits describing the quality of an information object's
 * value. Reserved bits are ignored on decode and written as zero on encode.
 *
 * @param overflow whether the value is beyond a predefined range (OV).
 * @param blocked whether the value is blocked for transmission (BL).
 * @param substituted whether the value was substituted by an operator or automatic source (SB).
 * @param notTopical whether the value was not refreshed within the expected interval (NT).
 * @param invalid whether the value could not be correctly acquired (IV).
 */
public record Qds(
    boolean overflow, boolean blocked, boolean substituted, boolean notTopical, boolean invalid) {

  /** OV — overflow bit (bit 1). */
  private static final int OV_MASK = 0x01;

  /** BL — blocked bit (bit 5). */
  private static final int BL_MASK = 0x10;

  /** SB — substituted bit (bit 6). */
  private static final int SB_MASK = 0x20;

  /** NT — not-topical bit (bit 7). */
  private static final int NT_MASK = 0x40;

  /** IV — invalid bit (bit 8). */
  private static final int IV_MASK = 0x80;

  /** Serde for the {@link Qds} quality descriptor, encoded as a single octet. */
  public static final class Serde {

    private Serde() {}

    /**
     * Encodes the quality descriptor as one octet into {@code buffer}.
     *
     * <p>Wire layout (bit 1 = least significant bit): OV(b1), BL(b5), SB(b6), NT(b7), IV(b8);
     * reserved bits 2..4 are written as zero. Does not release the buffer.
     *
     * @param qds the quality descriptor to encode.
     * @param buffer the caller-owned buffer to write into.
     */
    public static void encode(Qds qds, ByteBuf buffer) {
      int b = 0;
      if (qds.overflow()) {
        b |= OV_MASK;
      }
      if (qds.blocked()) {
        b |= BL_MASK;
      }
      if (qds.substituted()) {
        b |= SB_MASK;
      }
      if (qds.notTopical()) {
        b |= NT_MASK;
      }
      if (qds.invalid()) {
        b |= IV_MASK;
      }
      buffer.writeByte(b);
    }

    /**
     * Decodes one octet from {@code buffer} into a {@link Qds}.
     *
     * <p>Reserved bits 2..4 are ignored. Does not release the buffer.
     *
     * @param buffer the caller-owned buffer to read from.
     * @return the decoded quality descriptor.
     */
    public static Qds decode(ByteBuf buffer) {
      int b = buffer.readUnsignedByte();
      return new Qds(
          (b & OV_MASK) != 0,
          (b & BL_MASK) != 0,
          (b & SB_MASK) != 0,
          (b & NT_MASK) != 0,
          (b & IV_MASK) != 0);
    }
  }
}
