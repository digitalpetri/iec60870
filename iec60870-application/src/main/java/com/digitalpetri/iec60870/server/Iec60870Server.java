package com.digitalpetri.iec60870.server;

import com.digitalpetri.iec60870.OutboundQueuePolicy;
import com.digitalpetri.iec60870.address.PointAddress;
import com.digitalpetri.iec60870.asdu.Cause;
import com.digitalpetri.iec60870.point.PointValue;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

/**
 * The high-level IEC 60870-5-104 server (controlled station): accepts controlling-station
 * connections, answers their requests from a per-station value image, and spontaneously publishes
 * monitored point values.
 *
 * <p>A server is built from a {@link com.digitalpetri.iec60870.transport.ServerTransport} and a
 * {@link ServerConfig} (the {@link DefaultIec60870Server} implementation drives any transport; the
 * transport module's entry points add the host/port/TLS knobs). Start it with {@link #start()},
 * mutate or read station values through {@link #stations()} and {@link #publish(PointAddress,
 * PointValue, Cause)}, observe lifecycle and request events through {@link #events()}, and stop it
 * with {@link #stop()} or by closing it.
 *
 * <p>Blocking methods are the primary surface; every one has a {@code *Async} variant returning a
 * {@link CompletionStage}. The instance is thread-safe.
 *
 * <pre>{@code
 * try (Iec60870Server server = TcpIec104Server.builder()
 *         .port(2404)
 *         .config(config)
 *         .build()) {                 // returns com.digitalpetri.iec60870.server.Iec60870Server
 *   server.start();
 *   server.publish(PointAddress.of(1, 100), PointValue.single(true), Cause.SPONTANEOUS);
 * }
 * }</pre>
 */
public interface Iec60870Server extends AutoCloseable {

  /**
   * Starts the server, binding its transport and accepting connections.
   *
   * @throws com.digitalpetri.iec60870.Iec60870Exception if the transport fails to bind.
   */
  void start();

  /**
   * Starts the server asynchronously.
   *
   * @return a stage that completes when the transport is listening, or completes exceptionally if
   *     binding fails.
   */
  CompletionStage<Void> startAsync();

  /**
   * Stops the server, closing all connections and unbinding its transport.
   *
   * @throws com.digitalpetri.iec60870.Iec60870Exception if stopping the transport fails.
   */
  void stop();

  /**
   * Stops the server asynchronously.
   *
   * @return a stage that completes when the transport and its connections have been closed.
   */
  CompletionStage<Void> stopAsync();

  /**
   * Returns the registry of stations hosted by this server.
   *
   * @return the station registry.
   */
  StationRegistry stations();

  /**
   * Returns a publisher of server lifecycle and request events.
   *
   * <p>Events are delivered serially on the configured callback executor.
   *
   * @return the event publisher.
   */
  Flow.Publisher<ServerEvent> events();

  /**
   * Publishes a new value for a point: updates the owning station's value image and pushes a
   * monitor ASDU to every started connection.
   *
   * <p>The point must be defined on a hosted station, and the value's runtime type must match the
   * point's type. Connections that have not started data transfer are skipped; for started
   * connections whose outbound queue is full, the configured {@link OutboundQueuePolicy} applies.
   *
   * @param point the fully qualified address of the point.
   * @param value the new value to publish.
   * @param cause the cause of transmission to carry (for example {@link Cause#SPONTANEOUS}).
   * @throws IllegalArgumentException if no station hosts the point or the value's type does not
   *     match the point's type.
   * @throws NullPointerException if any argument is null.
   */
  void publish(PointAddress point, PointValue<?> value, Cause cause);

  /**
   * Publishes a new value for a point asynchronously.
   *
   * @param point the fully qualified address of the point.
   * @param value the new value to publish.
   * @param cause the cause of transmission to carry.
   * @return a stage that completes when the value has been recorded and enqueued to the started
   *     connections, or completes exceptionally on validation failure.
   */
  CompletionStage<Void> publishAsync(PointAddress point, PointValue<?> value, Cause cause);

  /**
   * Stops the server and releases its resources.
   *
   * <p>Equivalent to {@link #stop()} but never throws a checked exception, so the server can be
   * used with try-with-resources.
   */
  @Override
  void close();
}
