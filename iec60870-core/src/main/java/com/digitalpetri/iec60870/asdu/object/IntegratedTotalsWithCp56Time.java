package com.digitalpetri.iec60870.asdu.object;

import com.digitalpetri.iec60870.address.InformationObjectAddress;
import com.digitalpetri.iec60870.asdu.InformationObject;
import com.digitalpetri.iec60870.asdu.element.BinaryCounterReading;
import com.digitalpetri.iec60870.asdu.time.Cp56Time2a;
import io.netty.buffer.ByteBuf;

/**
 * M_IT_TB_1 (37) — integrated totals with a CP56Time2a time tag (IEC 60870-5-101 §7.3.1.29).
 *
 * <p>Reports a binary counter reading (BCR) together with a seven-octet absolute time tag. Each
 * total carries its own time tag, so this type is only transmitted as a sequence of information
 * objects (SQ = 0). Used spontaneously or in response to a counter interrogation.
 *
 * @param address the information object address.
 * @param counter the binary counter reading (BCR).
 * @param time the CP56Time2a time tag.
 */
public record IntegratedTotalsWithCp56Time(
    InformationObjectAddress address, BinaryCounterReading counter, Cp56Time2a time)
    implements InformationObject {

  /** Serde for the {@link IntegratedTotalsWithCp56Time} information elements (after the IOA). */
  public static final class Serde {

    private Serde() {}

    /**
     * Encodes the BCR and CP56Time2a elements into {@code buffer}.
     *
     * <p>Wire layout (Mode 1, least significant octet first): octets 1..5 = BCR (a 4-octet
     * little-endian signed value (I32) followed by one status octet carrying the sequence number in
     * bits 1..5, CY(b6), CA(b7), and IV(b8)), octets 6..12 = CP56Time2a. The information object
     * address is not written here. Does not release the buffer.
     *
     * @param object the information object to encode.
     * @param buffer the caller-owned buffer to write into.
     */
    public static void encode(IntegratedTotalsWithCp56Time object, ByteBuf buffer) {
      BinaryCounterReading.Serde.encode(object.counter(), buffer);
      Cp56Time2a.Serde.encode(object.time(), buffer);
    }

    /**
     * Decodes the BCR and CP56Time2a elements (information object address already read) from {@code
     * buffer}.
     *
     * <p>Reads a 5-octet BCR followed by a 7-octet CP56Time2a (twelve octets in total). Does not
     * release the buffer.
     *
     * @param address the information object address already read by the caller.
     * @param buffer the caller-owned buffer to read from.
     * @return the decoded information object.
     */
    public static IntegratedTotalsWithCp56Time decode(
        InformationObjectAddress address, ByteBuf buffer) {
      BinaryCounterReading counter = BinaryCounterReading.Serde.decode(buffer);
      Cp56Time2a time = Cp56Time2a.Serde.decode(buffer);
      return new IntegratedTotalsWithCp56Time(address, counter, time);
    }
  }
}
