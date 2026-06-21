package com.digitalpetri.iec104.asdu.object;

import static org.joou.Unsigned.ushort;

import com.digitalpetri.iec104.address.InformationObjectAddress;
import com.digitalpetri.iec104.asdu.InformationObject;
import com.digitalpetri.iec104.asdu.time.Cp56Time2a;
import io.netty.buffer.ByteBuf;
import org.joou.UShort;

/**
 * C_TS_TA_1 (107) — test command with time tag CP56Time2a (IEC 60870-5-104 §8.8).
 *
 * <p>In the control direction the controlling station sends this command (cause of transmission
 * {@code activation}) to verify the data path to an outstation. The outstation echoes the same type
 * back (cause {@code activation confirmation}). The requesting station may choose any value for the
 * test sequence counter; the counter and the time tag in the response must match the request
 * exactly.
 *
 * <p>The information object always appears individually (SQ = 0). It carries a 16-bit test sequence
 * counter followed by a single seven-octet CP56Time2a time element.
 *
 * @param address the information object address; this type conventionally uses address {@code 0}.
 * @param testSequenceCounter the 16-bit test sequence counter (TSC), in the range {@code 0..65535};
 *     echoed unchanged in the confirmation.
 * @param time the CP56Time2a time tag; echoed unchanged in the confirmation.
 */
public record TestCommandWithCp56Time(
    InformationObjectAddress address, UShort testSequenceCounter, Cp56Time2a time)
    implements InformationObject {

  /**
   * Validates that the required components are present.
   *
   * @param address the information object address; this type conventionally uses address {@code 0}.
   * @param testSequenceCounter the 16-bit test sequence counter (TSC), in the range {@code
   *     0..65535}; echoed unchanged in the confirmation.
   * @param time the CP56Time2a time tag; echoed unchanged in the confirmation.
   * @throws IllegalArgumentException if {@code testSequenceCounter} or {@code time} is {@code
   *     null}.
   */
  public TestCommandWithCp56Time {
    if (testSequenceCounter == null) {
      throw new IllegalArgumentException("testSequenceCounter must not be null");
    }
    if (time == null) {
      throw new IllegalArgumentException("time must not be null");
    }
  }

  /**
   * Serde for the {@link TestCommandWithCp56Time} information elements (after the IOA).
   *
   * <p>Wire layout (Mode 1, least significant octet first), nine octets total:
   *
   * <ul>
   *   <li>octets 1-2: test sequence counter (TSC) as an unsigned 16-bit integer, little-endian,
   *       range {@code 0..65535};
   *   <li>octets 3-9: CP56Time2a, as encoded by {@link Cp56Time2a.Serde}.
   * </ul>
   */
  public static final class Serde {

    private Serde() {}

    /**
     * Encodes the test sequence counter and CP56Time2a element into {@code buffer}; does not write
     * the IOA or release the buffer.
     *
     * @param o the object to encode.
     * @param buffer the caller-owned buffer to write into.
     */
    public static void encode(TestCommandWithCp56Time o, ByteBuf buffer) {
      buffer.writeShortLE(o.testSequenceCounter().intValue());
      Cp56Time2a.Serde.encode(o.time(), buffer);
    }

    /**
     * Decodes the elements (IOA already read) from {@code buffer}.
     *
     * @param address the information object address already read by the caller.
     * @param buffer the caller-owned buffer positioned at the first TSC octet.
     * @return the decoded object.
     */
    public static TestCommandWithCp56Time decode(InformationObjectAddress address, ByteBuf buffer) {
      UShort testSequenceCounter = ushort(buffer.readUnsignedShortLE());
      Cp56Time2a time = Cp56Time2a.Serde.decode(buffer);
      return new TestCommandWithCp56Time(address, testSequenceCounter, time);
    }
  }
}
