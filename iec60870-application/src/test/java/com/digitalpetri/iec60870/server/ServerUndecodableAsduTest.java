package com.digitalpetri.iec60870.server;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.digitalpetri.iec60870.AsduDecodeException;
import com.digitalpetri.iec60870.UnsupportedAsduTypeException;
import com.digitalpetri.iec60870.address.CommonAddress;
import com.digitalpetri.iec60870.fakes.FakeServerTransport;
import com.digitalpetri.iec60870.fakes.FakeSession;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Flow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests how {@link DefaultIec60870Server} answers ASDUs its session could not decode: it publishes
 * {@link ServerEvent.UndecodableAsdu}, mirrors an undefined type identification with cause 44 and
 * an undefined cause with cause 45 (IEC 60870-5-101 7.2.3), and keeps the connection open.
 */
class ServerUndecodableAsduTest {

  private final FakeServerTransport transport = new FakeServerTransport();
  private final List<ServerEvent> events = new CopyOnWriteArrayList<>();
  private FakeServerTransport.FakeConnection connection;

  @BeforeEach
  void setUp() {
    ServerConfig config =
        ServerConfig.builder()
            .station(Station.builder(CommonAddress.of(1)).build())
            .callbackExecutor(Runnable::run)
            .build();
    DefaultIec60870Server server =
        new DefaultIec60870Server(
            transport,
            config,
            (connection, events, scheduler) -> {
              FakeSession session =
                  FakeSession.server(events, config.maxOutboundQueue(), config.eventQueuePolicy());
              ((FakeServerTransport.FakeConnection) connection).attachSession(session);
              return session;
            });
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
                events.add(item);
              }

              @Override
              public void onError(Throwable throwable) {}

              @Override
              public void onComplete() {}
            });
    server.start();
    connection = transport.accept("client");
    connection.startDataTransfer();
  }

  @Test
  void undefinedTypeIdIsMirroredWithCause44() {
    byte[] request = hex("88010300010064000001");
    connection.deliverUndecodableAsdu(request, new UnsupportedAsduTypeException("136"));

    assertEquals(1, connection.sentRawAsdus().size());
    assertArrayEquals(hex("88016c00010064000001"), connection.sentRawAsdus().get(0));
    assertReportedAndOpen(request);
  }

  @Test
  void fileTransferTypeWithoutCodecIsMirroredWithCause44() {
    byte[] request = hex("780106000100640000010203");
    connection.deliverUndecodableAsdu(request, new UnsupportedAsduTypeException("F_FR_NA_1"));

    assertArrayEquals(hex("78016c000100640000010203"), connection.sentRawAsdus().get(0));
  }

  @Test
  void undefinedCauseIsMirroredWithCause45AndKeepsTheTestBit() {
    // C_IC_NA_1 with T=1 and the private cause 48.
    byte[] request = hex("6401b000010000000014");
    connection.deliverUndecodableAsdu(request, new AsduDecodeException("cause 48"));

    assertArrayEquals(hex("6401ed00010000000014"), connection.sentRawAsdus().get(0));
    assertReportedAndOpen(request);
  }

  @Test
  void malformedObjectsHaveNoMirror() {
    // M_ME_NC_1 with a known cause but a float cut short.
    byte[] request = hex("0d0103000100640000000000");
    connection.deliverUndecodableAsdu(request, new AsduDecodeException("truncated"));

    assertTrue(connection.sentRawAsdus().isEmpty());
    assertReportedAndOpen(request);
  }

  @Test
  void negativeAsduIsNotMirroredAgain() {
    assertNull(DefaultIec60870Server.undecodableMirror(hex("88016c00010064000001")));
    assertNull(DefaultIec60870Server.undecodableMirror(hex("8801")));
  }

  @Test
  void sessionWithoutRawSendsKeepsTheConnectionOpen() {
    connection.disableRawSends();
    byte[] request = hex("88010300010064000001");
    connection.deliverUndecodableAsdu(request, new UnsupportedAsduTypeException("136"));

    assertTrue(connection.sentRawAsdus().isEmpty());
    assertReportedAndOpen(request);
  }

  private void assertReportedAndOpen(byte[] request) {
    List<ServerEvent.UndecodableAsdu> undecodable =
        events.stream()
            .filter(ServerEvent.UndecodableAsdu.class::isInstance)
            .map(ServerEvent.UndecodableAsdu.class::cast)
            .toList();
    assertEquals(1, undecodable.size());
    assertArrayEquals(request, undecodable.get(0).asdu());
    assertTrue(events.stream().noneMatch(ServerEvent.AsduReceived.class::isInstance));
    assertTrue(events.stream().noneMatch(ServerEvent.ConnectionClosed.class::isInstance));
    assertEquals(0, connection.closeCount());
  }

  private static byte[] hex(String hex) {
    return HexFormat.of().parseHex(hex);
  }
}
