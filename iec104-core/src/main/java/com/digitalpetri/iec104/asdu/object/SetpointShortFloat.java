package com.digitalpetri.iec104.asdu.object;

import com.digitalpetri.iec104.address.InformationObjectAddress;
import com.digitalpetri.iec104.asdu.InformationObject;
import com.digitalpetri.iec104.asdu.element.QualifierOfSetpoint;
import io.netty.buffer.ByteBuf;
import java.util.Objects;

/**
 * C_SE_NC_1 (50) — set-point command, short floating point number.
 *
 * <p>Carries an IEEE STD 754 single-precision (32-bit) short floating point set-point value (R32)
 * accompanied by a qualifier of set-point command (QOS) that selects between select and execute
 * semantics. Used in the control direction with causes activation ({@code <6>}) and deactivation
 * ({@code <8>}).
 *
 * @param address the information object address.
 * @param value the set-point value as an IEEE STD 754 short floating point number (R32).
 * @param qualifier the qualifier of set-point command (QOS).
 */
public record SetpointShortFloat(
    InformationObjectAddress address, float value, QualifierOfSetpoint qualifier)
    implements InformationObject {

  /**
   * Validates that the required components are present.
   *
   * @param address the information object address.
   * @param value the set-point value as an IEEE STD 754 short floating point number (R32).
   * @param qualifier the qualifier of set-point command (QOS).
   * @throws NullPointerException if {@code address} or {@code qualifier} is null.
   */
  public SetpointShortFloat {
    Objects.requireNonNull(address, "address");
    Objects.requireNonNull(qualifier, "qualifier");
  }

  /** Serde for the {@link SetpointShortFloat} information elements (after the IOA). */
  public static final class Serde {

    private Serde() {}

    /**
     * Encodes the elements into {@code buffer}; does not write the IOA or release the buffer.
     *
     * <p>Wire layout (least significant octet first): octets 1..4 are the R32 IEEE STD 754 short
     * float (little-endian), octet 5 is the QOS ({@code QL} in bits 1..7, {@code S/E} in bit 8).
     *
     * @param o the set-point command to encode.
     * @param buffer the caller-owned buffer to write into.
     */
    public static void encode(SetpointShortFloat o, ByteBuf buffer) {
      buffer.writeFloatLE(o.value());
      QualifierOfSetpoint.Serde.encode(o.qualifier(), buffer);
    }

    /**
     * Decodes the elements (IOA already read) from {@code buffer}.
     *
     * <p>Reads the four-octet little-endian R32 short float followed by the QOS octet. Does not
     * release the buffer.
     *
     * @param address the information object address already read by the caller.
     * @param buffer the caller-owned buffer to read from.
     * @return the decoded set-point command.
     */
    public static SetpointShortFloat decode(InformationObjectAddress address, ByteBuf buffer) {
      float value = buffer.readFloatLE();
      QualifierOfSetpoint qualifier = QualifierOfSetpoint.Serde.decode(buffer);
      return new SetpointShortFloat(address, value, qualifier);
    }
  }
}
