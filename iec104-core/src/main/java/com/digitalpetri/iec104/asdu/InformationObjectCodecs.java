package com.digitalpetri.iec104.asdu;

import com.digitalpetri.iec104.UnsupportedAsduTypeException;
import com.digitalpetri.iec104.address.InformationObjectAddress;
import com.digitalpetri.iec104.asdu.object.Bitstring32;
import com.digitalpetri.iec104.asdu.object.Bitstring32Command;
import com.digitalpetri.iec104.asdu.object.Bitstring32CommandWithCp56Time;
import com.digitalpetri.iec104.asdu.object.Bitstring32WithCp24Time;
import com.digitalpetri.iec104.asdu.object.Bitstring32WithCp56Time;
import com.digitalpetri.iec104.asdu.object.ClockSynchronizationCommand;
import com.digitalpetri.iec104.asdu.object.CounterInterrogationCommand;
import com.digitalpetri.iec104.asdu.object.DelayAcquisitionCommand;
import com.digitalpetri.iec104.asdu.object.DoubleCommand;
import com.digitalpetri.iec104.asdu.object.DoubleCommandWithCp56Time;
import com.digitalpetri.iec104.asdu.object.DoublePointInformation;
import com.digitalpetri.iec104.asdu.object.DoublePointWithCp24Time;
import com.digitalpetri.iec104.asdu.object.DoublePointWithCp56Time;
import com.digitalpetri.iec104.asdu.object.EndOfInitialization;
import com.digitalpetri.iec104.asdu.object.EventOfProtectionEquipment;
import com.digitalpetri.iec104.asdu.object.EventOfProtectionEquipmentWithCp56Time;
import com.digitalpetri.iec104.asdu.object.IntegratedTotals;
import com.digitalpetri.iec104.asdu.object.IntegratedTotalsWithCp24Time;
import com.digitalpetri.iec104.asdu.object.IntegratedTotalsWithCp56Time;
import com.digitalpetri.iec104.asdu.object.InterrogationCommand;
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
import com.digitalpetri.iec104.asdu.object.PackedOutputCircuitInfo;
import com.digitalpetri.iec104.asdu.object.PackedOutputCircuitInfoWithCp56Time;
import com.digitalpetri.iec104.asdu.object.PackedSinglePointWithStatusChange;
import com.digitalpetri.iec104.asdu.object.PackedStartEventsOfProtection;
import com.digitalpetri.iec104.asdu.object.PackedStartEventsOfProtectionWithCp56Time;
import com.digitalpetri.iec104.asdu.object.ParameterActivation;
import com.digitalpetri.iec104.asdu.object.ParameterNormalized;
import com.digitalpetri.iec104.asdu.object.ParameterScaled;
import com.digitalpetri.iec104.asdu.object.ParameterShortFloat;
import com.digitalpetri.iec104.asdu.object.ReadCommand;
import com.digitalpetri.iec104.asdu.object.RegulatingStepCommand;
import com.digitalpetri.iec104.asdu.object.RegulatingStepCommandWithCp56Time;
import com.digitalpetri.iec104.asdu.object.ResetProcessCommand;
import com.digitalpetri.iec104.asdu.object.SetpointNormalized;
import com.digitalpetri.iec104.asdu.object.SetpointNormalizedWithCp56Time;
import com.digitalpetri.iec104.asdu.object.SetpointScaled;
import com.digitalpetri.iec104.asdu.object.SetpointScaledWithCp56Time;
import com.digitalpetri.iec104.asdu.object.SetpointShortFloat;
import com.digitalpetri.iec104.asdu.object.SetpointShortFloatWithCp56Time;
import com.digitalpetri.iec104.asdu.object.SingleCommand;
import com.digitalpetri.iec104.asdu.object.SingleCommandWithCp56Time;
import com.digitalpetri.iec104.asdu.object.SinglePointInformation;
import com.digitalpetri.iec104.asdu.object.SinglePointWithCp24Time;
import com.digitalpetri.iec104.asdu.object.SinglePointWithCp56Time;
import com.digitalpetri.iec104.asdu.object.StepPositionInformation;
import com.digitalpetri.iec104.asdu.object.StepPositionWithCp24Time;
import com.digitalpetri.iec104.asdu.object.StepPositionWithCp56Time;
import com.digitalpetri.iec104.asdu.object.TestCommand;
import com.digitalpetri.iec104.asdu.object.TestCommandWithCp56Time;
import io.netty.buffer.ByteBuf;
import java.util.EnumMap;
import java.util.Map;

/**
 * The standard {@link InformationObjectCodecRegistry}, mapping every {@linkplain
 * AsduType#supported() supported} type identification to a codec that delegates to that type's
 * record {@code Serde}.
 *
 * <p>Each registered {@link InformationObjectCodec} forwards {@link
 * InformationObjectCodec#encodeElements(InformationObject, ByteBuf)} to the record's {@code
 * Serde.encode(record, buffer)} and {@link InformationObjectCodec#decode(InformationObjectAddress,
 * ByteBuf)} to the record's {@code Serde.decode(address, buffer)}. The information object address
 * is framed centrally by {@link Asdu.Serde}, so the codecs operate only on the elements that follow
 * it.
 *
 * <p>The {@linkplain AsduType#supported() unsupported} file-transfer types, and any type with no
 * registered codec, raise {@link UnsupportedAsduTypeException} from {@link #codecFor(AsduType)}.
 */
public final class InformationObjectCodecs implements InformationObjectCodecRegistry {

  /** The eagerly-built shared standard registry. */
  private static final InformationObjectCodecs STANDARD = new InformationObjectCodecs();

  /** The per-type codecs, keyed by type identification. */
  private final Map<AsduType, InformationObjectCodec<? extends InformationObject>> codecs;

  /** Builds the standard registry, populating the codec table once. */
  private InformationObjectCodecs() {
    Map<AsduType, InformationObjectCodec<? extends InformationObject>> map =
        new EnumMap<>(AsduType.class);

    // Table 8 - Process information in monitor direction.
    map.put(
        AsduType.M_SP_NA_1,
        codec(SinglePointInformation.Serde::encode, SinglePointInformation.Serde::decode));
    map.put(
        AsduType.M_SP_TA_1,
        codec(SinglePointWithCp24Time.Serde::encode, SinglePointWithCp24Time.Serde::decode));
    map.put(
        AsduType.M_DP_NA_1,
        codec(DoublePointInformation.Serde::encode, DoublePointInformation.Serde::decode));
    map.put(
        AsduType.M_DP_TA_1,
        codec(DoublePointWithCp24Time.Serde::encode, DoublePointWithCp24Time.Serde::decode));
    map.put(
        AsduType.M_ST_NA_1,
        codec(StepPositionInformation.Serde::encode, StepPositionInformation.Serde::decode));
    map.put(
        AsduType.M_ST_TA_1,
        codec(StepPositionWithCp24Time.Serde::encode, StepPositionWithCp24Time.Serde::decode));
    map.put(AsduType.M_BO_NA_1, codec(Bitstring32.Serde::encode, Bitstring32.Serde::decode));
    map.put(
        AsduType.M_BO_TA_1,
        codec(Bitstring32WithCp24Time.Serde::encode, Bitstring32WithCp24Time.Serde::decode));
    map.put(
        AsduType.M_ME_NA_1,
        codec(MeasuredValueNormalized.Serde::encode, MeasuredValueNormalized.Serde::decode));
    map.put(
        AsduType.M_ME_TA_1,
        codec(
            MeasuredValueNormalizedWithCp24Time.Serde::encode,
            MeasuredValueNormalizedWithCp24Time.Serde::decode));
    map.put(
        AsduType.M_ME_NB_1,
        codec(MeasuredValueScaled.Serde::encode, MeasuredValueScaled.Serde::decode));
    map.put(
        AsduType.M_ME_TB_1,
        codec(
            MeasuredValueScaledWithCp24Time.Serde::encode,
            MeasuredValueScaledWithCp24Time.Serde::decode));
    map.put(
        AsduType.M_ME_NC_1,
        codec(MeasuredValueShortFloat.Serde::encode, MeasuredValueShortFloat.Serde::decode));
    map.put(
        AsduType.M_ME_TC_1,
        codec(
            MeasuredValueShortFloatWithCp24Time.Serde::encode,
            MeasuredValueShortFloatWithCp24Time.Serde::decode));
    map.put(
        AsduType.M_IT_NA_1, codec(IntegratedTotals.Serde::encode, IntegratedTotals.Serde::decode));
    map.put(
        AsduType.M_IT_TA_1,
        codec(
            IntegratedTotalsWithCp24Time.Serde::encode,
            IntegratedTotalsWithCp24Time.Serde::decode));
    map.put(
        AsduType.M_EP_TA_1,
        codec(EventOfProtectionEquipment.Serde::encode, EventOfProtectionEquipment.Serde::decode));
    map.put(
        AsduType.M_EP_TB_1,
        codec(
            PackedStartEventsOfProtection.Serde::encode,
            PackedStartEventsOfProtection.Serde::decode));
    map.put(
        AsduType.M_EP_TC_1,
        codec(PackedOutputCircuitInfo.Serde::encode, PackedOutputCircuitInfo.Serde::decode));
    map.put(
        AsduType.M_PS_NA_1,
        codec(
            PackedSinglePointWithStatusChange.Serde::encode,
            PackedSinglePointWithStatusChange.Serde::decode));
    map.put(
        AsduType.M_ME_ND_1,
        codec(
            MeasuredValueNormalizedNoQuality.Serde::encode,
            MeasuredValueNormalizedNoQuality.Serde::decode));
    map.put(
        AsduType.M_SP_TB_1,
        codec(SinglePointWithCp56Time.Serde::encode, SinglePointWithCp56Time.Serde::decode));
    map.put(
        AsduType.M_DP_TB_1,
        codec(DoublePointWithCp56Time.Serde::encode, DoublePointWithCp56Time.Serde::decode));
    map.put(
        AsduType.M_ST_TB_1,
        codec(StepPositionWithCp56Time.Serde::encode, StepPositionWithCp56Time.Serde::decode));
    map.put(
        AsduType.M_BO_TB_1,
        codec(Bitstring32WithCp56Time.Serde::encode, Bitstring32WithCp56Time.Serde::decode));
    map.put(
        AsduType.M_ME_TD_1,
        codec(
            MeasuredValueNormalizedWithCp56Time.Serde::encode,
            MeasuredValueNormalizedWithCp56Time.Serde::decode));
    map.put(
        AsduType.M_ME_TE_1,
        codec(
            MeasuredValueScaledWithCp56Time.Serde::encode,
            MeasuredValueScaledWithCp56Time.Serde::decode));
    map.put(
        AsduType.M_ME_TF_1,
        codec(
            MeasuredValueShortFloatWithCp56Time.Serde::encode,
            MeasuredValueShortFloatWithCp56Time.Serde::decode));
    map.put(
        AsduType.M_IT_TB_1,
        codec(
            IntegratedTotalsWithCp56Time.Serde::encode,
            IntegratedTotalsWithCp56Time.Serde::decode));
    map.put(
        AsduType.M_EP_TD_1,
        codec(
            EventOfProtectionEquipmentWithCp56Time.Serde::encode,
            EventOfProtectionEquipmentWithCp56Time.Serde::decode));
    map.put(
        AsduType.M_EP_TE_1,
        codec(
            PackedStartEventsOfProtectionWithCp56Time.Serde::encode,
            PackedStartEventsOfProtectionWithCp56Time.Serde::decode));
    map.put(
        AsduType.M_EP_TF_1,
        codec(
            PackedOutputCircuitInfoWithCp56Time.Serde::encode,
            PackedOutputCircuitInfoWithCp56Time.Serde::decode));

    // Table 9 - Process information in control direction.
    map.put(AsduType.C_SC_NA_1, codec(SingleCommand.Serde::encode, SingleCommand.Serde::decode));
    map.put(AsduType.C_DC_NA_1, codec(DoubleCommand.Serde::encode, DoubleCommand.Serde::decode));
    map.put(
        AsduType.C_RC_NA_1,
        codec(RegulatingStepCommand.Serde::encode, RegulatingStepCommand.Serde::decode));
    map.put(
        AsduType.C_SE_NA_1,
        codec(SetpointNormalized.Serde::encode, SetpointNormalized.Serde::decode));
    map.put(AsduType.C_SE_NB_1, codec(SetpointScaled.Serde::encode, SetpointScaled.Serde::decode));
    map.put(
        AsduType.C_SE_NC_1,
        codec(SetpointShortFloat.Serde::encode, SetpointShortFloat.Serde::decode));
    map.put(
        AsduType.C_BO_NA_1,
        codec(Bitstring32Command.Serde::encode, Bitstring32Command.Serde::decode));

    // IEC 60870-5-104 clause 8 - Control direction with time tag CP56Time2a.
    map.put(
        AsduType.C_SC_TA_1,
        codec(SingleCommandWithCp56Time.Serde::encode, SingleCommandWithCp56Time.Serde::decode));
    map.put(
        AsduType.C_DC_TA_1,
        codec(DoubleCommandWithCp56Time.Serde::encode, DoubleCommandWithCp56Time.Serde::decode));
    map.put(
        AsduType.C_RC_TA_1,
        codec(
            RegulatingStepCommandWithCp56Time.Serde::encode,
            RegulatingStepCommandWithCp56Time.Serde::decode));
    map.put(
        AsduType.C_SE_TA_1,
        codec(
            SetpointNormalizedWithCp56Time.Serde::encode,
            SetpointNormalizedWithCp56Time.Serde::decode));
    map.put(
        AsduType.C_SE_TB_1,
        codec(SetpointScaledWithCp56Time.Serde::encode, SetpointScaledWithCp56Time.Serde::decode));
    map.put(
        AsduType.C_SE_TC_1,
        codec(
            SetpointShortFloatWithCp56Time.Serde::encode,
            SetpointShortFloatWithCp56Time.Serde::decode));
    map.put(
        AsduType.C_BO_TA_1,
        codec(
            Bitstring32CommandWithCp56Time.Serde::encode,
            Bitstring32CommandWithCp56Time.Serde::decode));

    // Table 10 - System information in monitor direction.
    map.put(
        AsduType.M_EI_NA_1,
        codec(EndOfInitialization.Serde::encode, EndOfInitialization.Serde::decode));

    // Table 11 - System information in control direction.
    map.put(
        AsduType.C_IC_NA_1,
        codec(InterrogationCommand.Serde::encode, InterrogationCommand.Serde::decode));
    map.put(
        AsduType.C_CI_NA_1,
        codec(
            CounterInterrogationCommand.Serde::encode, CounterInterrogationCommand.Serde::decode));
    map.put(AsduType.C_RD_NA_1, codec(ReadCommand.Serde::encode, ReadCommand.Serde::decode));
    map.put(
        AsduType.C_CS_NA_1,
        codec(
            ClockSynchronizationCommand.Serde::encode, ClockSynchronizationCommand.Serde::decode));
    map.put(AsduType.C_TS_NA_1, codec(TestCommand.Serde::encode, TestCommand.Serde::decode));
    map.put(
        AsduType.C_RP_NA_1,
        codec(ResetProcessCommand.Serde::encode, ResetProcessCommand.Serde::decode));
    map.put(
        AsduType.C_CD_NA_1,
        codec(DelayAcquisitionCommand.Serde::encode, DelayAcquisitionCommand.Serde::decode));
    map.put(
        AsduType.C_TS_TA_1,
        codec(TestCommandWithCp56Time.Serde::encode, TestCommandWithCp56Time.Serde::decode));

    // Table 12 - Parameter in control direction.
    map.put(
        AsduType.P_ME_NA_1,
        codec(ParameterNormalized.Serde::encode, ParameterNormalized.Serde::decode));
    map.put(
        AsduType.P_ME_NB_1, codec(ParameterScaled.Serde::encode, ParameterScaled.Serde::decode));
    map.put(
        AsduType.P_ME_NC_1,
        codec(ParameterShortFloat.Serde::encode, ParameterShortFloat.Serde::decode));
    map.put(
        AsduType.P_AC_NA_1,
        codec(ParameterActivation.Serde::encode, ParameterActivation.Serde::decode));

    this.codecs = map;
  }

  /**
   * Returns the shared standard registry that resolves a codec for every {@linkplain
   * AsduType#supported() supported} type identification.
   *
   * @return the singleton standard registry.
   */
  public static InformationObjectCodecRegistry standard() {
    return STANDARD;
  }

  /**
   * Returns the codec registered for {@code type}.
   *
   * @param type the ASDU type whose codec is requested.
   * @param <T> the concrete information object type produced by the returned codec.
   * @return the codec registered for {@code type}.
   * @throws UnsupportedAsduTypeException if no codec is registered for {@code type} (including the
   *     unsupported file-transfer types).
   */
  @Override
  @SuppressWarnings("unchecked")
  public <T extends InformationObject> InformationObjectCodec<T> codecFor(AsduType type) {
    InformationObjectCodec<? extends InformationObject> codec = codecs.get(type);
    if (codec == null) {
      throw new UnsupportedAsduTypeException(
          "no information object codec registered for type " + type);
    }
    return (InformationObjectCodec<T>) codec;
  }

  /**
   * Adapts a record {@code Serde}'s element encoder and decoder into an {@link
   * InformationObjectCodec}.
   *
   * @param encoder the record's {@code Serde.encode(record, buffer)} method reference.
   * @param decoder the record's {@code Serde.decode(address, buffer)} method reference.
   * @param <T> the concrete information object type handled by the codec.
   * @return a codec delegating to {@code encoder} and {@code decoder}.
   */
  private static <T extends InformationObject> InformationObjectCodec<T> codec(
      ElementEncoder<T> encoder, ElementDecoder<T> decoder) {
    return new InformationObjectCodec<>() {
      @Override
      public void encodeElements(T object, ByteBuf buffer) {
        encoder.encode(object, buffer);
      }

      @Override
      public T decode(InformationObjectAddress address, ByteBuf buffer) {
        return decoder.decode(address, buffer);
      }
    };
  }

  /**
   * Writes a typed information object's elements into a buffer, matching a record {@code
   * Serde.encode}.
   *
   * @param <T> the information object type.
   */
  @FunctionalInterface
  private interface ElementEncoder<T extends InformationObject> {
    void encode(T object, ByteBuf buffer);
  }

  /**
   * Reads a typed information object's elements from a buffer, matching a record {@code
   * Serde.decode}.
   *
   * @param <T> the information object type.
   */
  @FunctionalInterface
  private interface ElementDecoder<T extends InformationObject> {
    T decode(InformationObjectAddress address, ByteBuf buffer);
  }
}
