package com.digitalpetri.iec60870.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

import com.digitalpetri.iec60870.ProtocolProfile;
import com.digitalpetri.iec60870.address.CommonAddress;
import com.digitalpetri.iec60870.address.InformationObjectAddress;
import com.digitalpetri.iec60870.address.OriginatorAddress;
import com.digitalpetri.iec60870.address.PointAddress;
import com.digitalpetri.iec60870.asdu.Asdu;
import com.digitalpetri.iec60870.asdu.AsduType;
import com.digitalpetri.iec60870.asdu.Cause;
import com.digitalpetri.iec60870.asdu.InformationObject;
import com.digitalpetri.iec60870.asdu.element.FixedTestBitPattern;
import com.digitalpetri.iec60870.asdu.element.FreezeMode;
import com.digitalpetri.iec60870.asdu.element.QualifierOfCommand;
import com.digitalpetri.iec60870.asdu.element.QualifierOfCounterInterrogation;
import com.digitalpetri.iec60870.asdu.element.QualifierOfInterrogation;
import com.digitalpetri.iec60870.asdu.element.QualifierOfResetProcess;
import com.digitalpetri.iec60870.asdu.object.Bitstring32Command;
import com.digitalpetri.iec60870.asdu.object.ClockSynchronizationCommand;
import com.digitalpetri.iec60870.asdu.object.CounterInterrogationCommand;
import com.digitalpetri.iec60870.asdu.object.InterrogationCommand;
import com.digitalpetri.iec60870.asdu.object.ReadCommand;
import com.digitalpetri.iec60870.asdu.object.ResetProcessCommand;
import com.digitalpetri.iec60870.asdu.object.SingleCommand;
import com.digitalpetri.iec60870.asdu.object.SingleCommandWithCp56Time;
import com.digitalpetri.iec60870.asdu.object.TestCommand;
import com.digitalpetri.iec60870.asdu.object.TestCommandWithCp56Time;
import com.digitalpetri.iec60870.asdu.time.Cp56Time2a;
import com.digitalpetri.iec60870.fakes.FakeServerTransport;
import com.digitalpetri.iec60870.fakes.FakeSession;
import com.digitalpetri.iec60870.point.PointCapability;
import com.digitalpetri.iec60870.point.PointType;
import com.digitalpetri.iec60870.point.PointValue;
import com.digitalpetri.iec60870.point.TimeTagStyle;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.joou.UShort;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

class ServerControlValidationTest {

  private static final CommonAddress CA = CommonAddress.of(1);
  private static final PointAddress POINT = PointAddress.of(1, 100);
  private static final InformationObjectAddress ZERO = InformationObjectAddress.of(0);
  private static final Cp56Time2a TIME =
      Cp56Time2a.from(Instant.parse("2026-09-28T12:00:00Z"), ZoneOffset.UTC);

  @TestFactory
  Stream<DynamicTest> unsupportedCausesDoNotInvokeHandlers() {
    return controls(ZERO)
        .flatMap(
            request ->
                Stream.of(Cause.SPONTANEOUS, Cause.DEACTIVATION, Cause.ACTIVATION_CONFIRMATION)
                    .map(
                        cause ->
                            dynamicTest(
                                request.type() + "/" + cause,
                                () ->
                                    assertRejected(
                                        copy(
                                            request, cause, false, false, false, request.objects()),
                                        Cause.UNKNOWN_CAUSE))));
  }

  @TestFactory
  Stream<DynamicTest> malformedObjectCountsAndSequenceAreDiscarded() {
    return controls(ZERO)
        .flatMap(
            request ->
                Stream.of(
                        copy(request, request.cause(), false, false, false, List.of()),
                        copy(
                            request,
                            request.cause(),
                            false,
                            false,
                            false,
                            List.of(request.objects().get(0), request.objects().get(0))),
                        copy(request, request.cause(), false, false, true, request.objects()))
                    .map(
                        malformed ->
                            dynamicTest(
                                request.type()
                                    + "/count="
                                    + malformed.objects().size()
                                    + "/sequence="
                                    + malformed.sequence(),
                                () -> assertRejected(malformed, null))));
  }

  @TestFactory
  Stream<DynamicTest> negativeRequestsAreDiscarded() {
    return controls(ZERO)
        .map(
            request ->
                dynamicTest(
                    request.type().name(),
                    () ->
                        assertRejected(
                            copy(request, request.cause(), true, false, false, request.objects()),
                            null)));
  }

  @TestFactory
  Stream<DynamicTest> testRequestsCannotOperateTheLiveProcess() {
    return controls(ZERO)
        .map(
            request ->
                dynamicTest(
                    request.type().name(),
                    () ->
                        assertRejected(
                            copy(request, request.cause(), false, true, false, request.objects()),
                            request.type() == AsduType.C_RD_NA_1
                                ? null
                                : Cause.ACTIVATION_CONFIRMATION)));
  }

  @TestFactory
  Stream<DynamicTest> stationControlsRequireZeroInformationObjectAddress() {
    return controls(InformationObjectAddress.of(42))
        .filter(request -> request.type().typeId() >= 100 && request.type() != AsduType.C_RD_NA_1)
        .map(
            request ->
                dynamicTest(
                    request.type().name(),
                    () -> assertRejected(request, Cause.UNKNOWN_INFORMATION_OBJECT_ADDRESS)));
  }

  @TestFactory
  Stream<DynamicTest> validControlsStillReceivePositiveReplies() {
    return controls(ZERO)
        .map(
            request ->
                dynamicTest(
                    request.type().name(),
                    () -> {
                      var transport = new FakeServerTransport();
                      AtomicInteger calls = new AtomicInteger();
                      try (DefaultIec60870Server server =
                          server(transport, acceptingHandler(calls))) {
                        server.start();
                        FakeServerTransport.FakeConnection connection = transport.accept("client");
                        connection.startDataTransfer();
                        connection.deliverAsdu(roundTrip(request));
                        assertFalse(connection.sentAsdus().isEmpty());
                        assertTrue(connection.sentAsdus().stream().noneMatch(Asdu::negative));
                      }
                    }));
  }

  @Test
  void rawHookCanHandleUnsupportedProceduresBeforeValidation() {
    Asdu request =
        copy(
            controls(ZERO).findFirst().orElseThrow(),
            Cause.DEACTIVATION,
            false,
            true,
            false,
            List.of());
    AtomicReference<@Nullable Asdu> seen = new AtomicReference<>();
    AtomicInteger calls = new AtomicInteger();
    var transport = new FakeServerTransport();
    ServerHandler handler =
        new ServerHandler() {
          @Override
          public boolean onRawAsdu(ServerContext context, Asdu asdu) {
            seen.set(asdu);
            context.send(
                copy(asdu, Cause.DEACTIVATION_CONFIRMATION, false, true, false, List.of()));
            return true;
          }

          @Override
          public CommandDecision onCommand(ServerContext context, CommandRequest command) {
            calls.incrementAndGet();
            return CommandDecision.accept();
          }
        };
    try (DefaultIec60870Server server = server(transport, handler)) {
      server.start();
      FakeServerTransport.FakeConnection connection = transport.accept("client");
      connection.startDataTransfer();
      connection.deliverAsdu(roundTrip(request));
      assertEquals(request, seen.get());
      assertEquals(0, calls.get());
      assertEquals(1, connection.sentAsdus().size());
      assertEquals(Cause.DEACTIVATION_CONFIRMATION, connection.sentAsdus().get(0).cause());
      assertTrue(connection.sentAsdus().get(0).test());
    }
  }

  private static void assertRejected(Asdu request, @Nullable Cause expectedCause) {
    var transport = new FakeServerTransport();
    AtomicInteger calls = new AtomicInteger();
    try (DefaultIec60870Server server = server(transport, acceptingHandler(calls))) {
      server.start();
      FakeServerTransport.FakeConnection connection = transport.accept("client");
      connection.startDataTransfer();
      connection.deliverAsdu(roundTrip(request));
      assertEquals(0, calls.get(), "invalid requests must not invoke typed application handlers");
      assertEquals(
          true,
          server
              .stations()
              .station(CA)
              .orElseThrow()
              .currentValue(POINT.objectAddress())
              .orElseThrow()
              .value());
      List<Asdu> sent = connection.sentAsdus();
      if (expectedCause == null) {
        assertTrue(sent.isEmpty(), "malformed or negative requests must be discarded");
      } else {
        assertEquals(1, sent.size(), "rejection must not emit data or a positive termination");
        Asdu reply = roundTrip(sent.get(0));
        assertEquals(expectedCause, reply.cause());
        assertTrue(reply.negative());
        assertEquals(request.test(), reply.test());
        assertEquals(request.type(), reply.type());
        assertEquals(request.objects(), reply.objects());
      }
    }
  }

  private static ServerHandler acceptingHandler(AtomicInteger calls) {
    return new ServerHandler() {
      @Override
      public CommandDecision onCommand(ServerContext context, CommandRequest request) {
        calls.incrementAndGet();
        return CommandDecision.acceptAndUpdate(PointValue.single(false));
      }

      @Override
      public InterrogationResponse onInterrogation(
          ServerContext context, InterrogationRequest request) {
        calls.incrementAndGet();
        return context.defaultInterrogation(request);
      }

      @Override
      public ReadResponse onRead(ServerContext context, ReadRequest request) {
        calls.incrementAndGet();
        return context.defaultRead(request);
      }

      @Override
      public ClockSyncDecision onClockSync(ServerContext context, ClockSyncRequest request) {
        calls.incrementAndGet();
        return ClockSyncDecision.accept();
      }

      @Override
      public ResetDecision onReset(ServerContext context, ResetRequest request) {
        calls.incrementAndGet();
        return ResetDecision.accept();
      }
    };
  }

  private static DefaultIec60870Server server(
      FakeServerTransport transport, ServerHandler handler) {
    Station station =
        Station.builder(CA)
            .point(
                PointDefinition.of(
                    POINT,
                    PointType.SINGLE_POINT,
                    PointValue.single(true),
                    PointCapability.REPORTED,
                    PointCapability.READABLE,
                    PointCapability.COMMANDABLE))
            .build();
    ServerConfig config =
        ServerConfig.builder()
            .station(station)
            .handler(handler)
            .timeTagStyle(TimeTagStyle.NONE)
            .callbackExecutor(Runnable::run)
            .build();
    return new DefaultIec60870Server(
        transport,
        config,
        (connection, events, scheduler) -> {
          FakeSession session =
              FakeSession.server(events, config.maxOutboundQueue(), config.eventQueuePolicy());
          ((FakeServerTransport.FakeConnection) connection).attachSession(session);
          return session;
        });
  }

  private static Stream<Asdu> controls(InformationObjectAddress stationAddress) {
    var qualifier = new QualifierOfCommand(0, false);
    return Stream.of(
        control(AsduType.C_SC_NA_1, new SingleCommand(POINT.objectAddress(), false, qualifier)),
        control(
            AsduType.C_SC_TA_1,
            new SingleCommandWithCp56Time(POINT.objectAddress(), false, qualifier, TIME)),
        control(AsduType.C_BO_NA_1, new Bitstring32Command(POINT.objectAddress(), 0)),
        control(
            AsduType.C_IC_NA_1,
            new InterrogationCommand(stationAddress, QualifierOfInterrogation.STATION)),
        control(
            AsduType.C_CI_NA_1,
            new CounterInterrogationCommand(
                stationAddress, new QualifierOfCounterInterrogation(5, FreezeMode.READ))),
        control(AsduType.C_RD_NA_1, new ReadCommand(POINT.objectAddress())),
        control(AsduType.C_CS_NA_1, new ClockSynchronizationCommand(stationAddress, TIME)),
        control(AsduType.C_TS_NA_1, new TestCommand(stationAddress, FixedTestBitPattern.DEFAULT)),
        control(
            AsduType.C_TS_TA_1,
            new TestCommandWithCp56Time(stationAddress, UShort.valueOf(5), TIME)),
        control(
            AsduType.C_RP_NA_1,
            new ResetProcessCommand(stationAddress, QualifierOfResetProcess.GENERAL)));
  }

  private static Asdu control(AsduType type, InformationObject object) {
    return new Asdu(
        type,
        false,
        type == AsduType.C_RD_NA_1 ? Cause.REQUEST : Cause.ACTIVATION,
        false,
        false,
        OriginatorAddress.none(),
        CA,
        List.of(object));
  }

  private static Asdu copy(
      Asdu request,
      Cause cause,
      boolean negative,
      boolean test,
      boolean sequence,
      List<InformationObject> objects) {
    return new Asdu(
        request.type(),
        sequence,
        cause,
        negative,
        test,
        request.originatorAddress(),
        request.commonAddress(),
        objects);
  }

  private static Asdu roundTrip(Asdu asdu) {
    ByteBuf buffer = Unpooled.buffer();
    try {
      Asdu.Serde.encode(asdu, ProtocolProfile.iec104Default(), buffer);
      return Asdu.Serde.decode(ProtocolProfile.iec104Default(), buffer);
    } finally {
      buffer.release();
    }
  }
}
