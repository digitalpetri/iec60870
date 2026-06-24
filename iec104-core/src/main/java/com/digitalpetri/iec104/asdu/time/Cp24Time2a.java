package com.digitalpetri.iec104.asdu.time;

import com.digitalpetri.iec104.AsduDecodeException;
import io.netty.buffer.ByteBuf;

/**
 * CP24Time2a — three octet binary time, as defined by IEC 60870-5-4 §6.8 and IEC 60870-5-101
 * §7.2.6.19.
 *
 * <p>This element time-tags an information object with the milliseconds and minute-of-hour at which
 * an event occurred; the calendar octets (hours and above) are discarded. It carries an {@code
 * invalid} flag (IV) and a {@code genuine} flag derived from the RES1/GEN bit.
 *
 * <p>The {@code genuine} component indicates whether the time tag was added by the acquiring device
 * (genuine time) or substituted by intermediate equipment or the controlling station (substituted
 * time). It maps to the RES1/GEN bit such that {@code genuine == true} corresponds to a RES1 bit of
 * {@code 0} (GEN&lt;0&gt; = genuine time).
 *
 * @param milliseconds the milliseconds within the minute, in the range {@code 0..59999}.
 * @param minute the minute of the hour, in the range {@code 0..59}.
 * @param invalid {@code true} if the time tag is marked invalid (IV bit set).
 * @param genuine {@code true} if the time tag is genuine (RES1/GEN bit clear), {@code false} if
 *     substituted.
 */
public record Cp24Time2a(int milliseconds, int minute, boolean invalid, boolean genuine) {

  /**
   * Validates the component ranges of a {@code Cp24Time2a}.
   *
   * @param milliseconds the milliseconds within the minute, in the range {@code 0..59999}.
   * @param minute the minute of the hour, in the range {@code 0..59}.
   * @param invalid {@code true} if the time tag is marked invalid (IV bit set).
   * @param genuine {@code true} if the time tag is genuine (RES1/GEN bit clear), {@code false} if
   *     substituted.
   * @throws IllegalArgumentException if {@code milliseconds} is outside {@code 0..59999} or {@code
   *     minute} is outside {@code 0..59}.
   */
  public Cp24Time2a {
    if (milliseconds < 0 || milliseconds > 59999) {
      throw new IllegalArgumentException("milliseconds out of range [0, 59999]: " + milliseconds);
    }
    if (minute < 0 || minute > 59) {
      throw new IllegalArgumentException("minute out of range [0, 59]: " + minute);
    }
  }

  /**
   * Encoder and decoder for the three-octet CP24Time2a wire representation.
   *
   * <p>Wire layout (Mode 1, least significant octet first):
   *
   * <ul>
   *   <li>octets 1-2: milliseconds as an unsigned 16-bit integer, little-endian, range {@code
   *       0..59999};
   *   <li>octet 3: bit 8 = IV (invalid), bit 7 = RES1/GEN ({@code 0} = genuine, {@code 1} =
   *       substituted), bits 6..1 = minutes ({@code 0..59}).
   * </ul>
   */
  public static final class Serde {

    private Serde() {}

    /**
     * Encodes {@code time} into {@code buffer} as three octets.
     *
     * <p>The buffer is owned by the caller; this method writes into it and never releases it.
     *
     * @param time the time element to encode.
     * @param buffer the destination buffer to write the three octets into.
     */
    public static void encode(Cp24Time2a time, ByteBuf buffer) {
      buffer.writeShortLE(time.milliseconds());

      int octet3 = time.minute() & 0x3F;
      if (!time.genuine()) {
        octet3 |= 0x40;
      }
      if (time.invalid()) {
        octet3 |= 0x80;
      }
      buffer.writeByte(octet3);
    }

    /**
     * Decodes a {@code Cp24Time2a} from the next three octets of {@code buffer}.
     *
     * <p>The buffer is owned by the caller; this method reads from it and never releases it.
     *
     * @param buffer the source buffer positioned at the first octet of the time element.
     * @return the decoded time element.
     * @throws AsduDecodeException if the decoded milliseconds value is outside {@code 0..59999} or
     *     the decoded minute value is outside {@code 0..59}.
     */
    public static Cp24Time2a decode(ByteBuf buffer) {
      int milliseconds = buffer.readUnsignedShortLE();
      if (milliseconds > 59999) {
        throw new AsduDecodeException("CP24Time2a milliseconds out of range: " + milliseconds);
      }

      int octet3 = buffer.readUnsignedByte();
      int minute = octet3 & 0x3F;
      boolean genuine = (octet3 & 0x40) == 0;
      boolean invalid = (octet3 & 0x80) != 0;

      try {
        return new Cp24Time2a(milliseconds, minute, invalid, genuine);
      } catch (IllegalArgumentException e) {
        throw new AsduDecodeException("malformed CP24Time2a: " + e.getMessage());
      }
    }
  }
}
