package com.digitalpetri.iec104.asdu.object;

import com.digitalpetri.iec104.address.InformationObjectAddress;
import com.digitalpetri.iec104.asdu.InformationObject;
import com.digitalpetri.iec104.asdu.element.QualifierOfParameterActivation;
import io.netty.buffer.ByteBuf;
import java.util.Objects;

/**
 * P_AC_NA_1 (113) — parameter activation.
 *
 * <p>Activates or deactivates a previously loaded parameter (or persistent cyclic transmission) for
 * the addressed information object. Sent in the control direction with cause {@code activation} or
 * {@code deactivation}, and confirmed in the monitor direction with the corresponding confirmation
 * cause. The single qualifier-of-parameter-activation (QPA) octet selects what is being activated.
 *
 * @param address the information object address.
 * @param qualifier the qualifier of parameter activation (QPA).
 */
public record ParameterActivation(
    InformationObjectAddress address, QualifierOfParameterActivation qualifier)
    implements InformationObject {

  /**
   * Validates that the required components are present.
   *
   * @param address the information object address.
   * @param qualifier the qualifier of parameter activation (QPA).
   * @throws NullPointerException if any component is null.
   */
  public ParameterActivation {
    Objects.requireNonNull(address, "address");
    Objects.requireNonNull(qualifier, "qualifier");
  }

  /** Serde for the {@link ParameterActivation} information elements (after the IOA). */
  public static final class Serde {

    private Serde() {}

    /**
     * Encodes the elements into {@code buffer}; does not write the IOA or release the buffer.
     *
     * <p>Wire layout: octet 1 is the QPA (qualifier of parameter activation, UI8).
     *
     * @param o the parameter-activation object to encode.
     * @param buffer the caller-owned buffer to write into.
     */
    public static void encode(ParameterActivation o, ByteBuf buffer) {
      QualifierOfParameterActivation.Serde.encode(o.qualifier(), buffer);
    }

    /**
     * Decodes the elements (IOA already read) from {@code buffer}.
     *
     * <p>Reads the single QPA octet. Does not release the buffer.
     *
     * @param address the information object address already read by the caller.
     * @param buffer the caller-owned buffer to read from.
     * @return the decoded parameter-activation object.
     */
    public static ParameterActivation decode(InformationObjectAddress address, ByteBuf buffer) {
      QualifierOfParameterActivation qualifier =
          QualifierOfParameterActivation.Serde.decode(buffer);
      return new ParameterActivation(address, qualifier);
    }
  }
}
