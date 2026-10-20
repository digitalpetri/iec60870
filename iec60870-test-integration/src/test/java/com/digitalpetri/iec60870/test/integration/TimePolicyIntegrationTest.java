package com.digitalpetri.iec60870.test.integration;

import static java.util.Objects.requireNonNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.digitalpetri.iec60870.address.CommonAddress;
import com.digitalpetri.iec60870.address.PointAddress;
import com.digitalpetri.iec60870.asdu.Cause;
import com.digitalpetri.iec60870.asdu.object.SingleCommandWithCp56Time;
import com.digitalpetri.iec60870.client.ClientEvent;
import com.digitalpetri.iec60870.client.Command;
import com.digitalpetri.iec60870.client.CommandMode;
import com.digitalpetri.iec60870.client.CommandResult;
import com.digitalpetri.iec60870.client.Iec60870Client;
import com.digitalpetri.iec60870.point.PointCapability;
import com.digitalpetri.iec60870.point.PointType;
import com.digitalpetri.iec60870.point.PointValue;
import com.digitalpetri.iec60870.point.TimePolicy;
import com.digitalpetri.iec60870.server.ClockSyncDecision;
import com.digitalpetri.iec60870.server.ClockSyncRequest;
import com.digitalpetri.iec60870.server.CommandDecision;
import com.digitalpetri.iec60870.server.CommandRequest;
import com.digitalpetri.iec60870.server.Iec60870Server;
import com.digitalpetri.iec60870.server.PointDefinition;
import com.digitalpetri.iec60870.server.ServerContext;
import com.digitalpetri.iec60870.server.ServerHandler;
import com.digitalpetri.iec60870.server.Station;
import com.digitalpetri.iec60870.tcp.TcpIec104Client;
import com.digitalpetri.iec60870.tcp.TcpIec104Server;
import java.io.IOException;
import java.net.ServerSocket;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * A client and a server that share a local-time {@link TimePolicy} agree on every instant they
 * exchange over a real TCP connection.
 */
class TimePolicyIntegrationTest {

  private static final CommonAddress STATION = CommonAddress.of(1);
  private static final PointAddress POINT = PointAddress.of(1, 100);
  private static final Instant TIME = Instant.parse("2026-06-01T12:00:00.250Z");
  private static final TimePolicy BERLIN = TimePolicy.of(ZoneId.of("Europe/Berlin"));

  private final AtomicReference<Optional<Instant>> clockSyncTime =
      new AtomicReference<>(Optional.empty());
  private final AtomicReference<Optional<Instant>> commandTime =
      new AtomicReference<>(Optional.empty());

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
  void publishedTimeRoundTrips() throws Exception {
    EventCollector events = start(BERLIN);
    Iec60870Server server = requireNonNull(this.server);

    server.publish(POINT, PointValue.single(true).withTimestamp(TIME), Cause.SPONTANEOUS);

    ClientEvent.PointUpdated update = awaitUpdate(events);
    assertEquals(Optional.of(TIME), update.timestamp());
    assertTrue(update.timeTag().orElseThrow().summerTime());
    assertEquals(14, requireNonNull(update.timeTag().orElseThrow().cp56()).hour());
  }

  @Test
  void utcClientSeesLocalTimeOffset() throws Exception {
    EventCollector events = start(TimePolicy.utc());
    Iec60870Server server = requireNonNull(this.server);

    server.publish(POINT, PointValue.single(true).withTimestamp(TIME), Cause.SPONTANEOUS);

    // The station encodes 14:00 local; a UTC client reads it two hours late.
    assertEquals(Optional.of(TIME.plusSeconds(7200)), awaitUpdate(events).timestamp());
  }

  @Test
  void clockSyncAndCommandTimesRoundTrip() throws Exception {
    start(BERLIN);
    Iec60870Client client = requireNonNull(this.client);

    client.synchronizeClock(STATION, TIME);
    CommandResult result =
        client
            .commands()
            .send(
                new Command.SingleCommandRequest(POINT, true, 0, Optional.of(TIME)),
                CommandMode.directExecute());

    assertEquals(Optional.of(TIME), clockSyncTime.get());
    assertTrue(result.positive());
    assertEquals(Optional.of(TIME), commandTime.get());
  }

  private ClientEvent.PointUpdated awaitUpdate(EventCollector events) throws Exception {
    AtomicReference<ClientEvent.@Nullable PointUpdated> seen = new AtomicReference<>();
    Await.until(
        "time-tagged point update",
        () -> {
          for (ClientEvent event : events.events()) {
            if (event instanceof ClientEvent.PointUpdated update
                && update.address().equals(POINT)
                && update.cause() == Cause.SPONTANEOUS) {
              seen.set(update);
              return true;
            }
          }
          return false;
        });
    return requireNonNull(seen.get());
  }

  /**
   * Starts a Berlin-time server and a client using {@code clientPolicy}, and connects the client.
   */
  private EventCollector start(TimePolicy clientPolicy) throws IOException {
    int port = reserveEphemeralPort();

    Station station =
        Station.builder(STATION)
            .point(
                PointDefinition.of(
                    POINT,
                    PointType.SINGLE_POINT,
                    PointValue.single(false),
                    PointCapability.REPORTED,
                    PointCapability.COMMANDABLE))
            .build();

    ServerHandler handler =
        new ServerHandler() {
          @Override
          public ClockSyncDecision onClockSync(ServerContext context, ClockSyncRequest request) {
            clockSyncTime.set(context.timePolicy().toInstant(request.time()));
            return ClockSyncDecision.accept();
          }

          @Override
          public CommandDecision onCommand(ServerContext context, CommandRequest request) {
            if (request.commandObject() instanceof SingleCommandWithCp56Time command) {
              commandTime.set(context.timePolicy().toInstant(command.time()));
            }
            return CommandDecision.accept();
          }
        };

    Iec60870Server server =
        TcpIec104Server.builder()
            .bindAddress("127.0.0.1")
            .port(port)
            .addStation(station)
            .handler(handler)
            .timePolicy(BERLIN)
            .build();
    this.server = server;
    server.start();

    Iec60870Client client =
        TcpIec104Client.builder().host("127.0.0.1").port(port).timePolicy(clientPolicy).build();
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
