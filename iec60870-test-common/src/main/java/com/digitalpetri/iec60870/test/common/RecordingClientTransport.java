package com.digitalpetri.iec60870.test.common;

import com.digitalpetri.iec60870.transport.ClientTransport;
import com.digitalpetri.iec60870.transport.TransportListener;
import io.netty.buffer.ByteBuf;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.jspecify.annotations.Nullable;

/**
 * An in-memory {@link ClientTransport} that captures every outbound frame for assertions and
 * exposes the registered {@link TransportListener} so a test can feed inbound frames and
 * connection-loss events back through it.
 *
 * <p>It always reports itself connected. {@link #send(ByteBuf)} mirrors a real transport's
 * write-and-release: it keeps a {@linkplain ByteBuf#copy() copy} of the frame in {@link #sent()}
 * and releases the handed-over buffer. {@link #closeConnection()} is counted so a test can assert
 * that a binding dropped only the current connection (for example after a malformed inbound frame).
 * The captured copies are unpooled and are not released by this fixture.
 */
public final class RecordingClientTransport implements ClientTransport {

  private final List<ByteBuf> sent = new ArrayList<>();
  private @Nullable TransportListener listener;
  private int closeConnectionCount;

  @Override
  public CompletionStage<Void> connect() {
    return CompletableFuture.completedFuture(null);
  }

  @Override
  public CompletionStage<Void> disconnect() {
    return CompletableFuture.completedFuture(null);
  }

  @Override
  public void closeConnection() {
    closeConnectionCount++;
  }

  @Override
  public boolean isConnected() {
    return true;
  }

  @Override
  public CompletionStage<Void> send(ByteBuf frame) {
    // The binding transfers ownership to the transport; keep a copy for assertions and release the
    // handed-over buffer, mirroring a real transport's write-and-release.
    sent.add(frame.copy());
    frame.release();
    return CompletableFuture.completedFuture(null);
  }

  @Override
  public void setListener(TransportListener listener) {
    this.listener = listener;
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

  /**
   * Returns how many times {@link #closeConnection()} has been invoked.
   *
   * @return the current-connection close count.
   */
  public int closeConnectionCount() {
    return closeConnectionCount;
  }
}
