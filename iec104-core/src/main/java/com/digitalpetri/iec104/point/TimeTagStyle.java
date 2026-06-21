package com.digitalpetri.iec104.point;

/**
 * The time-tag variant of a monitor information object.
 *
 * <p>Each {@link PointType} is carried on the wire in one of three forms distinguished only by the
 * presence and width of a time tag. {@link MonitorMapping#toMonitorObject} uses this style to
 * choose the concrete monitor record to build.
 */
public enum TimeTagStyle {

  /** No time tag (the untimed monitor type, for example M_SP_NA_1). */
  NONE,

  /** A three-octet CP24Time2a time tag (for example M_SP_TA_1). */
  CP24,

  /** A seven-octet CP56Time2a time tag (for example M_SP_TB_1). */
  CP56
}
