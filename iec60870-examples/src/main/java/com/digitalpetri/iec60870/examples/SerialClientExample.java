package com.digitalpetri.iec60870.examples;

import com.digitalpetri.iec60870.ProtocolProfile;
import com.digitalpetri.iec60870.address.CommonAddress;
import com.digitalpetri.iec60870.address.PointAddress;
import com.digitalpetri.iec60870.client.ClientEvent;
import com.digitalpetri.iec60870.client.CommandResult;
import com.digitalpetri.iec60870.client.Iec60870Client;
import com.digitalpetri.iec60870.client.InterrogationResult;
import com.digitalpetri.iec60870.cs101.LinkSettings;
import com.digitalpetri.iec60870.serial.SerialIec101Client;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Flow;

/**
 * A controlling station (client) that runs over an IEC 60870-5-101 serial link, prints the events
 * it receives, and exercises the main request operations.
 *
 * <p>This is the {@link ClientExample} of the serial transport: it speaks the same {@link
 * Iec60870Client} facade, but the session rides an FT1.2 link over a serial port rather than the
 * 104 APCI over TCP. The high-level API is identical — only the builder ({@link SerialIec101Client}
 * instead of {@code TcpIec104Client}) and its transport and link-layer knobs differ.
 *
 * <p>The {@link #run(String, int, Duration)} method opens the serial port (which also drives the
 * FT1.2 balanced link-reset bring-up because {@code startDataTransferOnConnect} defaults to {@code
 * true}), runs a general interrogation of common address {@code 1}, sends a single command, and
 * synchronizes the clock, then lingers for the supplied duration so spontaneous updates from the
 * outstation arrive on the event stream before the client closes. A {@link Flow.Subscriber}
 * subscribed to {@link Iec60870Client#events()} prints {@link ClientEvent.PointUpdated} and {@link
 * ClientEvent.AsduReceived} events as they arrive.
 *
 * <p>Because no real serial port is attached in continuous integration, {@link #main(String[])}
 * wraps {@link #run(String, int, Duration)} in a try/catch that prints a friendly message and
 * returns without throwing when the port cannot be opened. Point it at a real device — for example
 * a USB-to-serial adapter wired to an outstation — to see it complete.
 */
public final class SerialClientExample {

  /** Common address of the outstation to interrogate. */
  static final CommonAddress STATION = CommonAddress.of(1);

  /** Commandable single point on the outstation, matching {@code SerialServerExample}. */
  static final PointAddress SWITCH = PointAddress.of(1, 300);

  /** Default serial port to open; override the first argument of {@code main} to change it. */
  static final String DEFAULT_PORT = "/dev/ttyUSB0";

  /**
   * The narrower wire field widths an IEC 60870-5-101 link commonly uses: a 1-octet cause of
   * transmission, a 1-octet common address, a 2-octet information-object address, and a maximum
   * ASDU length of {@code 255}. Both stations must agree on these widths.
   */
  static final ProtocolProfile IEC101_PROFILE = new ProtocolProfile(1, 1, 2, 255);

  private SerialClientExample() {}

  /**
   * Opens the serial port, runs a general interrogation, lingers for spontaneous updates, then
   * closes the client.
   *
   * <p>This builds a {@linkplain LinkSettings#balanced() balanced} point-to-point link: either side
   * may initiate, and {@link Iec60870Client#connect()} drives the FT1.2 link-reset bring-up before
   * completing. It then runs the same request operations as {@link ClientExample} — a general
   * interrogation, a single command (direct execute) on {@link #SWITCH}, and a clock
   * synchronization — before lingering for spontaneous updates.
   *
   * @param portName the system serial port to open, for example {@code "/dev/ttyUSB0"} or {@code
   *     "COM3"}.
   * @param baudRate the symbol rate in bits per second.
   * @param linger how long to wait for spontaneous updates after the request operations complete.
   */
  public static void run(String portName, int baudRate, Duration linger) {
    try (Iec60870Client client =
        SerialIec101Client.builder()
            .serialPort(portName)
            .baudRate(baudRate)
            .profile(IEC101_PROFILE)
            .linkSettings(LinkSettings.balanced().linkAddress(1).build())
            .startDataTransferOnConnect(true)
            .build()) {

      // Subscribe before connecting so no early events are missed.
      client.events().subscribe(new PrintingSubscriber());

      System.out.println("[client] opening " + portName + " at " + baudRate + " baud");
      client.connect();
      System.out.println("[client] port open; balanced link established");

      InterrogationResult snapshot = client.interrogate(STATION);
      System.out.println(
          "[client] interrogation reported " + snapshot.pointValues().size() + " points");
      for (InterrogationResult.PointEntry entry : snapshot.pointValues()) {
        System.out.println(
            "[client]   "
                + entry.address()
                + " ["
                + entry.value().type()
                + "] = "
                + entry.value().value());
      }

      CommandResult command = client.commands().single(SWITCH, true);
      System.out.println("[client] command on " + SWITCH + " positive=" + command.positive());

      client.synchronizeClock(STATION, Instant.now());
      System.out.println("[client] clock synchronized");

      System.out.println("[client] waiting " + linger.toMillis() + " ms for spontaneous updates");
      sleep(linger);
    }
    System.out.println("[client] closed");
  }

  /**
   * Builds an unbalanced-master client that polls one secondary station, without opening the port.
   *
   * <p>An unbalanced link is master/secondary and half-duplex: the master (this client) owns the
   * bus and polls each configured secondary in turn, while the secondary never initiates a
   * transmission and instead buffers its spontaneous data until polled. The master's {@link
   * LinkSettings.PollConfig} — the secondary {@linkplain LinkSettings.Builder#slaveAddresses(List)
   * addresses} to poll and the {@linkplain LinkSettings.Builder#pollInterval(Duration) poll
   * cadence} — replaces the either-side-initiates behaviour of the balanced link {@link
   * #run(String, int, Duration)} uses. The high-level {@link Iec60870Client} surface is otherwise
   * identical.
   *
   * <p>The caller owns the returned client and must {@link Iec60870Client#close() close} it.
   *
   * @param portName the system serial port to open.
   * @param baudRate the symbol rate in bits per second.
   * @return a configured, not-yet-connected unbalanced-master client.
   */
  static Iec60870Client unbalancedMaster(String portName, int baudRate) {
    LinkSettings settings =
        LinkSettings.unbalanced()
            .linkAddressLength(1)
            .slaveAddresses(List.of(1))
            .pollInterval(Duration.ofSeconds(1))
            .build();

    return SerialIec101Client.builder()
        .serialPort(portName)
        .baudRate(baudRate)
        .profile(IEC101_PROFILE)
        .linkSettings(settings)
        .build();
  }

  /**
   * Opens {@link #DEFAULT_PORT} at {@code 9600} baud and runs the balanced example, lingering five
   * seconds for spontaneous updates.
   *
   * <p>Opening the port fails when no serial device is attached (the usual case in continuous
   * integration); this prints a friendly message and returns without throwing rather than
   * propagating the failure.
   *
   * @param args optionally {@code [portName [baudRate]]}; defaults to {@link #DEFAULT_PORT} at
   *     {@code 9600} baud.
   */
  public static void main(String[] args) {
    String portName = args.length > 0 ? args[0] : DEFAULT_PORT;
    int baudRate = args.length > 1 ? Integer.parseInt(args[1]) : 9600;

    // Show the unbalanced-master configuration alongside the balanced one. Building a client opens
    // no serial port (only connect() does), so this touches no hardware and cannot throw here.
    try (Iec60870Client master = unbalancedMaster(portName, baudRate)) {
      System.out.println(
          "[client] also configured an unbalanced master polling secondary 1; connected="
              + master.isConnected());
    }

    try {
      run(portName, baudRate, Duration.ofSeconds(5));
    } catch (Exception e) {
      System.out.println("[client] could not run the serial example on " + portName + ": " + e);
      System.out.println(
          "[client] this is expected when no serial port is attached; point it at a real device.");
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
   * A subscriber that requests every event and prints the point updates and received ASDUs it sees.
   */
  private static final class PrintingSubscriber implements Flow.Subscriber<ClientEvent> {

    @Override
    public void onSubscribe(Flow.Subscription subscription) {
      subscription.request(Long.MAX_VALUE);
    }

    @Override
    public void onNext(ClientEvent event) {
      if (event instanceof ClientEvent.PointUpdated updated) {
        System.out.println(
            "[client] point updated "
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
      } else if (event instanceof ClientEvent.AsduReceived received) {
        System.out.println("[client] asdu received " + received.asdu().type());
      } else if (event instanceof ClientEvent.ConnectionClosed closed) {
        System.out.println("[client] connection closed: " + closed.causeOptional());
      }
      // Other lifecycle events are not printed in this example.
    }

    @Override
    public void onError(Throwable throwable) {
      System.out.println("[client] event stream error: " + throwable);
    }

    @Override
    public void onComplete() {
      System.out.println("[client] event stream complete");
    }
  }
}
