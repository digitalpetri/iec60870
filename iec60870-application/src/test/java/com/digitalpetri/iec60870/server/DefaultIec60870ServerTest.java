package com.digitalpetri.iec60870.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import com.digitalpetri.iec60870.asdu.element.FreezeMode;
import com.digitalpetri.iec60870.asdu.element.QualifierOfCommand;
import com.digitalpetri.iec60870.asdu.element.QualifierOfCounterInterrogation;
import com.digitalpetri.iec60870.asdu.element.QualifierOfInterrogation;
import com.digitalpetri.iec60870.asdu.object.ClockSynchronizationCommand;
import com.digitalpetri.iec60870.asdu.object.CounterInterrogationCommand;
import com.digitalpetri.iec60870.asdu.object.EndOfInitialization;
import com.digitalpetri.iec60870.asdu.object.InterrogationCommand;
import com.digitalpetri.iec60870.asdu.object.ReadCommand;
import com.digitalpetri.iec60870.asdu.object.SingleCommand;
import com.digitalpetri.iec60870.asdu.object.SinglePointInformation;
import com.digitalpetri.iec60870.asdu.object.TestCommandWithCp56Time;
import com.digitalpetri.iec60870.asdu.time.Cp56Time2a;
import com.digitalpetri.iec60870.fakes.FakeServerTransport;
import com.digitalpetri.iec60870.fakes.FakeSession;
import com.digitalpetri.iec60870.point.PointCapability;
import com.digitalpetri.iec60870.point.PointType;
import com.digitalpetri.iec60870.point.PointValue;
import com.digitalpetri.iec60870.point.TimeTagStyle;
import com.digitalpetri.iec60870.session.Session;
import java.net.SocketAddress;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link DefaultIec60870Server} dispatch driven by a neutral fake {@link Session}.
 */
class DefaultIec60870ServerTest {

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

  /**
   * Builds a session factory that creates a SERVER-role fake session honoring the config's outbound
   * bound and policy, attaches it to the accepted fake connection, and returns it.
   */
  private static DefaultIec60870Server.SessionFactory sessionFactory(ServerConfig config) {
    return (connection, events, scheduler) -> {
      FakeSession session =
          FakeSession.server(events, config.maxOutboundQueue(), config.eventQueuePolicy());
      ((FakeServerTransport.FakeConnection) connection).attachSession(session);
      return session;
    };
  }

  private DefaultIec60870Server server(ServerHandler handler) {
    ServerConfig config =
        ServerConfig.builder()
            .station(singlePointStation())
            .handler(handler)
            .timeTagStyle(TimeTagStyle.NONE)
            .callbackExecutor(DIRECT)
            .build();
    return new DefaultIec60870Server(transport, config, sessionFactory(config));
  }

  private Asdu control(AsduType type, Cause cause, InformationObject object) {
    return new Asdu(
        type, false, cause, false, false, OriginatorAddress.none(), CA, List.of(object));
  }

  @Test
  void interrogationAnswersFromStationImage() {
    DefaultIec60870Server server = server(new ServerHandler() {});
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
    DefaultIec60870Server server = server(new ServerHandler() {});
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
    DefaultIec60870Server server = server(new ServerHandler() {});
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
    DefaultIec60870Server server = server(handler);
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
    DefaultIec60870Server server = server(handler);
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
    DefaultIec60870Server server = server(new ServerHandler() {});
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
            List.of(new EndOfInitialization(ZERO, new CauseOfInitialization(0, false))));
    connection.deliverAsdu(request);

    List<Asdu> sent = connection.sentAsdus();
    assertEquals(1, sent.size());
    assertTrue(sent.get(0).negative());
    assertEquals(Cause.UNKNOWN_TYPE_ID, sent.get(0).cause());

    server.close();
  }

  @Test
  void publishPushesMonitorToStartedConnection() {
    DefaultIec60870Server server = server(new ServerHandler() {});
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
    DefaultIec60870Server server = server(new ServerHandler() {});
    server.start();
    FakeServerTransport.FakeConnection connection = transport.accept("client");
    // No STARTDT.

    server.publish(POINT, PointValue.single(false), Cause.SPONTANEOUS);

    assertTrue(connection.sentAsdus().isEmpty());

    server.close();
  }

  @Test
  void closeUnbindsTransport() {
    DefaultIec60870Server server = server(new ServerHandler() {});
    server.start();
    assertTrue(transport.isBound());
    server.close();
    assertFalse(transport.isBound());
  }

  @Test
  void readAnswersCurrentValueFromStationImage() {
    DefaultIec60870Server server = server(new ServerHandler() {});
    server.start();
    FakeServerTransport.FakeConnection connection = transport.accept("client");
    connection.startDataTransfer();

    connection.deliverAsdu(
        control(AsduType.C_RD_NA_1, Cause.REQUEST, new ReadCommand(POINT.objectAddress())));

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
    DefaultIec60870Server server = server(new ServerHandler() {});
    server.start();
    FakeServerTransport.FakeConnection connection = transport.accept("client");
    connection.startDataTransfer();

    Cp56Time2a time = Cp56Time2a.from(Instant.parse("2024-01-02T03:04:05Z"), ZoneOffset.UTC);
    connection.deliverAsdu(
        control(AsduType.C_CS_NA_1, Cause.ACTIVATION, new ClockSynchronizationCommand(ZERO, time)));

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
    DefaultIec60870Server server = server(handler);
    server.start();
    FakeServerTransport.FakeConnection connection = transport.accept("client");
    connection.startDataTransfer();

    Cp56Time2a time = Cp56Time2a.from(Instant.parse("2024-01-02T03:04:05Z"), ZoneOffset.UTC);
    connection.deliverAsdu(
        control(AsduType.C_CS_NA_1, Cause.ACTIVATION, new ClockSynchronizationCommand(ZERO, time)));

    List<Asdu> sent = connection.sentAsdus();
    assertEquals(1, sent.size());
    assertTrue(sent.get(0).negative());
    assertEquals(Cause.UNKNOWN_CAUSE, sent.get(0).cause());

    server.close();
  }

  @Test
  void requestContextIsPopulatedWithRemoteAddressAndStation() {
    AtomicReference<@Nullable SocketAddress> seenRemote = new AtomicReference<>();
    AtomicReference<@Nullable CommonAddress> seenStation = new AtomicReference<>();

    ServerHandler handler =
        new ServerHandler() {
          @Override
          public CommandDecision onCommand(ServerContext context, CommandRequest request) {
            seenRemote.set(context.remoteAddress());
            seenStation.set(context.station().map(Station::commonAddress).orElse(null));
            return CommandDecision.acceptAndUpdate(PointValue.single(false));
          }
        };
    DefaultIec60870Server server = server(handler);
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

  // --- F11: idempotent connection close --------------------------------------------------------

  @Test
  void connectionCloseIsIdempotentAcrossBothPaths() {
    DefaultIec60870Server server = server(new ServerHandler() {});
    server.start();
    List<ServerEvent> events = new CopyOnWriteArrayList<>();
    subscribe(server, events);
    FakeServerTransport.FakeConnection connection = transport.accept("client");
    connection.startDataTransfer();

    // Path A: a fatal protocol error self-closes the session -> SessionEvents.onClosed -> close().
    connection.deliverBadAcknowledgement(
        control(AsduType.C_SC_NA_1, Cause.SPONTANEOUS, singleCommand()));
    // Path B: the transport later reports the loss -> a second onClosed is a no-op on the session.
    connection.loseConnection();

    long closedEvents =
        events.stream().filter(e -> e instanceof ServerEvent.ConnectionClosed).count();
    assertEquals(1, closedEvents, "ConnectionClosed must be published exactly once");
    assertEquals(
        1, connection.closeCount(), "the transport connection must be closed exactly once");

    server.close();
  }

  // --- F21: maxConnections admission frees a slot on close -------------------------------------

  @Test
  void maxConnectionsRejectsThenAdmitsAfterClose() {
    ServerConfig config =
        ServerConfig.builder()
            .station(singlePointStation())
            .handler(new ServerHandler() {})
            .timeTagStyle(TimeTagStyle.NONE)
            .callbackExecutor(DIRECT)
            .maxConnections(1)
            .build();
    DefaultIec60870Server server =
        new DefaultIec60870Server(transport, config, sessionFactory(config));
    server.start();

    FakeServerTransport.FakeConnection first = transport.accept("client-1");
    // The cap is 1: the second connection is rejected (closed immediately, no session attached).
    FakeServerTransport.FakeConnection second = transport.accept("client-2");
    assertTrue(second.isClosed(), "a connection past the cap must be rejected");

    // Close the first, freeing its slot, then a third connection is admitted.
    first.loseConnection();
    FakeServerTransport.FakeConnection third = transport.accept("client-3");
    assertFalse(third.isClosed(), "a slot freed by close must admit a new connection");

    server.close();
  }

  // --- F13: type-mismatched acceptAndUpdate must not corrupt the image; ACT_TERM still sent -----

  @Test
  void typeMismatchedAcceptedValueLeavesImageIntactAndStillSendsTermination() {
    ServerHandler handler =
        new ServerHandler() {
          @Override
          public CommandDecision onCommand(ServerContext context, CommandRequest request) {
            // The point is SINGLE_POINT but the accepted value is a scaled (Short) value: a runtime
            // type mismatch that must be rejected before the image is mutated.
            return CommandDecision.acceptAndUpdate(PointValue.scaled((short) 7));
          }
        };
    DefaultIec60870Server server = server(handler);
    server.start();
    FakeServerTransport.FakeConnection connection = transport.accept("client");
    connection.startDataTransfer();

    SingleCommand command =
        new SingleCommand(POINT.objectAddress(), false, new QualifierOfCommand(0, false));
    connection.deliverAsdu(control(AsduType.C_SC_NA_1, Cause.ACTIVATION, command));

    // The station image still holds the original value (true), not the wrong-typed scaled value.
    assertSame(
        true,
        server
            .stations()
            .station(CA)
            .orElseThrow()
            .currentValue(POINT.objectAddress())
            .orElseThrow()
            .value());

    // ACT_TERM is still sent despite the type-mismatch throw building the return information.
    List<Asdu> sent = connection.sentAsdus();
    assertTrue(
        sent.stream().anyMatch(a -> a.cause() == Cause.ACTIVATION_TERMINATION),
        "ACT_TERM must be sent even when building return information throws");

    server.close();
  }

  // --- F19: empty C_TS_TA_1 echo must encode cleanly -------------------------------------------

  @Test
  void emptyTimedTestCommandReplyEchoesTimedCommand() {
    DefaultIec60870Server server = server(new ServerHandler() {});
    server.start();
    FakeServerTransport.FakeConnection connection = transport.accept("client");
    connection.startDataTransfer();

    // C_TS_TA_1 with no information objects: the synthesized echo must be a TestCommandWithCp56Time
    // so the codec does not throw a ClassCastException at encode time.
    Asdu request =
        new Asdu(
            AsduType.C_TS_TA_1,
            false,
            Cause.ACTIVATION,
            false,
            false,
            OriginatorAddress.none(),
            CA,
            List.of());
    connection.deliverAsdu(request);

    List<Asdu> sent = connection.sentAsdus();
    assertEquals(1, sent.size());
    Asdu reply = sent.get(0);
    assertEquals(AsduType.C_TS_TA_1, reply.type());
    assertTrue(reply.objects().get(0) instanceof TestCommandWithCp56Time);

    server.close();
  }

  // --- F24: counter interrogation honors the QCC request field --------------------------------

  @Test
  void counterInterrogationSelectsRequestedGroupAndCause() {
    PointAddress counter1 = PointAddress.of(1, 200);
    PointAddress counter2 = PointAddress.of(1, 201);
    Station station =
        Station.builder(CA)
            .point(
                PointDefinition.of(
                    counter1,
                    PointType.INTEGRATED_TOTALS,
                    PointValue.counter(new BinaryCounterReading(11, 0, false, false, false)),
                    PointCapability.REPORTED))
            .point(
                PointDefinition.of(
                    counter2,
                    PointType.INTEGRATED_TOTALS,
                    PointValue.counter(new BinaryCounterReading(22, 0, false, false, false)),
                    PointCapability.REPORTED))
            .counterGroup(1, counter1)
            .counterGroup(2, counter2)
            .build();
    ServerConfig config =
        ServerConfig.builder()
            .station(station)
            .handler(new ServerHandler() {})
            .timeTagStyle(TimeTagStyle.NONE)
            .callbackExecutor(DIRECT)
            .build();
    DefaultIec60870Server server =
        new DefaultIec60870Server(transport, config, sessionFactory(config));
    server.start();
    FakeServerTransport.FakeConnection connection = transport.accept("client");
    connection.startDataTransfer();

    // A group-2 counter request must report only the group-2 point with the group-2 counter cause.
    connection.deliverAsdu(
        control(
            AsduType.C_CI_NA_1,
            Cause.ACTIVATION,
            new CounterInterrogationCommand(
                ZERO, new QualifierOfCounterInterrogation(2, FreezeMode.READ))));

    List<Asdu> group2 = connection.sentAsdus();
    List<Asdu> group2Monitors =
        group2.stream()
            .filter(a -> a.cause() == Cause.REQUESTED_BY_GROUP_2_COUNTER)
            .collect(Collectors.toList());
    assertEquals(1, group2Monitors.size(), "only the group-2 counter is reported");
    assertEquals(counter2.objectAddress(), group2Monitors.get(0).objects().get(0).address());
    assertTrue(
        group2.stream().noneMatch(a -> a.cause() == Cause.REQUESTED_BY_GROUP_1_COUNTER),
        "the group-1 counter must not be reported for a group-2 request");

    // A general (RQT=5) request reports every counter with the general counter cause.
    FakeServerTransport.FakeConnection general = transport.accept("client-general");
    general.startDataTransfer();
    general.deliverAsdu(
        control(
            AsduType.C_CI_NA_1,
            Cause.ACTIVATION,
            new CounterInterrogationCommand(
                ZERO, new QualifierOfCounterInterrogation(5, FreezeMode.READ))));

    long generalMonitors =
        general.sentAsdus().stream()
            .filter(a -> a.cause() == Cause.REQUESTED_BY_GENERAL_COUNTER)
            .count();
    assertEquals(2, generalMonitors, "a general request reports every integrated-totals point");

    server.close();
  }

  private void subscribe(DefaultIec60870Server server, List<ServerEvent> sink) {
    server
        .events()
        .subscribe(
            new Flow.Subscriber<>() {
              @Override
              public void onSubscribe(Flow.Subscription subscription) {
                subscription.request(Long.MAX_VALUE);
              }

              @Override
              public void onNext(ServerEvent item) {
                sink.add(item);
              }

              @Override
              public void onError(Throwable throwable) {}

              @Override
              public void onComplete() {}
            });
  }

  private SingleCommand singleCommand() {
    return new SingleCommand(POINT.objectAddress(), true, new QualifierOfCommand(0, false));
  }

  @Test
  void repliesAreQueuedUntilDataTransferStarts() {
    DefaultIec60870Server server = server(new ServerHandler() {});
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
