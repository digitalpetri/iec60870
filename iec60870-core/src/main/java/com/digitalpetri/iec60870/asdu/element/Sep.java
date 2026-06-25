package com.digitalpetri.iec60870.asdu.element;

import io.netty.buffer.ByteBuf;

/**
 * SEP — single event of protection equipment (IEC 60870-5-101 §7.2.6.10).
 *
 * <p>Combines a two-bit event state with the protection-equipment quality bits (EI/BL/SB/NT/IV).
 * Reserved bit 3 is ignored on decode and written as zero on encode.
 *
 * @param state the event state (ES).
 * @param elapsedTimeInvalid whether the elapsed time could not be correctly acquired (EI).
 * @param blocked whether the value is blocked for transmission (BL).
 * @param substituted whether the value was substituted by an operator or automatic source (SB).
 * @param notTopical whether the value was not refreshed within the expected interval (NT).
 * @param invalid whether the event is invalid (IV).
 */
public record Sep(
    EventState state,
    boolean elapsedTimeInvalid,
    boolean blocked,
    boolean substituted,
    boolean notTopical,
    boolean invalid) {

  /** Mask selecting the event-state bits (bits 1..2). */
  private static final int ES_MASK = 0x03;

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

  /** Serde for the {@link Sep} element, encoded as a single octet. */
  public static final class Serde {

    private Serde() {}

    /**
     * Encodes the event state and quality bits as one octet into {@code buffer}.
     *
     * <p>Wire layout (bit 1 = least significant bit): ES(b1..2), EI(b4), BL(b5), SB(b6), NT(b7),
     * IV(b8); reserved bit 3 is written as zero. Does not release the buffer.
     *
     * @param sep the element to encode.
     * @param buffer the caller-owned buffer to write into.
     */
    public static void encode(Sep sep, ByteBuf buffer) {
      int b = sep.state().value() & ES_MASK;
      if (sep.elapsedTimeInvalid()) {
        b |= EI_MASK;
      }
      if (sep.blocked()) {
        b |= BL_MASK;
      }
      if (sep.substituted()) {
        b |= SB_MASK;
      }
      if (sep.notTopical()) {
        b |= NT_MASK;
      }
      if (sep.invalid()) {
        b |= IV_MASK;
      }
      buffer.writeByte(b);
    }

    /**
     * Decodes one octet from {@code buffer} into a {@link Sep}.
     *
     * <p>Reserved bit 3 is ignored. Does not release the buffer.
     *
     * @param buffer the caller-owned buffer to read from.
     * @return the decoded element.
     */
    public static Sep decode(ByteBuf buffer) {
      int b = buffer.readUnsignedByte();
      EventState state = EventState.fromValue(b & ES_MASK);
      return new Sep(
          state,
          (b & EI_MASK) != 0,
          (b & BL_MASK) != 0,
          (b & SB_MASK) != 0,
          (b & NT_MASK) != 0,
          (b & IV_MASK) != 0);
    }
  }
}
