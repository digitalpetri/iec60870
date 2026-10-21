package com.digitalpetri.iec60870.transport;

import io.netty.buffer.ByteBuf;
import java.util.concurrent.CompletionStage;

/**
 * An octet-shaped client connection to a single IEC 60870-5 peer.
 *
 * <p>A {@code ClientTransport} owns one outgoing connection at a time. Callers establish it with
 * {@link #connect()}, exchange complete length-delimited frame {@link ByteBuf}s with {@link
 * #send(ByteBuf)} and a registered {@link TransportListener}, and close it with {@link
 * #disconnect()} or {@link #closeConnection()}. Both close only the current connection; a later
 * {@code connect()} opens a new one. {@link #close()} ends the transport and releases what it owns.
 * The transport handles length framing, optional TLS, and channel lifecycle; it never exposes
 * networking-framework types other than the {@link ByteBuf} that carries one whole frame.
 *
 * <p>A transport never reconnects on its own. Each {@code connect()} makes one attempt, and a lost
 * connection stays closed until the next {@code connect()}. Retrying belongs to the caller.
 *
 * <p>Register a {@link TransportListener} with {@link #setListener(TransportListener)} before
 * connecting so no inbound frame is missed. Listener callbacks may be invoked on a transport I/O
 * thread and must not block. The listener hears about the loss of a connection that the peer or the
 * network closed; a connection closed through {@code disconnect()}, {@code closeConnection()}, or
 * {@code close()} need not be reported.
 *
 * <p>Lifecycle methods are asynchronous and idempotent-friendly: the returned {@link
 * CompletionStage} completes when the requested transition has finished. Stages may complete on a
 * transport I/O thread, so dependent actions must not block.
 */
public interface ClientTransport extends AutoCloseable {

  /**
   * Makes one attempt to connect to the configured peer.
   *
   * <p>The attempt is bounded by the transport's connect timeout and, when TLS is configured, by
   * the TLS handshake timeout; the returned stage completes only after the handshake succeeds. A
   * failed attempt is not retried. Calling this while connected completes at once, and calling it
   * while an attempt is in progress joins that attempt. After {@link #close()} the stage fails with
   * a {@link com.digitalpetri.iec60870.ConnectionClosedException}.
   *
   * @return a stage that completes when the connection is established, or completes exceptionally
   *     if the connection attempt fails.
   */
  CompletionStage<Void> connect();

  /**
   * Closes the current connection and aborts an attempt in progress.
   *
   * <p>The transport stays usable: a later {@link #connect()} opens a new connection.
   *
   * @return a stage that completes when the connection has been closed.
   */
  CompletionStage<Void> disconnect();

  /**
   * Closes the current connection without waiting for the close to finish.
   *
   * <p>Protocol bindings call this to drop a wire connection they can no longer trust, for example
   * after a malformed inbound frame. Like {@link #disconnect()}, it leaves the transport usable.
   * The method is a no-op if there is no current connection.
   */
  void closeConnection();

  /**
   * Closes the current connection and releases the resources the transport owns, such as a private
   * event loop.
   *
   * <p>The transport cannot be connected again afterwards. This method is idempotent. The default
   * only calls {@link #disconnect()}; an implementation must override this method to reject later
   * {@link #connect()} calls and to release resources it owns.
   */
  @Override
  default void close() {
    disconnect();
  }

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
   * <p><b>Ordering.</b> Frames reach the peer in the order {@code send} is called, even when the
   * calls come from different threads. Sessions emit frames under their lock from both transport
   * and application threads and rely on this to keep the I-frame sequence intact.
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
   * Pauses or resumes reading inbound frames from the current connection.
   *
   * <p>While paused, the transport stops reading from the peer, so frames wait in the operating
   * system's buffers and the peer's own flow control throttles it. Frames the transport has already
   * read may still be delivered to the listener after the call. Writes are not affected. The pause
   * applies to the current connection only; a new connection starts with reading enabled.
   *
   * <p>May be called from any thread, including from within a {@link TransportListener} callback.
   * The default does nothing, which leaves reading enabled.
   *
   * @param paused {@code true} to stop reading, {@code false} to resume.
   */
  default void setReadPaused(boolean paused) {}

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
