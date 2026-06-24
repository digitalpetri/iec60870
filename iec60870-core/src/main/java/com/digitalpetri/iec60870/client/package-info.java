/**
 * The high-level IEC 60870-5-104 client (controlling station) API.
 *
 * <p>This package turns the raw protocol model into the workflows a SCADA master needs: connecting,
 * starting and stopping data transfer, interrogating stations, reading points, issuing commands,
 * and synchronizing clocks. Callers enter through the {@link
 * com.digitalpetri.iec60870.client.Iec60870Client} interface; {@link
 * com.digitalpetri.iec60870.client.DefaultIec60870Client} is the implementation that drives any
 * {@link com.digitalpetri.iec60870.transport.ClientTransport}. The transport knobs (host/port/TLS)
 * live in the transport module, not here.
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
 * <p>Interrogation, read, command, and clock-synchronization requests are correlated against the
 * controlled station's confirmations. Commands flow through {@link
 * com.digitalpetri.iec60870.client.CommandService} as a {@link
 * com.digitalpetri.iec60870.client.Command} plus a {@link
 * com.digitalpetri.iec60870.client.CommandMode} (direct-execute or select-before-operate) and
 * return a {@link com.digitalpetri.iec60870.client.CommandResult}. A negative confirmation (P/N =
 * 1) is surfaced as a non-positive {@code CommandResult} for commands and as a {@link
 * com.digitalpetri.iec60870.NegativeConfirmationException} for interrogation, read, and clock-sync.
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
