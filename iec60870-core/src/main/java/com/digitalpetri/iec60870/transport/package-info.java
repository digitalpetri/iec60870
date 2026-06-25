/**
 * Octet-shaped transport boundary for IEC 60870-5.
 *
 * <p>This package defines the abstractions that connect the protocol stack to the network without
 * leaking any networking framework types beyond the {@link io.netty.buffer.ByteBuf} that carries
 * one whole frame. Everything here is expressed in terms of complete, length-delimited frame {@link
 * io.netty.buffer.ByteBuf}s: a {@link com.digitalpetri.iec60870.transport.ClientTransport}
 * establishes a single outgoing connection and exchanges whole frames, a {@link
 * com.digitalpetri.iec60870.transport.ServerTransport} accepts inbound connections and surfaces
 * each one as a {@link com.digitalpetri.iec60870.transport.ServerTransportConnection}, and a {@link
 * com.digitalpetri.iec60870.transport.TransportListener} receives one inbound frame per callback
 * plus connection-loss notifications. A client transport also separates intentional shutdown
 * ({@link com.digitalpetri.iec60870.transport.ClientTransport#disconnect()}) from closing only the
 * current connection ({@link
 * com.digitalpetri.iec60870.transport.ClientTransport#closeConnection()}) so persistent
 * implementations can reconnect after protocol bindings drop a bad wire connection. Translating a
 * frame to and from a protocol data unit (a 104 {@code Apdu}, a future 101 link frame) is the job
 * of the protocol layer above this SPI, not of the transport.
 *
 * <h2>Runtime boundaries</h2>
 *
 * <p>The interfaces here are the seam between the protocol layers above (the APCI session, the
 * higher-level client and server) and the concrete I/O layer. Implementations are responsible for
 * length framing, TLS, and channel lifecycle, and live entirely outside core in the {@code
 * iec60870-transport-tcp} module (package {@code com.digitalpetri.iec60870.transport.tcp}). No
 * channel, event-loop, or TLS-engine type appears here; {@link io.netty.buffer.ByteBuf} is the
 * single sanctioned codec-boundary type on this SPI — the same {@code netty-buffer} dependency core
 * already relies on for the raw {@code Serde} layer — and a transport never surfaces any other
 * networking-framework type across the boundary.
 *
 * <h2>Buffer ownership</h2>
 *
 * <p>The {@link io.netty.buffer.ByteBuf} that crosses this SPI is reference-counted, and ownership
 * transfers in a single, uniform direction at each call:
 *
 * <ul>
 *   <li><b>Inbound</b> ({@link
 *       com.digitalpetri.iec60870.transport.TransportListener#onFrame(io.netty.buffer.ByteBuf)}):
 *       the transport <b>owns</b> the buffer. The listener decodes it synchronously and must not
 *       retain it past the call and must not release it. This mirrors Netty's {@code
 *       SimpleChannelInboundHandler}, which auto-releases the message after the callback returns.
 *   <li><b>Outbound</b> ({@link
 *       com.digitalpetri.iec60870.transport.ClientTransport#send(io.netty.buffer.ByteBuf)} and
 *       {@link
 *       com.digitalpetri.iec60870.transport.ServerTransportConnection#send(io.netty.buffer.ByteBuf)}):
 *       the caller allocates the buffer; the transport writes-and-flushes it and releases it. The
 *       caller must not release it after calling {@code send}.
 * </ul>
 *
 * <h2>Threading</h2>
 *
 * <p>Transports drive callbacks on whatever thread their I/O layer uses. A {@link
 * com.digitalpetri.iec60870.transport.TransportListener} callback and a {@link
 * java.util.function.Consumer} passed to {@link
 * com.digitalpetri.iec60870.transport.ServerTransport#setConnectionHandler} may therefore be
 * invoked on an I/O thread. Higher layers must treat these callbacks as latency-sensitive and must
 * not block them; any blocking or long-running work belongs on a separate executor. The {@link
 * java.util.concurrent.CompletionStage} returned by the lifecycle and send methods completes
 * asynchronously, also potentially on an I/O thread, so dependent actions attached to those stages
 * must follow the same non-blocking discipline.
 *
 * <h2>Extension points</h2>
 *
 * <p>New transports (for example an in-memory loopback for tests, or a serial transport) implement
 * these interfaces directly. Anything carrying networking-framework types beyond the frame {@link
 * io.netty.buffer.ByteBuf} belongs in a transport module, never here.
 */
@org.jspecify.annotations.NullMarked
package com.digitalpetri.iec60870.transport;
