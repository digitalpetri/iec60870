package com.digitalpetri.iec60870.server;

import com.digitalpetri.iec60870.address.CommonAddress;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The default {@link StationRegistry} backed by a fixed set of stations supplied at construction.
 *
 * <p>The registry is immutable in its membership — stations are not added or removed after
 * construction — but the individual {@link Station} instances remain mutable in their value images.
 * It is safe to share across threads.
 */
public final class DefaultStationRegistry implements StationRegistry {

  private final Map<CommonAddress, Station> stations = new LinkedHashMap<>();

  /**
   * Creates a registry over the given stations.
   *
   * @param stations the stations to host; each must have a distinct common address.
   * @throws NullPointerException if {@code stations} or any element is null.
   * @throws IllegalArgumentException if two stations share a common address.
   */
  public DefaultStationRegistry(List<Station> stations) {
    Objects.requireNonNull(stations, "stations");
    for (Station station : stations) {
      Objects.requireNonNull(station, "station");
      if (this.stations.putIfAbsent(station.commonAddress(), station) != null) {
        throw new IllegalArgumentException(
            "duplicate station common address: " + station.commonAddress());
      }
    }
  }

  @Override
  public List<Station> stations() {
    return List.copyOf(stations.values());
  }

  @Override
  public Optional<Station> station(CommonAddress commonAddress) {
    return Optional.ofNullable(stations.get(commonAddress));
  }
}
