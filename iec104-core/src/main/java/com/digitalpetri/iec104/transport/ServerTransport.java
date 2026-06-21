package com.digitalpetri.iec104.transport;

import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

/**
 * A protocol-frame-shaped listening endpoint that accepts inbound IEC 60870-5-104 connections.
 *
 * <p>A {@code ServerTransport} owns the listening socket. Callers register a connection handler
 * with {@link #setConnectionHandler(Consumer)} before binding, start listening with {@link
 * #bind()}, and stop with {@link #unbind()}. Each accepted client is delivered to the handler as a
 * {@link ServerTransportConnection}, through which frames are exchanged. The transport handles
 * framing, optional TLS, and channel lifecycle; it never exposes networking-framework types.
 *
 * <p>The connection handler may be invoked on a transport I/O thread as connections are accepted
 * and must not block. Lifecycle methods are asynchronous: the returned {@link CompletionStage}
 * completes when the requested transition has finished, possibly on a transport I/O thread.
 */
public interface ServerTransport {

  /**
   * Starts listening for inbound connections on the configured endpoint.
   *
   * <p>Set a connection handler with {@link #setConnectionHandler(Consumer)} before calling this so
   * no accepted connection is missed.
   *
   * @return a stage that completes when the endpoint is listening, or completes exceptionally if
   *     binding fails.
   */
  CompletionStage<Void> bind();

  /**
   * Stops listening and closes the listening endpoint along with its accepted connections.
   *
   * @return a stage that completes when the endpoint and its connections have been closed.
   */
  CompletionStage<Void> unbind();

  /**
   * Registers the handler invoked once for each accepted client connection.
   *
   * <p>Should be set before {@link #bind()} so no accepted connection is missed. Setting a new
   * handler replaces any previously registered one. The handler typically registers a {@link
   * TransportListener} on the supplied {@link ServerTransportConnection}. The handler may be
   * invoked on a transport I/O thread and must not block.
   *
   * @param onAccept the consumer invoked with each newly accepted connection.
   */
  void setConnectionHandler(Consumer<ServerTransportConnection> onAccept);
}
