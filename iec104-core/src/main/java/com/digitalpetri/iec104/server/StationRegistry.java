package com.digitalpetri.iec104.server;

import com.digitalpetri.iec104.address.CommonAddress;
import java.util.List;
import java.util.Optional;

/**
 * Provides access to the {@link Station Stations} hosted by an {@link Iec104Server}.
 *
 * <p>Obtain a registry from {@link Iec104Server#stations()}. Look up a station by its {@link
 * CommonAddress} to read or update its current-value image at runtime, for example to seed values
 * before any controlling station connects:
 *
 * <pre>{@code
 * server.stations().station(CommonAddress.of(1)).ifPresent(station ->
 *     station.updateValue(InformationObjectAddress.of(100), PointValue.single(true)));
 * }</pre>
 */
public interface StationRegistry {

  /**
   * Returns every station hosted by the server.
   *
   * @return an unmodifiable list of the configured stations.
   */
  List<Station> stations();

  /**
   * Returns the station with the given common address, if one is configured.
   *
   * @param commonAddress the common address to look up.
   * @return the matching station, or an empty {@link Optional} if none is configured.
   */
  Optional<Station> station(CommonAddress commonAddress);

  /**
   * Reports whether a station with the given common address is configured.
   *
   * @param commonAddress the common address to test.
   * @return {@code true} if a station with that common address is configured.
   */
  default boolean contains(CommonAddress commonAddress) {
    return station(commonAddress).isPresent();
  }
}
