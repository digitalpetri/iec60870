package com.digitalpetri.iec60870.examples;

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
import java.time.Duration;
import java.util.concurrent.Flow;

/**
 * A runnable 101-over-TCP example that starts a controlled station (server) and a controlling
 * station (client) in the same process and exchanges traffic over a loopback TCP connection.
 *
 * <p>Both peers run the IEC 60870-5-101 FT1.2 link layer carried over TCP: {@link TcpIec101Server}
 * and {@link TcpIec101Client} reuse the Netty octet transport but frame FT1.2 link frames rather
 * than 104 APCI. Both run a balanced link ({@link LinkSettings#balanced()}) on the 101 wire profile
 * {@code (1, 1, 2, 255)} — a one-octet cause of transmission, a one-octet common address, a
 * two-octet information-object address, and a 255-octet maximum ASDU length — and both bind {@code
 * 127.0.0.1} on the same ephemeral port, reserved up front with a throwaway {@link ServerSocket} so
 * the example does not collide with whatever else is listening on the host.
 *
 * <p>The {@code main} method runs entirely in-process and, in order: starts the server, connects
 * the client (which also drives the balanced FT1.2 link reset because {@code
 * startDataTransferOnConnect} defaults to {@code true}), runs a general interrogation of common
 * address {@code 1}, sends a single command to the commandable point, publishes a spontaneous
 * update from the server, then lingers briefly so the spontaneous update arrives on the client's
 * event stream before both facades close. A {@link Flow.Subscriber} subscribed to {@link
 * Iec60870Client#events()} prints each {@link ClientEvent.PointUpdated} as it arrives; every step
 * prints a line prefixed with {@code [101-tcp]}.
 */
public final class Tcp101Example {

  /** Common address of the hosted station. */
  static final CommonAddress STATION = CommonAddress.of(1);

  /** Single-point status indication, reported in the monitor direction and in group {@code 1}. */
  static final PointAddress MONITOR = PointAddress.of(1, 100);

  /** Commandable single point; its handler accepts the command and updates the station image. */
  static final PointAddress SWITCH = PointAddress.of(1, 300);

  /**
   * The 101 wire profile: a 1-octet cause of transmission, a 1-octet common address, a 2-octet
   * information-object address, and a 255-octet maximum ASDU length.
   */
  static final ProtocolProfile PROFILE = new ProtocolProfile(1, 1, 2, 255);

  private Tcp101Example() {}

  /**
   * Starts the server, connects the client over loopback TCP, interrogates the station, sends a
   * command, publishes a spontaneous update, lingers for it to arrive, then closes both facades.
   *
   * @param args ignored.
   * @throws IOException if an ephemeral loopback port cannot be reserved.
   */
  public static void main(String[] args) throws IOException {
    int port = reserveEphemeralPort();

    Station station =
        Station.builder(STATION)
            .point(
                PointDefinition.of(
                    MONITOR,
                    PointType.SINGLE_POINT,
                    PointValue.single(false),
                    PointCapability.REPORTED))
            .point(
                PointDefinition.of(
                    SWITCH,
                    PointType.SINGLE_POINT,
                    PointValue.single(false),
                    PointCapability.REPORTED,
                    PointCapability.COMMANDABLE))
            .group(1, MONITOR)
            .group(2, SWITCH)
            .build();

    try (Iec60870Server server =
        TcpIec101Server.builder()
            .bindAddress("127.0.0.1")
            .port(port)
            .profile(PROFILE)
            .linkSettings(LinkSettings.balanced().build())
            .addStation(station)
            .handler(new ExampleHandler())
            .build()) {

      server.start();
      System.out.println("[101-tcp] server listening on 127.0.0.1:" + port);

      try (Iec60870Client client =
          TcpIec101Client.builder()
              .host("127.0.0.1")
              .port(port)
              .profile(PROFILE)
              .linkSettings(LinkSettings.balanced().build())
              .startDataTransferOnConnect(true)
              .build()) {

        // Subscribe before connecting so no early events are missed.
        client.events().subscribe(new PrintingSubscriber());

        System.out.println("[101-tcp] connecting; balanced FT1.2 link reset runs on connect");
        client.connect();
        System.out.println("[101-tcp] connected; data transfer started");

        InterrogationResult snapshot = client.interrogate(STATION);
        System.out.println(
            "[101-tcp] interrogation reported " + snapshot.pointValues().size() + " points");
        for (InterrogationResult.PointEntry entry : snapshot.pointValues()) {
          System.out.println(
              "[101-tcp]   "
                  + entry.address()
                  + " ["
                  + entry.value().type()
                  + "] = "
                  + entry.value().value());
        }

        CommandResult command = client.commands().single(SWITCH, true);
        System.out.println("[101-tcp] command on " + SWITCH + " positive=" + command.positive());

        server.publish(MONITOR, PointValue.single(true), Cause.SPONTANEOUS);
        System.out.println("[101-tcp] server published a spontaneous update for " + MONITOR);

        System.out.println("[101-tcp] lingering for the spontaneous update");
        sleep(Duration.ofSeconds(1));
      }
    }
    System.out.println("[101-tcp] done");
  }

  /**
   * Reserves an ephemeral loopback port by opening and immediately closing a throwaway server
   * socket; the kernel is unlikely to reuse the freed port before the example binds it.
   *
   * @return a port that was free on {@code 127.0.0.1}.
   * @throws IOException if a port cannot be reserved.
   */
  private static int reserveEphemeralPort() throws IOException {
    try (ServerSocket socket = new ServerSocket(0)) {
      socket.setReuseAddress(true);
      return socket.getLocalPort();
    }
  }

  private static void sleep(Duration duration) {
    try {
      Thread.sleep(Math.max(0, duration.toMillis()));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  /**
   * A handler that accepts commands to {@link #SWITCH}, updating the station image so the new value
   * is returned to the controlling station; every other request falls through to the default
   * behavior, which answers interrogations from the station image.
   */
  private static final class ExampleHandler implements ServerHandler {

    @Override
    public CommandDecision onCommand(ServerContext context, CommandRequest request) {
      if (request.target().equals(SWITCH)
          && request.commandObject() instanceof SingleCommand command) {
        System.out.println(
            "[101-tcp] server accepting single command on " + SWITCH + " -> " + command.on());
        return CommandDecision.acceptAndUpdate(PointValue.single(command.on()));
      }
      return CommandDecision.reject(Cause.UNKNOWN_INFORMATION_OBJECT_ADDRESS);
    }
  }

  /** A subscriber that requests every event and prints the point updates it sees. */
  private static final class PrintingSubscriber implements Flow.Subscriber<ClientEvent> {

    @Override
    public void onSubscribe(Flow.Subscription subscription) {
      subscription.request(Long.MAX_VALUE);
    }

    @Override
    public void onNext(ClientEvent event) {
      if (event instanceof ClientEvent.PointUpdated updated) {
        System.out.println(
            "[101-tcp] point updated "
                + updated.address()
                + " ["
                + updated.value().type()
                + " via "
                + updated.asduType()
                + "] = "
                + updated.value().value()
                + " ("
                + updated.cause()
                + ")");
      }
      // Other lifecycle events are not printed in this example.
    }

    @Override
    public void onError(Throwable throwable) {
      System.out.println("[101-tcp] event stream error: " + throwable);
    }

    @Override
    public void onComplete() {
      System.out.println("[101-tcp] event stream complete");
    }
  }
}
