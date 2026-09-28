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
import com.digitalpetri.iec60870.asdu.element.BinaryCounterReading;
import com.digitalpetri.iec60870.asdu.element.FreezeMode;
import com.digitalpetri.iec60870.asdu.element.QualifierOfCounterInterrogation;
import com.digitalpetri.iec60870.asdu.object.CounterInterrogationCommand;
import com.digitalpetri.iec60870.asdu.object.IntegratedTotals;
import com.digitalpetri.iec60870.fakes.FakeServerTransport;
import com.digitalpetri.iec60870.fakes.FakeSession;
import com.digitalpetri.iec60870.point.PointCapability;
import com.digitalpetri.iec60870.point.PointType;
import com.digitalpetri.iec60870.point.PointValue;
import com.digitalpetri.iec60870.point.TimeTagStyle;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

class ServerCounterInterrogationTest {

  private static final CommonAddress CA = CommonAddress.of(1);
  private static final PointAddress COUNTER = PointAddress.of(1, 200);
  private static final BinaryCounterReading INITIAL =
      new BinaryCounterReading(123, 0, false, false, false);

  @TestFactory
  Stream<DynamicTest> unsupportedFreezeAndResetModesAreRejected() {
    return Stream.of(FreezeMode.FREEZE_NO_RESET, FreezeMode.FREEZE_WITH_RESET, FreezeMode.RESET)
        .map(
            mode ->
                dynamicTest(
                    mode.name(),
                    () -> {
                      var transport = new FakeServerTransport();
                      try (DefaultIec60870Server server =
                          server(transport, new ServerHandler() {})) {
                        server.start();
                        FakeServerTransport.FakeConnection connection = transport.accept("client");
                        connection.startDataTransfer();
                        Asdu request = request(mode);
                        connection.deliverAsdu(roundTrip(request));

                        List<Asdu> sent = connection.sentAsdus();
                        assertEquals(
                            1, sent.size(), "a rejected operation must not emit data or ACT_TERM");
                        Asdu reply = roundTrip(sent.get(0));
                        assertEquals(AsduType.C_CI_NA_1, reply.type());
                        assertEquals(Cause.ACTIVATION_CONFIRMATION, reply.cause());
                        assertTrue(
                            reply.negative(),
                            "unsupported operations must not be acknowledged as done");
                        assertEquals(request.objects(), reply.objects());
                        assertEquals(INITIAL, currentCounter(server));
                      }
                    }));
  }

  @Test
  void readStillReportsTheCounterAndTerminates() {
    var transport = new FakeServerTransport();
    try (DefaultIec60870Server server = server(transport, new ServerHandler() {})) {
      server.start();
      FakeServerTransport.FakeConnection connection = transport.accept("client");
      connection.startDataTransfer();
      Asdu request = request(FreezeMode.READ);
      connection.deliverAsdu(roundTrip(request));

      List<Asdu> sent = connection.sentAsdus();
      assertEquals(3, sent.size());
      assertEquals(Cause.ACTIVATION_CONFIRMATION, sent.get(0).cause());
      assertFalse(sent.get(0).negative());
      assertEquals(request.objects(), sent.get(0).objects());
      Asdu data = roundTrip(sent.get(1));
      assertEquals(AsduType.M_IT_NA_1, data.type());
      assertEquals(Cause.REQUESTED_BY_GENERAL_COUNTER, data.cause());
      assertEquals(List.of(new IntegratedTotals(COUNTER.objectAddress(), INITIAL)), data.objects());
      assertEquals(Cause.ACTIVATION_TERMINATION, sent.get(2).cause());
      assertFalse(sent.get(2).negative());
      assertEquals(INITIAL, currentCounter(server));
    }
  }

  @Test
  void rawHookCanImplementResetBeforeBuiltInHandling() {
    var reset = new BinaryCounterReading(0, 0, false, false, false);
    ServerHandler handler =
        new ServerHandler() {
          @Override
          public boolean onRawAsdu(ServerContext context, Asdu asdu) {
            assertEquals(request(FreezeMode.RESET), asdu);
            context
                .station()
                .orElseThrow()
                .updateValue(COUNTER.objectAddress(), PointValue.counter(reset));
            context.send(
                new Asdu(
                    asdu.type(),
                    false,
                    Cause.ACTIVATION_CONFIRMATION,
                    false,
                    false,
                    asdu.originatorAddress(),
                    asdu.commonAddress(),
                    asdu.objects()));
            return true;
          }
        };
    var transport = new FakeServerTransport();
    try (DefaultIec60870Server server = server(transport, handler)) {
      server.start();
      FakeServerTransport.FakeConnection connection = transport.accept("client");
      connection.startDataTransfer();
      connection.deliverAsdu(roundTrip(request(FreezeMode.RESET)));

      assertEquals(reset, currentCounter(server));
      assertEquals(1, connection.sentAsdus().size());
      Asdu reply = roundTrip(connection.sentAsdus().get(0));
      assertEquals(Cause.ACTIVATION_CONFIRMATION, reply.cause());
      assertFalse(reply.negative());
      assertEquals(request(FreezeMode.RESET).objects(), reply.objects());
    }
  }

  private static Object currentCounter(DefaultIec60870Server server) {
    return server
        .stations()
        .station(CA)
        .orElseThrow()
        .currentValue(COUNTER.objectAddress())
        .orElseThrow()
        .value();
  }

  private static DefaultIec60870Server server(
      FakeServerTransport transport, ServerHandler handler) {
    Station station =
        Station.builder(CA)
            .point(
                PointDefinition.of(
                    COUNTER,
                    PointType.INTEGRATED_TOTALS,
                    PointValue.counter(INITIAL),
                    PointCapability.REPORTED))
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

  private static Asdu request(FreezeMode mode) {
    return new Asdu(
        AsduType.C_CI_NA_1,
        false,
        Cause.ACTIVATION,
        false,
        false,
        OriginatorAddress.none(),
        CA,
        List.of(
            new CounterInterrogationCommand(
                InformationObjectAddress.of(0), new QualifierOfCounterInterrogation(5, mode))));
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
