package com.digitalpetri.iec104.point;

/**
 * The logical kind of a monitored point, independent of which time-tag variant is used to carry it
 * on the wire.
 *
 * <p>Each constant documents the family of monitor type identifications it maps to. The protocol
 * carries the same logical value in an untimed form, a form with a CP24Time2a time tag, and a form
 * with a CP56Time2a time tag; {@link MonitorMapping} selects the concrete record for a chosen
 * {@link TimeTagStyle}.
 */
public enum PointType {

  /**
   * Single-point information (a binary state). Maps to M_SP_NA_1 (1, untimed), M_SP_TA_1 (2,
   * CP24Time2a), and M_SP_TB_1 (30, CP56Time2a).
   */
  SINGLE_POINT,

  /**
   * Double-point information (a four-state binary value). Maps to M_DP_NA_1 (3, untimed), M_DP_TA_1
   * (4, CP24Time2a), and M_DP_TB_1 (31, CP56Time2a).
   */
  DOUBLE_POINT,

  /**
   * Step-position information (a transformer tap position with transient indication). Maps to
   * M_ST_NA_1 (5, untimed), M_ST_TA_1 (6, CP24Time2a), and M_ST_TB_1 (32, CP56Time2a).
   */
  STEP_POSITION,

  /**
   * A 32-bit bit string. Maps to M_BO_NA_1 (7, untimed), M_BO_TA_1 (8, CP24Time2a), and M_BO_TB_1
   * (33, CP56Time2a).
   */
  BITSTRING32,

  /**
   * Normalized measured value (a signed fixed-point fraction). Maps to M_ME_NA_1 (9, untimed),
   * M_ME_TA_1 (10, CP24Time2a), and M_ME_TD_1 (34, CP56Time2a).
   */
  NORMALIZED,

  /**
   * Scaled measured value (a signed 16-bit integer). Maps to M_ME_NB_1 (11, untimed), M_ME_TB_1
   * (12, CP24Time2a), and M_ME_TE_1 (35, CP56Time2a).
   */
  SCALED,

  /**
   * Short floating-point measured value (an IEEE 754 32-bit float). Maps to M_ME_NC_1 (13,
   * untimed), M_ME_TC_1 (14, CP24Time2a), and M_ME_TF_1 (36, CP56Time2a).
   */
  SHORT_FLOAT,

  /**
   * Integrated totals (a binary counter reading). Maps to M_IT_NA_1 (15, untimed), M_IT_TA_1 (16,
   * CP24Time2a), and M_IT_TB_1 (37, CP56Time2a).
   */
  INTEGRATED_TOTALS
}
