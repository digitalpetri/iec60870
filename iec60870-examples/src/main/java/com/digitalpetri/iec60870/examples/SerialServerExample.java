package com.digitalpetri.iec60870.examples;

import com.digitalpetri.iec60870.ProtocolProfile;
import com.digitalpetri.iec60870.address.CommonAddress;
import com.digitalpetri.iec60870.address.PointAddress;
import com.digitalpetri.iec60870.asdu.Cause;
import com.digitalpetri.iec60870.asdu.object.SingleCommand;
import com.digitalpetri.iec60870.cs101.LinkSettings;
import com.digitalpetri.iec60870.point.PointCapability;
import com.digitalpetri.iec60870.point.PointType;
import com.digitalpetri.iec60870.point.PointValue;
import com.digitalpetri.iec60870.serial.SerialIec101Server;
import com.digitalpetri.iec60870.server.CommandDecision;
import com.digitalpetri.iec60870.server.CommandRequest;
import com.digitalpetri.iec60870.server.Iec60870Server;
import com.digitalpetri.iec60870.server.PointDefinition;
import com.digitalpetri.iec60870.server.ServerContext;
import com.digitalpetri.iec60870.server.ServerEvent;
import com.digitalpetri.iec60870.server.ServerHandler;
import com.digitalpetri.iec60870.server.Station;
import com.digitalpetri.iec60870.server.StationRegistry;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A controlled station (outstation) that runs over an IEC 60870-5-101 serial link, answers
 * control-direction requests, and spontaneously republishes a fresh value for each point on a
 * timer.
 *
 * <p>This is the {@link ServerExample} of the serial transport: it hosts the same {@link Station}
 * /point model and {@link ServerHandler} surface, but the session rides an FT1.2 link over a serial
 * port rather than the 104 APCI over TCP. The high-level API is identical — only the builder
 * ({@link SerialIec101Server} instead of {@code TcpIec104Server}) and its transport and link-layer
 * knobs differ. A serial line connects exactly two stations, so there is no bind address, port, or
 * connection cap.
 *
 * <p>The station defines, on common address {@code 1}, a single-point status indication at IOA
 * {@code 100} and a short-float measured value at IOA {@code 140} (both reported, in interrogation
 * group {@code 1}), plus a commandable single point at IOA {@code 300} (group {@code 2}) whose
 * handler accepts the command and updates the station image. A periodic task republishes a fresh
 * value for the two monitor points every two seconds with cause {@link Cause#SPONTANEOUS}.
 *
 * <p>Because no real serial port is attached in continuous integration, {@link #main(String[])}
 * guards {@link #start(String, int)} with a try/catch that prints a friendly message and returns
 * without throwing when the port cannot be opened. Point it at a real device to see it serve.
 */
public final class SerialServerExample {

  /** Common address of the hosted station. */
  static final CommonAddress STATION = CommonAddress.of(1);

  /** Single-point status indication, reported in the monitor direction. */
  static final PointAddress STATUS = PointAddress.of(1, 100);

  /** Short floating-point measured value, reported in the monitor direction. */
  static final PointAddress MEASURED = PointAddress.of(1, 140);

  /** Commandable single point; its handler accepts the command and updates the image. */
  static final PointAddress SWITCH = PointAddress.of(1, 300);

  /** Default serial port to open; override the first argument of {@code main} to change it. */
  static final String DEFAULT_PORT = "/dev/ttyUSB0";

  /**
   * The narrower wire field widths an IEC 60870-5-101 link commonly uses: a 1-octet cause of
   * transmission, a 1-octet common address, a 2-octet information-object address, and a maximum
   * ASDU length of {@code 255}. Both stations must agree on these widths.
   */
  static final ProtocolProfile IEC101_PROFILE = new ProtocolProfile(1, 1, 2, 255);

  private SerialServerExample() {}

  /**
   * Builds and starts an outstation on the given serial port, wiring the station, handler, and
   * periodic publisher.
   *
   * <p>The returned server is already started and registers a periodic task that republishes a
   * fresh value for the monitor points; closing the server cancels that task. The caller owns the
   * returned server and must {@link Iec60870Server#close() close} it.
   *
   * @param portName the system serial port to open, for example {@code "/dev/ttyUSB0"} or {@code
   *     "COM3"}.
   * @param baudRate the symbol rate in bits per second.
   * @return the started server.
   */
  public static Iec60870Server start(String portName, int baudRate) {
    Station station =
        Station.builder(STATION)
            .point(
                PointDefinition.of(
                    STATUS,
                    PointType.SINGLE_POINT,
                    PointValue.single(false),
                    PointCapability.REPORTED))
            .point(
                PointDefinition.of(
                    MEASURED,
                    PointType.SHORT_FLOAT,
                    PointValue.shortFloat(0f),
                    PointCapability.REPORTED))
            .point(
                PointDefinition.of(
                    SWITCH,
                    PointType.SINGLE_POINT,
                    PointValue.single(false),
                    PointCapability.REPORTED,
                    PointCapability.COMMANDABLE))
            .group(1, STATUS)
            .group(1, MEASURED)
            .group(2, SWITCH)
            .build();

    Iec60870Server server =
        SerialIec101Server.builder()
            .serialPort(portName)
            .baudRate(baudRate)
            .profile(IEC101_PROFILE)
            .linkSettings(LinkSettings.balanced().linkAddress(1).build())
            .addStation(station)
            .handler(new ExampleHandler())
            .build();

    server.start();
    System.out.println("[server] serving on " + portName + " at " + baudRate + " baud");

    ScheduledExecutorService publisher =
        Executors.newSingleThreadScheduledExecutor(
            runnable -> {
              Thread thread = new Thread(runnable, "iec60870-serial-example-publisher");
              thread.setDaemon(true);
              return thread;
            });

    AtomicInteger counter = new AtomicInteger();
    publisher.scheduleAtFixedRate(
        () -> {
          int tick = counter.incrementAndGet();
          server.publish(STATUS, PointValue.single(tick % 2 == 0), Cause.SPONTANEOUS);
          server.publish(MEASURED, PointValue.shortFloat(tick / 10.0f), Cause.SPONTANEOUS);
          System.out.println(
              "[server] published a spontaneous value for every monitor point (tick " + tick + ")");
        },
        2,
        2,
        TimeUnit.SECONDS);

    // Stop the publisher when the server is closed so the daemon thread does not outlive it.
    return new PublishingServer(server, publisher);
  }

  /**
   * Opens {@link #DEFAULT_PORT} at {@code 9600} baud and serves until the JVM is interrupted (for
   * example with Ctrl+C), at which point the shutdown hook stops the server cleanly.
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

    Iec60870Server server;
    try {
      server = start(portName, baudRate);
    } catch (Exception e) {
      System.out.println("[server] could not open " + portName + ": " + e);
      System.out.println(
          "[server] this is expected when no serial port is attached; point it at a real device.");
      return;
    }

    Runtime.getRuntime()
        .addShutdownHook(
            new Thread(
                () -> {
                  System.out.println("[server] shutting down");
                  server.close();
                }));
    System.out.println("[server] running; press Ctrl+C to stop");

    // Keep the main thread alive; the publisher runs on its own scheduled thread.
    try {
      Thread.currentThread().join();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      server.close();
    }
  }

  /**
   * A handler that accepts commands to {@link #SWITCH}, updating the station image so the new value
   * is returned to the controlling station; all other requests fall through to the default
   * behavior, which answers interrogations and reads from the station image.
   */
  private static final class ExampleHandler implements ServerHandler {

    @Override
    public CommandDecision onCommand(ServerContext context, CommandRequest request) {
      if (request.target().equals(SWITCH)
          && request.commandObject() instanceof SingleCommand command) {
        System.out.println(
            "[server] accepting single command on " + SWITCH + " -> " + command.on());
        return CommandDecision.acceptAndUpdate(PointValue.single(command.on()));
      }
      return CommandDecision.reject(Cause.UNKNOWN_INFORMATION_OBJECT_ADDRESS);
    }
  }

  /**
   * Wraps a server so that {@link #close()} also stops the periodic publisher started alongside it.
   * Every other method delegates to the wrapped server.
   */
  // Intentional delegating decorator, not a data aggregate; record value-semantics (generated
  // equals/hashCode and component accessors) would misrepresent intent.
  @SuppressWarnings("ClassCanBeRecord")
  private static final class PublishingServer implements Iec60870Server {

    private final Iec60870Server delegate;
    private final ScheduledExecutorService publisher;

    PublishingServer(Iec60870Server delegate, ScheduledExecutorService publisher) {
      this.delegate = delegate;
      this.publisher = publisher;
    }

    @Override
    public void start() {
      delegate.start();
    }

    @Override
    public CompletionStage<Void> startAsync() {
      return delegate.startAsync();
    }

    @Override
    public void stop() {
      publisher.shutdownNow();
      delegate.stop();
    }

    @Override
    public CompletionStage<Void> stopAsync() {
      publisher.shutdownNow();
      return delegate.stopAsync();
    }

    @Override
    public StationRegistry stations() {
      return delegate.stations();
    }

    @Override
    public Flow.Publisher<ServerEvent> events() {
      return delegate.events();
    }

    @Override
    public void publish(PointAddress point, PointValue<?> value, Cause cause) {
      delegate.publish(point, value, cause);
    }

    @Override
    public CompletionStage<Void> publishAsync(
        PointAddress point, PointValue<?> value, Cause cause) {
      return delegate.publishAsync(point, value, cause);
    }

    @Override
    public void close() {
      publisher.shutdownNow();
      delegate.close();
    }
  }
}
