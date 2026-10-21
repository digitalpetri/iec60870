package com.digitalpetri.iec60870.test.integration;

import static java.util.Objects.requireNonNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.digitalpetri.iec60870.NegativeConfirmationException;
import com.digitalpetri.iec60870.address.CommonAddress;
import com.digitalpetri.iec60870.address.PointAddress;
import com.digitalpetri.iec60870.asdu.AsduType;
import com.digitalpetri.iec60870.asdu.Cause;
import com.digitalpetri.iec60870.asdu.element.BinaryCounterReading;
import com.digitalpetri.iec60870.asdu.element.FreezeMode;
import com.digitalpetri.iec60870.asdu.element.QualifierOfCounterInterrogation;
import com.digitalpetri.iec60870.asdu.element.QualifierOfResetProcess;
import com.digitalpetri.iec60870.client.CounterInterrogationResult;
import com.digitalpetri.iec60870.client.Iec60870Client;
import com.digitalpetri.iec60870.client.InterrogationResult;
import com.digitalpetri.iec60870.client.TestResult;
import com.digitalpetri.iec60870.point.PointCapability;
import com.digitalpetri.iec60870.point.PointType;
import com.digitalpetri.iec60870.point.PointValue;
import com.digitalpetri.iec60870.server.Iec60870Server;
import com.digitalpetri.iec60870.server.PointDefinition;
import com.digitalpetri.iec60870.server.ResetDecision;
import com.digitalpetri.iec60870.server.ResetRequest;
import com.digitalpetri.iec60870.server.ServerContext;
import com.digitalpetri.iec60870.server.ServerHandler;
import com.digitalpetri.iec60870.server.Station;
import com.digitalpetri.iec60870.tcp.TcpIec104Client;
import com.digitalpetri.iec60870.tcp.TcpIec104Server;
import java.io.IOException;
import java.net.ServerSocket;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The correlated counter interrogation, test, and reset methods of the client against the built-in
 * server over a real TCP connection.
 */
class CorrelatedServicesIntegrationTest {

  private static final CommonAddress STATION = CommonAddress.of(1);
  private static final PointAddress COUNTER_1 = PointAddress.of(1, 700);
  private static final PointAddress COUNTER_2 = PointAddress.of(1, 701);

  private final List<ResetRequest> resets = new CopyOnWriteArrayList<>();

  private @Nullable Iec60870Server server;
  private @Nullable Iec60870Client client;

  @BeforeEach
  void setUp() throws IOException {
    int port = reserveEphemeralPort();

    Station station =
        Station.builder(STATION)
            .point(counter(COUNTER_1, 11))
            .point(counter(COUNTER_2, 22))
            .counterGroup(1, COUNTER_1)
            .build();

    ServerHandler handler =
        new ServerHandler() {
          @Override
          public ResetDecision onReset(ServerContext context, ResetRequest request) {
            resets.add(request);
            return request.qualifier().equals(QualifierOfResetProcess.GENERAL)
                ? ResetDecision.accept()
                : ResetDecision.reject(Cause.UNKNOWN_CAUSE);
          }
        };

    Iec60870Server server =
        TcpIec104Server.builder()
            .bindAddress("127.0.0.1")
            .port(port)
            .addStation(station)
            .handler(handler)
            .build();
    this.server = server;
    server.start();

    Iec60870Client client = TcpIec104Client.builder().host("127.0.0.1").port(port).build();
    this.client = client;
    client.connect();
  }

  @AfterEach
  void tearDown() {
    if (client != null) {
      client.close();
    }
    if (server != null) {
      server.close();
    }
  }

  @Test
  void generalCounterReadReturnsEveryCounter() {
    CounterInterrogationResult result = client().counterInterrogate(STATION);

    List<InterrogationResult.PointEntry> values = result.pointValues();
    assertEquals(2, values.size());
    assertEquals(COUNTER_1, values.get(0).address());
    assertEquals(11, ((BinaryCounterReading) values.get(0).value().value()).value());
    assertEquals(COUNTER_2, values.get(1).address());
    assertEquals(22, ((BinaryCounterReading) values.get(1).value().value()).value());
  }

  @Test
  void groupCounterReadReturnsTheGroup() {
    CounterInterrogationResult result =
        client()
            .counterInterrogate(STATION, QualifierOfCounterInterrogation.group(1, FreezeMode.READ));

    assertEquals(1, result.objects().size());
    assertEquals(COUNTER_1, result.pointValues().get(0).address());
  }

  @Test
  void builtInServerRejectsFreeze() {
    var e =
        assertThrows(
            NegativeConfirmationException.class,
            () ->
                client()
                    .counterInterrogate(
                        STATION,
                        QualifierOfCounterInterrogation.general(FreezeMode.FREEZE_NO_RESET)));

    assertTrue(e.asdu().orElseThrow().negative());
    assertEquals(AsduType.C_CI_NA_1, e.asdu().orElseThrow().type());

    // The rejected freeze left nothing in flight; a read follows normally.
    assertEquals(2, client().counterInterrogate(STATION).objects().size());
  }

  @Test
  void testCommandRoundTrips() {
    TestResult timed = client().test(STATION);
    assertEquals(AsduType.C_TS_TA_1, timed.confirmation().type());
    assertFalse(timed.roundTripTime().isNegative());

    TestResult untimed = client().test(STATION, AsduType.C_TS_NA_1);
    assertEquals(AsduType.C_TS_NA_1, untimed.confirmation().type());
  }

  @Test
  void resetProcessIsAcceptedOrRejectedByTheHandler() {
    client().resetProcess(STATION, QualifierOfResetProcess.GENERAL);

    var e =
        assertThrows(
            NegativeConfirmationException.class,
            () -> client().resetProcess(STATION, QualifierOfResetProcess.EVENT_BUFFER));
    assertEquals(Cause.UNKNOWN_CAUSE, e.cause());

    assertEquals(
        List.of(QualifierOfResetProcess.GENERAL, QualifierOfResetProcess.EVENT_BUFFER),
        resets.stream().map(ResetRequest::qualifier).toList());
  }

  private Iec60870Client client() {
    return requireNonNull(client);
  }

  private static PointDefinition<BinaryCounterReading> counter(PointAddress address, int value) {
    return PointDefinition.of(
        address,
        PointType.INTEGRATED_TOTALS,
        PointValue.counter(new BinaryCounterReading(value, 0, false, false, false)),
        PointCapability.REPORTED);
  }

  private static int reserveEphemeralPort() throws IOException {
    try (ServerSocket socket = new ServerSocket(0)) {
      socket.setReuseAddress(true);
      return socket.getLocalPort();
    }
  }
}
