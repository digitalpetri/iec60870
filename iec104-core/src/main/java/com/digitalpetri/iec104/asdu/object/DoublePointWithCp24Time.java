package com.digitalpetri.iec104.asdu.object;

import com.digitalpetri.iec104.address.InformationObjectAddress;
import com.digitalpetri.iec104.asdu.InformationObject;
import com.digitalpetri.iec104.asdu.element.DoublePointState;
import com.digitalpetri.iec104.asdu.element.Qds;
import com.digitalpetri.iec104.asdu.time.Cp24Time2a;
import io.netty.buffer.ByteBuf;
import java.util.Objects;

/**
 * M_DP_TA_1 (4) — double-point information with CP24Time2a time tag.
 *
 * <p>Carries a double-point state with its quality descriptor (DIQ) and a three-octet time tag
 * marking when the event occurred. Used with causes of transmission spontaneous (3), requested (5),
 * and return information caused by a remote (11) or local (12) command.
 *
 * <p>The quality is the subset of QDS carried by DIQ: the BL, SB, NT, and IV bits. DIQ has no
 * overflow bit, so {@link Qds#overflow()} is always {@code false} for an object of this type.
 *
 * @param address the information object address.
 * @param state the double-point state (DPI).
 * @param quality the quality descriptor; its overflow flag must be {@code false} because DIQ has no
 *     OV bit.
 * @param time the CP24Time2a time tag.
 */
public record DoublePointWithCp24Time(
    InformationObjectAddress address, DoublePointState state, Qds quality, Cp24Time2a time)
    implements InformationObject {

  /**
   * Validates the components of a {@code DoublePointWithCp24Time}.
   *
   * @param address the information object address.
   * @param state the double-point state (DPI).
   * @param quality the quality descriptor; its overflow flag must be {@code false} because DIQ has
   *     no OV bit.
   * @param time the CP24Time2a time tag.
   * @throws NullPointerException if {@code address}, {@code state}, {@code quality}, or {@code
   *     time} is null.
   * @throws IllegalArgumentException if {@code quality.overflow()} is {@code true}, since DIQ does
   *     not carry an overflow bit.
   */
  public DoublePointWithCp24Time {
    Objects.requireNonNull(address, "address");
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(quality, "quality");
    Objects.requireNonNull(time, "time");
    if (quality.overflow()) {
      throw new IllegalArgumentException(
          "DIQ has no overflow bit; quality.overflow() must be false");
    }
  }

  /**
   * Serde for the {@link DoublePointWithCp24Time} information elements (after the IOA).
   *
   * <p>Wire layout (Mode 1, least significant octet first):
   *
   * <ul>
   *   <li>octet 1: DIQ — bits 2..1 = DPI (double-point state), bits 4..3 = {@code 0}, bit 5 = BL
   *       (blocked), bit 6 = SB (substituted), bit 7 = NT (not topical), bit 8 = IV (invalid);
   *   <li>octets 2..4: CP24Time2a time tag (see {@link Cp24Time2a.Serde}).
   * </ul>
   */
  public static final class Serde {

    private Serde() {}

    /**
     * Encodes the DIQ octet and CP24Time2a time tag into {@code buffer}; does not write the IOA or
     * release the buffer.
     *
     * @param o the information object to encode.
     * @param buffer the destination buffer to write into; not released by this method.
     */
    public static void encode(DoublePointWithCp24Time o, ByteBuf buffer) {
      Qds quality = o.quality();
      int diq = o.state().value() & 0x03;
      if (quality.blocked()) {
        diq |= 0x10;
      }
      if (quality.substituted()) {
        diq |= 0x20;
      }
      if (quality.notTopical()) {
        diq |= 0x40;
      }
      if (quality.invalid()) {
        diq |= 0x80;
      }
      buffer.writeByte(diq);

      Cp24Time2a.Serde.encode(o.time(), buffer);
    }

    /**
     * Decodes the elements (IOA already read) from {@code buffer}.
     *
     * @param address the information object address already decoded by the caller.
     * @param buffer the source buffer positioned at the DIQ octet; not released by this method.
     * @return the decoded information object.
     */
    public static DoublePointWithCp24Time decode(InformationObjectAddress address, ByteBuf buffer) {
      int diq = buffer.readUnsignedByte();
      DoublePointState state = DoublePointState.fromValue(diq & 0x03);
      Qds quality =
          new Qds(
              false, (diq & 0x10) != 0, (diq & 0x20) != 0, (diq & 0x40) != 0, (diq & 0x80) != 0);

      Cp24Time2a time = Cp24Time2a.Serde.decode(buffer);

      return new DoublePointWithCp24Time(address, state, quality, time);
    }
  }
}
