package com.digitalpetri.iec104.examples;

import com.digitalpetri.iec104.address.CommonAddress;
import com.digitalpetri.iec104.address.PointAddress;
import com.digitalpetri.iec104.asdu.Cause;
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
 * A controlled station (server) that hosts a handful of points, answers control-direction requests,
 * and spontaneously publishes a measured value on a timer.
 *
 * <p>The station defines three points on common address {@code 1}:
 *
 * <ul>
 *   <li>a single-point status at IOA {@code 100} (reported);
 *   <li>a scaled measured value at IOA {@code 200} (reported), republished every two seconds; and
 *   <li>a commandable single point at IOA {@code 300} whose handler accepts the command and updates
 *       the station image, so the controlling station receives the new value as return information.
 * </ul>
 *
 * <p>Run it with {@code main}; it binds port 2404 on all interfaces and runs until interrupted (for
 * example with Ctrl+C), at which point the shutdown hook stops the server cleanly. The {@link
 * #start(int)} method exposes the same wiring for tests that need an explicit port.
 */
public final class ServerExample {

  /** Common address of the hosted station. */
  static final CommonAddress STATION = CommonAddress.of(1);

  /** Single-point status indication, reported in the monitor direction. */
  static final PointAddress STATUS = PointAddress.of(1, 100);

  /** Scaled measured value, republished periodically. */
  static final PointAddress MEASURAND = PointAddress.of(1, 200);

  /** Commandable single point; its handler accepts the command and updates the image. */
  static final PointAddress SWITCH = PointAddress.of(1, 300);

  private ServerExample() {}

  /**
   * Builds and starts a server on the given port, wiring the station, handler, and periodic
   * publisher.
   *
   * <p>The returned server is already started and registers a periodic task that republishes the
   * measured value; closing the server cancels that task. The caller owns the returned server and
   * must {@link Iec104Server#close() close} it.
   *
   * @param port the TCP port to bind on the loopback interface.
   * @return the started server.
   */
  public static Iec104Server start(int port) {
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
                    MEASURAND,
                    PointType.SCALED,
                    PointValue.scaled((short) 0),
                    PointCapability.REPORTED))
            .point(
                PointDefinition.of(
                    SWITCH,
                    PointType.SINGLE_POINT,
                    PointValue.single(false),
                    PointCapability.REPORTED,
                    PointCapability.COMMANDABLE))
            .group(1, STATUS)
            .group(2, MEASURAND)
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
          short value = (short) counter.incrementAndGet();
          server.publish(MEASURAND, PointValue.scaled(value), Cause.SPONTANEOUS);
          System.out.println("[server] published measured value " + value);
        },
        2,
        2,
        TimeUnit.SECONDS);

    // Stop the publisher when the server is closed so the daemon thread does not outlive it.
    return new PublishingServer(server, publisher);
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
