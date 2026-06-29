package com.digitalpetri.iec60870.test.interop;

import com.digitalpetri.iec60870.address.PointAddress;
import com.digitalpetri.iec60870.asdu.Cause;
import com.digitalpetri.iec60870.asdu.element.BinaryCounterReading;
import com.digitalpetri.iec60870.asdu.element.DoublePointState;
import com.digitalpetri.iec60870.asdu.element.NormalizedValue;
import com.digitalpetri.iec60870.asdu.element.Vti;
import com.digitalpetri.iec60870.asdu.object.SingleCommand;
import com.digitalpetri.iec60870.point.PointCapability;
import com.digitalpetri.iec60870.point.PointType;
import com.digitalpetri.iec60870.point.PointValue;
import com.digitalpetri.iec60870.server.CommandDecision;
import com.digitalpetri.iec60870.server.CommandRequest;
import com.digitalpetri.iec60870.server.PointDefinition;
import com.digitalpetri.iec60870.server.ServerContext;
import com.digitalpetri.iec60870.server.ServerHandler;
import com.digitalpetri.iec60870.server.Station;

/** Test-only station and handler shared by server-side interop scenarios. */
final class InteropServerFixture {

  private static final short SCALED_VALUE = 12345;
  private static final int COUNTER_VALUE = 1000;

  private InteropServerFixture() {}

  static Station buildStation() {
    return Station.builder(InteropClientContract.STATION)
        .point(
            PointDefinition.of(
                PointAddress.of(1, InteropClientContract.IOA_SINGLE),
                PointType.SINGLE_POINT,
                PointValue.single(true),
                PointCapability.REPORTED,
                PointCapability.READABLE,
                PointCapability.COMMANDABLE))
        .point(
            PointDefinition.of(
                PointAddress.of(1, InteropClientContract.IOA_DOUBLE),
                PointType.DOUBLE_POINT,
                PointValue.doublePoint(DoublePointState.ON),
                PointCapability.REPORTED,
                PointCapability.READABLE))
        .point(
            PointDefinition.of(
                PointAddress.of(1, InteropClientContract.IOA_STEP),
                PointType.STEP_POSITION,
                PointValue.stepPosition(new Vti(7, false)),
                PointCapability.REPORTED,
                PointCapability.READABLE))
        .point(
            PointDefinition.of(
                PointAddress.of(1, InteropClientContract.IOA_BITS),
                PointType.BITSTRING32,
                PointValue.bitstring(0x12345678),
                PointCapability.REPORTED,
                PointCapability.READABLE))
        .point(
            PointDefinition.of(
                PointAddress.of(1, InteropClientContract.IOA_NORM),
                PointType.NORMALIZED,
                PointValue.normalized(NormalizedValue.of(0.5)),
                PointCapability.REPORTED,
                PointCapability.READABLE))
        .point(
            PointDefinition.of(
                PointAddress.of(1, InteropClientContract.IOA_SCALED),
                PointType.SCALED,
                PointValue.scaled(SCALED_VALUE),
                PointCapability.REPORTED,
                PointCapability.READABLE))
        .point(
            PointDefinition.of(
                PointAddress.of(1, InteropClientContract.IOA_SHORT),
                PointType.SHORT_FLOAT,
                PointValue.shortFloat(3.14159f),
                PointCapability.REPORTED,
                PointCapability.READABLE,
                PointCapability.COMMANDABLE))
        .point(
            PointDefinition.of(
                PointAddress.of(1, InteropClientContract.IOA_COUNTER),
                PointType.INTEGRATED_TOTALS,
                PointValue.counter(new BinaryCounterReading(COUNTER_VALUE, 0, false, false, false)),
                PointCapability.REPORTED))
        .point(
            PointDefinition.of(
                PointAddress.of(1, InteropClientContract.IOA_COUNTER_T),
                PointType.INTEGRATED_TOTALS,
                PointValue.counter(new BinaryCounterReading(COUNTER_VALUE, 0, false, false, false)),
                PointCapability.REPORTED))
        .group(1, PointAddress.of(1, InteropClientContract.IOA_SINGLE))
        .group(1, PointAddress.of(1, InteropClientContract.IOA_DOUBLE))
        .group(1, PointAddress.of(1, InteropClientContract.IOA_STEP))
        .group(1, PointAddress.of(1, InteropClientContract.IOA_BITS))
        .group(1, PointAddress.of(1, InteropClientContract.IOA_NORM))
        .group(1, PointAddress.of(1, InteropClientContract.IOA_SCALED))
        .group(1, PointAddress.of(1, InteropClientContract.IOA_SHORT))
        .build();
  }

  static ServerHandler acceptRejectHandler() {
    return new AcceptRejectHandler();
  }

  static Thread daemon(Runnable runnable) {
    Thread thread = new Thread(runnable, "interop-periodic");
    thread.setDaemon(true);
    return thread;
  }

  private static final class AcceptRejectHandler implements ServerHandler {

    @Override
    public CommandDecision onCommand(ServerContext context, CommandRequest request) {
      long ioa = request.target().objectAddress().value().longValue();

      if (ioa == InteropClientContract.IOA_REJECT) {
        return CommandDecision.reject(Cause.ACTIVATION_CONFIRMATION);
      }
      if (ioa < InteropClientContract.IOA_ACCEPT || ioa > 2999) {
        return CommandDecision.reject(Cause.UNKNOWN_INFORMATION_OBJECT_ADDRESS);
      }
      if (request.commandObject() instanceof SingleCommand single) {
        return CommandDecision.acceptAndUpdate(PointValue.single(single.on()));
      }
      return CommandDecision.accept();
    }
  }
}
