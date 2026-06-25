package com.digitalpetri.iec60870.address;

import java.util.Objects;

/**
 * A fully qualified address of a single information point, pairing the station's {@link
 * CommonAddress} with the {@link InformationObjectAddress} of the object within that station.
 *
 * <p>Use this type as a stable key for a point across a system, since the same information object
 * address may be reused by different stations.
 *
 * @param commonAddress the common address identifying the station.
 * @param objectAddress the information object address identifying the object within the station.
 */
public record PointAddress(CommonAddress commonAddress, InformationObjectAddress objectAddress) {

  /**
   * Validates the point address.
   *
   * @param commonAddress the common address identifying the station.
   * @param objectAddress the information object address identifying the object within the station.
   * @throws NullPointerException if {@code commonAddress} or {@code objectAddress} is null.
   */
  public PointAddress {
    Objects.requireNonNull(commonAddress, "commonAddress");
    Objects.requireNonNull(objectAddress, "objectAddress");
  }

  /**
   * Creates a point address from primitive common address and information object address values.
   *
   * @param commonAddress the common address value, in the range {@code 0..65535}.
   * @param objectAddress the information object address value, in the range {@code 0..16777215}.
   * @return the point address.
   * @throws IllegalArgumentException if either value is out of range.
   */
  public static PointAddress of(int commonAddress, long objectAddress) {
    return new PointAddress(
        CommonAddress.of(commonAddress), InformationObjectAddress.of(objectAddress));
  }
}
