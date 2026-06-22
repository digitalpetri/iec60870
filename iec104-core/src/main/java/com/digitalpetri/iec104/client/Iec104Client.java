package com.digitalpetri.iec104.client;

import com.digitalpetri.iec104.address.CommonAddress;
import com.digitalpetri.iec104.address.PointAddress;
import com.digitalpetri.iec104.asdu.Asdu;
import com.digitalpetri.iec104.asdu.InformationObject;
import com.digitalpetri.iec104.asdu.element.QualifierOfInterrogation;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

/**
 * A high-level IEC 60870-5-104 client (controlling station).
 *
 * <p>An {@code Iec104Client} drives one connection to a controlled station: it connects, starts and
 * stops data transfer, interrogates stations, reads points, issues commands, and synchronizes
 * clocks. Blocking methods are the primary surface; each has a {@code *Async} variant returning a
 * {@link CompletionStage}. The client is {@link AutoCloseable}, so it works with
 * try-with-resources.
 *
 * <p>Received data is delivered through {@link #events()}: every monitor ASDU yields one {@link
 * ClientEvent.PointUpdated} per information object, alongside an always-published {@link
 * ClientEvent.AsduReceived}. Each {@code PointUpdated} reports the value's point type and the wire
 * type identification that carried it. Events are delivered serially on the client's callback
 * executor.
 *
 * <pre>{@code
 * try (Iec104Client client = TcpIec104Client.builder()
 *         .host("127.0.0.1").port(2404)
 *         .startDataTransferOnConnect(true)
 *         .build()) {
 *   client.connect();
 *   InterrogationResult snapshot = client.interrogate(CommonAddress.of(1));
 *   CommandResult r = client.commands().single(point, true);
 * }
 * }</pre>
 */
public interface Iec104Client extends AutoCloseable {

  /**
   * Establishes the connection and, when configured, starts data transfer.
   *
   * @throws com.digitalpetri.iec104.ConnectionClosedException if the connection cannot be
   *     established.
   * @throws com.digitalpetri.iec104.ProtocolTimeoutException if a {@code STARTDT} handshake does
   *     not complete in time.
   */
  void connect();

  /**
   * Establishes the connection and, when configured, starts data transfer.
   *
   * @return a stage that completes once the client is connected (and data transfer started, if
   *     configured), or completes exceptionally on failure.
   */
  CompletionStage<Void> connectAsync();

  /**
   * Starts user-data transfer by performing the {@code STARTDT} handshake.
   *
   * @throws com.digitalpetri.iec104.ProtocolTimeoutException if the {@code STARTDT} confirmation
   *     does not arrive in time.
   * @throws com.digitalpetri.iec104.ConnectionClosedException if the connection is closed.
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
   * @throws com.digitalpetri.iec104.ProtocolTimeoutException if the {@code STOPDT} confirmation
   *     does not arrive in time.
   * @throws com.digitalpetri.iec104.ConnectionClosedException if the connection is closed.
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
   * two events concurrently. Subscribe before connecting to avoid missing early events.
   *
   * @return the event publisher.
   */
  Flow.Publisher<ClientEvent> events();

  /**
   * Performs a station (global) interrogation and collects the reported snapshot.
   *
   * @param station the common address to interrogate.
   * @return the interrogation result.
   * @throws com.digitalpetri.iec104.NegativeConfirmationException if the station rejects the
   *     interrogation.
   * @throws com.digitalpetri.iec104.ProtocolTimeoutException if the interrogation does not complete
   *     in time.
   * @throws com.digitalpetri.iec104.ConnectionClosedException if the connection is closed.
   */
  InterrogationResult interrogate(CommonAddress station);

  /**
   * Performs an interrogation with the given qualifier and collects the reported snapshot.
   *
   * @param station the common address to interrogate.
   * @param qoi the qualifier of interrogation selecting the station or a specific group.
   * @return the interrogation result.
   * @throws com.digitalpetri.iec104.NegativeConfirmationException if the station rejects the
   *     interrogation.
   * @throws com.digitalpetri.iec104.ProtocolTimeoutException if the interrogation does not complete
   *     in time.
   * @throws com.digitalpetri.iec104.ConnectionClosedException if the connection is closed.
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
   * @throws com.digitalpetri.iec104.NegativeConfirmationException if the station rejects the read.
   * @throws com.digitalpetri.iec104.ProtocolTimeoutException if no response arrives in time.
   * @throws com.digitalpetri.iec104.ConnectionClosedException if the connection is closed.
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
   * @param station the common address whose clock to set.
   * @param time the time to set.
   * @throws com.digitalpetri.iec104.NegativeConfirmationException if the station rejects the
   *     command.
   * @throws com.digitalpetri.iec104.ProtocolTimeoutException if the confirmation does not arrive in
   *     time.
   * @throws com.digitalpetri.iec104.ConnectionClosedException if the connection is closed.
   */
  void synchronizeClock(CommonAddress station, Instant time);

  /**
   * Synchronizes the clock of a station with a clock synchronization command (C_CS_NA_1).
   *
   * @param station the common address whose clock to set.
   * @param time the time to set.
   * @return a stage that completes when the command is confirmed, or completes exceptionally on
   *     failure.
   */
  CompletionStage<Void> synchronizeClockAsync(CommonAddress station, Instant time);

  /**
   * Sends a raw ASDU, bypassing the high-level command and request helpers.
   *
   * <p>This is the escape hatch for private TypeIDs and conformance work. The send completes once
   * the frame has been written; any response arrives asynchronously through {@link #events()}.
   *
   * @param asdu the ASDU to send.
   * @throws com.digitalpetri.iec104.ConnectionClosedException if the connection is closed.
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
   * Reports whether the client currently has an established connection.
   *
   * @return {@code true} if connected.
   */
  boolean isConnected();

  /** Closes the client and the underlying connection. This method is idempotent. */
  @Override
  void close();
}
