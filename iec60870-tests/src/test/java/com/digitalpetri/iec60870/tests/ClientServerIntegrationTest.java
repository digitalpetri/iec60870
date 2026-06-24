package com.digitalpetri.iec60870.tests;

import static java.util.Objects.requireNonNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.digitalpetri.iec60870.address.CommonAddress;
import com.digitalpetri.iec60870.address.PointAddress;
import com.digitalpetri.iec60870.asdu.Cause;
import com.digitalpetri.iec60870.asdu.element.DoublePointState;
import com.digitalpetri.iec60870.asdu.object.SingleCommand;
import com.digitalpetri.iec60870.client.ClientEvent;
import com.digitalpetri.iec60870.client.Command;
import com.digitalpetri.iec60870.client.CommandMode;
import com.digitalpetri.iec60870.client.CommandResult;
import com.digitalpetri.iec60870.client.Iec60870Client;
import com.digitalpetri.iec60870.client.InterrogationResult;
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
import com.digitalpetri.iec60870.transport.tcp.TcpIec104Client;
import com.digitalpetri.iec60870.transport.tcp.TcpIec104Server;
import java.io.IOException;
import java.net.ServerSocket;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * End-to-end in-JVM integration tests that run a real {@link Iec60870Server} and {@link
 * Iec60870Client} over a loopback TCP connection ({@code 127.0.0.1} on an ephemeral port), with no
 * Docker or external broker.
 *
 * <p>One server hosts a single station carrying several point types. A command point is accepted by
 * the configured handler while a second command point is rejected, so the tests exercise both
 * confirmation outcomes. The scenarios walk the full session lifecycle — {@code connect} -> {@code
 * STARTDT} -> general interrogation -> direct-execute command -> select-before-operate command -> a
 * spontaneous server {@code publish} delivered as a {@link ClientEvent.PointUpdated} -> clock
 * synchronization -> {@code STOPDT} -> {@code close} — using bounded {@link Await} polling rather
 * than fixed sleeps so the tests neither race a slow callback nor hang.
 */
class ClientServerIntegrationTest {

  private static final CommonAddress STATION = CommonAddress.of(1);

  private static final PointAddress SINGLE_POINT = PointAddress.of(1, 100);
  private static final PointAddress DOUBLE_POINT = PointAddress.of(1, 101);
  private static final PointAddress SCALED_POINT = PointAddress.of(1, 102);
  private static final PointAddress FLOAT_POINT = PointAddress.of(1, 103);

  private static final PointAddress ACCEPTED_COMMAND = PointAddress.of(1, 200);
  private static final PointAddress REJECTED_COMMAND = PointAddress.of(1, 201);

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

  @Test
  void fullLifecycleHappyPath() throws Exception {
    EventCollector events = startServerAndClient();
    Iec60870Client client = requireNonNull(this.client);
    Iec60870Server server = requireNonNull(this.server);

    // connect() also performs the STARTDT handshake (startDataTransferOnConnect defaults true).
    client.connect();
    assertTrue(client.isConnected(), "client should be connected after connect()");
    Await.until("connection-opened event", () -> events.hasAny(ClientEvent.ConnectionOpened.class));
    Await.until(
        "data-transfer-started event", () -> events.hasAny(ClientEvent.DataTransferStarted.class));

    // General (station) interrogation must return every reported point of the station.
    InterrogationResult interrogation = client.interrogate(STATION);
    assertTrue(interrogation.terminated(), "interrogation should end with ACT_TERM");
    List<InterrogationResult.PointEntry> points = interrogation.pointValues();
    assertTrue(
        points.stream().anyMatch(p -> p.address().equals(SINGLE_POINT)),
        "single point should be reported by general interrogation");
    assertTrue(
        points.stream().anyMatch(p -> p.address().equals(DOUBLE_POINT)),
        "double point should be reported by general interrogation");
    assertTrue(
        points.stream().anyMatch(p -> p.address().equals(SCALED_POINT)),
        "scaled point should be reported by general interrogation");
    assertTrue(
        points.stream().anyMatch(p -> p.address().equals(FLOAT_POINT)),
        "short-float point should be reported by general interrogation");

    // Direct-execute command on the accepted point is confirmed positively.
    CommandResult directExecute =
        client.commands().send(Command.single(ACCEPTED_COMMAND, true), CommandMode.directExecute());
    assertTrue(directExecute.positive(), "direct-execute command should be confirmed positively");
    assertEquals(ACCEPTED_COMMAND, directExecute.target());

    // Select-before-operate command on the accepted point is also confirmed positively.
    CommandResult selectBeforeOperate =
        client
            .commands()
            .send(Command.single(ACCEPTED_COMMAND, false), CommandMode.selectBeforeOperate());
    assertTrue(
        selectBeforeOperate.positive(),
        "select-before-operate command should be confirmed positively");

    // A spontaneous server publish must reach the client as a PointUpdated event.
    server.publish(SINGLE_POINT, PointValue.single(true), Cause.SPONTANEOUS);
    Await.until(
        "spontaneous point update for the single point",
        () ->
            events.hasMatch(
                ClientEvent.PointUpdated.class,
                u -> u.address().equals(SINGLE_POINT) && Boolean.TRUE.equals(u.value().value())));

    // Clock synchronization is accepted by the default handler.
    client.synchronizeClock(STATION, Instant.now());

    // STOPDT then close: data transfer stops cleanly and the client reports disconnected.
    client.stopDataTransfer();
    Await.until(
        "data-transfer-stopped event", () -> events.hasAny(ClientEvent.DataTransferStopped.class));

    client.close();
    Await.until("client disconnected after close", () -> !client.isConnected());
  }

  @Test
  void generalInterrogationReturnsConfiguredStationPoints() throws Exception {
    startServerAndClient();
    Iec60870Client client = requireNonNull(this.client);
    client.connect();

    InterrogationResult interrogation = client.interrogate(STATION);

    assertTrue(interrogation.terminated(), "interrogation should end with ACT_TERM");
    assertEquals(STATION, interrogation.station());

    // The two commandable points are not REPORTED, so only the four monitor points come back.
    List<PointAddress> reported =
        interrogation.pointValues().stream().map(InterrogationResult.PointEntry::address).toList();
    assertEquals(4, reported.size(), "exactly the four reported monitor points should be returned");
    assertTrue(reported.contains(SINGLE_POINT));
    assertTrue(reported.contains(DOUBLE_POINT));
    assertTrue(reported.contains(SCALED_POINT));
    assertTrue(reported.contains(FLOAT_POINT));
    assertFalse(
        reported.contains(ACCEPTED_COMMAND),
        "a command-only (non-reported) point must not appear in the interrogation snapshot");
  }

  @Test
  void directExecuteCommandIsAcceptedAndRejectedAppropriately() throws Exception {
    startServerAndClient();
    Iec60870Client client = requireNonNull(this.client);
    client.connect();

    CommandResult accepted = client.commands().single(ACCEPTED_COMMAND, true);
    assertTrue(accepted.positive(), "command to the accepted point should be positive");
    assertEquals(Cause.ACTIVATION_CONFIRMATION, accepted.cause());

    CommandResult rejected = client.commands().single(REJECTED_COMMAND, true);
    assertFalse(rejected.positive(), "command to the rejected point should be negative");
  }

  @Test
  void spontaneousPublishDeliversPointUpdatedEvent() throws Exception {
    EventCollector events = startServerAndClient();
    Iec60870Client client = requireNonNull(this.client);
    Iec60870Server server = requireNonNull(this.server);
    client.connect();

    server.publish(SCALED_POINT, PointValue.scaled((short) 4242), Cause.SPONTANEOUS);

    AtomicReference<ClientEvent.@Nullable PointUpdated> seen = new AtomicReference<>();
    Await.until(
        "spontaneous scaled point update",
        () -> {
          for (ClientEvent event : events.events()) {
            if (event instanceof ClientEvent.PointUpdated update
                && update.address().equals(SCALED_POINT)) {
              seen.set(update);
              return true;
            }
          }
          return false;
        });

    ClientEvent.PointUpdated update = requireNonNull(seen.get());
    assertEquals(Cause.SPONTANEOUS, update.cause());
    assertEquals((short) 4242, update.value().value());
  }

  @Test
  void clockSynchronizationCompletes() throws Exception {
    startServerAndClient();
    Iec60870Client client = requireNonNull(this.client);
    client.connect();

    // The default ServerHandler accepts clock sync; the call returns normally on a positive
    // confirmation and throws on a negative one.
    client.synchronizeClock(STATION, Instant.now());
  }

  /**
   * Builds and starts a server hosting the configured station, then builds a client pointed at it
   * and subscribes an {@link EventCollector} before connecting.
   *
   * @return the event collector subscribed to the client's events.
   * @throws IOException if an ephemeral loopback port cannot be reserved.
   */
  private EventCollector startServerAndClient() throws IOException {
    int port = reserveEphemeralPort();

    Station station =
        Station.builder(STATION)
            .point(
                PointDefinition.of(
                    SINGLE_POINT,
                    PointType.SINGLE_POINT,
                    PointValue.single(false),
                    PointCapability.REPORTED))
            .point(
                PointDefinition.of(
                    DOUBLE_POINT,
                    PointType.DOUBLE_POINT,
                    PointValue.doublePoint(DoublePointState.ON),
                    PointCapability.REPORTED))
            .point(
                PointDefinition.of(
                    SCALED_POINT,
                    PointType.SCALED,
                    PointValue.scaled((short) 100),
                    PointCapability.REPORTED))
            .point(
                PointDefinition.of(
                    FLOAT_POINT,
                    PointType.SHORT_FLOAT,
                    PointValue.shortFloat(1.5f),
                    PointCapability.REPORTED))
            .point(
                PointDefinition.of(
                    ACCEPTED_COMMAND,
                    PointType.SINGLE_POINT,
                    PointValue.single(false),
                    PointCapability.COMMANDABLE))
            .point(
                PointDefinition.of(
                    REJECTED_COMMAND,
                    PointType.SINGLE_POINT,
                    PointValue.single(false),
                    PointCapability.COMMANDABLE))
            .build();

    server =
        TcpIec104Server.builder()
            .bindAddress("127.0.0.1")
            .port(port)
            .addStation(station)
            .handler(new SelectiveCommandHandler())
            .build();
    server.start();

    client =
        TcpIec104Client.builder()
            .host("127.0.0.1")
            .port(port)
            .startDataTransferOnConnect(true)
            .build();

    EventCollector events = new EventCollector();
    client.events().subscribe(events);
    return events;
  }

  /**
   * A handler that accepts single commands targeting {@link #ACCEPTED_COMMAND} (updating its image)
   * and rejects every other command, so the tests can assert both confirmation outcomes.
   */
  private static final class SelectiveCommandHandler implements ServerHandler {

    @Override
    public CommandDecision onCommand(ServerContext context, CommandRequest request) {
      if (request.target().equals(ACCEPTED_COMMAND)) {
        boolean on = request.commandObject() instanceof SingleCommand sc && sc.on();
        return CommandDecision.acceptAndUpdate(PointValue.single(on));
      }
      return CommandDecision.reject(Cause.UNKNOWN_INFORMATION_OBJECT_ADDRESS);
    }
  }

  private static int reserveEphemeralPort() throws IOException {
    try (ServerSocket socket = new ServerSocket(0)) {
      socket.setReuseAddress(true);
      return socket.getLocalPort();
    }
  }
}
