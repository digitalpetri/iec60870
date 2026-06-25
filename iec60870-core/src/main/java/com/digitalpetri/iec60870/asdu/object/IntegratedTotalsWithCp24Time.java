package com.digitalpetri.iec60870.asdu.object;

import com.digitalpetri.iec60870.address.InformationObjectAddress;
import com.digitalpetri.iec60870.asdu.InformationObject;
import com.digitalpetri.iec60870.asdu.element.BinaryCounterReading;
import com.digitalpetri.iec60870.asdu.time.Cp24Time2a;
import io.netty.buffer.ByteBuf;
import java.util.Objects;

/**
 * M_IT_TA_1 (16) — integrated totals with a CP24Time2a time tag (IEC 60870-5-101 §7.3.1.16).
 *
 * <p>Carries a binary counter reading (BCR) together with a three-octet time tag recording when the
 * counter was acquired. Because each integrated total carries its own time tag, this type never
 * appears as a sequence of information elements (SQ = 0).
 *
 * @param address the information object address.
 * @param counter the binary counter reading (BCR): signed 32-bit value, sequence number, and the
 *     carry, adjusted, and invalid status bits.
 * @param time the CP24Time2a time tag at which the counter was read.
 */
public record IntegratedTotalsWithCp24Time(
    InformationObjectAddress address, BinaryCounterReading counter, Cp24Time2a time)
    implements InformationObject {

  /**
   * Validates that all required components are present.
   *
   * @param address the information object address.
   * @param counter the binary counter reading (BCR): signed 32-bit value, sequence number, and the
   *     carry, adjusted, and invalid status bits.
   * @param time the CP24Time2a time tag at which the counter was read.
   * @throws NullPointerException if {@code address}, {@code counter}, or {@code time} is {@code
   *     null}.
   */
  public IntegratedTotalsWithCp24Time {
    Objects.requireNonNull(address, "address");
    Objects.requireNonNull(counter, "counter");
    Objects.requireNonNull(time, "time");
  }

  /** Serde for the {@link IntegratedTotalsWithCp24Time} information elements (after the IOA). */
  public static final class Serde {

    private Serde() {}

    /**
     * Encodes the BCR and CP24Time2a elements into {@code buffer}.
     *
     * <p>Wire layout (Mode 1, least significant octet first), written after the IOA:
     *
     * <ul>
     *   <li>octets 1-5: BCR — the 32-bit value little-endian (octets 1-4) followed by a status
     *       octet with the sequence number in bits 1..5, CY(b6), CA(b7), and IV(b8);
     *   <li>octets 6-8: CP24Time2a — milliseconds (octets 1-2, LE) and minute/RES1/IV (octet 3).
     * </ul>
     *
     * <p>Does not write the IOA and does not release the buffer.
     *
     * @param o the object whose elements are encoded.
     * @param buffer the caller-owned buffer to write into.
     */
    public static void encode(IntegratedTotalsWithCp24Time o, ByteBuf buffer) {
      BinaryCounterReading.Serde.encode(o.counter(), buffer);
      Cp24Time2a.Serde.encode(o.time(), buffer);
    }

    /**
     * Decodes the BCR and CP24Time2a elements (IOA already read) from {@code buffer}.
     *
     * <p>Reads a 5-octet BCR then a 3-octet CP24Time2a. Does not release the buffer.
     *
     * @param address the information object address already read by the caller.
     * @param buffer the caller-owned buffer to read from.
     * @return the decoded {@link IntegratedTotalsWithCp24Time}.
     */
    public static IntegratedTotalsWithCp24Time decode(
        InformationObjectAddress address, ByteBuf buffer) {
      BinaryCounterReading counter = BinaryCounterReading.Serde.decode(buffer);
      Cp24Time2a time = Cp24Time2a.Serde.decode(buffer);
      return new IntegratedTotalsWithCp24Time(address, counter, time);
    }
  }
}
