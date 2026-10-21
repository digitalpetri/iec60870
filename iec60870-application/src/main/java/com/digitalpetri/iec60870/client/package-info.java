/**
 * The high-level IEC 60870-5-104 client (controlling station) API.
 *
 * <p>This package turns the raw protocol model into the workflows a SCADA master needs: connecting,
 * starting and stopping data transfer, interrogating stations and their counters, reading points,
 * issuing commands, synchronizing clocks, testing the link, resetting processes, and loading
 * parameters. Callers enter through the {@link com.digitalpetri.iec60870.client.Iec60870Client}
 * interface; {@link com.digitalpetri.iec60870.client.DefaultIec60870Client} is the implementation
 * that drives any {@link com.digitalpetri.iec60870.transport.ClientTransport}. The transport knobs
 * (host/port/TLS) live in the transport module, not here.
 *
 * <h2>Lifecycle</h2>
 *
 * <p>A client is built from a {@link com.digitalpetri.iec60870.transport.ClientTransport} and a
 * {@link com.digitalpetri.iec60870.client.ClientConfig}. {@link
 * com.digitalpetri.iec60870.client.Iec60870Client#connect()} establishes the transport and, when
 * {@link com.digitalpetri.iec60870.client.ClientConfig#startDataTransferOnConnect()} is set, sends
 * {@code STARTDT}. Blocking methods are the primary surface; every one has a {@code *Async} variant
 * that returns a {@link java.util.concurrent.CompletionStage}.
 *
 * <h2>Events</h2>
 *
 * <p>{@link com.digitalpetri.iec60870.client.Iec60870Client#events()} exposes a {@link
 * java.util.concurrent.Flow.Publisher} of {@link com.digitalpetri.iec60870.client.ClientEvent}.
 * Every received monitor ASDU yields one {@link
 * com.digitalpetri.iec60870.client.ClientEvent.PointUpdated} per information object, and an {@link
 * com.digitalpetri.iec60870.client.ClientEvent.AsduReceived} is always published as well. Each
 * {@code PointUpdated} reports the value's point type and the wire type identification that carried
 * it. Events are delivered serially on the configured callback executor, so a subscriber never sees
 * two callbacks at once.
 *
 * <h2>Requests and correlation</h2>
 *
 * <p>Every request except the raw {@code send} is correlated against the controlled station's
 * confirmations: interrogation, counter interrogation, read, commands, clock synchronization, test,
 * reset process, and parameter load and activation. Correlation matches the reply's fields to the
 * pending request, since the protocol carries no request identifier, so a second request that would
 * compete for the same replies fails with {@link
 * com.digitalpetri.iec60870.RequestInProgressException}. A request to the global common address is
 * rejected for the same reason: every station answers it. Commands flow through {@link
 * com.digitalpetri.iec60870.client.CommandService} as a {@link
 * com.digitalpetri.iec60870.client.Command} plus a {@link
 * com.digitalpetri.iec60870.client.CommandMode} (direct-execute or select-before-operate) and
 * return a {@link com.digitalpetri.iec60870.client.CommandResult}. A command completes on its
 * activation confirmation or, under {@link
 * com.digitalpetri.iec60870.client.CommandCompletion#TERMINATION}, on the activation termination
 * that follows. {@link com.digitalpetri.iec60870.client.CommandService#select} returns a {@link
 * com.digitalpetri.iec60870.client.SelectedCommand} that holds the selection until it is executed
 * or cancelled with a deactivation. A negative confirmation (P/N = 1) is surfaced as a non-positive
 * {@code CommandResult} for commands, a non-positive {@link
 * com.digitalpetri.iec60870.client.ParameterResult} for parameter loads, and a {@link
 * com.digitalpetri.iec60870.NegativeConfirmationException} for the other requests.
 *
 * <h2>Boundaries</h2>
 *
 * <p>This package contains no networking-framework types. It builds and consumes {@link
 * com.digitalpetri.iec60870.asdu.Asdu} values through the {@link
 * com.digitalpetri.iec60870.transport.ClientTransport} interface and never touches a socket or a
 * {@code ByteBuf}.
 */
@NullMarked
package com.digitalpetri.iec60870.client;

import org.jspecify.annotations.NullMarked;
