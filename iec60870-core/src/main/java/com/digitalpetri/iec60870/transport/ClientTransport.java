package com.digitalpetri.iec60870.transport;

import io.netty.buffer.ByteBuf;
import java.util.concurrent.CompletionStage;

/**
 * An octet-shaped client connection to a single IEC 60870-5 peer.
 *
 * <p>A {@code ClientTransport} owns one outgoing connection. Callers establish it with {@link
 * #connect()}, exchange complete length-delimited frame {@link ByteBuf}s with {@link
 * #send(ByteBuf)} and a registered {@link TransportListener}, and tear it down with {@link
 * #disconnect()}. When a protocol binding has to drop only the current wire connection (for example
 * after a malformed inbound frame) it uses {@link #closeConnection()} instead. The transport
 * handles length framing, optional TLS, and channel lifecycle; it never exposes
 * networking-framework types other than the {@link ByteBuf} that carries one whole frame.
 *
 * <p>Register a {@link TransportListener} with {@link #setListener(TransportListener)} before
 * connecting so no inbound frame is missed. Listener callbacks may be invoked on a transport I/O
 * thread and must not block.
 *
 * <p>Lifecycle methods are asynchronous and idempotent-friendly: the returned {@link
 * CompletionStage} completes when the requested transition has finished. Stages may complete on a
 * transport I/O thread, so dependent actions must not block.
 */
public interface ClientTransport {

  /**
   * Establishes the connection to the configured peer.
   *
   * <p>When TLS is configured, the returned stage completes only after the handshake succeeds.
   *
   * @return a stage that completes when the connection is established, or completes exceptionally
   *     if the connection attempt fails.
   */
  CompletionStage<Void> connect();

  /**
   * Closes the connection to the peer.
   *
   * @return a stage that completes when the connection has been closed.
   */
  CompletionStage<Void> disconnect();

  /**
   * Closes only the current connection, without treating the close as an intentional transport
   * shutdown.
   *
   * <p>This differs from {@link #disconnect()}: persistent transports may reconnect after this
   * close, and owned transport resources such as event loops remain available. The method is a
   * no-op if there is no current connection.
   */
  void closeConnection();

  /**
   * Indicates whether the transport currently has an established connection.
   *
   * @return {@code true} if connected, {@code false} otherwise.
   */
  boolean isConnected();

  /**
   * Sends one complete, length-delimited frame to the peer.
   *
   * <p><b>Buffer ownership.</b> The caller allocates {@code frame}; this method writes-and-flushes
   * it and releases it. The caller must <b>not</b> release {@code frame} after invoking {@code
   * send}.
   *
   * <p>The returned stage reflects only the outcome of the send (the channel write); it does not
   * represent any application-level acknowledgement. Responses arrive asynchronously through the
   * registered {@link TransportListener}.
   *
   * @param frame a complete length-delimited frame; ownership transfers to the transport.
   * @return a stage that completes when the frame has been written, or completes exceptionally if
   *     the send fails (for example because the transport is not connected).
   */
  CompletionStage<Void> send(ByteBuf frame);

  /**
   * Registers the listener that receives inbound frames and connection-loss notifications.
   *
   * <p>Should be set before {@link #connect()} so no inbound frame is missed. Setting a new
   * listener replaces any previously registered one. Callbacks may be invoked on a transport I/O
   * thread and must not block.
   *
   * @param listener the listener to receive transport callbacks.
   */
  void setListener(TransportListener listener);
}
