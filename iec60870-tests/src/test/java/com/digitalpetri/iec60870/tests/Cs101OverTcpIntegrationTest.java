package com.digitalpetri.iec60870.tests;

import static java.util.Objects.requireNonNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.digitalpetri.iec60870.ProtocolProfile;
import com.digitalpetri.iec60870.address.CommonAddress;
import com.digitalpetri.iec60870.address.PointAddress;
import com.digitalpetri.iec60870.asdu.Cause;
import com.digitalpetri.iec60870.asdu.object.SingleCommand;
import com.digitalpetri.iec60870.client.ClientEvent;
import com.digitalpetri.iec60870.client.CommandResult;
import com.digitalpetri.iec60870.client.Iec60870Client;
import com.digitalpetri.iec60870.client.InterrogationResult;
import com.digitalpetri.iec60870.cs101.LinkSettings;
import com.digitalpetri.iec60870.point.PointCapability;
import com.digitalpetri.iec60870.point.PointType;
import com.digitalpetri.iec60870.point.PointValue;
import com.digitalpetri.iec60870.server.CommandDecision;
import com.digitalpetri.iec60870.server.CommandRequest;
import com.digitalpetri.iec60870.server.Iec60870Server;
import com.digitalpetri.iec60870.server.PointDefinition;
import com.digitalpetri.iec60870.server.ServerContext;
import com.digitalpetri.iec60870.server.ServerHandler;
import com.digitalpetri.iec60870.server.Station;
import com.digitalpetri.iec60870.tcp.TcpIec101Client;
import com.digitalpetri.iec60870.tcp.TcpIec101Server;
import java.io.IOException;
import java.net.ServerSocket;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * End-to-end in-JVM integration tests that run a real {@link Iec60870Server} and {@link
 * Iec60870Client} over a loopback TCP connection ({@code 127.0.0.1} on an ephemeral port) using the
 * <em>101-over-TCP</em> stack: {@link TcpIec101Server} and {@link TcpIec101Client} carry the IEC
 * 60870-5-101 FT1.2 link layer over the Netty octet transport, with no serial port and no Docker.
 *
 * <p>Both peers run a balanced FT1.2 link ({@link LinkSettings#balanced()}) with a matching 101
 * wire {@link ProtocolProfile} (1-octet cause of transmission and common address, 2-octet
 * information-object address). Unlike the synchronous in-process loopback variant, the link reset
 * and every stop-and-wait acknowledgement here cross a real TCP connection on Netty's event loops,
 * so {@link Iec60870Client#connect() connect()} drives the balanced bring-up over the wire and the
 * tests use bounded {@link Await} polling rather than fixed sleeps for the asynchronous spontaneous
 * delivery. One station hosts a reported monitor point and a commandable point; the three tests
 * cover the three FT1.2 message directions over the balanced link — a controlling-station
 * interrogation, a controlling-station command, and a controlled-station spontaneous publish — and
 * each asserts up front, through the shared {@link #startAndConnect(ServerHandler) wiring helper},
 * that {@code connect()} both connected the transport and started data transfer (the balanced link
 * reset completed). Per-test teardown closes both facades.
 */
class Cs101OverTcpIntegrationTest {

  private static final CommonAddress STATION = CommonAddress.of(1);
  private static final PointAddress MONITOR_POINT = PointAddress.of(1, 100);
  private static final PointAddress COMMAND_POINT = PointAddress.of(1, 200);

  /** The 101 wire profile: 1-octet cause of transmission, 1-octet common address, 2-octet IOA. */
  private static final ProtocolProfile PROFILE = new ProtocolProfile(1, 1, 2, 255);

  private @Nullable Iec60870Server server;
  private @Nullable Iec60870Client client;

  @AfterEach
  void tearDown() {
    if (client != null) {
      client.close();
    }
    if (server != null) {
      server.close();
    }
  }

  /**
   * {@code connect()} drives the balanced FT1.2 link reset to completion over real loopback TCP.
   */
  @Test
  void connectCompletesBalancedLinkReset() throws IOException {
    EventCollector events = startAndConnect(new ServerHandler() {});
    Iec60870Client client = requireNonNull(this.client);

    // connect() returns once the link reset completes; isConnected() is a direct state query and is
    // true synchronously, while the DataTransferStarted event is delivered on the callback
    // executor,
    // so it is awaited rather than asserted synchronously.
    assertTrue(client.isConnected(), "client should be connected after connect()");
    Await.until(
        "data-transfer-started event after the balanced FT1.2 link reset",
        () -> events.hasAny(ClientEvent.DataTransferStarted.class));
  }

  /**
   * A general interrogation over the 101-over-TCP balanced link returns the station's reported
   * monitor point.
   */
  @Test
  void interrogationRoundTripReturnsStationPoints() throws IOException {
    startAndConnect(new ServerHandler() {});
    Iec60870Client client = requireNonNull(this.client);

    InterrogationResult interrogation = client.interrogate(STATION);

    assertTrue(interrogation.terminated(), "interrogation should end with ACT_TERM");
    assertEquals(STATION, interrogation.station());
    List<PointAddress> reported =
        interrogation.pointValues().stream().map(InterrogationResult.PointEntry::address).toList();
    assertTrue(
        reported.contains(MONITOR_POINT),
        "the reported monitor point should be returned by general interrogation");
  }

  /**
   * A single command over the 101-over-TCP balanced link is confirmed positively and the server
   * handler observes the command.
   */
  @Test
  void commandRoundTripIsConfirmedPositively() throws IOException {
    AtomicReference<@Nullable PointAddress> commanded = new AtomicReference<>();
    startAndConnect(
        new ServerHandler() {
          @Override
          public CommandDecision onCommand(ServerContext context, CommandRequest request) {
            commanded.set(request.target());
            boolean on = request.commandObject() instanceof SingleCommand sc && sc.on();
            return CommandDecision.acceptAndUpdate(PointValue.single(on));
          }
        });
    Iec60870Client client = requireNonNull(this.client);

    CommandResult result = client.commands().single(COMMAND_POINT, true);

    assertTrue(result.positive(), "the command should be confirmed positively");
    assertEquals(COMMAND_POINT, result.target());
    assertEquals(Cause.ACTIVATION_CONFIRMATION, result.cause());
    assertEquals(
        COMMAND_POINT,
        commanded.get(),
        "the server handler must have been invoked for the command");
  }

  /**
   * A controlled-station spontaneous publish over the 101-over-TCP balanced link reaches the client
   * as a {@link ClientEvent.PointUpdated} carrying the published value and cause.
   */
  @Test
  void spontaneousPublishReachesClient() throws IOException {
    EventCollector events = startAndConnect(new ServerHandler() {});
    Iec60870Server server = requireNonNull(this.server);

    server.publish(MONITOR_POINT, PointValue.single(true), Cause.SPONTANEOUS);

    Await.until(
        "spontaneous point update for the monitor point",
        () ->
            events.hasMatch(
                ClientEvent.PointUpdated.class,
                update ->
                    update.address().equals(MONITOR_POINT)
                        && update.cause() == Cause.SPONTANEOUS
                        && Boolean.TRUE.equals(update.value().value())));
  }

  // --- Wiring helper ----------------------------------------------------------------------------

  /**
   * Builds and starts a one-station {@link TcpIec101Server} on an ephemeral loopback port, builds a
   * {@link TcpIec101Client} pointed at it (both balanced, both on the 101 profile), subscribes an
   * {@link EventCollector} before connecting, then connects — which performs the balanced FT1.2
   * link reset over the real TCP connection.
   *
   * @param handler the server handler under test.
   * @return the event collector subscribed to the client's events.
   * @throws IOException if an ephemeral loopback port cannot be reserved.
   */
  private EventCollector startAndConnect(ServerHandler handler) throws IOException {
    int port = reserveEphemeralPort();

    Station station =
        Station.builder(STATION)
            .point(
                PointDefinition.of(
                    MONITOR_POINT,
                    PointType.SINGLE_POINT,
                    PointValue.single(false),
                    PointCapability.REPORTED))
            .point(
                PointDefinition.of(
                    COMMAND_POINT,
                    PointType.SINGLE_POINT,
                    PointValue.single(false),
                    PointCapability.COMMANDABLE))
            .build();

    Iec60870Server server =
        TcpIec101Server.builder()
            .bindAddress("127.0.0.1")
            .port(port)
            .profile(PROFILE)
            .linkSettings(LinkSettings.balanced().build())
            .addStation(station)
            .handler(handler)
            .build();
    server.start();
    this.server = server;

    Iec60870Client client =
        TcpIec101Client.builder()
            .host("127.0.0.1")
            .port(port)
            .profile(PROFILE)
            .linkSettings(LinkSettings.balanced().build())
            .startDataTransferOnConnect(true)
            .build();
    this.client = client;

    EventCollector events = new EventCollector();
    client.events().subscribe(events);
    client.connect();
    return events;
  }

  private static int reserveEphemeralPort() throws IOException {
    try (ServerSocket socket = new ServerSocket(0)) {
      socket.setReuseAddress(true);
      return socket.getLocalPort();
    }
  }
}
