package com.digitalpetri.iec104.catalog;

import com.digitalpetri.iec104.address.PointAddress;
import com.digitalpetri.iec104.point.PointCapability;
import com.digitalpetri.iec104.point.PointType;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Optional;

/**
 * A single described point in a {@link PointCatalog}, pairing a {@link PointAddress} with the
 * metadata a bridge needs to expose the point to a higher-level system.
 *
 * <p>The {@code browseName} is required and stable; {@code displayName} and {@code engineeringUnit}
 * are optional human-facing descriptions. {@code capabilities} declares how the point may be
 * interacted with, and {@code source} records where the entry's knowledge came from.
 *
 * <p>Example:
 *
 * <pre>{@code
 * CatalogEntry<?> entry = new CatalogEntry<>(
 *     PointAddress.of(1, 110),
 *     PointType.SCALED,
 *     "CA1/IOA110",
 *     Optional.of("Feeder 3 Voltage"),
 *     Optional.of("V"),
 *     EnumSet.of(PointCapability.REPORTED, PointCapability.READABLE),
 *     new CatalogSource.ConfiguredCatalogSource());
 * }</pre>
 *
 * @param <T> the logical value type of the point, as carried by its {@link PointType}.
 * @param address the fully qualified address of the point.
 * @param type the logical kind of the point.
 * @param browseName a stable, machine-friendly name for the point.
 * @param displayName an optional human-friendly name for the point.
 * @param engineeringUnit an optional engineering unit for the point's value (for example {@code
 *     "V"} or {@code "A"}).
 * @param capabilities the ways in which the point may be interacted with; never null and copied
 *     defensively.
 * @param source the provenance of the entry.
 */
// Phantom type parameter T documents the point's logical value type as part of the public API;
// it is intentionally unused by the components and bound to a wildcard at every call site.
@SuppressWarnings("unused")
public record CatalogEntry<T>(
    PointAddress address,
    PointType type,
    String browseName,
    Optional<String> displayName,
    Optional<String> engineeringUnit,
    EnumSet<PointCapability> capabilities,
    CatalogSource source) {

  /**
   * Validates the components and defensively copies {@code capabilities}.
   *
   * @param address the fully qualified address of the point.
   * @param type the logical kind of the point.
   * @param browseName a stable, machine-friendly name for the point.
   * @param displayName an optional human-friendly name for the point.
   * @param engineeringUnit an optional engineering unit for the point's value (for example {@code
   *     "V"} or {@code "A"}).
   * @param capabilities the ways in which the point may be interacted with; never null and copied
   *     defensively.
   * @param source the provenance of the entry.
   * @throws NullPointerException if any component is null.
   * @throws IllegalArgumentException if {@code browseName} is blank.
   */
  public CatalogEntry {
    Objects.requireNonNull(address, "address");
    Objects.requireNonNull(type, "type");
    Objects.requireNonNull(browseName, "browseName");
    Objects.requireNonNull(displayName, "displayName");
    Objects.requireNonNull(engineeringUnit, "engineeringUnit");
    Objects.requireNonNull(capabilities, "capabilities");
    Objects.requireNonNull(source, "source");
    if (browseName.isBlank()) {
      throw new IllegalArgumentException("browseName must not be blank");
    }
    capabilities =
        capabilities.isEmpty()
            ? EnumSet.noneOf(PointCapability.class)
            : EnumSet.copyOf(capabilities);
  }

  /**
   * Returns the declared capabilities as an independent copy that the caller may modify freely.
   *
   * @return a copy of the entry's capability set.
   */
  @Override
  public EnumSet<PointCapability> capabilities() {
    return capabilities.isEmpty()
        ? EnumSet.noneOf(PointCapability.class)
        : EnumSet.copyOf(capabilities);
  }
}
