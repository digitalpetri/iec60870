package com.digitalpetri.iec60870.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.digitalpetri.iec60870.address.CommonAddress;
import com.digitalpetri.iec60870.address.InformationObjectAddress;
import com.digitalpetri.iec60870.address.OriginatorAddress;
import com.digitalpetri.iec60870.address.PointAddress;
import com.digitalpetri.iec60870.asdu.Asdu;
import com.digitalpetri.iec60870.asdu.AsduType;
import com.digitalpetri.iec60870.asdu.Cause;
import com.digitalpetri.iec60870.asdu.InformationObject;
import com.digitalpetri.iec60870.asdu.element.QualifierOfCommand;
import com.digitalpetri.iec60870.asdu.object.ClockSynchronizationCommand;
import com.digitalpetri.iec60870.asdu.object.ReadCommand;
import com.digitalpetri.iec60870.asdu.object.SingleCommand;
import com.digitalpetri.iec60870.asdu.object.SinglePointWithCp56Time;
import com.digitalpetri.iec60870.asdu.time.Cp56Time2a;
import com.digitalpetri.iec60870.fakes.FakeServerTransport;
import com.digitalpetri.iec60870.fakes.FakeSession;
import com.digitalpetri.iec60870.point.PointCapability;
import com.digitalpetri.iec60870.point.PointType;
import com.digitalpetri.iec60870.point.PointValue;
import com.digitalpetri.iec60870.point.TimePolicy;
import com.digitalpetri.iec60870.point.TimeTagStyle;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/** The server encodes published time tags with its configured {@link TimePolicy}. */
class ServerTimePolicyTest {

  private static final CommonAddress CA = CommonAddress.of(1);
  private static final PointAddress POINT = PointAddress.of(1, 100);
  private static final Instant TIME = Instant.parse("2026-06-01T12:00:00Z");
  private static final TimePolicy BERLIN = TimePolicy.of(ZoneId.of("Europe/Berlin"));

  /** 2026-06-01 14:00 CEST, SU=1, Monday. */
  private static final Cp56Time2a BERLIN_TAG =
      new Cp56Time2a(0, 0, 14, 1, 1, 6, 26, false, true, true);

  private final FakeServerTransport transport = new FakeServerTransport();

  @Test
  void publishUsesTimePolicy() {
    DefaultIec60870Server server = server(BERLIN, new ServerHandler() {});
    FakeServerTransport.FakeConnection connection = connect(server);

    server.publish(POINT, PointValue.single(false).withTimestamp(TIME), Cause.SPONTANEOUS);

    assertEquals(BERLIN_TAG, timeOf(connection.sentAsdus().get(0)));
    server.close();
  }

  @Test
  void defaultPublishIsUtc() {
    DefaultIec60870Server server = server(null, new ServerHandler() {});
    FakeServerTransport.FakeConnection connection = connect(server);

    server.publish(POINT, PointValue.single(false).withTimestamp(TIME), Cause.SPONTANEOUS);

    assertEquals(Cp56Time2a.from(TIME, ZoneOffset.UTC), timeOf(connection.sentAsdus().get(0)));
    server.close();
  }

  @Test
  void readAnswerUsesTimePolicy() {
    DefaultIec60870Server server = server(BERLIN, new ServerHandler() {});
    FakeServerTransport.FakeConnection connection = connect(server);

    connection.deliverAsdu(
        control(AsduType.C_RD_NA_1, Cause.REQUEST, new ReadCommand(POINT.objectAddress())));

    assertEquals(BERLIN_TAG, timeOf(connection.sentAsdus().get(0)));
    server.close();
  }

  @Test
  void returnInformationUsesTimePolicy() {
    ServerHandler handler =
        new ServerHandler() {
          @Override
          public CommandDecision onCommand(ServerContext context, CommandRequest request) {
            return CommandDecision.acceptAndUpdate(PointValue.single(false).withTimestamp(TIME));
          }
        };
    DefaultIec60870Server server = server(BERLIN, handler);
    FakeServerTransport.FakeConnection connection = connect(server);

    connection.deliverAsdu(
        control(
            AsduType.C_SC_NA_1,
            Cause.ACTIVATION,
            new SingleCommand(POINT.objectAddress(), false, new QualifierOfCommand(0, false))));

    Asdu returnInformation = connection.sentAsdus().get(1);
    assertEquals(Cause.RETURN_REMOTE, returnInformation.cause());
    assertEquals(BERLIN_TAG, timeOf(returnInformation));
    server.close();
  }

  @Test
  void contextTimePolicyResolvesClockSync() {
    AtomicReference<@Nullable TimePolicy> seenPolicy = new AtomicReference<>();
    AtomicReference<Optional<Instant>> resolved = new AtomicReference<>(Optional.empty());
    ServerHandler handler =
        new ServerHandler() {
          @Override
          public ClockSyncDecision onClockSync(ServerContext context, ClockSyncRequest request) {
            seenPolicy.set(context.timePolicy());
            resolved.set(context.timePolicy().toInstant(request.time()));
            return ClockSyncDecision.accept();
          }
        };
    DefaultIec60870Server server = server(BERLIN, handler);
    FakeServerTransport.FakeConnection connection = connect(server);

    connection.deliverAsdu(
        control(
            AsduType.C_CS_NA_1,
            Cause.ACTIVATION,
            new ClockSynchronizationCommand(InformationObjectAddress.of(0), BERLIN_TAG)));

    assertSame(BERLIN, seenPolicy.get());
    assertEquals(Optional.of(TIME), resolved.get());
    server.close();
  }

  private DefaultIec60870Server server(@Nullable TimePolicy policy, ServerHandler handler) {
    Station station =
        Station.builder(CA)
            .point(
                PointDefinition.of(
                    POINT,
                    PointType.SINGLE_POINT,
                    PointValue.single(true).withTimestamp(TIME),
                    PointCapability.REPORTED,
                    PointCapability.READABLE,
                    PointCapability.COMMANDABLE))
            .build();
    ServerConfig.Builder builder =
        ServerConfig.builder()
            .station(station)
            .handler(handler)
            .timeTagStyle(TimeTagStyle.CP56)
            .callbackExecutor(Runnable::run);
    if (policy != null) {
      builder.timePolicy(policy);
    }
    ServerConfig config = builder.build();
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

  private FakeServerTransport.FakeConnection connect(DefaultIec60870Server server) {
    server.start();
    FakeServerTransport.FakeConnection connection = transport.accept("client");
    connection.startDataTransfer();
    return connection;
  }

  private static Asdu control(AsduType type, Cause cause, InformationObject object) {
    return new Asdu(
        type, false, cause, false, false, OriginatorAddress.none(), CA, List.of(object));
  }

  private static Cp56Time2a timeOf(Asdu asdu) {
    assertEquals(AsduType.M_SP_TB_1, asdu.type());
    return assertInstanceOf(SinglePointWithCp56Time.class, asdu.objects().get(0)).time();
  }
}
