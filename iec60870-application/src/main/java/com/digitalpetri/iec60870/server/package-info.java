/**
 * The high-level IEC 60870-5-104 server (controlled station) API.
 *
 * <p>This package turns the raw protocol model into the workflows an outstation needs: accepting
 * controlling-station connections, answering interrogations and reads from a current-value image,
 * confirming commands, synchronizing clocks, and spontaneously publishing monitored point values.
 * Callers enter through the {@link com.digitalpetri.iec60870.server.Iec60870Server} interface;
 * {@link com.digitalpetri.iec60870.server.DefaultIec60870Server} is the implementation that drives
 * any {@link com.digitalpetri.iec60870.transport.ServerTransport}. The transport knobs
 * (host/port/TLS) live in the transport module, not here.
 *
 * <h2>Stations and the value image</h2>
 *
 * <p>A server owns one or more {@link com.digitalpetri.iec60870.server.Station Stations}, each
 * keyed by a {@link com.digitalpetri.iec60870.address.CommonAddress}. A station declares its points
 * with {@link com.digitalpetri.iec60870.server.PointDefinition PointDefinitions} and maintains a
 * thread-safe current-value image. {@link
 * com.digitalpetri.iec60870.server.Iec60870Server#publish(com.digitalpetri.iec60870.address.PointAddress,
 * com.digitalpetri.iec60870.point.PointValue, com.digitalpetri.iec60870.asdu.Cause) publish}
 * updates that image and pushes a monitor ASDU to every started connection; interrogation and read
 * answers are served from the same image. The {@link
 * com.digitalpetri.iec60870.server.StationRegistry} gives access to the configured stations at
 * runtime.
 *
 * <h2>Request handling</h2>
 *
 * <p>Inbound control-direction ASDUs are dispatched to a {@link
 * com.digitalpetri.iec60870.server.ServerHandler}. Every handler method has a default that
 * implements the standard outstation behavior (answer interrogations and reads from the station
 * image, reject unknown commands, accept clock synchronization) and an asynchronous variant, so an
 * application overrides only the operations it cares about. Each callback receives a {@link
 * com.digitalpetri.iec60870.server.ServerContext} carrying the originating connection, the matched
 * station, default-answer helpers, and a raw {@code send} escape hatch, together with a typed
 * request record; it returns a typed response or decision record (for example {@link
 * com.digitalpetri.iec60870.server.CommandDecision} or {@link
 * com.digitalpetri.iec60870.server.ClockSyncDecision}).
 *
 * <h2>Events</h2>
 *
 * <p>{@link com.digitalpetri.iec60870.server.Iec60870Server#events()} exposes a {@link
 * java.util.concurrent.Flow.Publisher} of {@link com.digitalpetri.iec60870.server.ServerEvent},
 * delivered serially on the configured callback executor so a subscriber never sees two callbacks
 * at once.
 *
 * <h2>Threading and boundaries</h2>
 *
 * <p>Handler callbacks are serialized per connection: a connection never invokes two handler
 * callbacks concurrently. This package contains no networking-framework types; it builds and
 * consumes {@link com.digitalpetri.iec60870.asdu.Asdu} values through the {@link
 * com.digitalpetri.iec60870.transport.ServerTransport} interface and never touches a socket or a
 * {@code ByteBuf}.
 */
@NullMarked
package com.digitalpetri.iec60870.server;

import org.jspecify.annotations.NullMarked;
