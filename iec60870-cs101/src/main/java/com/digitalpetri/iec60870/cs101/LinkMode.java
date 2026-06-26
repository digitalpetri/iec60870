package com.digitalpetri.iec60870.cs101;

/**
 * The FT1.2 link transmission procedure used by a {@code Ft12LinkLayer}.
 *
 * <p>The mode selects the link-layer service set and addressing rules: balanced transmission is the
 * symmetric point-to-point procedure where either station may initiate, while unbalanced
 * transmission is the master/slave polling procedure where a single primary station controls one or
 * more secondary stations.
 */
public enum LinkMode {

  /**
   * Balanced transmission: a symmetric point-to-point link on which either station may act as the
   * primary and initiate transfers. The link address is optional (0, 1, or 2 octets) and there is
   * no broadcast address.
   */
  BALANCED,

  /**
   * Unbalanced transmission: an asymmetric master/slave link on which the primary station polls one
   * or more secondary stations. The link address is always present (1 or 2 octets) and a broadcast
   * address is defined for send/no-reply messages.
   */
  UNBALANCED
}
