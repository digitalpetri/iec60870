package com.digitalpetri.iec60870.asdu.object;

import com.digitalpetri.iec60870.address.InformationObjectAddress;
import com.digitalpetri.iec60870.asdu.InformationObject;
import com.digitalpetri.iec60870.asdu.element.BinaryCounterReading;
import io.netty.buffer.ByteBuf;

/**
 * M_IT_NA_1 (15) — integrated totals (IEC 60870-5-101 §7.3.1.15).
 *
 * <p>Reports a binary counter reading (BCR): a signed 32-bit accumulated total accompanied by a
 * sequence number and the carry, counter-adjusted, and invalid status bits.
 *
 * @param address the information object address.
 * @param counter the binary counter reading (BCR).
 */
public record IntegratedTotals(InformationObjectAddress address, BinaryCounterReading counter)
    implements InformationObject {

  /** Serde for the {@link IntegratedTotals} information elements (after the IOA). */
  public static final class Serde {

    private Serde() {}

    /**
     * Encodes the BCR into {@code buffer}; does not write the IOA or release the buffer.
     *
     * <p>Wire layout (least significant octet first): a 4-octet little-endian signed value (I32)
     * followed by one status octet carrying the sequence number in bits 1..5, CY(b6), CA(b7), and
     * IV(b8) — five octets in total.
     *
     * @param o the information object to encode.
     * @param buffer the caller-owned buffer to write into.
     */
    public static void encode(IntegratedTotals o, ByteBuf buffer) {
      BinaryCounterReading.Serde.encode(o.counter(), buffer);
    }

    /**
     * Decodes the elements (IOA already read) from {@code buffer}.
     *
     * <p>Reads a 4-octet little-endian value followed by a 1-octet BCR status octet (five octets in
     * total). Does not release the buffer.
     *
     * @param address the information object address already read by the caller.
     * @param buffer the caller-owned buffer to read from.
     * @return the decoded {@link IntegratedTotals}.
     */
    public static IntegratedTotals decode(InformationObjectAddress address, ByteBuf buffer) {
      BinaryCounterReading counter = BinaryCounterReading.Serde.decode(buffer);
      return new IntegratedTotals(address, counter);
    }
  }
}
