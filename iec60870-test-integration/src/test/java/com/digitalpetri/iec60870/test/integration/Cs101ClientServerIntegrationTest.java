package com.digitalpetri.iec60870.test.integration;

import static java.util.Objects.requireNonNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.digitalpetri.iec60870.ProtocolProfile;
import com.digitalpetri.iec60870.address.CommonAddress;
import com.digitalpetri.iec60870.address.PointAddress;
import com.digitalpetri.iec60870.asdu.Cause;
import com.digitalpetri.iec60870.asdu.object.SingleCommand;
import com.digitalpetri.iec60870.client.ClientConfig;
import com.digitalpetri.iec60870.client.ClientEvent;
import com.digitalpetri.iec60870.client.CommandResult;
import com.digitalpetri.iec60870.client.DefaultIec60870Client;
import com.digitalpetri.iec60870.client.InterrogationResult;
import com.digitalpetri.iec60870.cs101.Cs101Binding;
import com.digitalpetri.iec60870.cs101.LinkSettings;
import com.digitalpetri.iec60870.point.PointCapability;
import com.digitalpetri.iec60870.point.PointType;
import com.digitalpetri.iec60870.point.PointValue;
import com.digitalpetri.iec60870.server.CommandDecision;
import com.digitalpetri.iec60870.server.CommandRequest;
import com.digitalpetri.iec60870.server.DefaultIec60870Server;
import com.digitalpetri.iec60870.server.PointDefinition;
import com.digitalpetri.iec60870.server.ServerConfig;
import com.digitalpetri.iec60870.server.ServerContext;
import com.digitalpetri.iec60870.server.ServerHandler;
import com.digitalpetri.iec60870.server.Station;
import com.digitalpetri.iec60870.test.common.LoopbackOctetTransport;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * End-to-end in-JVM integration tests that run a real {@link DefaultIec60870Client} and {@link
 * DefaultIec60870Server} over an IEC 60870-5-101 balanced FT1.2 link, with no serial port, Docker,
 * or wall-clock timing.
 *
 * <p>The two facades are wired exactly as the production builders wire a 104 stack — a single
 * {@link Cs101Binding} assembles both sessions, both facades run their callbacks on a same-thread
 * executor ({@link Runnable#run}) and share one {@link ScheduledExecutorService} — except the octet
 * transport is a {@link LoopbackOctetTransport} that relays whole FT1.2 frames synchronously and
 * re-entrantly between the two legs in process. Because the relay is synchronous and the callback
 * executor runs inline, the balanced bring-up handshake (FC9 request-status &rarr; FC11 status
 * &rarr; FC0 reset-of-remote-link &rarr; ACK) and every subsequent stop-and-wait data
 * acknowledgement complete on the calling thread, so {@link DefaultIec60870Client#connect()
 * connect()} drives the FT1.2 link reset to completion and request futures resolve before they are
 * awaited.
 *
 * <p>One station hosts a reported monitor point and a commandable point. The three tests cover the
 * three FT1.2 message directions over the balanced link: a controlling-station interrogation
 * round-trip, a controlling-station command round-trip, and a controlled-station spontaneous
 * publish that reaches the client as a {@link ClientEvent.PointUpdated}. Each test asserts up
 * front, through the shared {@link #startAndConnect(ServerHandler) wiring helper}, that {@code
 * connect()} both connected the transport and started data transfer (the balanced link reset
 * completed). Per-test teardown closes both facades and shuts the shared scheduler down.
 */
class Cs101ClientServerIntegrationTest {

  private static final CommonAddress STATION = CommonAddress.of(1);
  private static final PointAddress MONITOR_POINT = PointAddress.of(1, 100);
  private static final PointAddress COMMAND_POINT = PointAddress.of(1, 200);

  /** The 101 wire profile: 1-octet cause of transmission, common address, and 2-octet IOA. */
  private static final ProtocolProfile PROFILE = new ProtocolProfile(1, 1, 2, 255);

  private final LoopbackOctetTransport loopback = new LoopbackOctetTransport();
  private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

  private @Nullable DefaultIec60870Client client;
  private @Nullable DefaultIec60870Server server;

  @AfterEach
  void tearDown() {
    if (client != null) {
      client.close();
    }
    if (server != null) {
      server.close();
    }
    scheduler.shutdownNow();
  }

  /**
   * A general interrogation over the balanced link returns the station's reported monitor point.
   */
  @Test
  void interrogationRoundTripReturnsStationPoints() {
    startAndConnect(new ServerHandler() {});
    DefaultIec60870Client client = requireNonNull(this.client);

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
   * A single command over the balanced link is confirmed positively and the server handler observes
   * the command.
   */
  @Test
  void commandRoundTripIsConfirmedPositively() {
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
    DefaultIec60870Client client = requireNonNull(this.client);

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
   * A controlled-station spontaneous publish over the balanced link reaches the client as a {@link
   * ClientEvent.PointUpdated} carrying the published value and cause.
   */
  @Test
  void spontaneousPublishReachesClient() {
    EventCollector events = startAndConnect(new ServerHandler() {});
    DefaultIec60870Server server = requireNonNull(this.server);

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
   * Mirrors the production assembly (a {@code Serial*} builder delegating to {@link Cs101Binding})
   * but over the in-JVM loopback octet transport, with a same-thread callback executor and one
   * shared scheduler so the whole relay is deterministic and synchronous. Builds a one-station
   * server and a client over the loopback legs, subscribes an {@link EventCollector} to the client,
   * connects (which performs the balanced FT1.2 link reset), and asserts the link came up.
   *
   * @param handler the server handler under test.
   * @return the event collector subscribed to the client's events.
   */
  private EventCollector startAndConnect(ServerHandler handler) {
    LinkSettings linkSettings = LinkSettings.balanced().build();
    Cs101Binding binding = new Cs101Binding(linkSettings, PROFILE);

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

    ServerConfig serverConfig =
        ServerConfig.builder()
            .protocolProfile(PROFILE)
            .sessionSettings(linkSettings)
            .station(station)
            .handler(handler)
            .callbackExecutor(Runnable::run)
            .build();
    DefaultIec60870Server server =
        new DefaultIec60870Server(
            loopback.server(),
            serverConfig,
            (connection, events, sched) ->
                binding.bindServer(
                    connection,
                    events,
                    sched,
                    serverConfig.maxOutboundQueue(),
                    serverConfig.eventQueuePolicy()),
            scheduler);
    server.start();
    this.server = server;

    ClientConfig clientConfig =
        ClientConfig.builder()
            .protocolProfile(PROFILE)
            .sessionSettings(linkSettings)
            .startDataTransferOnConnect(true)
            .callbackExecutor(Runnable::run)
            .build();
    DefaultIec60870Client client =
        new DefaultIec60870Client(
            loopback.client(),
            clientConfig,
            (events, sched) -> binding.bindClient(loopback.client(), events, sched),
            scheduler);
    this.client = client;

    EventCollector events = new EventCollector();
    client.events().subscribe(events);
    client.connect();

    assertTrue(client.isConnected(), "client should be connected after connect()");
    assertTrue(
        events.hasAny(ClientEvent.DataTransferStarted.class),
        "the balanced FT1.2 link reset should have started data transfer on connect");
    return events;
  }
}
