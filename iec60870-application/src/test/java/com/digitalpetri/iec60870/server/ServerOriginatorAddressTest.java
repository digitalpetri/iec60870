package com.digitalpetri.iec60870.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

import com.digitalpetri.iec60870.address.CommonAddress;
import com.digitalpetri.iec60870.address.InformationObjectAddress;
import com.digitalpetri.iec60870.address.OriginatorAddress;
import com.digitalpetri.iec60870.address.PointAddress;
import com.digitalpetri.iec60870.asdu.Asdu;
import com.digitalpetri.iec60870.asdu.AsduType;
import com.digitalpetri.iec60870.asdu.Cause;
import com.digitalpetri.iec60870.asdu.InformationObject;
import com.digitalpetri.iec60870.asdu.element.BinaryCounterReading;
import com.digitalpetri.iec60870.asdu.element.CauseOfInitialization;
import com.digitalpetri.iec60870.asdu.element.FixedTestBitPattern;
import com.digitalpetri.iec60870.asdu.element.FreezeMode;
import com.digitalpetri.iec60870.asdu.element.QualifierOfCommand;
import com.digitalpetri.iec60870.asdu.element.QualifierOfCounterInterrogation;
import com.digitalpetri.iec60870.asdu.element.QualifierOfInterrogation;
import com.digitalpetri.iec60870.asdu.element.QualifierOfResetProcess;
import com.digitalpetri.iec60870.asdu.object.ClockSynchronizationCommand;
import com.digitalpetri.iec60870.asdu.object.CounterInterrogationCommand;
import com.digitalpetri.iec60870.asdu.object.EndOfInitialization;
import com.digitalpetri.iec60870.asdu.object.InterrogationCommand;
import com.digitalpetri.iec60870.asdu.object.ReadCommand;
import com.digitalpetri.iec60870.asdu.object.ResetProcessCommand;
import com.digitalpetri.iec60870.asdu.object.SingleCommand;
import com.digitalpetri.iec60870.asdu.object.TestCommand;
import com.digitalpetri.iec60870.asdu.object.TestCommandWithCp56Time;
import com.digitalpetri.iec60870.asdu.time.Cp56Time2a;
import com.digitalpetri.iec60870.fakes.FakeServerTransport;
import com.digitalpetri.iec60870.fakes.FakeSession;
import com.digitalpetri.iec60870.point.PointCapability;
import com.digitalpetri.iec60870.point.PointType;
import com.digitalpetri.iec60870.point.PointValue;
import com.digitalpetri.iec60870.point.TimeTagStyle;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.stream.Stream;
import org.joou.UShort;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

class ServerOriginatorAddressTest {

  private static final OriginatorAddress ORIGINATOR = OriginatorAddress.of(27);
  private static final PointAddress POINT = PointAddress.of(1, 100);
  private static final InformationObjectAddress ZERO = InformationObjectAddress.of(0);
  private static final Cp56Time2a TIME =
      Cp56Time2a.from(Instant.parse("2026-01-02T03:04:05Z"), ZoneOffset.UTC);

  @TestFactory
  Stream<DynamicTest> solicitedRepliesEchoOriginator() {
    return Stream.of(
            new ReplyCase(
                "station interrogation",
                request(
                    AsduType.C_IC_NA_1,
                    Cause.ACTIVATION,
                    1,
                    new InterrogationCommand(ZERO, QualifierOfInterrogation.STATION)),
                List.of(
                    Cause.ACTIVATION_CONFIRMATION,
                    Cause.INTERROGATED_BY_STATION,
                    Cause.ACTIVATION_TERMINATION),
                false),
            new ReplyCase(
                "group interrogation",
                request(
                    AsduType.C_IC_NA_1,
                    Cause.ACTIVATION,
                    1,
                    new InterrogationCommand(ZERO, QualifierOfInterrogation.GROUP_1)),
                List.of(
                    Cause.ACTIVATION_CONFIRMATION,
                    Cause.INTERROGATED_BY_GROUP_1,
                    Cause.ACTIVATION_TERMINATION),
                false),
            new ReplyCase(
                "counter interrogation",
                request(
                    AsduType.C_CI_NA_1,
                    Cause.ACTIVATION,
                    2,
                    new CounterInterrogationCommand(
                        ZERO, new QualifierOfCounterInterrogation(5, FreezeMode.READ))),
                List.of(
                    Cause.ACTIVATION_CONFIRMATION,
                    Cause.REQUESTED_BY_GENERAL_COUNTER,
                    Cause.ACTIVATION_TERMINATION),
                false),
            new ReplyCase(
                "read",
                request(
                    AsduType.C_RD_NA_1, Cause.REQUEST, 1, new ReadCommand(POINT.objectAddress())),
                List.of(Cause.REQUEST),
                false),
            new ReplyCase(
                "clock synchronization",
                request(
                    AsduType.C_CS_NA_1,
                    Cause.ACTIVATION,
                    1,
                    new ClockSynchronizationCommand(ZERO, TIME)),
                List.of(Cause.ACTIVATION_CONFIRMATION),
                false),
            new ReplyCase(
                "untimed test",
                request(
                    AsduType.C_TS_NA_1,
                    Cause.ACTIVATION,
                    1,
                    new TestCommand(ZERO, FixedTestBitPattern.DEFAULT)),
                List.of(Cause.ACTIVATION_CONFIRMATION),
                false),
            new ReplyCase(
                "timed test",
                request(
                    AsduType.C_TS_TA_1,
                    Cause.ACTIVATION,
                    1,
                    new TestCommandWithCp56Time(ZERO, UShort.valueOf(42), TIME)),
                List.of(Cause.ACTIVATION_CONFIRMATION),
                false),
            new ReplyCase(
                "reset",
                request(
                    AsduType.C_RP_NA_1,
                    Cause.ACTIVATION,
                    1,
                    new ResetProcessCommand(ZERO, QualifierOfResetProcess.GENERAL)),
                List.of(Cause.ACTIVATION_CONFIRMATION),
                false),
            new ReplyCase(
                "select",
                request(
                    AsduType.C_SC_NA_1,
                    Cause.ACTIVATION,
                    1,
                    new SingleCommand(
                        POINT.objectAddress(), false, new QualifierOfCommand(0, true))),
                List.of(Cause.ACTIVATION_CONFIRMATION),
                false),
            new ReplyCase(
                "execute",
                request(
                    AsduType.C_SC_NA_1,
                    Cause.ACTIVATION,
                    1,
                    new SingleCommand(
                        POINT.objectAddress(), false, new QualifierOfCommand(0, false))),
                List.of(
                    Cause.ACTIVATION_CONFIRMATION,
                    Cause.RETURN_REMOTE,
                    Cause.ACTIVATION_TERMINATION),
                false),
            new ReplyCase(
                "unknown station",
                request(
                    AsduType.C_IC_NA_1,
                    Cause.ACTIVATION,
                    99,
                    new InterrogationCommand(ZERO, QualifierOfInterrogation.STATION)),
                List.of(Cause.UNKNOWN_COMMON_ADDRESS),
                true),
            new ReplyCase(
                "unknown point",
                request(
                    AsduType.C_RD_NA_1,
                    Cause.REQUEST,
                    1,
                    new ReadCommand(InformationObjectAddress.of(999))),
                List.of(Cause.UNKNOWN_INFORMATION_OBJECT_ADDRESS),
                true),
            new ReplyCase(
                "unknown type",
                request(
                    AsduType.M_EI_NA_1,
                    Cause.INITIALIZED,
                    1,
                    new EndOfInitialization(ZERO, new CauseOfInitialization(0, false))),
                List.of(Cause.UNKNOWN_TYPE_ID),
                true))
        .map(replyCase -> dynamicTest(replyCase.name(), () -> assertReplyOriginators(replyCase)));
  }

  private void assertReplyOriginators(ReplyCase replyCase) {
    var transport = new FakeServerTransport();
    try (DefaultIec60870Server server = server(transport)) {
      server.start();
      FakeServerTransport.FakeConnection connection = transport.accept("client");
      connection.startDataTransfer();
      connection.deliverAsdu(replyCase.request());

      List<Asdu> replies = connection.sentAsdus();
      assertEquals(replyCase.causes(), replies.stream().map(Asdu::cause).toList());
      for (Asdu reply : replies) {
        assertEquals(replyCase.negative(), reply.negative());
        assertEquals(replyCase.request().commonAddress(), reply.commonAddress());
        assertEquals(
            reply.cause() == Cause.RETURN_REMOTE ? OriginatorAddress.none() : ORIGINATOR,
            reply.originatorAddress(),
            reply.cause().toString());
      }
    }
  }

  @Test
  void spontaneousReportsKeepDefaultOriginator() {
    var transport = new FakeServerTransport();
    try (DefaultIec60870Server server = server(transport)) {
      server.start();
      FakeServerTransport.FakeConnection connection = transport.accept("client");
      connection.startDataTransfer();
      server.publish(POINT, PointValue.single(false), Cause.SPONTANEOUS);
      List<Asdu> sent = connection.sentAsdus();
      assertEquals(1, sent.size());
      assertEquals(Cause.SPONTANEOUS, sent.get(0).cause());
      assertFalse(sent.get(0).negative());
      assertEquals(OriginatorAddress.none(), sent.get(0).originatorAddress());
    }
  }

  private static Asdu request(AsduType type, Cause cause, int station, InformationObject object) {
    return new Asdu(
        type, false, cause, false, false, ORIGINATOR, CommonAddress.of(station), List.of(object));
  }

  private static DefaultIec60870Server server(FakeServerTransport transport) {
    Station station =
        Station.builder(POINT.commonAddress())
            .point(
                PointDefinition.of(
                    POINT,
                    PointType.SINGLE_POINT,
                    PointValue.single(true),
                    PointCapability.REPORTED,
                    PointCapability.READABLE,
                    PointCapability.COMMANDABLE))
            .group(1, POINT)
            .build();
    Station counterStation =
        Station.builder(CommonAddress.of(2))
            .point(
                PointDefinition.of(
                    PointAddress.of(2, 200),
                    PointType.INTEGRATED_TOTALS,
                    PointValue.counter(new BinaryCounterReading(123, 0, false, false, false)),
                    PointCapability.REPORTED))
            .build();
    ServerConfig config =
        ServerConfig.builder()
            .station(station)
            .station(counterStation)
            .timeTagStyle(TimeTagStyle.NONE)
            .callbackExecutor(Runnable::run)
            .handler(
                new ServerHandler() {
                  @Override
                  public CommandDecision onCommand(ServerContext context, CommandRequest request) {
                    return CommandDecision.acceptAndUpdate(PointValue.single(false));
                  }
                })
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

  private record ReplyCase(String name, Asdu request, List<Cause> causes, boolean negative) {}
}
