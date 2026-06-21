package com.digitalpetri.iec104.asdu.object;

import com.digitalpetri.iec104.address.InformationObjectAddress;
import com.digitalpetri.iec104.asdu.InformationObject;
import com.digitalpetri.iec104.asdu.element.QualifierOfCounterInterrogation;
import io.netty.buffer.ByteBuf;
import java.util.Objects;

/**
 * C_CI_NA_1 (101) — counter interrogation command.
 *
 * <p>Requests integrated totals (counters) from the controlled station. The single qualifier octet
 * (QCC) selects the counter group to read and the freeze action to apply to it. Carried as a single
 * information object (SQ = 0).
 *
 * @param address the information object address (per IEC 60870-5-101 the IOA is {@code 0}).
 * @param qualifier the qualifier of counter interrogation command (QCC) selecting the counter group
 *     and freeze mode.
 */
public record CounterInterrogationCommand(
    InformationObjectAddress address, QualifierOfCounterInterrogation qualifier)
    implements InformationObject {

  /**
   * Validates that the required components are present.
   *
   * @param address the information object address (per IEC 60870-5-101 the IOA is {@code 0}).
   * @param qualifier the qualifier of counter interrogation command (QCC) selecting the counter
   *     group and freeze mode.
   * @throws NullPointerException if any component is null.
   */
  public CounterInterrogationCommand {
    Objects.requireNonNull(address, "address");
    Objects.requireNonNull(qualifier, "qualifier");
  }

  /** Serde for the {@link CounterInterrogationCommand} information elements (after the IOA). */
  public static final class Serde {

    private Serde() {}

    /**
     * Encodes the elements into {@code buffer}; does not write the IOA or release the buffer.
     *
     * <p>Wire layout: octet 1 is the QCC ({@code RQT} in bits 1..6, {@code FRZ} in bits 7..8).
     *
     * @param o the counter interrogation command to encode.
     * @param buffer the caller-owned buffer to write into.
     */
    public static void encode(CounterInterrogationCommand o, ByteBuf buffer) {
      QualifierOfCounterInterrogation.Serde.encode(o.qualifier(), buffer);
    }

    /**
     * Decodes the elements (IOA already read) from {@code buffer}.
     *
     * <p>Reads the single QCC octet. Does not release the buffer.
     *
     * @param address the information object address already read by the caller.
     * @param buffer the caller-owned buffer to read from.
     * @return the decoded counter interrogation command.
     */
    public static CounterInterrogationCommand decode(
        InformationObjectAddress address, ByteBuf buffer) {
      QualifierOfCounterInterrogation qualifier =
          QualifierOfCounterInterrogation.Serde.decode(buffer);
      return new CounterInterrogationCommand(address, qualifier);
    }
  }
}
