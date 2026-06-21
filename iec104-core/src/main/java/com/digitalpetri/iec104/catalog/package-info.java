/**
 * Point catalog and bridge-discovery model: the source of truth for which points exist and how they
 * are described, kept separate from the live IEC 104 traffic that supplies values and evidence.
 *
 * <p>A {@link com.digitalpetri.iec104.catalog.PointCatalog} is a keyed collection of {@link
 * com.digitalpetri.iec104.catalog.CatalogEntry} records, each pairing a {@link
 * com.digitalpetri.iec104.address.PointAddress} with descriptive metadata (browse name, display
 * name, engineering unit, capabilities) and a {@link com.digitalpetri.iec104.catalog.CatalogSource}
 * recording where the entry came from. Catalogs are immutable; {@link
 * com.digitalpetri.iec104.catalog.PointCatalog#merge merge} returns a new catalog rather than
 * mutating the original.
 *
 * <h2>Observation, not browsing</h2>
 *
 * <p>IEC 104 has no authoritative browse operation. A client can only <em>observe</em> evidence of
 * points by interrogating a station or watching spontaneous traffic. An {@link
 * com.digitalpetri.iec104.catalog.ObservedCatalog} captures that evidence as a list of {@link
 * com.digitalpetri.iec104.catalog.ObservedPoint} records, each carrying the {@link
 * com.digitalpetri.iec104.asdu.AsduType} and {@link com.digitalpetri.iec104.asdu.Cause} seen on the
 * wire together with an {@link com.digitalpetri.iec104.catalog.ObservationConfidence}. The {@link
 * com.digitalpetri.iec104.catalog.ObservationMode} chosen by the caller decides how that evidence
 * is gathered: general interrogation, group interrogation, passive watching, or a combination.
 *
 * <h2>Reconciliation</h2>
 *
 * <p>{@link com.digitalpetri.iec104.catalog.CatalogReconciliation} compares a configured catalog
 * against an observed catalog and reports which configured points were not observed, which observed
 * points are missing from the configuration, and where the observed type identifications disagree
 * with the configured {@link com.digitalpetri.iec104.point.PointType}. {@link
 * com.digitalpetri.iec104.catalog.MergePolicy} controls how the two evidence sources are folded
 * together into a single catalog.
 *
 * <h2>Boundaries</h2>
 *
 * <p>This package is dependency-free with respect to the transport and wire layers: it contains no
 * Netty types and references only the immutable address, ASDU-type, and point-model records.
 */
@NullMarked
package com.digitalpetri.iec104.catalog;

import org.jspecify.annotations.NullMarked;
