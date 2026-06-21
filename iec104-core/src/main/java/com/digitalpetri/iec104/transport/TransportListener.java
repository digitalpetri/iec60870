package com.digitalpetri.iec104.transport;

import com.digitalpetri.iec104.apci.Apdu;
import org.jspecify.annotations.Nullable;

/**
 * Receives inbound protocol frames and connection-loss notifications from a transport.
 *
 * <p>A listener is registered on a {@link ClientTransport} or {@link ServerTransportConnection} via
 * {@code setListener}. While a connection is established, every successfully decoded {@link Apdu}
 * is delivered to {@link #onApdu(Apdu)} in receive order; when the connection is lost or closed,
 * {@link #onConnectionLost(Throwable)} is invoked exactly once.
 *
 * <p>Callbacks may be invoked on a transport I/O thread. Implementations must not block: any
 * blocking or long-running processing should be dispatched to a separate executor so the
 * transport's I/O thread is not stalled.
 */
public interface TransportListener {

  /**
   * Called when a protocol frame is received from the peer.
   *
   * <p>Invoked once per decoded frame, in receive order. May run on a transport I/O thread, so the
   * implementation must return promptly and must not block.
   *
   * @param apdu the received application protocol data unit.
   */
  void onApdu(Apdu apdu);

  /**
   * Called once when the underlying connection is lost or closed.
   *
   * <p>After this callback no further {@link #onApdu(Apdu)} calls occur for the connection. May run
   * on a transport I/O thread, so the implementation must return promptly and must not block.
   *
   * @param cause the failure that caused the connection to be lost, or {@code null} if the
   *     connection was closed normally.
   */
  void onConnectionLost(@Nullable Throwable cause);
}
