package com.digitalpetri.iec60870.asdu.object;

import com.digitalpetri.iec60870.address.InformationObjectAddress;
import com.digitalpetri.iec60870.asdu.InformationObject;
import com.digitalpetri.iec60870.asdu.element.QualifierOfInterrogation;
import io.netty.buffer.ByteBuf;
import java.util.Objects;

/**
 * C_IC_NA_1 (100) — interrogation command.
 *
 * <p>Requests an interrogation of the controlled station in the control direction. The {@link
 * QualifierOfInterrogation} selects a global station interrogation or a specific interrogation
 * group (see {@link QualifierOfInterrogation#STATION} and {@link
 * QualifierOfInterrogation#GROUP_1}..{@code GROUP_16}). Carried as a single information object with
 * {@code SQ = 0} and an information object address that is conventionally {@code 0}.
 *
 * @param address the information object address (conventionally {@code 0} for this command).
 * @param qualifier the qualifier of interrogation (QOI) selecting the station or group.
 */
public record InterrogationCommand(
    InformationObjectAddress address, QualifierOfInterrogation qualifier)
    implements InformationObject {

  /**
   * Validates that the required components are present.
   *
   * @param address the information object address (conventionally {@code 0} for this command).
   * @param qualifier the qualifier of interrogation (QOI) selecting the station or group.
   * @throws NullPointerException if any component is null.
   */
  public InterrogationCommand {
    Objects.requireNonNull(address, "address");
    Objects.requireNonNull(qualifier, "qualifier");
  }

  /** Serde for the {@link InterrogationCommand} information elements (after the IOA). */
  public static final class Serde {

    private Serde() {}

    /**
     * Encodes the elements into {@code buffer}; does not write the IOA or release the buffer.
     *
     * <p>Wire layout: octet 1 is the QOI (UI8), the single unsigned qualifier-of-interrogation
     * octet.
     *
     * @param o the interrogation command to encode.
     * @param buffer the caller-owned buffer to write into.
     */
    public static void encode(InterrogationCommand o, ByteBuf buffer) {
      QualifierOfInterrogation.Serde.encode(o.qualifier(), buffer);
    }

    /**
     * Decodes the elements (IOA already read) from {@code buffer}.
     *
     * <p>Reads the single QOI octet. Does not release the buffer.
     *
     * @param address the information object address already read by the caller.
     * @param buffer the caller-owned buffer to read from.
     * @return the decoded interrogation command.
     */
    public static InterrogationCommand decode(InformationObjectAddress address, ByteBuf buffer) {
      QualifierOfInterrogation qualifier = QualifierOfInterrogation.Serde.decode(buffer);
      return new InterrogationCommand(address, qualifier);
    }
  }
}
