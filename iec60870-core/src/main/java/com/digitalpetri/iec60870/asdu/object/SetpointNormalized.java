package com.digitalpetri.iec60870.asdu.object;

import com.digitalpetri.iec60870.address.InformationObjectAddress;
import com.digitalpetri.iec60870.asdu.InformationObject;
import com.digitalpetri.iec60870.asdu.element.NormalizedValue;
import com.digitalpetri.iec60870.asdu.element.QualifierOfSetpoint;
import io.netty.buffer.ByteBuf;
import java.util.Objects;

/**
 * C_SE_NA_1 (48) — set-point command, normalized value.
 *
 * <p>Carries a set-point command whose target is a signed 16-bit normalized fraction (NVA),
 * together with a qualifier of set-point command (QOS) that conveys the select/execute flag and the
 * {@code QL} field.
 *
 * @param address the information object address.
 * @param value the normalized set-point value (NVA).
 * @param qualifier the qualifier of set-point command (QOS).
 */
public record SetpointNormalized(
    InformationObjectAddress address, NormalizedValue value, QualifierOfSetpoint qualifier)
    implements InformationObject {

  /**
   * Validates that the required components are present.
   *
   * @param address the information object address.
   * @param value the normalized set-point value (NVA).
   * @param qualifier the qualifier of set-point command (QOS).
   * @throws NullPointerException if any component is null.
   */
  public SetpointNormalized {
    Objects.requireNonNull(address, "address");
    Objects.requireNonNull(value, "value");
    Objects.requireNonNull(qualifier, "qualifier");
  }

  /** Serde for the {@link SetpointNormalized} information elements (after the IOA). */
  public static final class Serde {

    private Serde() {}

    /**
     * Encodes the elements into {@code buffer}; does not write the IOA or release the buffer.
     *
     * <p>Wire layout (little-endian): octets 1..2 are the NVA (I16, low octet first), octet 3 is
     * the QOS (QL b1..7, S/E b8).
     *
     * @param o the set-point command to encode.
     * @param buffer the caller-owned buffer to write into.
     */
    public static void encode(SetpointNormalized o, ByteBuf buffer) {
      NormalizedValue.Serde.encode(o.value(), buffer);
      QualifierOfSetpoint.Serde.encode(o.qualifier(), buffer);
    }

    /**
     * Decodes the elements (IOA already read) from {@code buffer}.
     *
     * <p>Reads the two NVA octets followed by the QOS octet. Does not release the buffer.
     *
     * @param address the information object address already read by the caller.
     * @param buffer the caller-owned buffer to read from.
     * @return the decoded set-point command.
     */
    public static SetpointNormalized decode(InformationObjectAddress address, ByteBuf buffer) {
      NormalizedValue value = NormalizedValue.Serde.decode(buffer);
      QualifierOfSetpoint qualifier = QualifierOfSetpoint.Serde.decode(buffer);
      return new SetpointNormalized(address, value, qualifier);
    }
  }
}
