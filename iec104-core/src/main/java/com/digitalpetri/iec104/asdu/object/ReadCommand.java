package com.digitalpetri.iec104.asdu.object;

import com.digitalpetri.iec104.address.InformationObjectAddress;
import com.digitalpetri.iec104.asdu.InformationObject;
import io.netty.buffer.ByteBuf;
import java.util.Objects;

/**
 * C_RD_NA_1 (102) — read command.
 *
 * <p>Requests the current value of the addressed information object. The command carries no
 * information elements: the information object address alone identifies the value to read. In
 * control direction it is sent with cause {@code request (5)}.
 *
 * @param address the information object address whose value is requested.
 */
public record ReadCommand(InformationObjectAddress address) implements InformationObject {

  /**
   * Validates that the required components are present.
   *
   * @param address the information object address whose value is requested.
   * @throws NullPointerException if {@code address} is null.
   */
  public ReadCommand {
    Objects.requireNonNull(address, "address");
  }

  /** Serde for the {@link ReadCommand} information elements (after the IOA). */
  public static final class Serde {

    private Serde() {}

    /**
     * Encodes the elements into {@code buffer}; does not write the IOA or release the buffer.
     *
     * <p>C_RD_NA_1 has no information elements following the IOA, so this method writes nothing.
     *
     * @param o the read command to encode.
     * @param buffer the caller-owned buffer to write into.
     */
    public static void encode(ReadCommand o, ByteBuf buffer) {
      // No information elements: the IOA (written by the caller) fully specifies the command.
    }

    /**
     * Decodes the elements (IOA already read) from {@code buffer}.
     *
     * <p>C_RD_NA_1 has no information elements following the IOA, so this method reads nothing and
     * returns a command built from the supplied address. Does not release the buffer.
     *
     * @param address the information object address already read by the caller.
     * @param buffer the caller-owned buffer to read from.
     * @return the decoded read command.
     */
    public static ReadCommand decode(InformationObjectAddress address, ByteBuf buffer) {
      return new ReadCommand(address);
    }
  }
}
