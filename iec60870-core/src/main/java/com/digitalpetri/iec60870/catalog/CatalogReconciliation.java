package com.digitalpetri.iec60870.catalog;

import com.digitalpetri.iec60870.address.PointAddress;
import com.digitalpetri.iec60870.point.PointType;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The result of comparing a configured {@link PointCatalog} against an {@link ObservedCatalog},
 * surfacing the differences a bridge needs to review before trusting a station's points.
 *
 * <p>Build one with {@link #compare(PointCatalog, ObservedCatalog)}. The three reported lists
 * answer the questions a reconciliation UI asks: which configured points have not yet been seen in
 * traffic, which observed points are missing from the configuration, and where the observed type
 * identifications disagree with what was configured.
 *
 * <p>Example:
 *
 * <pre>{@code
 * CatalogReconciliation r = CatalogReconciliation.compare(configured, observed);
 * if (!r.typeMismatches().isEmpty()) {
 *     // review configuration vs. observed type identifications
 * }
 * }</pre>
 *
 * @param configuredNotObserved configured entries whose address was not present in the observed
 *     catalog.
 * @param observedNotConfigured observed points whose address was not present in the configured
 *     catalog.
 * @param typeMismatches points present in both catalogs whose configured {@link PointType}
 *     disagrees with the observed type identification.
 */
public record CatalogReconciliation(
    List<CatalogEntry<?>> configuredNotObserved,
    List<ObservedPoint> observedNotConfigured,
    List<TypeMismatch> typeMismatches) {

  /**
   * Validates the components and defensively copies the reported lists.
   *
   * @param configuredNotObserved configured entries whose address was not present in the observed
   *     catalog.
   * @param observedNotConfigured observed points whose address was not present in the configured
   *     catalog.
   * @param typeMismatches points present in both catalogs whose configured {@link PointType}
   *     disagrees with the observed type identification.
   * @throws NullPointerException if any list, or any element, is null.
   */
  public CatalogReconciliation {
    configuredNotObserved = List.copyOf(configuredNotObserved);
    observedNotConfigured = List.copyOf(observedNotConfigured);
    typeMismatches = List.copyOf(typeMismatches);
  }

  /**
   * A point present in both catalogs whose configured point type does not match the type
   * identification observed in traffic.
   *
   * @param address the address of the point.
   * @param configuredType the logical point type the configuration declared.
   * @param observed the observed point whose type identification did not map onto {@code
   *     configuredType}.
   */
  public record TypeMismatch(
      PointAddress address, PointType configuredType, ObservedPoint observed) {

    /**
     * Validates the components.
     *
     * @param address the address of the point.
     * @param configuredType the logical point type the configuration declared.
     * @param observed the observed point whose type identification did not map onto {@code
     *     configuredType}.
     * @throws NullPointerException if any component is null.
     */
    public TypeMismatch {
      Objects.requireNonNull(address, "address");
      Objects.requireNonNull(configuredType, "configuredType");
      Objects.requireNonNull(observed, "observed");
    }
  }

  /**
   * Compares a configured catalog against observed evidence and reports their differences.
   *
   * <p>Observed points whose type identification does not map onto a logical {@link PointType} are
   * never reported as type mismatches; if such a point is missing from the configuration it is
   * still reported under {@link #observedNotConfigured()}.
   *
   * @param configured the configured catalog (the specification).
   * @param observed the observed catalog (the evidence).
   * @return the reconciliation result.
   * @throws NullPointerException if {@code configured} or {@code observed} is null.
   */
  public static CatalogReconciliation compare(PointCatalog configured, ObservedCatalog observed) {
    Objects.requireNonNull(configured, "configured");
    Objects.requireNonNull(observed, "observed");

    List<ObservedPoint> observedNotConfigured = new ArrayList<>();
    List<TypeMismatch> typeMismatches = new ArrayList<>();
    Set<PointAddress> observedAddresses =
        observed.points().stream().map(ObservedPoint::address).collect(Collectors.toSet());

    for (ObservedPoint point : observed.points()) {
      Optional<CatalogEntry<?>> entry = configured.find(point.address());
      if (entry.isEmpty()) {
        observedNotConfigured.add(point);
        continue;
      }
      PointType configuredType = entry.get().type();
      Optional<PointType> observedType = CatalogTypes.pointTypeOf(point.observedType());
      if (observedType.isPresent() && observedType.get() != configuredType) {
        typeMismatches.add(new TypeMismatch(point.address(), configuredType, point));
      }
    }

    List<CatalogEntry<?>> configuredNotObserved =
        configured
            .entries()
            .filter(entry -> !observedAddresses.contains(entry.address()))
            .collect(Collectors.toList());

    return new CatalogReconciliation(configuredNotObserved, observedNotConfigured, typeMismatches);
  }
}
