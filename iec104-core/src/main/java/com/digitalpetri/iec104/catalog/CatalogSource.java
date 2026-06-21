package com.digitalpetri.iec104.catalog;

import com.digitalpetri.iec104.address.CommonAddress;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * The provenance of a {@link CatalogEntry}: where the knowledge of the point originally came from.
 *
 * <p>Provenance matters when reconciling catalogs and choosing which description to trust. A point
 * declared in operator configuration is authoritative; a point imported from an external file is
 * trusted but second-hand; a point that only exists because it was seen in live traffic is
 * evidence, not specification.
 */
public sealed interface CatalogSource
    permits CatalogSource.ConfiguredCatalogSource,
        CatalogSource.ImportedCatalogSource,
        CatalogSource.ObservedCatalogSource {

  /**
   * A point declared directly in operator or engineering configuration.
   *
   * <p>This is the most authoritative source: its metadata is taken as the specification of the
   * point rather than as evidence to be confirmed.
   */
  record ConfiguredCatalogSource() implements CatalogSource {}

  /**
   * A point imported from an external description, such as a point-map file exported from an RTU
   * configuration tool.
   *
   * @param origin a short identifier of where the import came from (for example a file name or tool
   *     name).
   */
  record ImportedCatalogSource(String origin) implements CatalogSource {

    /**
     * Validates the components.
     *
     * @param origin a short identifier of where the import came from (for example a file name or
     *     tool name).
     * @throws NullPointerException if {@code origin} is null.
     */
    public ImportedCatalogSource {
      Objects.requireNonNull(origin, "origin");
    }
  }

  /**
   * A point that is known only because it was observed in IEC 104 traffic.
   *
   * <p>The presence of this source means "this point was seen", not "this point is fully
   * described"; such entries typically carry generated browse names and minimal metadata until
   * reconciled against a configured catalog.
   *
   * @param station the common address of the station the point was observed on.
   * @param observedAt the instant at which the observation was made, if known.
   */
  record ObservedCatalogSource(CommonAddress station, Optional<Instant> observedAt)
      implements CatalogSource {

    /**
     * Validates the components.
     *
     * @param station the common address of the station the point was observed on.
     * @param observedAt the instant at which the observation was made, if known.
     * @throws NullPointerException if {@code station} or {@code observedAt} is null.
     */
    public ObservedCatalogSource {
      Objects.requireNonNull(station, "station");
      Objects.requireNonNull(observedAt, "observedAt");
    }
  }
}
