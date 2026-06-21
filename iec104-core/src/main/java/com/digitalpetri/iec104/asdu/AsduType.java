package com.digitalpetri.iec104.asdu;

import com.digitalpetri.iec104.UnsupportedAsduTypeException;

/**
 * Type identification (octet 1 of the ASDU) as defined by IEC 60870-5-101 Tables 8-13 and the
 * additional control-direction-with-time types of IEC 60870-5-104.
 *
 * <p>Each constant carries its numeric {@linkplain #typeId() type identification} and a {@linkplain
 * #supported() support flag} that reports whether the library provides a typed information object
 * record for that type. Types that are present on the wire but not modelled as typed records (the
 * file transfer {@code F_*} types) are included with {@code supported() == false} so that they
 * remain reachable through the raw ASDU and the codec extension points without being silently
 * dropped.
 *
 * <p>Undefined type identifications (values with no defined constant, and the unused value {@code
 * 0}) have no constant; {@link #fromId(int)} throws {@link UnsupportedAsduTypeException} for them.
 */
public enum AsduType {

  // Table 8 - Process information in monitor direction.

  /** Single-point information (M_SP_NA_1). */
  M_SP_NA_1(1, true),
  /** Single-point information with time tag CP24Time2a (M_SP_TA_1). */
  M_SP_TA_1(2, true),
  /** Double-point information (M_DP_NA_1). */
  M_DP_NA_1(3, true),
  /** Double-point information with time tag CP24Time2a (M_DP_TA_1). */
  M_DP_TA_1(4, true),
  /** Step position information (M_ST_NA_1). */
  M_ST_NA_1(5, true),
  /** Step position information with time tag CP24Time2a (M_ST_TA_1). */
  M_ST_TA_1(6, true),
  /** Bitstring of 32 bits (M_BO_NA_1). */
  M_BO_NA_1(7, true),
  /** Bitstring of 32 bits with time tag CP24Time2a (M_BO_TA_1). */
  M_BO_TA_1(8, true),
  /** Measured value, normalized value (M_ME_NA_1). */
  M_ME_NA_1(9, true),
  /** Measured value, normalized value with time tag CP24Time2a (M_ME_TA_1). */
  M_ME_TA_1(10, true),
  /** Measured value, scaled value (M_ME_NB_1). */
  M_ME_NB_1(11, true),
  /** Measured value, scaled value with time tag CP24Time2a (M_ME_TB_1). */
  M_ME_TB_1(12, true),
  /** Measured value, short floating point number (M_ME_NC_1). */
  M_ME_NC_1(13, true),
  /** Measured value, short floating point number with time tag CP24Time2a (M_ME_TC_1). */
  M_ME_TC_1(14, true),
  /** Integrated totals (M_IT_NA_1). */
  M_IT_NA_1(15, true),
  /** Integrated totals with time tag CP24Time2a (M_IT_TA_1). */
  M_IT_TA_1(16, true),
  /** Event of protection equipment with time tag CP24Time2a (M_EP_TA_1). */
  M_EP_TA_1(17, true),
  /** Packed start events of protection equipment with time tag CP24Time2a (M_EP_TB_1). */
  M_EP_TB_1(18, true),
  /**
   * Packed output circuit information of protection equipment with time tag CP24Time2a (M_EP_TC_1).
   */
  M_EP_TC_1(19, true),
  /** Packed single-point information with status change detection (M_PS_NA_1). */
  M_PS_NA_1(20, true),
  /** Measured value, normalized value without quality descriptor (M_ME_ND_1). */
  M_ME_ND_1(21, true),
  /** Single-point information with time tag CP56Time2a (M_SP_TB_1). */
  M_SP_TB_1(30, true),
  /** Double-point information with time tag CP56Time2a (M_DP_TB_1). */
  M_DP_TB_1(31, true),
  /** Step position information with time tag CP56Time2a (M_ST_TB_1). */
  M_ST_TB_1(32, true),
  /** Bitstring of 32 bits with time tag CP56Time2a (M_BO_TB_1). */
  M_BO_TB_1(33, true),
  /** Measured value, normalized value with time tag CP56Time2a (M_ME_TD_1). */
  M_ME_TD_1(34, true),
  /** Measured value, scaled value with time tag CP56Time2a (M_ME_TE_1). */
  M_ME_TE_1(35, true),
  /** Measured value, short floating point number with time tag CP56Time2a (M_ME_TF_1). */
  M_ME_TF_1(36, true),
  /** Integrated totals with time tag CP56Time2a (M_IT_TB_1). */
  M_IT_TB_1(37, true),
  /** Event of protection equipment with time tag CP56Time2a (M_EP_TD_1). */
  M_EP_TD_1(38, true),
  /** Packed start events of protection equipment with time tag CP56Time2a (M_EP_TE_1). */
  M_EP_TE_1(39, true),
  /**
   * Packed output circuit information of protection equipment with time tag CP56Time2a (M_EP_TF_1).
   */
  M_EP_TF_1(40, true),

  // Table 9 - Process information in control direction.

  /** Single command (C_SC_NA_1). */
  C_SC_NA_1(45, true),
  /** Double command (C_DC_NA_1). */
  C_DC_NA_1(46, true),
  /** Regulating step command (C_RC_NA_1). */
  C_RC_NA_1(47, true),
  /** Set point command, normalized value (C_SE_NA_1). */
  C_SE_NA_1(48, true),
  /** Set point command, scaled value (C_SE_NB_1). */
  C_SE_NB_1(49, true),
  /** Set point command, short floating point number (C_SE_NC_1). */
  C_SE_NC_1(50, true),
  /** Bitstring of 32 bits command (C_BO_NA_1). */
  C_BO_NA_1(51, true),

  // IEC 60870-5-104 clause 8 - Process information in control direction with time tag CP56Time2a.

  /** Single command with time tag CP56Time2a (C_SC_TA_1). */
  C_SC_TA_1(58, true),
  /** Double command with time tag CP56Time2a (C_DC_TA_1). */
  C_DC_TA_1(59, true),
  /** Regulating step command with time tag CP56Time2a (C_RC_TA_1). */
  C_RC_TA_1(60, true),
  /** Set point command, normalized value with time tag CP56Time2a (C_SE_TA_1). */
  C_SE_TA_1(61, true),
  /** Set point command, scaled value with time tag CP56Time2a (C_SE_TB_1). */
  C_SE_TB_1(62, true),
  /** Set point command, short floating point number with time tag CP56Time2a (C_SE_TC_1). */
  C_SE_TC_1(63, true),
  /** Bitstring of 32 bits command with time tag CP56Time2a (C_BO_TA_1). */
  C_BO_TA_1(64, true),

  // Table 10 - System information in monitor direction.

  /** End of initialization (M_EI_NA_1). */
  M_EI_NA_1(70, true),

  // Table 11 - System information in control direction.

  /** Interrogation command (C_IC_NA_1). */
  C_IC_NA_1(100, true),
  /** Counter interrogation command (C_CI_NA_1). */
  C_CI_NA_1(101, true),
  /** Read command (C_RD_NA_1). */
  C_RD_NA_1(102, true),
  /** Clock synchronization command (C_CS_NA_1). */
  C_CS_NA_1(103, true),
  /** Test command (C_TS_NA_1). */
  C_TS_NA_1(104, true),
  /** Reset process command (C_RP_NA_1). */
  C_RP_NA_1(105, true),
  /** Delay acquisition command (C_CD_NA_1). */
  C_CD_NA_1(106, true),
  /** Test command with time tag CP56Time2a (C_TS_TA_1). */
  C_TS_TA_1(107, true),

  // Table 12 - Parameter in control direction.

  /** Parameter of measured value, normalized value (P_ME_NA_1). */
  P_ME_NA_1(110, true),
  /** Parameter of measured value, scaled value (P_ME_NB_1). */
  P_ME_NB_1(111, true),
  /** Parameter of measured value, short floating point number (P_ME_NC_1). */
  P_ME_NC_1(112, true),
  /** Parameter activation (P_AC_NA_1). */
  P_AC_NA_1(113, true),

  // Table 13 - File transfer (out of scope; raw-layer only).

  /** File ready (F_FR_NA_1); out of scope, no typed record. */
  F_FR_NA_1(120, false),
  /** Section ready (F_SR_NA_1); out of scope, no typed record. */
  F_SR_NA_1(121, false),
  /**
   * Call directory, select file, call file, call section (F_SC_NA_1); out of scope, no typed
   * record.
   */
  F_SC_NA_1(122, false),
  /** Last section, last segment (F_LS_NA_1); out of scope, no typed record. */
  F_LS_NA_1(123, false),
  /** Acknowledge file, acknowledge section (F_AF_NA_1); out of scope, no typed record. */
  F_AF_NA_1(124, false),
  /** Segment (F_SG_NA_1); out of scope, no typed record. */
  F_SG_NA_1(125, false),
  /** Directory (F_DR_TA_1); out of scope, no typed record. */
  F_DR_TA_1(126, false),
  /** Call/select/call section with time (F_SC_NB_1); out of scope, no typed record. */
  F_SC_NB_1(127, false);

  private final int typeId;
  private final boolean supported;

  AsduType(int typeId, boolean supported) {
    this.typeId = typeId;
    this.supported = supported;
  }

  /**
   * Returns the numeric type identification carried in octet 1 of the ASDU.
   *
   * @return the type identification value (1..127).
   */
  public int typeId() {
    return typeId;
  }

  /**
   * Indicates whether the library provides a typed information object record for this type.
   *
   * <p>When {@code false} the type is still representable as a raw ASDU and reachable through the
   * codec extension points, but no dedicated {@code .asdu.object} record exists for it.
   *
   * @return {@code true} if a typed record exists for this type; {@code false} otherwise.
   */
  public boolean supported() {
    return supported;
  }

  /**
   * Returns the {@link AsduType} for the given numeric type identification.
   *
   * @param typeId the type identification value read from octet 1 of an ASDU.
   * @return the matching {@link AsduType}.
   * @throws UnsupportedAsduTypeException if no defined type identification matches {@code typeId}.
   */
  public static AsduType fromId(int typeId) {
    for (AsduType type : values()) {
      if (type.typeId == typeId) {
        return type;
      }
    }
    throw new UnsupportedAsduTypeException("undefined type identification: " + typeId);
  }
}
