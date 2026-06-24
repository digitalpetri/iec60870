/**
 * Protocol-frame-shaped transport boundary for IEC 60870-5-104.
 *
 * <p>This package defines the abstractions that connect the protocol model to the network without
 * leaking any networking framework types. Everything here is expressed in terms of {@link
 * com.digitalpetri.iec60870.apci.Apdu} frames: a {@link
 * com.digitalpetri.iec60870.transport.ClientTransport} establishes a single outgoing connection and
 * exchanges APDUs, a {@link com.digitalpetri.iec60870.transport.ServerTransport} accepts inbound
 * connections and surfaces each one as a {@link
 * com.digitalpetri.iec60870.transport.ServerTransportConnection}, and a {@link
 * com.digitalpetri.iec60870.transport.TransportListener} receives decoded APDUs and connection-loss
 * notifications.
 *
 * <h2>Runtime boundaries</h2>
 *
 * <p>The interfaces here are the seam between the higher-level client and server state machines (in
 * {@code com.digitalpetri.iec60870.client} and {@code com.digitalpetri.iec60870.server}) and the
 * concrete I/O layer. Implementations are responsible for framing, TLS, and channel lifecycle, and
 * live entirely outside core in the {@code iec60870-transport-tcp} module (package {@code
 * com.digitalpetri.iec60870.transport.tcp}). No Netty type, {@code Channel}, {@code
 * EventLoopGroup}, {@code SslHandler}, or {@code ByteBuf} appears in this package or anywhere else
 * in core's public API; transports translate between wire bytes and {@code Apdu} values on their
 * own side of the boundary.
 *
 * <h2>Threading</h2>
 *
 * <p>Transports drive callbacks on whatever thread their I/O layer uses. A {@link
 * com.digitalpetri.iec60870.transport.TransportListener} callback and a {@link
 * java.util.function.Consumer} passed to {@link
 * com.digitalpetri.iec60870.transport.ServerTransport#setConnectionHandler} may therefore be
 * invoked on an I/O thread. High-level layers must treat these callbacks as latency-sensitive and
 * must not block them; any blocking or long-running work belongs on a separate executor. The {@link
 * java.util.concurrent.CompletionStage} returned by the lifecycle and send methods completes
 * asynchronously, also potentially on an I/O thread, so dependent actions attached to those stages
 * must follow the same non-blocking discipline.
 *
 * <h2>Extension points</h2>
 *
 * <p>New transports (for example an in-memory loopback for tests, or a non-Netty TCP stack)
 * implement these interfaces directly. Anything carrying networking-framework types belongs in a
 * transport module, never here.
 */
@org.jspecify.annotations.NullMarked
package com.digitalpetri.iec60870.transport;
