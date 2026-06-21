package com.digitalpetri.iec104.asdu.object;

import com.digitalpetri.iec104.address.InformationObjectAddress;
import com.digitalpetri.iec104.asdu.InformationObject;
import com.digitalpetri.iec104.asdu.element.QualifierOfParameter;
import io.netty.buffer.ByteBuf;
import java.util.Objects;

/**
 * P_ME_NC_1 (112) — parameter of measured values, short floating point number.
 *
 * <p>Carries an IEEE STD 754 single-precision (32-bit) short floating point parameter value (R32)
 * accompanied by a qualifier of parameter of measured values (QPM) that identifies the kind of
 * parameter (for example a threshold, smoothing factor, or limit). Used in the control direction
 * with cause activation ({@code <6>}) and reported in the monitor direction with causes such as
 * activation confirmation ({@code <7>}) and interrogation responses.
 *
 * @param address the information object address.
 * @param value the parameter value as an IEEE STD 754 short floating point number (R32).
 * @param qualifier the qualifier of parameter of measured values (QPM).
 */
public record ParameterShortFloat(
    InformationObjectAddress address, float value, QualifierOfParameter qualifier)
    implements InformationObject {

  /**
   * Validates that the required components are present.
   *
   * @param address the information object address.
   * @param value the parameter value as an IEEE STD 754 short floating point number (R32).
   * @param qualifier the qualifier of parameter of measured values (QPM).
   * @throws NullPointerException if {@code address} or {@code qualifier} is null.
   */
  public ParameterShortFloat {
    Objects.requireNonNull(address, "address");
    Objects.requireNonNull(qualifier, "qualifier");
  }

  /** Serde for the {@link ParameterShortFloat} information elements (after the IOA). */
  public static final class Serde {

    private Serde() {}

    /**
     * Encodes the elements into {@code buffer}; does not write the IOA or release the buffer.
     *
     * <p>Wire layout (least significant octet first): octets 1..4 are the R32 IEEE STD 754 short
     * float (little-endian), octet 5 is the QPM ({@code KPA} in bits 1..6, {@code LPC} in bit 7,
     * {@code POP} in bit 8).
     *
     * @param o the parameter to encode.
     * @param buffer the caller-owned buffer to write into.
     */
    public static void encode(ParameterShortFloat o, ByteBuf buffer) {
      buffer.writeFloatLE(o.value());
      QualifierOfParameter.Serde.encode(o.qualifier(), buffer);
    }

    /**
     * Decodes the elements (IOA already read) from {@code buffer}.
     *
     * <p>Reads the four-octet little-endian R32 short float followed by the QPM octet. Does not
     * release the buffer.
     *
     * @param address the information object address already read by the caller.
     * @param buffer the caller-owned buffer to read from.
     * @return the decoded parameter.
     */
    public static ParameterShortFloat decode(InformationObjectAddress address, ByteBuf buffer) {
      float value = buffer.readFloatLE();
      QualifierOfParameter qualifier = QualifierOfParameter.Serde.decode(buffer);
      return new ParameterShortFloat(address, value, qualifier);
    }
  }
}
