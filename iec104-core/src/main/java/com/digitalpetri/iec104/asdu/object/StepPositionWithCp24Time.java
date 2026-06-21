package com.digitalpetri.iec104.asdu.object;

import com.digitalpetri.iec104.address.InformationObjectAddress;
import com.digitalpetri.iec104.asdu.InformationObject;
import com.digitalpetri.iec104.asdu.element.Qds;
import com.digitalpetri.iec104.asdu.element.Vti;
import com.digitalpetri.iec104.asdu.time.Cp24Time2a;
import io.netty.buffer.ByteBuf;

/**
 * M_ST_TA_1 (6) — step position information with a CP24Time2a time tag.
 *
 * <p>Reports a transformer tap position (or similar step position) together with its quality
 * descriptor and a three-octet time tag. Because each step position carries its own time tag, this
 * type never appears as a sequence of information elements (SQ = 0).
 *
 * @param address the information object address.
 * @param value the step position value with transient-state indication (VTI).
 * @param quality the quality descriptor (QDS).
 * @param time the CP24Time2a time tag of the event.
 */
public record StepPositionWithCp24Time(
    InformationObjectAddress address, Vti value, Qds quality, Cp24Time2a time)
    implements InformationObject {

  /**
   * Validates that all required components are present.
   *
   * @param address the information object address.
   * @param value the step position value with transient-state indication (VTI).
   * @param quality the quality descriptor (QDS).
   * @param time the CP24Time2a time tag of the event.
   * @throws IllegalArgumentException if {@code address}, {@code value}, {@code quality}, or {@code
   *     time} is {@code null}.
   */
  public StepPositionWithCp24Time {
    if (address == null) {
      throw new IllegalArgumentException("address must not be null");
    }
    if (value == null) {
      throw new IllegalArgumentException("value must not be null");
    }
    if (quality == null) {
      throw new IllegalArgumentException("quality must not be null");
    }
    if (time == null) {
      throw new IllegalArgumentException("time must not be null");
    }
  }

  /** Serde for the {@link StepPositionWithCp24Time} information elements (after the IOA). */
  public static final class Serde {

    private Serde() {}

    /**
     * Encodes the VTI, QDS, and CP24Time2a elements into {@code buffer}.
     *
     * <p>Wire layout (Mode 1, least significant octet first), written after the IOA:
     *
     * <ul>
     *   <li>octet 1: VTI — value (bits 1..7) and transient state (bit 8);
     *   <li>octet 2: QDS — OV(b1), BL(b5), SB(b6), NT(b7), IV(b8);
     *   <li>octets 3-5: CP24Time2a — milliseconds (octets 1-2, LE) and minute/RES1/IV (octet 3).
     * </ul>
     *
     * <p>Does not write the IOA and does not release the buffer.
     *
     * @param o the object whose elements are encoded.
     * @param buffer the caller-owned buffer to write into.
     */
    public static void encode(StepPositionWithCp24Time o, ByteBuf buffer) {
      Vti.Serde.encode(o.value(), buffer);
      Qds.Serde.encode(o.quality(), buffer);
      Cp24Time2a.Serde.encode(o.time(), buffer);
    }

    /**
     * Decodes the VTI, QDS, and CP24Time2a elements (IOA already read) from {@code buffer}.
     *
     * <p>Does not release the buffer.
     *
     * @param address the information object address already read by the caller.
     * @param buffer the caller-owned buffer to read from.
     * @return the decoded step position information.
     */
    public static StepPositionWithCp24Time decode(
        InformationObjectAddress address, ByteBuf buffer) {
      Vti value = Vti.Serde.decode(buffer);
      Qds quality = Qds.Serde.decode(buffer);
      Cp24Time2a time = Cp24Time2a.Serde.decode(buffer);
      return new StepPositionWithCp24Time(address, value, quality, time);
    }
  }
}
