package com.digitalpetri.iec104.asdu.object;

import com.digitalpetri.iec104.address.InformationObjectAddress;
import com.digitalpetri.iec104.asdu.InformationObject;
import com.digitalpetri.iec104.asdu.element.QualifierOfResetProcess;
import io.netty.buffer.ByteBuf;
import java.util.Objects;

/**
 * C_RP_NA_1 (105) — reset process command.
 *
 * <p>Requests a reset of the controlled station's process in the control direction. The {@link
 * QualifierOfResetProcess} selects a general process reset ({@link
 * QualifierOfResetProcess#GENERAL}) or a reset of pending information with time tag in the event
 * buffer ({@link QualifierOfResetProcess#EVENT_BUFFER}). Carried as a single information object
 * with {@code SQ = 0} and an information object address that is conventionally {@code 0}.
 *
 * @param address the information object address (conventionally {@code 0} for this command).
 * @param qualifier the qualifier of reset process (QRP) selecting the reset action.
 */
public record ResetProcessCommand(
    InformationObjectAddress address, QualifierOfResetProcess qualifier)
    implements InformationObject {

  /**
   * Validates that the required components are present.
   *
   * @param address the information object address (conventionally {@code 0} for this command).
   * @param qualifier the qualifier of reset process (QRP) selecting the reset action.
   * @throws NullPointerException if any component is null.
   */
  public ResetProcessCommand {
    Objects.requireNonNull(address, "address");
    Objects.requireNonNull(qualifier, "qualifier");
  }

  /** Serde for the {@link ResetProcessCommand} information elements (after the IOA). */
  public static final class Serde {

    private Serde() {}

    /**
     * Encodes the elements into {@code buffer}; does not write the IOA or release the buffer.
     *
     * <p>Wire layout: octet 1 is the QRP (UI8), the single unsigned qualifier-of-reset-process
     * octet.
     *
     * @param o the reset process command to encode.
     * @param buffer the caller-owned buffer to write into.
     */
    public static void encode(ResetProcessCommand o, ByteBuf buffer) {
      QualifierOfResetProcess.Serde.encode(o.qualifier(), buffer);
    }

    /**
     * Decodes the elements (IOA already read) from {@code buffer}.
     *
     * <p>Reads the single QRP octet. Does not release the buffer.
     *
     * @param address the information object address already read by the caller.
     * @param buffer the caller-owned buffer to read from.
     * @return the decoded reset process command.
     */
    public static ResetProcessCommand decode(InformationObjectAddress address, ByteBuf buffer) {
      QualifierOfResetProcess qualifier = QualifierOfResetProcess.Serde.decode(buffer);
      return new ResetProcessCommand(address, qualifier);
    }
  }
}
