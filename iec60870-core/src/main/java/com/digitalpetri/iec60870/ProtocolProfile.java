package com.digitalpetri.iec60870;

/**
 * Station-wide field widths that govern how the data unit identifier and information objects are
 * framed on the wire.
 *
 * <p>These widths are fixed per system and must be agreed by both communicating stations. The cause
 * of transmission length selects whether an originator address octet is present: a length of {@code
 * 1} omits it, while a length of {@code 2} includes it. The common address and information object
 * address lengths set how many little-endian octets carry those addresses.
 *
 * <p>Use {@link #iec104Default()} for the standard IEC 60870-5-104 profile {@code (2, 2, 3, 249)}.
 *
 * @param cotLength the number of cause-of-transmission octets, either {@code 1} (no originator
 *     address) or {@code 2} (with originator address).
 * @param commonAddressLength the number of common address octets, in the range {@code 1..2}.
 * @param ioaLength the number of information object address octets, in the range {@code 1..3}.
 * @param maxAsduLength the maximum ASDU length in octets, in the range {@code 1..249}.
 */
public record ProtocolProfile(
    int cotLength, int commonAddressLength, int ioaLength, int maxAsduLength) {

  /**
   * Validates the field widths.
   *
   * @param cotLength the number of cause-of-transmission octets, either {@code 1} (no originator
   *     address) or {@code 2} (with originator address).
   * @param commonAddressLength the number of common address octets, in the range {@code 1..2}.
   * @param ioaLength the number of information object address octets, in the range {@code 1..3}.
   * @param maxAsduLength the maximum ASDU length in octets, in the range {@code 1..249}.
   * @throws IllegalArgumentException if {@code cotLength} is not in {@code 1..2}, if {@code
   *     commonAddressLength} is not in {@code 1..2}, if {@code ioaLength} is not in {@code 1..3},
   *     or if {@code maxAsduLength} is not in {@code 1..249}.
   */
  public ProtocolProfile {
    if (cotLength < 1 || cotLength > 2) {
      throw new IllegalArgumentException("cotLength must be in 1..2: " + cotLength);
    }
    if (commonAddressLength < 1 || commonAddressLength > 2) {
      throw new IllegalArgumentException(
          "commonAddressLength must be in 1..2: " + commonAddressLength);
    }
    if (ioaLength < 1 || ioaLength > 3) {
      throw new IllegalArgumentException("ioaLength must be in 1..3: " + ioaLength);
    }
    if (maxAsduLength < 1 || maxAsduLength > 249) {
      throw new IllegalArgumentException("maxAsduLength must be in 1..249: " + maxAsduLength);
    }
  }

  /**
   * Returns the standard IEC 60870-5-104 profile.
   *
   * <p>The returned profile uses a 2-octet cause of transmission (with originator address), a
   * 2-octet common address, a 3-octet information object address, and a maximum ASDU length of
   * {@code 249} octets.
   *
   * @return the default IEC 60870-5-104 profile {@code (2, 2, 3, 249)}.
   */
  public static ProtocolProfile iec104Default() {
    return new ProtocolProfile(2, 2, 3, 249);
  }
}
