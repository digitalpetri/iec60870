package com.digitalpetri.iec104.catalog;

import com.digitalpetri.iec104.address.PointAddress;
import com.digitalpetri.iec104.point.PointCapability;
import com.digitalpetri.iec104.point.PointType;
import java.util.Collection;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * An immutable, address-keyed collection of {@link CatalogEntry} records describing the points
 * known for one or more stations.
 *
 * <p>A catalog is the source of truth for how points are named and described; live IEC 104 traffic
 * supplies their values. Build a catalog from configured entries with {@link #of(Collection)} or
 * start empty with {@link #empty()}, then fold in observed evidence with {@link
 * #merge(ObservedCatalog, MergePolicy)}. All operations return new catalogs; instances are never
 * mutated.
 *
 * <p>Example:
 *
 * <pre>{@code
 * PointCatalog configured = PointCatalog.of(List.of(entry1, entry2));
 * Optional<CatalogEntry<?>> found = configured.find(PointAddress.of(1, 110));
 * PointCatalog merged = configured.merge(observed, MergePolicy.ADD_OBSERVED_ONLY);
 * }</pre>
 */
public interface PointCatalog {

  /**
   * Returns the catalog's entries as a stream, in a stable iteration order.
   *
   * @return a stream over the catalog's entries.
   */
  Stream<CatalogEntry<?>> entries();

  /**
   * Looks up the entry for a point address.
   *
   * @param address the address to look up.
   * @return the entry for {@code address}, or {@link Optional#empty()} if no entry exists.
   * @throws NullPointerException if {@code address} is null.
   */
  Optional<CatalogEntry<?>> find(PointAddress address);

  /**
   * Returns a new catalog that folds the observed evidence into this one according to {@code
   * policy}.
   *
   * <p>Observed points whose type identification does not map onto a logical {@link PointType} are
   * ignored. New entries derived from observation are given a generated browse name of the form
   * {@code CA<ca>/IOA<ioa>}, the {@link PointCapability#REPORTED} capability, and an {@link
   * CatalogSource.ObservedCatalogSource} source.
   *
   * @param observed the observed catalog to merge in.
   * @param policy how to resolve addresses present in both this catalog and {@code observed}.
   * @return a new catalog containing the merged entries.
   * @throws NullPointerException if {@code observed} or {@code policy} is null.
   */
  PointCatalog merge(ObservedCatalog observed, MergePolicy policy);

  /**
   * Returns an empty catalog.
   *
   * @return a catalog with no entries.
   */
  static PointCatalog empty() {
    return new DefaultPointCatalog(Map.of());
  }

  /**
   * Returns a catalog containing the given entries.
   *
   * <p>If two entries share the same address, the later one in iteration order wins.
   *
   * @param entries the entries to include; copied defensively.
   * @return a catalog containing {@code entries}.
   * @throws NullPointerException if {@code entries} or any element is null.
   */
  static PointCatalog of(Collection<CatalogEntry<?>> entries) {
    Objects.requireNonNull(entries, "entries");
    Map<PointAddress, CatalogEntry<?>> map = new LinkedHashMap<>();
    for (CatalogEntry<?> entry : entries) {
      Objects.requireNonNull(entry, "entry");
      map.put(entry.address(), entry);
    }
    return new DefaultPointCatalog(map);
  }

  /** The default immutable {@link PointCatalog} backed by an unmodifiable address-keyed map. */
  final class DefaultPointCatalog implements PointCatalog {

    private final Map<PointAddress, CatalogEntry<?>> entries;

    private DefaultPointCatalog(Map<PointAddress, CatalogEntry<?>> entries) {
      this.entries = entries;
    }

    @Override
    public Stream<CatalogEntry<?>> entries() {
      return entries.values().stream();
    }

    @Override
    public Optional<CatalogEntry<?>> find(PointAddress address) {
      Objects.requireNonNull(address, "address");
      return Optional.ofNullable(entries.get(address));
    }

    @Override
    public PointCatalog merge(ObservedCatalog observed, MergePolicy policy) {
      Objects.requireNonNull(observed, "observed");
      Objects.requireNonNull(policy, "policy");

      Map<PointAddress, CatalogEntry<?>> merged = new LinkedHashMap<>(entries);

      for (ObservedPoint point : observed.points()) {
        Optional<PointType> maybeType = CatalogTypes.pointTypeOf(point.observedType());
        if (maybeType.isEmpty()) {
          continue;
        }
        PointAddress address = point.address();
        boolean present = merged.containsKey(address);

        switch (policy) {
          case PREFER_CONFIGURED -> {
            if (!present) {
              merged.put(address, observedEntry(observed, point, maybeType.get()));
            }
          }
          case PREFER_OBSERVED ->
              merged.put(address, observedEntry(observed, point, maybeType.get()));
          case ADD_OBSERVED_ONLY -> {
            if (!present) {
              merged.put(address, observedEntry(observed, point, maybeType.get()));
            }
          }
        }
      }

      return new DefaultPointCatalog(merged);
    }

    /**
     * Builds a catalog entry derived from an observed point.
     *
     * @param observed the observed catalog the point came from.
     * @param point the observed point.
     * @param type the logical point type derived from the observed type identification.
     * @return the derived catalog entry.
     */
    private static CatalogEntry<?> observedEntry(
        ObservedCatalog observed, ObservedPoint point, PointType type) {
      PointAddress address = point.address();
      String browseName =
          "CA"
              + address.commonAddress().value().intValue()
              + "/IOA"
              + address.objectAddress().value().longValue();
      return new CatalogEntry<>(
          address,
          type,
          browseName,
          Optional.empty(),
          Optional.empty(),
          EnumSet.of(PointCapability.REPORTED),
          new CatalogSource.ObservedCatalogSource(
              observed.station(), Optional.of(observed.observedAt())));
    }
  }
}
