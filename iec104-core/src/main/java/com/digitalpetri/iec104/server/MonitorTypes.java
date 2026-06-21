package com.digitalpetri.iec104.server;

import com.digitalpetri.iec104.asdu.AsduType;
import com.digitalpetri.iec104.asdu.InformationObject;
import com.digitalpetri.iec104.asdu.object.Bitstring32;
import com.digitalpetri.iec104.asdu.object.Bitstring32WithCp24Time;
import com.digitalpetri.iec104.asdu.object.Bitstring32WithCp56Time;
import com.digitalpetri.iec104.asdu.object.DoublePointInformation;
import com.digitalpetri.iec104.asdu.object.DoublePointWithCp24Time;
import com.digitalpetri.iec104.asdu.object.DoublePointWithCp56Time;
import com.digitalpetri.iec104.asdu.object.IntegratedTotals;
import com.digitalpetri.iec104.asdu.object.IntegratedTotalsWithCp24Time;
import com.digitalpetri.iec104.asdu.object.IntegratedTotalsWithCp56Time;
import com.digitalpetri.iec104.asdu.object.MeasuredValueNormalized;
import com.digitalpetri.iec104.asdu.object.MeasuredValueNormalizedNoQuality;
import com.digitalpetri.iec104.asdu.object.MeasuredValueNormalizedWithCp24Time;
import com.digitalpetri.iec104.asdu.object.MeasuredValueNormalizedWithCp56Time;
import com.digitalpetri.iec104.asdu.object.MeasuredValueScaled;
import com.digitalpetri.iec104.asdu.object.MeasuredValueScaledWithCp24Time;
import com.digitalpetri.iec104.asdu.object.MeasuredValueScaledWithCp56Time;
import com.digitalpetri.iec104.asdu.object.MeasuredValueShortFloat;
import com.digitalpetri.iec104.asdu.object.MeasuredValueShortFloatWithCp24Time;
import com.digitalpetri.iec104.asdu.object.MeasuredValueShortFloatWithCp56Time;
import com.digitalpetri.iec104.asdu.object.SinglePointInformation;
import com.digitalpetri.iec104.asdu.object.SinglePointWithCp24Time;
import com.digitalpetri.iec104.asdu.object.SinglePointWithCp56Time;
import com.digitalpetri.iec104.asdu.object.StepPositionInformation;
import com.digitalpetri.iec104.asdu.object.StepPositionWithCp24Time;
import com.digitalpetri.iec104.asdu.object.StepPositionWithCp56Time;
import com.digitalpetri.iec104.point.PointType;
import com.digitalpetri.iec104.point.TimeTagStyle;

/**
 * Maps logical monitor descriptions onto the concrete monitor type identifications the server
 * emits.
 *
 * <p>{@link #of(PointType, TimeTagStyle)} resolves the monitor {@link AsduType} that carries a
 * given {@link PointType} in a given {@link TimeTagStyle}, the counterpart of {@link
 * com.digitalpetri.iec104.point.MonitorMapping#toMonitorObject MonitorMapping.toMonitorObject}
 * which builds the matching record. {@link #styleOf(InformationObject)} recovers the time-tag style
 * a concrete monitor record uses, so a handler-supplied object is wrapped in the right type
 * identification.
 */
final class MonitorTypes {

  private MonitorTypes() {}

  /**
   * Returns the monitor type identification that carries the given point type in the given time-tag
   * style.
   *
   * @param type the logical point type.
   * @param style the time-tag style.
   * @return the matching monitor ASDU type.
   */
  static AsduType of(PointType type, TimeTagStyle style) {
    return switch (type) {
      case SINGLE_POINT ->
          switch (style) {
            case NONE -> AsduType.M_SP_NA_1;
            case CP24 -> AsduType.M_SP_TA_1;
            case CP56 -> AsduType.M_SP_TB_1;
          };
      case DOUBLE_POINT ->
          switch (style) {
            case NONE -> AsduType.M_DP_NA_1;
            case CP24 -> AsduType.M_DP_TA_1;
            case CP56 -> AsduType.M_DP_TB_1;
          };
      case STEP_POSITION ->
          switch (style) {
            case NONE -> AsduType.M_ST_NA_1;
            case CP24 -> AsduType.M_ST_TA_1;
            case CP56 -> AsduType.M_ST_TB_1;
          };
      case BITSTRING32 ->
          switch (style) {
            case NONE -> AsduType.M_BO_NA_1;
            case CP24 -> AsduType.M_BO_TA_1;
            case CP56 -> AsduType.M_BO_TB_1;
          };
      case NORMALIZED ->
          switch (style) {
            case NONE -> AsduType.M_ME_NA_1;
            case CP24 -> AsduType.M_ME_TA_1;
            case CP56 -> AsduType.M_ME_TD_1;
          };
      case SCALED ->
          switch (style) {
            case NONE -> AsduType.M_ME_NB_1;
            case CP24 -> AsduType.M_ME_TB_1;
            case CP56 -> AsduType.M_ME_TE_1;
          };
      case SHORT_FLOAT ->
          switch (style) {
            case NONE -> AsduType.M_ME_NC_1;
            case CP24 -> AsduType.M_ME_TC_1;
            case CP56 -> AsduType.M_ME_TF_1;
          };
      case INTEGRATED_TOTALS ->
          switch (style) {
            case NONE -> AsduType.M_IT_NA_1;
            case CP24 -> AsduType.M_IT_TA_1;
            case CP56 -> AsduType.M_IT_TB_1;
          };
    };
  }

  /**
   * Returns the time-tag style of a concrete monitor information object record.
   *
   * @param object the monitor information object.
   * @return the time-tag style of {@code object}; {@link TimeTagStyle#NONE} for untimed and for
   *     records this helper does not recognize.
   */
  static TimeTagStyle styleOf(InformationObject object) {
    if (object instanceof SinglePointWithCp56Time
        || object instanceof DoublePointWithCp56Time
        || object instanceof StepPositionWithCp56Time
        || object instanceof Bitstring32WithCp56Time
        || object instanceof MeasuredValueNormalizedWithCp56Time
        || object instanceof MeasuredValueScaledWithCp56Time
        || object instanceof MeasuredValueShortFloatWithCp56Time
        || object instanceof IntegratedTotalsWithCp56Time) {
      return TimeTagStyle.CP56;
    }
    if (object instanceof SinglePointWithCp24Time
        || object instanceof DoublePointWithCp24Time
        || object instanceof StepPositionWithCp24Time
        || object instanceof Bitstring32WithCp24Time
        || object instanceof MeasuredValueNormalizedWithCp24Time
        || object instanceof MeasuredValueScaledWithCp24Time
        || object instanceof MeasuredValueShortFloatWithCp24Time
        || object instanceof IntegratedTotalsWithCp24Time) {
      return TimeTagStyle.CP24;
    }
    // Untimed monitor records, the no-quality normalized variant, and anything else.
    if (object instanceof SinglePointInformation
        || object instanceof DoublePointInformation
        || object instanceof StepPositionInformation
        || object instanceof Bitstring32
        || object instanceof MeasuredValueNormalized
        || object instanceof MeasuredValueNormalizedNoQuality
        || object instanceof MeasuredValueScaled
        || object instanceof MeasuredValueShortFloat
        || object instanceof IntegratedTotals) {
      return TimeTagStyle.NONE;
    }
    return TimeTagStyle.NONE;
  }
}
