package com.digitalpetri.iec104.asdu.object;

import com.digitalpetri.iec104.address.InformationObjectAddress;
import com.digitalpetri.iec104.asdu.InformationObject;
import com.digitalpetri.iec104.asdu.element.CauseOfInitialization;
import io.netty.buffer.ByteBuf;
import java.util.Objects;

/**
 * M_EI_NA_1 (70) — end of initialization.
 *
 * <p>Spontaneously reported by a controlled station once its local initialization completes,
 * carrying a single cause-of-initialization (COI) descriptor that explains how the station was
 * brought up. The information object address is conventionally {@code 0}.
 *
 * @param address the information object address (conventionally {@code 0}).
 * @param cause the cause of initialization (COI).
 */
public record EndOfInitialization(InformationObjectAddress address, CauseOfInitialization cause)
    implements InformationObject {

  /**
   * Validates that the required components are present.
   *
   * @param address the information object address (conventionally {@code 0}).
   * @param cause the cause of initialization (COI).
   * @throws NullPointerException if any component is null.
   */
  public EndOfInitialization {
    Objects.requireNonNull(address, "address");
    Objects.requireNonNull(cause, "cause");
  }

  /** Serde for the {@link EndOfInitialization} information elements (after the IOA). */
  public static final class Serde {

    private Serde() {}

    /**
     * Encodes the elements into {@code buffer}; does not write the IOA or release the buffer.
     *
     * <p>Wire layout: octet 1 is the COI (cause value in bits 1..7, after-parameter-change flag in
     * bit 8).
     *
     * @param o the end-of-initialization object to encode.
     * @param buffer the caller-owned buffer to write into.
     */
    public static void encode(EndOfInitialization o, ByteBuf buffer) {
      CauseOfInitialization.Serde.encode(o.cause(), buffer);
    }

    /**
     * Decodes the elements (IOA already read) from {@code buffer}.
     *
     * <p>Reads the single COI octet. Does not release the buffer.
     *
     * @param address the information object address already read by the caller.
     * @param buffer the caller-owned buffer to read from.
     * @return the decoded end-of-initialization object.
     */
    public static EndOfInitialization decode(InformationObjectAddress address, ByteBuf buffer) {
      CauseOfInitialization cause = CauseOfInitialization.Serde.decode(buffer);
      return new EndOfInitialization(address, cause);
    }
  }
}
