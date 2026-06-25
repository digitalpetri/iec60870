package com.digitalpetri.iec60870.fakes;

import com.digitalpetri.iec60870.transport.ClientTransport;
import com.digitalpetri.iec60870.transport.TransportListener;
import io.netty.buffer.ByteBuf;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.jspecify.annotations.Nullable;

/**
 * A neutral in-memory {@link ClientTransport} for client facade unit tests.
 *
 * <p>The facade now drives only the transport's connection lifecycle ({@code connect}/{@code
 * disconnect}/{@code isConnected}); inbound frames and connection loss reach the facade through the
 * injected {@link com.digitalpetri.iec60870.session.Session}, so {@link #send(ByteBuf)} and {@link
 * #setListener} are inert here. This fake carries no wire-frame logic.
 */
public final class FakeClientTransport implements ClientTransport {

  private boolean connected;
  private @Nullable Throwable connectFailure;

  @Override
  public CompletionStage<Void> connect() {
    Throwable failure = connectFailure;
    if (failure != null) {
      return CompletableFuture.failedFuture(failure);
    }
    connected = true;
    return CompletableFuture.completedFuture(null);
  }

  @Override
  public CompletionStage<Void> disconnect() {
    connected = false;
    return CompletableFuture.completedFuture(null);
  }

  @Override
  public boolean isConnected() {
    return connected;
  }

  @Override
  public CompletionStage<Void> send(ByteBuf frame) {
    frame.release();
    return CompletableFuture.completedFuture(null);
  }

  @Override
  public void setListener(TransportListener listener) {
    // The facade no longer registers a transport listener; framing and connection-loss routing live
    // in the session assembly. Inert by design.
  }

  /**
   * Makes the next and subsequent {@link #connect()} calls fail with the given cause.
   *
   * @param cause the failure to report from {@code connect()}.
   */
  public void failConnect(Throwable cause) {
    this.connectFailure = cause;
  }
}
