package com.digitalpetri.iec104.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.digitalpetri.iec104.address.CommonAddress;
import com.digitalpetri.iec104.address.InformationObjectAddress;
import com.digitalpetri.iec104.address.OriginatorAddress;
import com.digitalpetri.iec104.address.PointAddress;
import com.digitalpetri.iec104.asdu.Asdu;
import com.digitalpetri.iec104.asdu.AsduType;
import com.digitalpetri.iec104.asdu.Cause;
import com.digitalpetri.iec104.asdu.InformationObject;
import com.digitalpetri.iec104.asdu.element.QualifierOfCommand;
import com.digitalpetri.iec104.asdu.element.QualifierOfInterrogation;
import com.digitalpetri.iec104.asdu.object.InterrogationCommand;
import com.digitalpetri.iec104.asdu.object.SingleCommand;
import com.digitalpetri.iec104.asdu.object.SinglePointInformation;
import com.digitalpetri.iec104.point.PointCapability;
import com.digitalpetri.iec104.point.PointType;
import com.digitalpetri.iec104.point.PointValue;
import com.digitalpetri.iec104.point.TimeTagStyle;
import java.util.List;
import java.util.concurrent.Executor;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link DefaultIec104Server} dispatch driven by a fake transport. */
class DefaultIec104ServerTest {

  private static final CommonAddress CA = CommonAddress.of(1);
  private static final PointAddress POINT = PointAddress.of(1, 100);
  private static final InformationObjectAddress ZERO = InformationObjectAddress.of(0);

  /** A same-thread executor so dispatch runs inline within the test thread. */
  private static final Executor DIRECT = Runnable::run;

  private FakeServerTransport transport;

  @BeforeEach
  void setUp() {
    transport = new FakeServerTransport();
  }

  @AfterEach
  void tearDown() {}

  private Station singlePointStation() {
    return Station.builder(CA)
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
  }

  private DefaultIec104Server server(ServerHandler handler) {
    ServerConfig config =
        ServerConfig.builder()
            .station(singlePointStation())
            .handler(handler)
            .timeTagStyle(TimeTagStyle.NONE)
            .callbackExecutor(DIRECT)
            .build();
    return new DefaultIec104Server(transport, config);
  }

  private Asdu control(AsduType type, Cause cause, InformationObject object) {
    return new Asdu(
        type, false, cause, false, false, OriginatorAddress.none(), CA, List.of(object));
  }

  @Test
  void interrogationAnswersFromStationImage() {
    DefaultIec104Server server = server(new ServerHandler() {});
    server.start();
    FakeServerTransport.FakeConnection connection = transport.accept("client");
    connection.startDataTransfer();

    connection.deliverAsdu(
        control(
            AsduType.C_IC_NA_1,
            Cause.ACTIVATION,
            new InterrogationCommand(ZERO, QualifierOfInterrogation.STATION)));

    List<Asdu> sent = connection.sentAsdus();
    // ACT_CON, one monitor object, ACT_TERM.
    assertEquals(3, sent.size());
    assertEquals(Cause.ACTIVATION_CONFIRMATION, sent.get(0).cause());
    assertFalse(sent.get(0).negative());

    Asdu monitor = sent.get(1);
    assertEquals(AsduType.M_SP_NA_1, monitor.type());
    assertEquals(Cause.INTERROGATED_BY_STATION, monitor.cause());
    assertTrue(monitor.objects().get(0) instanceof SinglePointInformation spi && spi.on());

    assertEquals(Cause.ACTIVATION_TERMINATION, sent.get(2).cause());

    server.close();
  }

  @Test
  void unknownCommonAddressInterrogationIsRejected() {
    DefaultIec104Server server = server(new ServerHandler() {});
    server.start();
    FakeServerTransport.FakeConnection connection = transport.accept("client");
    connection.startDataTransfer();

    Asdu request =
        new Asdu(
            AsduType.C_IC_NA_1,
            false,
            Cause.ACTIVATION,
            false,
            false,
            OriginatorAddress.none(),
            CommonAddress.of(99),
            List.of(new InterrogationCommand(ZERO, QualifierOfInterrogation.STATION)));
    connection.deliverAsdu(request);

    List<Asdu> sent = connection.sentAsdus();
    assertEquals(1, sent.size());
    assertTrue(sent.get(0).negative());
    assertEquals(Cause.UNKNOWN_COMMON_ADDRESS, sent.get(0).cause());

    server.close();
  }

  @Test
  void defaultCommandIsRejected() {
    DefaultIec104Server server = server(new ServerHandler() {});
    server.start();
    FakeServerTransport.FakeConnection connection = transport.accept("client");
    connection.startDataTransfer();

    SingleCommand command =
        new SingleCommand(POINT.objectAddress(), true, new QualifierOfCommand(0, false));
    connection.deliverAsdu(control(AsduType.C_SC_NA_1, Cause.ACTIVATION, command));

    List<Asdu> sent = connection.sentAsdus();
    assertEquals(1, sent.size());
    assertTrue(sent.get(0).negative());
    assertEquals(Cause.UNKNOWN_INFORMATION_OBJECT_ADDRESS, sent.get(0).cause());

    server.close();
  }

  @Test
  void acceptAndUpdateCommandEmitsReturnInformationAndTermination() {
    ServerHandler handler =
        new ServerHandler() {
          @Override
          public CommandDecision onCommand(ServerContext context, CommandRequest request) {
            boolean on = request.commandObject() instanceof SingleCommand sc && sc.on();
            return CommandDecision.acceptAndUpdate(PointValue.single(on));
          }
        };
    DefaultIec104Server server = server(handler);
    server.start();
    FakeServerTransport.FakeConnection connection = transport.accept("client");
    connection.startDataTransfer();

    SingleCommand command =
        new SingleCommand(POINT.objectAddress(), false, new QualifierOfCommand(0, false));
    connection.deliverAsdu(control(AsduType.C_SC_NA_1, Cause.ACTIVATION, command));

    List<Asdu> sent = connection.sentAsdus();
    // ACT_CON (positive), return information (M_SP_NA_1, retrem), ACT_TERM.
    assertEquals(3, sent.size());
    assertEquals(Cause.ACTIVATION_CONFIRMATION, sent.get(0).cause());
    assertFalse(sent.get(0).negative());

    Asdu returnInfo = sent.get(1);
    assertEquals(AsduType.M_SP_NA_1, returnInfo.type());
    assertEquals(Cause.RETURN_REMOTE, returnInfo.cause());
    assertTrue(returnInfo.objects().get(0) instanceof SinglePointInformation spi && !spi.on());

    assertEquals(Cause.ACTIVATION_TERMINATION, sent.get(2).cause());

    // The station image reflects the new value.
    assertSame(
        false,
        server
            .stations()
            .station(CA)
            .orElseThrow()
            .currentValue(POINT.objectAddress())
            .orElseThrow()
            .value());

    server.close();
  }

  @Test
  void selectCommandIsConfirmedWithoutUpdate() {
    ServerHandler handler =
        new ServerHandler() {
          @Override
          public CommandDecision onCommand(ServerContext context, CommandRequest request) {
            return CommandDecision.acceptAndUpdate(PointValue.single(false));
          }
        };
    DefaultIec104Server server = server(handler);
    server.start();
    FakeServerTransport.FakeConnection connection = transport.accept("client");
    connection.startDataTransfer();

    // S/E = 1 selects.
    SingleCommand select =
        new SingleCommand(POINT.objectAddress(), false, new QualifierOfCommand(0, true));
    connection.deliverAsdu(control(AsduType.C_SC_NA_1, Cause.ACTIVATION, select));

    List<Asdu> sent = connection.sentAsdus();
    // Only the positive ACT_CON; no return information, no termination, no image change.
    assertEquals(1, sent.size());
    assertEquals(Cause.ACTIVATION_CONFIRMATION, sent.get(0).cause());
    assertFalse(sent.get(0).negative());

    assertSame(
        true,
        server
            .stations()
            .station(CA)
            .orElseThrow()
            .currentValue(POINT.objectAddress())
            .orElseThrow()
            .value());

    server.close();
  }

  @Test
  void unknownTypeIsMirroredNegative() {
    DefaultIec104Server server = server(new ServerHandler() {});
    server.start();
    FakeServerTransport.FakeConnection connection = transport.accept("client");
    connection.startDataTransfer();

    // M_EI_NA_1 is a monitor system type with no server dispatch path.
    Asdu request =
        new Asdu(
            AsduType.M_EI_NA_1,
            false,
            Cause.SPONTANEOUS,
            false,
            false,
            OriginatorAddress.none(),
            CA,
            List.of(
                new com.digitalpetri.iec104.asdu.object.EndOfInitialization(
                    ZERO,
                    new com.digitalpetri.iec104.asdu.element.CauseOfInitialization(0, false))));
    connection.deliverAsdu(request);

    List<Asdu> sent = connection.sentAsdus();
    assertEquals(1, sent.size());
    assertTrue(sent.get(0).negative());
    assertEquals(Cause.UNKNOWN_TYPE_ID, sent.get(0).cause());

    server.close();
  }

  @Test
  void publishPushesMonitorToStartedConnection() {
    DefaultIec104Server server = server(new ServerHandler() {});
    server.start();
    FakeServerTransport.FakeConnection connection = transport.accept("client");
    connection.startDataTransfer();

    server.publish(POINT, PointValue.single(false), Cause.SPONTANEOUS);

    List<Asdu> sent = connection.sentAsdus();
    assertEquals(1, sent.size());
    assertEquals(AsduType.M_SP_NA_1, sent.get(0).type());
    assertEquals(Cause.SPONTANEOUS, sent.get(0).cause());
    assertTrue(sent.get(0).objects().get(0) instanceof SinglePointInformation spi && !spi.on());

    server.close();
  }

  @Test
  void publishIsNotSentBeforeDataTransferStarts() {
    DefaultIec104Server server = server(new ServerHandler() {});
    server.start();
    FakeServerTransport.FakeConnection connection = transport.accept("client");
    // No STARTDT.

    server.publish(POINT, PointValue.single(false), Cause.SPONTANEOUS);

    assertTrue(connection.sentAsdus().isEmpty());

    server.close();
  }

  @Test
  void closeUnbindsTransport() {
    DefaultIec104Server server = server(new ServerHandler() {});
    server.start();
    assertTrue(transport.isBound());
    server.close();
    assertFalse(transport.isBound());
  }

  @Test
  void readAnswersCurrentValueFromStationImage() {
    DefaultIec104Server server = server(new ServerHandler() {});
    server.start();
    FakeServerTransport.FakeConnection connection = transport.accept("client");
    connection.startDataTransfer();

    connection.deliverAsdu(
        control(
            AsduType.C_RD_NA_1,
            Cause.REQUEST,
            new com.digitalpetri.iec104.asdu.object.ReadCommand(POINT.objectAddress())));

    List<Asdu> sent = connection.sentAsdus();
    assertEquals(1, sent.size());
    Asdu response = sent.get(0);
    assertEquals(AsduType.M_SP_NA_1, response.type());
    assertEquals(Cause.REQUEST, response.cause());
    assertFalse(response.negative());
    assertTrue(response.objects().get(0) instanceof SinglePointInformation spi && spi.on());

    server.close();
  }

  @Test
  void clockSyncIsAcceptedByDefault() {
    DefaultIec104Server server = server(new ServerHandler() {});
    server.start();
    FakeServerTransport.FakeConnection connection = transport.accept("client");
    connection.startDataTransfer();

    com.digitalpetri.iec104.asdu.time.Cp56Time2a time =
        com.digitalpetri.iec104.asdu.time.Cp56Time2a.from(
            java.time.Instant.parse("2024-01-02T03:04:05Z"), java.time.ZoneOffset.UTC);
    connection.deliverAsdu(
        control(
            AsduType.C_CS_NA_1,
            Cause.ACTIVATION,
            new com.digitalpetri.iec104.asdu.object.ClockSynchronizationCommand(ZERO, time)));

    List<Asdu> sent = connection.sentAsdus();
    assertEquals(1, sent.size());
    assertEquals(AsduType.C_CS_NA_1, sent.get(0).type());
    assertEquals(Cause.ACTIVATION_CONFIRMATION, sent.get(0).cause());
    assertFalse(sent.get(0).negative());

    server.close();
  }

  @Test
  void clockSyncRejectionRepliesNegative() {
    ServerHandler handler =
        new ServerHandler() {
          @Override
          public ClockSyncDecision onClockSync(ServerContext context, ClockSyncRequest request) {
            return ClockSyncDecision.reject(Cause.UNKNOWN_CAUSE);
          }
        };
    DefaultIec104Server server = server(handler);
    server.start();
    FakeServerTransport.FakeConnection connection = transport.accept("client");
    connection.startDataTransfer();

    com.digitalpetri.iec104.asdu.time.Cp56Time2a time =
        com.digitalpetri.iec104.asdu.time.Cp56Time2a.from(
            java.time.Instant.parse("2024-01-02T03:04:05Z"), java.time.ZoneOffset.UTC);
    connection.deliverAsdu(
        control(
            AsduType.C_CS_NA_1,
            Cause.ACTIVATION,
            new com.digitalpetri.iec104.asdu.object.ClockSynchronizationCommand(ZERO, time)));

    List<Asdu> sent = connection.sentAsdus();
    assertEquals(1, sent.size());
    assertTrue(sent.get(0).negative());
    assertEquals(Cause.UNKNOWN_CAUSE, sent.get(0).cause());

    server.close();
  }

  @Test
  void requestContextIsPopulatedWithRemoteAddressAndStation() {
    java.util.concurrent.atomic.AtomicReference<java.net.@Nullable SocketAddress> seenRemote =
        new java.util.concurrent.atomic.AtomicReference<>();
    java.util.concurrent.atomic.AtomicReference<@Nullable CommonAddress> seenStation =
        new java.util.concurrent.atomic.AtomicReference<>();

    ServerHandler handler =
        new ServerHandler() {
          @Override
          public CommandDecision onCommand(ServerContext context, CommandRequest request) {
            seenRemote.set(context.remoteAddress());
            seenStation.set(context.station().map(Station::commonAddress).orElse(null));
            return CommandDecision.acceptAndUpdate(PointValue.single(false));
          }
        };
    DefaultIec104Server server = server(handler);
    server.start();
    FakeServerTransport.FakeConnection connection = transport.accept("station-host");
    connection.startDataTransfer();

    SingleCommand command =
        new SingleCommand(POINT.objectAddress(), false, new QualifierOfCommand(0, false));
    connection.deliverAsdu(control(AsduType.C_SC_NA_1, Cause.ACTIVATION, command));

    assertEquals(connection.remoteAddress(), seenRemote.get());
    assertEquals(CA, seenStation.get());

    server.close();
  }

  @Test
  void repliesAreQueuedUntilDataTransferStarts() {
    DefaultIec104Server server = server(new ServerHandler() {});
    server.start();
    FakeServerTransport.FakeConnection connection = transport.accept("client");

    // Interrogation arrives before STARTDT: the handler runs, but its replies are withheld.
    connection.deliverAsdu(
        control(
            AsduType.C_IC_NA_1,
            Cause.ACTIVATION,
            new InterrogationCommand(ZERO, QualifierOfInterrogation.STATION)));
    assertTrue(connection.sentAsdus().isEmpty(), "no application replies before STARTDT");

    // STARTDT flushes the queued ACT_CON, monitor data, and ACT_TERM.
    connection.startDataTransfer();
    List<Asdu> sent = connection.sentAsdus();
    assertEquals(3, sent.size());
    assertEquals(Cause.ACTIVATION_CONFIRMATION, sent.get(0).cause());
    assertEquals(Cause.ACTIVATION_TERMINATION, sent.get(2).cause());

    server.close();
  }
}
