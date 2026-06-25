package com.digitalpetri.iec60870.asdu.object;

import com.digitalpetri.iec60870.address.InformationObjectAddress;
import com.digitalpetri.iec60870.asdu.InformationObject;
import com.digitalpetri.iec60870.asdu.element.QualifierOfParameter;
import io.netty.buffer.ByteBuf;
import java.util.Objects;

/**
 * P_ME_NB_1 (111) — parameter of measured values, scaled value.
 *
 * <p>Carries a signed 16-bit scaled parameter value (SVA) accompanied by a qualifier of parameter
 * of measured values (QPM) that identifies the kind of parameter (threshold, smoothing factor, low
 * limit, or high limit). Used in the control direction with cause activation ({@code <6>}) and in
 * the monitor direction with cause activation confirmation ({@code <7>}) and the interrogation
 * causes.
 *
 * @param address the information object address.
 * @param value the scaled parameter value (SVA, signed 16-bit).
 * @param qualifier the qualifier of parameter of measured values (QPM).
 */
public record ParameterScaled(
    InformationObjectAddress address, short value, QualifierOfParameter qualifier)
    implements InformationObject {

  /**
   * Validates that the required components are present.
   *
   * @param address the information object address.
   * @param value the scaled parameter value (SVA, signed 16-bit).
   * @param qualifier the qualifier of parameter of measured values (QPM).
   * @throws NullPointerException if {@code address} or {@code qualifier} is null.
   */
  public ParameterScaled {
    Objects.requireNonNull(address, "address");
    Objects.requireNonNull(qualifier, "qualifier");
  }

  /** Serde for the {@link ParameterScaled} information elements (after the IOA). */
  public static final class Serde {

    private Serde() {}

    /**
     * Encodes the elements into {@code buffer}; does not write the IOA or release the buffer.
     *
     * <p>Wire layout (little-endian): octets 1..2 are the SVA scaled value (signed 16-bit, least
     * significant octet first), octet 3 is the QPM ({@code KPA} in bits 1..6, {@code LPC} in bit 7,
     * {@code POP} in bit 8).
     *
     * @param o the parameter to encode.
     * @param buffer the caller-owned buffer to write into.
     */
    public static void encode(ParameterScaled o, ByteBuf buffer) {
      buffer.writeShortLE(o.value());
      QualifierOfParameter.Serde.encode(o.qualifier(), buffer);
    }

    /**
     * Decodes the elements (IOA already read) from {@code buffer}.
     *
     * <p>Reads the two-octet SVA scaled value followed by the QPM octet. Does not release the
     * buffer.
     *
     * @param address the information object address already read by the caller.
     * @param buffer the caller-owned buffer to read from.
     * @return the decoded parameter.
     */
    public static ParameterScaled decode(InformationObjectAddress address, ByteBuf buffer) {
      short value = buffer.readShortLE();
      QualifierOfParameter qualifier = QualifierOfParameter.Serde.decode(buffer);
      return new ParameterScaled(address, value, qualifier);
    }
  }
}
