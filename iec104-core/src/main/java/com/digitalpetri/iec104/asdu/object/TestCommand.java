package com.digitalpetri.iec104.asdu.object;

import com.digitalpetri.iec104.address.InformationObjectAddress;
import com.digitalpetri.iec104.asdu.InformationObject;
import com.digitalpetri.iec104.asdu.element.FixedTestBitPattern;
import io.netty.buffer.ByteBuf;
import java.util.Objects;

/**
 * C_TS_NA_1 (104) — test command.
 *
 * <p>Sent in the control direction to verify the data path to a controlled station; the station
 * echoes the carried fixed test bit pattern (FBP) in its activation confirmation. The information
 * object address is conventionally {@code 0}. The standard test pattern is {@code 0x55AA},
 * available as {@link FixedTestBitPattern#DEFAULT}.
 *
 * @param address the information object address (conventionally {@code 0}).
 * @param pattern the fixed test bit pattern (FBP) to be echoed.
 */
public record TestCommand(InformationObjectAddress address, FixedTestBitPattern pattern)
    implements InformationObject {

  /**
   * Validates that the required components are present.
   *
   * @param address the information object address (conventionally {@code 0}).
   * @param pattern the fixed test bit pattern (FBP) to be echoed.
   * @throws NullPointerException if any component is null.
   */
  public TestCommand {
    Objects.requireNonNull(address, "address");
    Objects.requireNonNull(pattern, "pattern");
  }

  /** Serde for the {@link TestCommand} information elements (after the IOA). */
  public static final class Serde {

    private Serde() {}

    /**
     * Encodes the elements into {@code buffer}; does not write the IOA or release the buffer.
     *
     * <p>Wire layout: octets 1..2 are the FBP, least significant octet first (the standard default
     * pattern {@code 0x55AA} encodes as {@code aa 55}).
     *
     * @param o the test command to encode.
     * @param buffer the caller-owned buffer to write into.
     */
    public static void encode(TestCommand o, ByteBuf buffer) {
      FixedTestBitPattern.Serde.encode(o.pattern(), buffer);
    }

    /**
     * Decodes the elements (IOA already read) from {@code buffer}.
     *
     * <p>Reads the two FBP octets, least significant octet first. Does not release the buffer.
     *
     * @param address the information object address already read by the caller.
     * @param buffer the caller-owned buffer to read from.
     * @return the decoded test command.
     */
    public static TestCommand decode(InformationObjectAddress address, ByteBuf buffer) {
      FixedTestBitPattern pattern = FixedTestBitPattern.Serde.decode(buffer);
      return new TestCommand(address, pattern);
    }
  }
}
