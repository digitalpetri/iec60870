package com.digitalpetri.iec104.asdu.object;

import com.digitalpetri.iec104.address.InformationObjectAddress;
import com.digitalpetri.iec104.asdu.InformationObject;
import com.digitalpetri.iec104.asdu.element.NormalizedValue;
import com.digitalpetri.iec104.asdu.element.QualifierOfParameter;
import io.netty.buffer.ByteBuf;
import java.util.Objects;

/**
 * P_ME_NA_1 (110) — parameter of measured values, normalized value.
 *
 * <p>Carries a measurement parameter whose value is a signed 16-bit normalized fraction (NVA),
 * together with a qualifier of parameter of measured values (QPM) that conveys the kind of
 * parameter ({@code KPA}) and the {@code LPC}/{@code POP} flags.
 *
 * @param address the information object address.
 * @param value the normalized parameter value (NVA).
 * @param qualifier the qualifier of parameter of measured values (QPM).
 */
public record ParameterNormalized(
    InformationObjectAddress address, NormalizedValue value, QualifierOfParameter qualifier)
    implements InformationObject {

  /**
   * Validates that the required components are present.
   *
   * @param address the information object address.
   * @param value the normalized parameter value (NVA).
   * @param qualifier the qualifier of parameter of measured values (QPM).
   * @throws NullPointerException if any component is null.
   */
  public ParameterNormalized {
    Objects.requireNonNull(address, "address");
    Objects.requireNonNull(value, "value");
    Objects.requireNonNull(qualifier, "qualifier");
  }

  /** Serde for the {@link ParameterNormalized} information elements (after the IOA). */
  public static final class Serde {

    private Serde() {}

    /**
     * Encodes the elements into {@code buffer}; does not write the IOA or release the buffer.
     *
     * <p>Wire layout (little-endian): octets 1..2 are the NVA (I16, low octet first), octet 3 is
     * the QPM ({@code KPA} b1..6, {@code LPC} b7, {@code POP} b8).
     *
     * @param o the parameter to encode.
     * @param buffer the caller-owned buffer to write into.
     */
    public static void encode(ParameterNormalized o, ByteBuf buffer) {
      NormalizedValue.Serde.encode(o.value(), buffer);
      QualifierOfParameter.Serde.encode(o.qualifier(), buffer);
    }

    /**
     * Decodes the elements (IOA already read) from {@code buffer}.
     *
     * <p>Reads the two NVA octets followed by the QPM octet. Does not release the buffer.
     *
     * @param address the information object address already read by the caller.
     * @param buffer the caller-owned buffer to read from.
     * @return the decoded parameter.
     */
    public static ParameterNormalized decode(InformationObjectAddress address, ByteBuf buffer) {
      NormalizedValue value = NormalizedValue.Serde.decode(buffer);
      QualifierOfParameter qualifier = QualifierOfParameter.Serde.decode(buffer);
      return new ParameterNormalized(address, value, qualifier);
    }
  }
}
