package com.digitalpetri.iec60870.asdu.object;

import static org.junit.jupiter.api.Assertions.assertThrows;

import com.digitalpetri.iec60870.address.InformationObjectAddress;
import com.digitalpetri.iec60870.asdu.element.DoubleCommandState;
import com.digitalpetri.iec60870.asdu.element.NormalizedValue;
import com.digitalpetri.iec60870.asdu.element.QualifierOfCommand;
import com.digitalpetri.iec60870.asdu.element.QualifierOfSetpoint;
import com.digitalpetri.iec60870.asdu.element.StepCommandState;
import com.digitalpetri.iec60870.asdu.time.Cp56Time2a;
import org.junit.jupiter.api.Test;

/**
 * Verifies that the compact constructors of the command/setpoint information-object records reject
 * null reference components with a {@link NullPointerException}.
 */
class CommandRecordNullChecksTest {

  private static final InformationObjectAddress ADDRESS = InformationObjectAddress.of(0x010203);
  private static final QualifierOfCommand QOC = new QualifierOfCommand(1, true);
  private static final QualifierOfSetpoint QOS = new QualifierOfSetpoint(1, true);
  private static final NormalizedValue NVA = NormalizedValue.of(0.5);
  private static final Cp56Time2a TIME =
      new Cp56Time2a(45500, 30, 14, 20, 6, 6, 26, false, false, true);

  @Test
  void singleCommandRejectsNullComponents() {
    // noinspection DataFlowIssue - deliberate null passed to verify the record rejects it
    assertThrows(NullPointerException.class, () -> new SingleCommand(null, true, QOC));
    // noinspection DataFlowIssue - deliberate null passed to verify the record rejects it
    assertThrows(NullPointerException.class, () -> new SingleCommand(ADDRESS, true, null));
  }

  @Test
  void singleCommandWithCp56TimeRejectsNullComponents() {
    // noinspection DataFlowIssue - deliberate null passed to verify the record rejects it
    assertThrows(
        NullPointerException.class, () -> new SingleCommandWithCp56Time(null, true, QOC, TIME));
    // noinspection DataFlowIssue - deliberate null passed to verify the record rejects it
    assertThrows(
        NullPointerException.class, () -> new SingleCommandWithCp56Time(ADDRESS, true, null, TIME));
    // noinspection DataFlowIssue - deliberate null passed to verify the record rejects it
    assertThrows(
        NullPointerException.class, () -> new SingleCommandWithCp56Time(ADDRESS, true, QOC, null));
  }

  @Test
  void doubleCommandWithCp56TimeRejectsNullComponents() {
    // noinspection DataFlowIssue - deliberate null passed to verify the record rejects it
    assertThrows(
        NullPointerException.class,
        () -> new DoubleCommandWithCp56Time(null, DoubleCommandState.ON, QOC, TIME));
    // noinspection DataFlowIssue - deliberate null passed to verify the record rejects it
    assertThrows(
        NullPointerException.class, () -> new DoubleCommandWithCp56Time(ADDRESS, null, QOC, TIME));
    // noinspection DataFlowIssue - deliberate null passed to verify the record rejects it
    assertThrows(
        NullPointerException.class,
        () -> new DoubleCommandWithCp56Time(ADDRESS, DoubleCommandState.ON, null, TIME));
    // noinspection DataFlowIssue - deliberate null passed to verify the record rejects it
    assertThrows(
        NullPointerException.class,
        () -> new DoubleCommandWithCp56Time(ADDRESS, DoubleCommandState.ON, QOC, null));
  }

  @Test
  void regulatingStepCommandWithCp56TimeRejectsNullComponents() {
    // noinspection DataFlowIssue - deliberate null passed to verify the record rejects it
    assertThrows(
        NullPointerException.class,
        () ->
            new RegulatingStepCommandWithCp56Time(
                null, StepCommandState.NEXT_STEP_HIGHER, QOC, TIME));
    // noinspection DataFlowIssue - deliberate null passed to verify the record rejects it
    assertThrows(
        NullPointerException.class,
        () -> new RegulatingStepCommandWithCp56Time(ADDRESS, null, QOC, TIME));
    // noinspection DataFlowIssue - deliberate null passed to verify the record rejects it
    assertThrows(
        NullPointerException.class,
        () ->
            new RegulatingStepCommandWithCp56Time(
                ADDRESS, StepCommandState.NEXT_STEP_HIGHER, null, TIME));
    // noinspection DataFlowIssue - deliberate null passed to verify the record rejects it
    assertThrows(
        NullPointerException.class,
        () ->
            new RegulatingStepCommandWithCp56Time(
                ADDRESS, StepCommandState.NEXT_STEP_HIGHER, QOC, null));
  }

  @Test
  void setpointNormalizedWithCp56TimeRejectsNullComponents() {
    // noinspection DataFlowIssue - deliberate null passed to verify the record rejects it
    assertThrows(
        NullPointerException.class, () -> new SetpointNormalizedWithCp56Time(null, NVA, QOS, TIME));
    // noinspection DataFlowIssue - deliberate null passed to verify the record rejects it
    assertThrows(
        NullPointerException.class,
        () -> new SetpointNormalizedWithCp56Time(ADDRESS, null, QOS, TIME));
    // noinspection DataFlowIssue - deliberate null passed to verify the record rejects it
    assertThrows(
        NullPointerException.class,
        () -> new SetpointNormalizedWithCp56Time(ADDRESS, NVA, null, TIME));
    // noinspection DataFlowIssue - deliberate null passed to verify the record rejects it
    assertThrows(
        NullPointerException.class,
        () -> new SetpointNormalizedWithCp56Time(ADDRESS, NVA, QOS, null));
  }

  @Test
  void setpointShortFloatWithCp56TimeRejectsNullComponents() {
    // noinspection DataFlowIssue - deliberate null passed to verify the record rejects it
    assertThrows(
        NullPointerException.class,
        () -> new SetpointShortFloatWithCp56Time(null, 1.0f, QOS, TIME));
    // noinspection DataFlowIssue - deliberate null passed to verify the record rejects it
    assertThrows(
        NullPointerException.class,
        () -> new SetpointShortFloatWithCp56Time(ADDRESS, 1.0f, null, TIME));
    // noinspection DataFlowIssue - deliberate null passed to verify the record rejects it
    assertThrows(
        NullPointerException.class,
        () -> new SetpointShortFloatWithCp56Time(ADDRESS, 1.0f, QOS, null));
  }

  @Test
  void bitstring32CommandWithCp56TimeRejectsNullComponents() {
    // noinspection DataFlowIssue - deliberate null passed to verify the record rejects it
    assertThrows(
        NullPointerException.class,
        () -> new Bitstring32CommandWithCp56Time(null, 0x12345678, TIME));
    // noinspection DataFlowIssue - deliberate null passed to verify the record rejects it
    assertThrows(
        NullPointerException.class,
        () -> new Bitstring32CommandWithCp56Time(ADDRESS, 0x12345678, null));
  }
}
