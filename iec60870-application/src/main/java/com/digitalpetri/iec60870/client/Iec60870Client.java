package com.digitalpetri.iec60870.client;

import com.digitalpetri.iec60870.address.CommonAddress;
import com.digitalpetri.iec60870.address.PointAddress;
import com.digitalpetri.iec60870.asdu.Asdu;
import com.digitalpetri.iec60870.asdu.InformationObject;
import com.digitalpetri.iec60870.asdu.element.QualifierOfInterrogation;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

/**
 * A high-level IEC 60870-5-104 client (controlling station).
 *
 * <p>An {@code Iec60870Client} drives one connection to a controlled station: it connects, starts
 * and stops data transfer, interrogates stations, reads points, issues commands, and synchronizes
 * clocks. Blocking methods are the primary surface; each has a {@code *Async} variant returning a
 * {@link CompletionStage}. The client is {@link AutoCloseable}, so it works with
 * try-with-resources.
 *
 * <p>After {@link #connect()} the client manages the connection: if it is lost, or a connection
 * attempt fails, the client reconnects on its own according to its {@link ReconnectPolicy}, resets
 * the session, and starts data transfer again when configured. {@link #disconnect()} stops this,
 * and {@link #close()} ends the client. Requests in flight when a connection is lost fail with a
 * {@link com.digitalpetri.iec60870.ConnectionClosedException} and are never replayed. {@link
 * #connectionState()} and the lifecycle events on {@link #events()} report what the client is
 * doing.
 *
 * <p>Received data is delivered through {@link #events()}: every monitor ASDU yields one {@link
 * ClientEvent.PointUpdated} per information object, alongside an always-published {@link
 * ClientEvent.AsduReceived}. Each {@code PointUpdated} reports the value's point type and the wire
 * type identification that carried it. Events are delivered serially on the client's callback
 * executor.
 *
 * <pre>{@code
 * try (Iec60870Client client = TcpIec104Client.builder()
 *         .host("127.0.0.1").port(2404)
 *         .startDataTransferOnConnect(true)
 *         .build()) {
 *   client.connect();
 *   InterrogationResult snapshot = client.interrogate(CommonAddress.of(1));
 *   CommandResult r = client.commands().single(point, true);
 * }
 * }</pre>
 */
public interface Iec60870Client extends AutoCloseable {

  /**
   * Starts the managed connection and waits for it to be established.
   *
   * <p>Returns once a connection is established and, when configured, data transfer has started. If
   * that attempt fails, this method throws its failure; unless the {@link ReconnectPolicy} is
   * disabled, the client keeps retrying in the background. Each attempt is bounded by the
   * transport's connect timeout ({@code t0} for IEC 60870-5-104), the TLS handshake timeout when
   * TLS is enabled, and the {@code STARTDT} confirmation timeout ({@code t1}).
   *
   * <p>Calling again while connected preserves the session. A call while an attempt is in progress
   * joins that attempt, and a call while the client waits to reconnect starts an attempt at once. A
   * closed client cannot be connected again.
   *
   * @throws com.digitalpetri.iec60870.ConnectionClosedException if the connection cannot be
   *     established or the client is closed; the transport's failure is its cause.
   * @throws com.digitalpetri.iec60870.ProtocolTimeoutException if a {@code STARTDT} handshake does
   *     not complete in time.
   */
  void connect();

  /**
   * Starts the managed connection.
   *
   * <p>Behaves like {@link #connect()} without blocking.
   *
   * @return a stage that completes once the client is connected (and data transfer started, if
   *     configured), or completes exceptionally with the failure of the attempt.
   */
  CompletionStage<Void> connectAsync();

  /**
   * Stops the managed connection and waits for the connection to close.
   *
   * <p>Cancels any scheduled reconnect, fails requests in flight with a {@link
   * com.digitalpetri.iec60870.ConnectionClosedException}, and closes the current connection without
   * a {@code STOPDT} handshake. When a connection was open, {@link ClientEvent.DataTransferStopped}
   * (if data transfer was started) and {@link ClientEvent.ConnectionClosed} with no cause are
   * published. The client can be connected again with {@link #connect()}. Does nothing on a closed
   * client.
   */
  void disconnect();

  /**
   * Stops the managed connection.
   *
   * <p>Behaves like {@link #disconnect()} without blocking.
   *
   * @return a stage that completes once the connection has closed.
   */
  CompletionStage<Void> disconnectAsync();

  /**
   * Returns the state of the managed connection.
   *
   * @return the current connection state.
   */
  ConnectionState connectionState();

  /**
   * Starts user-data transfer by performing the {@code STARTDT} handshake.
   *
   * @throws com.digitalpetri.iec60870.ProtocolTimeoutException if the {@code STARTDT} confirmation
   *     does not arrive in time.
   * @throws com.digitalpetri.iec60870.ConnectionClosedException if the connection is closed.
   */
  void startDataTransfer();

  /**
   * Starts user-data transfer by performing the {@code STARTDT} handshake.
   *
   * @return a stage that completes when data transfer has started, or completes exceptionally on
   *     failure.
   */
  CompletionStage<Void> startDataTransferAsync();

  /**
   * Stops user-data transfer by performing the {@code STOPDT} handshake.
   *
   * @throws com.digitalpetri.iec60870.ProtocolTimeoutException if the {@code STOPDT} confirmation
   *     does not arrive in time.
   * @throws com.digitalpetri.iec60870.ConnectionClosedException if the connection is closed.
   */
  void stopDataTransfer();

  /**
   * Stops user-data transfer by performing the {@code STOPDT} handshake.
   *
   * @return a stage that completes when data transfer has stopped, or completes exceptionally on
   *     failure.
   */
  CompletionStage<Void> stopDataTransferAsync();

  /**
   * Returns the publisher of client events.
   *
   * <p>Events are delivered serially on the client's callback executor: a subscriber never observes
   * two events concurrently. Subscribe before connecting to avoid missing early events; there is no
   * replay.
   *
   * <p>The stream never drops an event for a slow subscriber, including one that requests events in
   * small batches with {@link Flow.Subscription#request(long)}. When the slowest subscriber falls
   * {@link ClientConfig#eventBacklogLimit()} events behind, the client stops taking in user data
   * until the backlog falls to half the limit, and the peer's own flow control holds it back. A
   * subscriber may therefore block or do slow work in {@code onNext}, at the cost of slowing the
   * data stream. A subscriber stalled longer than the peer's acknowledgement timeout ends in a
   * {@link ClientEvent.ConnectionClosed} with a cause. Lifecycle events are always delivered, after
   * the events published before them. A blocking client call made from {@code onNext} completes on
   * the callback executor, so it needs a free thread there, such as the default common pool; with a
   * single-threaded executor, use the {@code *Async} form instead. While a subscriber is so far
   * behind that its buffer is nearly full, {@link #connect()}, {@link #startDataTransfer()}, and
   * {@link #stopDataTransfer()} fail with {@link
   * com.digitalpetri.iec60870.EventBacklogExceededException} so their lifecycle events still fit.
   *
   * @return the event publisher.
   */
  Flow.Publisher<ClientEvent> events();

  /**
   * Performs a station (global) interrogation and collects the reported snapshot.
   *
   * @param station the common address to interrogate.
   * @return the interrogation result.
   * @throws com.digitalpetri.iec60870.NegativeConfirmationException if the station rejects the
   *     interrogation.
   * @throws com.digitalpetri.iec60870.ProtocolTimeoutException if the interrogation does not
   *     complete in time.
   * @throws com.digitalpetri.iec60870.ConnectionClosedException if the connection is closed.
   */
  InterrogationResult interrogate(CommonAddress station);

  /**
   * Performs an interrogation with the given qualifier and collects the reported snapshot.
   *
   * @param station the common address to interrogate.
   * @param qoi the qualifier of interrogation selecting the station or a specific group.
   * @return the interrogation result.
   * @throws com.digitalpetri.iec60870.NegativeConfirmationException if the station rejects the
   *     interrogation.
   * @throws com.digitalpetri.iec60870.ProtocolTimeoutException if the interrogation does not
   *     complete in time.
   * @throws com.digitalpetri.iec60870.ConnectionClosedException if the connection is closed.
   */
  InterrogationResult interrogate(CommonAddress station, QualifierOfInterrogation qoi);

  /**
   * Performs a station (global) interrogation and collects the reported snapshot.
   *
   * @param station the common address to interrogate.
   * @return a stage that completes with the interrogation result, or completes exceptionally on
   *     failure.
   */
  CompletionStage<InterrogationResult> interrogateAsync(CommonAddress station);

  /**
   * Performs an interrogation with the given qualifier and collects the reported snapshot.
   *
   * @param station the common address to interrogate.
   * @param qoi the qualifier of interrogation selecting the station or a specific group.
   * @return a stage that completes with the interrogation result, or completes exceptionally on
   *     failure.
   */
  CompletionStage<InterrogationResult> interrogateAsync(
      CommonAddress station, QualifierOfInterrogation qoi);

  /**
   * Reads the current value of a single point with a read command (C_RD_NA_1).
   *
   * @param point the address of the point to read.
   * @return the information objects the station returned in response, in receive order.
   * @throws com.digitalpetri.iec60870.NegativeConfirmationException if the station rejects the
   *     read.
   * @throws com.digitalpetri.iec60870.ProtocolTimeoutException if no response arrives in time.
   * @throws com.digitalpetri.iec60870.ConnectionClosedException if the connection is closed.
   */
  List<InformationObject> read(PointAddress point);

  /**
   * Reads the current value of a single point with a read command (C_RD_NA_1).
   *
   * @param point the address of the point to read.
   * @return a stage that completes with the response objects, or completes exceptionally on
   *     failure.
   */
  CompletionStage<List<InformationObject>> readAsync(PointAddress point);

  /**
   * Returns the command service used to issue control-direction commands.
   *
   * @return the command service.
   */
  CommandService commands();

  /**
   * Synchronizes the clock of a station with a clock synchronization command (C_CS_NA_1).
   *
   * <p>The time is encoded with {@link ClientConfig#timePolicy()}, so the station receives
   * wall-clock fields in the zone of its own clock.
   *
   * @param station the common address whose clock to set.
   * @param time the time to set.
   * @throws com.digitalpetri.iec60870.NegativeConfirmationException if the station rejects the
   *     command.
   * @throws com.digitalpetri.iec60870.ProtocolTimeoutException if the confirmation does not arrive
   *     in time.
   * @throws com.digitalpetri.iec60870.ConnectionClosedException if the connection is closed.
   * @throws IllegalArgumentException if {@code time} falls outside the time policy's century.
   */
  void synchronizeClock(CommonAddress station, Instant time);

  /**
   * Synchronizes the clock of a station with a clock synchronization command (C_CS_NA_1).
   *
   * <p>The time is encoded with {@link ClientConfig#timePolicy()}, so the station receives
   * wall-clock fields in the zone of its own clock.
   *
   * @param station the common address whose clock to set.
   * @param time the time to set.
   * @return a stage that completes when the command is confirmed, or completes exceptionally on
   *     failure.
   * @throws IllegalArgumentException if {@code time} falls outside the time policy's century.
   */
  CompletionStage<Void> synchronizeClockAsync(CommonAddress station, Instant time);

  /**
   * Sends a raw ASDU, bypassing the high-level command and request helpers.
   *
   * <p>This is the escape hatch for private TypeIDs and conformance work. The send completes once
   * the frame has been written; any response arrives asynchronously through {@link #events()}.
   *
   * @param asdu the ASDU to send.
   * @throws com.digitalpetri.iec60870.ConnectionClosedException if the connection is closed.
   */
  void send(Asdu asdu);

  /**
   * Sends a raw ASDU, bypassing the high-level command and request helpers.
   *
   * @param asdu the ASDU to send.
   * @return a stage that completes when the frame has been written, or completes exceptionally on
   *     failure.
   */
  CompletionStage<Void> sendAsync(Asdu asdu);

  /**
   * Reports whether the session is armed on a live connection.
   *
   * @return {@code true} if {@link #connectionState()} is {@link ConnectionState#CONNECTED} and the
   *     transport connection is open.
   */
  boolean isConnected();

  /**
   * Returns the number of ASDUs waiting in the session's send queue, not yet written to the
   * transport.
   *
   * <p>ASDUs wait while {@code k} sent I-frames are unacknowledged or data transfer is stopped. A
   * depth that keeps growing means the peer stopped acknowledging.
   *
   * @return the send-queue depth; {@code 0} when no connection is open.
   */
  int pendingSendCount();

  /**
   * Returns the number of requests awaiting a correlated response: interrogations, reads, commands,
   * and clock synchronizations.
   *
   * @return the number of in-flight requests.
   */
  int pendingRequestCount();

  /**
   * Closes the client for good.
   *
   * <p>Stops the managed connection like {@link #disconnect()}, publishes {@link
   * ClientEvent.ConnectionClosed} with no cause if a connection was open, completes the event
   * stream, and releases the transport's resources. After this call, {@link #connect()} fails with
   * a {@link com.digitalpetri.iec60870.ConnectionClosedException}. This method is idempotent.
   */
  @Override
  void close();
}
