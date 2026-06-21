package com.digitalpetri.iec104.catalog;

/**
 * How {@link PointCatalog#merge} folds observed evidence into an existing catalog when a point is
 * described by both sources.
 */
public enum MergePolicy {

  /**
   * Keep the existing configured entry whenever an address is present in both the catalog and the
   * observed evidence; observed-only addresses are still added as new entries.
   */
  PREFER_CONFIGURED,

  /**
   * Replace the existing entry with one derived from the observed evidence whenever an address is
   * present in both; observed-only addresses are added as new entries.
   */
  PREFER_OBSERVED,

  /**
   * Leave existing entries untouched and add only those observed addresses that are not already
   * present in the catalog.
   */
  ADD_OBSERVED_ONLY
}
