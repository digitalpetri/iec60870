package com.digitalpetri.iec60870.transport;

import io.netty.buffer.ByteBuf;
import java.net.SocketAddress;
import java.security.cert.Certificate;
import java.util.Optional;
import java.util.concurrent.CompletionStage;

/**
 * A single accepted client connection on a {@link ServerTransport}.
 *
 * <p>One instance represents one peer. The server's connection handler receives a new instance per
 * accepted connection (see {@link ServerTransport#setConnectionHandler}); the handler registers a
 * {@link TransportListener} with {@link #setListener(TransportListener)} to receive inbound frames,
 * sends complete length-delimited frame {@link ByteBuf}s with {@link #send(ByteBuf)}, and tears the
 * connection down with {@link #close()}.
 *
 * <p>The connection also exposes the narrow transport metadata a server needs: the peer's {@link
 * #remoteAddress() remote address} and, for TLS connections, its {@link #peerCertificate() peer
 * certificate}. Listener callbacks may be invoked on a transport I/O thread and must not block.
 */
public interface ServerTransportConnection {

  /**
   * Sends one complete, length-delimited frame to this connected peer.
   *
   * <p><b>Buffer ownership.</b> The caller allocates {@code frame}; this method writes-and-flushes
   * it and releases it. The caller must <b>not</b> release {@code frame} after invoking {@code
   * send}.
   *
   * <p>The returned stage reflects only the outcome of the send (the channel write), not any
   * application-level acknowledgement.
   *
   * @param frame a complete length-delimited frame; ownership transfers to the transport.
   * @return a stage that completes when the frame has been written, or completes exceptionally if
   *     the send fails (for example because the connection is closed).
   */
  CompletionStage<Void> send(ByteBuf frame);

  /**
   * Registers the listener that receives inbound frames and connection-loss notifications for this
   * connection.
   *
   * <p>Should be set as soon as the connection is accepted so no inbound frame is missed. Setting a
   * new listener replaces any previously registered one. Callbacks may be invoked on a transport
   * I/O thread and must not block.
   *
   * @param listener the listener to receive transport callbacks.
   */
  void setListener(TransportListener listener);

  /** Closes this connection to the peer. */
  void close();

  /**
   * Returns the remote socket address of the connected peer.
   *
   * @return the peer's remote address.
   */
  SocketAddress remoteAddress();

  /**
   * Returns the peer's TLS certificate, when the connection is secured with TLS and the peer
   * presented one.
   *
   * <p>The result is always empty for a non-TLS or serial transport, which carries no peer
   * certificate.
   *
   * @return an {@link Optional} containing the peer certificate, or empty if the connection is not
   *     using TLS or the peer presented no certificate.
   */
  Optional<Certificate> peerCertificate();
}
