package com.digitalpetri.iec104.catalog;

/**
 * How much trust to place in an {@link ObservedPoint}: the kind of evidence that led to the point
 * being recorded.
 */
public enum ObservationConfidence {

  /**
   * The point was seen in spontaneous or periodic traffic that was already flowing. The point is
   * known to be active, but no interrogation confirmed it belongs to a known group or snapshot.
   */
  OBSERVED,

  /**
   * The point appeared in the response to an interrogation, so the server actively reported it as
   * part of the requested station or group snapshot.
   */
  INTERROGATED,

  /**
   * The point was matched against a previously configured catalog entry, so its existence is
   * corroborated by configuration rather than by traffic alone.
   */
  CONFIGURED
}
