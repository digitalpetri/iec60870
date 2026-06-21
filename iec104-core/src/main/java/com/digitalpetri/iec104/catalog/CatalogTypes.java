package com.digitalpetri.iec104.catalog;

import com.digitalpetri.iec104.asdu.AsduType;
import com.digitalpetri.iec104.point.PointType;
import java.util.Optional;

/**
 * Internal helpers shared by the catalog types, in particular the mapping from an observed monitor
 * {@link AsduType} to a logical {@link PointType}.
 */
final class CatalogTypes {

  private CatalogTypes() {}

  /**
   * Maps a monitor type identification to its logical point type.
   *
   * @param type the observed type identification.
   * @return the logical point type, or {@link Optional#empty()} if {@code type} is not a recognized
   *     monitor type that maps onto the point model.
   */
  static Optional<PointType> pointTypeOf(AsduType type) {
    return switch (type) {
      case M_SP_NA_1, M_SP_TA_1, M_SP_TB_1 -> Optional.of(PointType.SINGLE_POINT);
      case M_DP_NA_1, M_DP_TA_1, M_DP_TB_1 -> Optional.of(PointType.DOUBLE_POINT);
      case M_ST_NA_1, M_ST_TA_1, M_ST_TB_1 -> Optional.of(PointType.STEP_POSITION);
      case M_BO_NA_1, M_BO_TA_1, M_BO_TB_1 -> Optional.of(PointType.BITSTRING32);
      case M_ME_NA_1, M_ME_TA_1, M_ME_TD_1, M_ME_ND_1 -> Optional.of(PointType.NORMALIZED);
      case M_ME_NB_1, M_ME_TB_1, M_ME_TE_1 -> Optional.of(PointType.SCALED);
      case M_ME_NC_1, M_ME_TC_1, M_ME_TF_1 -> Optional.of(PointType.SHORT_FLOAT);
      case M_IT_NA_1, M_IT_TA_1, M_IT_TB_1 -> Optional.of(PointType.INTEGRATED_TOTALS);
      default -> Optional.empty();
    };
  }
}
