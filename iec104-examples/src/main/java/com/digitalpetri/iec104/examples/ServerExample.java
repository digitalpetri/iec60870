package com.digitalpetri.iec104.examples;

import com.digitalpetri.iec104.address.CommonAddress;
import com.digitalpetri.iec104.address.PointAddress;
import com.digitalpetri.iec104.asdu.Cause;
import com.digitalpetri.iec104.asdu.element.BinaryCounterReading;
import com.digitalpetri.iec104.asdu.element.DoublePointState;
import com.digitalpetri.iec104.asdu.element.NormalizedValue;
import com.digitalpetri.iec104.asdu.element.Vti;
import com.digitalpetri.iec104.asdu.object.SingleCommand;
import com.digitalpetri.iec104.point.PointCapability;
import com.digitalpetri.iec104.point.PointType;
import com.digitalpetri.iec104.point.PointValue;
import com.digitalpetri.iec104.server.CommandDecision;
import com.digitalpetri.iec104.server.CommandRequest;
import com.digitalpetri.iec104.server.Iec104Server;
import com.digitalpetri.iec104.server.PointDefinition;
import com.digitalpetri.iec104.server.ServerContext;
import com.digitalpetri.iec104.server.ServerEvent;
import com.digitalpetri.iec104.server.ServerHandler;
import com.digitalpetri.iec104.server.Station;
import com.digitalpetri.iec104.server.StationRegistry;
import com.digitalpetri.iec104.transport.tcp.TcpIec104Server;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A controlled station (server) that hosts one monitor point of every logical point type, answers
 * control-direction requests, and spontaneously republishes a fresh value for each point on a
 * timer.
 *
 * <p>The station defines, on common address {@code 1}, one reported point of each monitor type
 * (single-point, double-point, step position, 32-bit bit string, normalized / scaled / short-float
 * measured value) at IOAs {@code 100}..{@code 160}, an integrated-totals counter at IOA {@code 170}
 * (readable; delivered spontaneously and by counter interrogation rather than general
 * interrogation), and a commandable single point at IOA {@code 300} whose handler accepts the
 * command and updates the station image, so the controlling station receives the new value as
 * return information. The seven reported monitor points belong to interrogation group {@code 1} and
 * the commandable point to group {@code 2}.
 *
 * <p>A periodic task republishes a fresh value for every point (including the counter) every two
 * seconds with cause {@link Cause#SPONTANEOUS}, so a connected controlling station observes a point
 * update of each {@link PointType}.
 *
 * <p>Run it with {@code main}; it binds port 2404 on all interfaces and runs until interrupted (for
 * example with Ctrl+C), at which point the shutdown hook stops the server cleanly. The {@link
 * #start(int)} method exposes the same wiring for tests that need an explicit port.
 */
public final class ServerExample {

  /** Common address of the hosted station. */
  static final CommonAddress STATION = CommonAddress.of(1);

  /** Single-point status indication, reported in the monitor direction. */
  static final PointAddress SINGLE = PointAddress.of(1, 100);

  /** Double-point status indication, reported in the monitor direction. */
  static final PointAddress DOUBLE = PointAddress.of(1, 110);

  /** Step-position (tap) information, reported in the monitor direction. */
  static final PointAddress STEP = PointAddress.of(1, 120);

  /** 32-bit bit string, reported in the monitor direction. */
  static final PointAddress BITSTRING = PointAddress.of(1, 130);

  /** Normalized measured value, reported in the monitor direction. */
  static final PointAddress NORMALIZED = PointAddress.of(1, 140);

  /** Scaled measured value, reported in the monitor direction. */
  static final PointAddress SCALED = PointAddress.of(1, 150);

  /** Short floating-point measured value, reported in the monitor direction. */
  static final PointAddress SHORT_FLOAT = PointAddress.of(1, 160);

  /** Integrated-totals counter, readable and republished spontaneously. */
  static final PointAddress COUNTER = PointAddress.of(1, 170);

  /** Commandable single point; its handler accepts the command and updates the image. */
  static final PointAddress SWITCH = PointAddress.of(1, 300);

  private ServerExample() {}

  /**
   * Builds and starts a server on the given port, wiring the station, handler, and periodic
   * publisher.
   *
   * <p>The returned server is already started and registers a periodic task that republishes a
   * fresh value for every point; closing the server cancels that task. The caller owns the returned
   * server and must {@link Iec104Server#close() close} it.
   *
   * @param port the TCP port to bind on the loopback interface.
   * @return the started server.
   */
  public static Iec104Server start(int port) {
    Station station =
        Station.builder(STATION)
            .point(
                PointDefinition.of(
                    SINGLE,
                    PointType.SINGLE_POINT,
                    PointValue.single(false),
                    PointCapability.REPORTED))
            .point(
                PointDefinition.of(
                    DOUBLE,
                    PointType.DOUBLE_POINT,
                    PointValue.doublePoint(DoublePointState.OFF),
                    PointCapability.REPORTED))
            .point(
                PointDefinition.of(
                    STEP,
                    PointType.STEP_POSITION,
                    PointValue.stepPosition(new Vti(0, false)),
                    PointCapability.REPORTED))
            .point(
                PointDefinition.of(
                    BITSTRING,
                    PointType.BITSTRING32,
                    PointValue.bitstring(0),
                    PointCapability.REPORTED))
            .point(
                PointDefinition.of(
                    NORMALIZED,
                    PointType.NORMALIZED,
                    PointValue.normalized(NormalizedValue.of(0.0)),
                    PointCapability.REPORTED))
            .point(
                PointDefinition.of(
                    SCALED,
                    PointType.SCALED,
                    PointValue.scaled((short) 0),
                    PointCapability.REPORTED))
            .point(
                PointDefinition.of(
                    SHORT_FLOAT,
                    PointType.SHORT_FLOAT,
                    PointValue.shortFloat(0f),
                    PointCapability.REPORTED))
            .point(
                PointDefinition.of(
                    COUNTER,
                    PointType.INTEGRATED_TOTALS,
                    PointValue.counter(new BinaryCounterReading(0, 0, false, false, false)),
                    PointCapability.READABLE))
            .point(
                PointDefinition.of(
                    SWITCH,
                    PointType.SINGLE_POINT,
                    PointValue.single(false),
                    PointCapability.REPORTED,
                    PointCapability.COMMANDABLE))
            .group(1, SINGLE)
            .group(1, DOUBLE)
            .group(1, STEP)
            .group(1, BITSTRING)
            .group(1, NORMALIZED)
            .group(1, SCALED)
            .group(1, SHORT_FLOAT)
            .group(2, SWITCH)
            .build();

    Iec104Server server =
        TcpIec104Server.builder()
            .bindAddress("0.0.0.0")
            .port(port)
            .addStation(station)
            .handler(new ExampleHandler())
            .build();

    server.start();
    System.out.println("[server] listening on port " + port);

    ScheduledExecutorService publisher =
        Executors.newSingleThreadScheduledExecutor(
            runnable -> {
              Thread thread = new Thread(runnable, "iec104-example-publisher");
              thread.setDaemon(true);
              return thread;
            });

    AtomicInteger counter = new AtomicInteger();
    publisher.scheduleAtFixedRate(
        () -> {
          int tick = counter.incrementAndGet();
          publishEveryType(server, tick);
          System.out.println(
              "[server] published a spontaneous value for every point (tick " + tick + ")");
        },
        2,
        2,
        TimeUnit.SECONDS);

    // Stop the publisher when the server is closed so the daemon thread does not outlive it.
    return new PublishingServer(server, publisher);
  }

  /**
   * Publishes a fresh value for every hosted monitor point, deriving each value from {@code tick}
   * so the data changes on every cycle. Each update is sent spontaneously ({@link
   * Cause#SPONTANEOUS}), so a connected controlling station observes a point update of every {@link
   * PointType}.
   *
   * @param server the server to publish through.
   * @param tick the monotonically increasing cycle counter used to vary the values.
   */
  private static void publishEveryType(Iec104Server server, int tick) {
    server.publish(SINGLE, PointValue.single(tick % 2 == 0), Cause.SPONTANEOUS);
    server.publish(
        DOUBLE,
        PointValue.doublePoint(tick % 2 == 0 ? DoublePointState.ON : DoublePointState.OFF),
        Cause.SPONTANEOUS);
    server.publish(STEP, PointValue.stepPosition(new Vti(tick % 64, false)), Cause.SPONTANEOUS);
    server.publish(BITSTRING, PointValue.bitstring(tick), Cause.SPONTANEOUS);
    server.publish(
        NORMALIZED,
        PointValue.normalized(NormalizedValue.of((tick % 100) / 100.0)),
        Cause.SPONTANEOUS);
    server.publish(SCALED, PointValue.scaled((short) tick), Cause.SPONTANEOUS);
    server.publish(SHORT_FLOAT, PointValue.shortFloat(tick / 10.0f), Cause.SPONTANEOUS);
    server.publish(
        COUNTER,
        PointValue.counter(new BinaryCounterReading(tick, 0, false, false, false)),
        Cause.SPONTANEOUS);
  }

  /**
   * Starts a server on the standard IEC 104 port 2404 and blocks until the JVM is interrupted.
   *
   * @param args ignored.
   */
  public static void main(String[] args) {
    Iec104Server server = start(2404);
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
  private static final class PublishingServer implements Iec104Server {

    private final Iec104Server delegate;
    private final ScheduledExecutorService publisher;

    PublishingServer(Iec104Server delegate, ScheduledExecutorService publisher) {
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
