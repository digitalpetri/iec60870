package com.digitalpetri.iec60870.transport;

import io.netty.buffer.ByteBuf;
import org.jspecify.annotations.Nullable;

/**
 * Receives inbound length-delimited frames and connection-loss notifications from a transport.
 *
 * <p>A listener is registered on a {@link ClientTransport} or {@link ServerTransportConnection} via
 * {@code setListener}. While a connection is established, every complete frame the transport reads
 * off the wire is delivered to {@link #onFrame(ByteBuf)} in receive order; when the connection is
 * lost or closed, {@link #onConnectionLost(Throwable)} is invoked exactly once.
 *
 * <p>Callbacks may be invoked on a transport I/O thread. Implementations must not block: any
 * blocking or long-running processing should be dispatched to a separate executor so the
 * transport's I/O thread is not stalled.
 */
public interface TransportListener {

  /**
   * Called when one complete, length-delimited frame is received from the peer.
   *
   * <p>Invoked once per framed unit, in receive order. May run on a transport I/O thread, so the
   * implementation must return promptly and must not block.
   *
   * <p><b>Buffer ownership.</b> The transport <b>owns</b> {@code frame}. The listener must decode
   * it synchronously within this call and must <b>not</b> retain a reference to it past the call;
   * it must not release the buffer either. This mirrors Netty's {@code
   * SimpleChannelInboundHandler}, which auto-releases the message once the callback returns.
   *
   * @param frame the received frame; owned by the transport and valid only for the duration of this
   *     call.
   */
  void onFrame(ByteBuf frame);

  /**
   * Called once when the underlying connection is lost or closed.
   *
   * <p>After this callback no further {@link #onFrame(ByteBuf)} calls occur for the connection. May
   * run on a transport I/O thread, so the implementation must return promptly and must not block.
   *
   * @param cause the failure that caused the connection to be lost, or {@code null} if the
   *     connection was closed normally.
   */
  void onConnectionLost(@Nullable Throwable cause);
}
