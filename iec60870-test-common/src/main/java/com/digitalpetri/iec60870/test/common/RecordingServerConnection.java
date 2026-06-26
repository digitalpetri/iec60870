package com.digitalpetri.iec60870.test.common;

import com.digitalpetri.iec60870.transport.ServerTransportConnection;
import com.digitalpetri.iec60870.transport.TransportListener;
import io.netty.buffer.ByteBuf;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.security.cert.Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.jspecify.annotations.Nullable;

/**
 * An in-memory {@link ServerTransportConnection} that captures every outbound frame for assertions
 * and exposes the registered {@link TransportListener} so a test can feed inbound frames and
 * connection-loss events back through it.
 *
 * <p>{@link #send(ByteBuf)} mirrors a real transport's write-and-release: it keeps a {@linkplain
 * ByteBuf#copy() copy} of the frame in {@link #sent()} and releases the handed-over buffer. {@link
 * #remoteAddress()} returns a fixed loopback address and {@link #peerCertificate()} is always
 * empty. The captured copies are unpooled and are not released by this fixture.
 */
public final class RecordingServerConnection implements ServerTransportConnection {

  private final List<ByteBuf> sent = new ArrayList<>();
  private @Nullable TransportListener listener;

  @Override
  public CompletionStage<Void> send(ByteBuf frame) {
    sent.add(frame.copy());
    frame.release();
    return CompletableFuture.completedFuture(null);
  }

  @Override
  public void setListener(TransportListener listener) {
    this.listener = listener;
  }

  @Override
  public void close() {}

  @Override
  public SocketAddress remoteAddress() {
    return new InetSocketAddress("127.0.0.1", 2404);
  }

  @Override
  public Optional<Certificate> peerCertificate() {
    return Optional.empty();
  }

  /**
   * Returns the frames handed to {@link #send(ByteBuf)}, each as an independent copy, in send
   * order.
   *
   * @return the captured outbound frames.
   */
  public List<ByteBuf> sent() {
    return sent;
  }

  /**
   * Returns the listener registered via {@link #setListener(TransportListener)}, or {@code null} if
   * none has been registered yet.
   *
   * @return the registered transport listener, or {@code null}.
   */
  public @Nullable TransportListener listener() {
    return listener;
  }
}
