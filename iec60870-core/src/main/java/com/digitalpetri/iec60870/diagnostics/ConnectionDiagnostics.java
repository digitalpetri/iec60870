package com.digitalpetri.iec60870.diagnostics;

import java.net.SocketAddress;
import java.time.Duration;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Receives connection-level diagnostic events from a transport and a client or server facade.
 *
 * <p>Every method has an empty default, so a listener overrides only the events it needs. Nothing
 * is reported unless a listener is configured; the default is {@link #NONE}. A listener may be
 * shared by many connections, for example to keep totals across every connection of a server.
 *
 * <p>Callbacks run on the thread that drives the event, possibly while a library lock is held. A
 * listener must return quickly, must not block, and must not call back into the client, server, or
 * session. The library catches and logs an exception thrown by a listener; it never changes
 * protocol state or closes a connection because of one.
 *
 * <p>A link layer extends this interface with frame-level callbacks, for example {@code
 * ApciDiagnostics} for IEC 60870-5-104, and the builders accept that richer type.
 */
public interface ConnectionDiagnostics {

  /** A listener that ignores every event. */
  ConnectionDiagnostics NONE = new ConnectionDiagnostics() {};

  /**
   * Reports that a client connection attempt started, including each automatic reconnect.
   *
   * @param remote the address being dialed; may be unresolved.
   */
  default void onConnectAttempt(SocketAddress remote) {}

  /**
   * Reports that a client connection attempt succeeded: the socket is connected and, with TLS, the
   * handshake succeeded.
   *
   * @param local the local socket address.
   * @param remote the remote socket address.
   */
  default void onConnected(SocketAddress local, SocketAddress remote) {}

  /**
   * Reports that a client connection attempt failed, or that a server rejected a connection.
   *
   * <p>On a client this covers a refused or unreachable peer, an unresolvable host, an elapsed
   * connect timeout ({@code t0}), a failed TLS handshake, and an attempt aborted by a disconnect.
   * On a server it reports a connection rejected because the connection limit was reached.
   *
   * @param remote the peer address.
   * @param cause why the attempt failed or the connection was rejected.
   */
  default void onConnectFailed(SocketAddress remote, Throwable cause) {}

  /**
   * Reports that a server accepted a connection.
   *
   * @param remote the address of the connecting peer.
   */
  default void onConnectionAccepted(SocketAddress remote) {}

  /**
   * Reports that an established connection closed.
   *
   * <p>Reported once per connection that was established, whoever closed it.
   *
   * @param cause why the connection closed, or {@code null} when the application closed it.
   */
  default void onConnectionClosed(@Nullable Throwable cause) {}

  /**
   * Reports that a client scheduled an automatic connection attempt.
   *
   * @param attempt the number of attempts scheduled since the last established connection, starting
   *     at 1.
   * @param delay the delay before the attempt starts.
   * @param cause why the previous connection or attempt ended.
   */
  default void onReconnectScheduled(int attempt, Duration delay, Throwable cause) {}

  /**
   * Reports that a client paused or resumed reading inbound data because an event subscriber fell
   * behind.
   *
   * @param paused {@code true} when reading was paused, {@code false} when it resumed.
   */
  default void onInboundPaused(boolean paused) {}

  /**
   * Reports that a facade discarded an event instead of delivering it to a subscriber.
   *
   * @param eventType the class of the discarded event.
   */
  default void onEventDropped(Class<?> eventType) {}

  /**
   * Reports that an ASDU was discarded without being processed or sent.
   *
   * <p>An inbound ASDU dropped here was already acknowledged at the link layer, so the peer does
   * not send it again. An outbound ASDU dropped here was discarded by a queue overflow policy.
   *
   * @param direction {@link Direction#IN} for a received ASDU, {@link Direction#OUT} for one that
   *     was queued for sending.
   * @param reason a short description of why the ASDU was discarded.
   */
  default void onAsduDropped(Direction direction, String reason) {}

  /**
   * Returns a listener that forwards every event to each of {@code listeners} in order.
   *
   * <p>An exception thrown by one listener is caught and logged, and the remaining listeners still
   * receive the event. Passing a single listener wraps it with that protection; passing {@link
   * #NONE} alone returns {@code NONE}.
   *
   * @param listeners the listeners to forward to.
   * @return the combined listener.
   */
  static ConnectionDiagnostics of(ConnectionDiagnostics... listeners) {
    Objects.requireNonNull(listeners, "listeners");
    if (listeners.length == 1) {
      ConnectionDiagnostics listener = Objects.requireNonNull(listeners[0], "listener");
      if (listener == NONE || listener instanceof GuardedConnectionDiagnostics) {
        return listener;
      }
    }
    return new GuardedConnectionDiagnostics(listeners);
  }
}
