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
 * <p>The facade drives only the transport's connection lifecycle ({@code connect}/{@code
 * disconnect}/{@code close}/{@code isConnected}); inbound frames and connection loss reach the
 * facade through the injected {@link com.digitalpetri.iec60870.session.Session}, so {@link
 * #send(ByteBuf)} and {@link #setListener} are inert here. This fake carries no wire-frame logic.
 */
public final class FakeClientTransport implements ClientTransport {

  private boolean connected;
  private int connectCount;
  private int disconnectCount;
  private int closeCount;
  private int closeConnectionCount;
  private @Nullable Throwable connectFailure;
  private boolean holdConnect;
  private @Nullable CompletableFuture<Void> heldConnect;

  @Override
  public CompletionStage<Void> connect() {
    connectCount++;
    Throwable failure = connectFailure;
    if (failure != null) {
      return CompletableFuture.failedFuture(failure);
    }
    connected = true;
    if (holdConnect) {
      holdConnect = false;
      var held = new CompletableFuture<Void>();
      heldConnect = held;
      return held;
    }
    return CompletableFuture.completedFuture(null);
  }

  @Override
  public CompletionStage<Void> disconnect() {
    connected = false;
    disconnectCount++;
    return CompletableFuture.completedFuture(null);
  }

  @Override
  public void closeConnection() {
    connected = false;
    closeConnectionCount++;
  }

  @Override
  public void close() {
    connected = false;
    closeCount++;
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
   * @param cause the failure to report from {@code connect()}, or {@code null} to let them succeed.
   */
  public void failConnect(@Nullable Throwable cause) {
    this.connectFailure = cause;
  }

  /**
   * Makes the next {@link #connect()} return a stage that completes only when the test completes
   * {@link #heldConnect()}, as a real transport does after it has reported the connection.
   */
  public void holdNextConnect() {
    holdConnect = true;
  }

  /**
   * Returns the stage of the held {@link #connect()} call.
   *
   * @return the pending connect stage.
   * @throws IllegalStateException if no connect was held.
   */
  public CompletableFuture<Void> heldConnect() {
    CompletableFuture<Void> held = heldConnect;
    if (held == null) {
      throw new IllegalStateException("no connect was held");
    }
    return held;
  }

  /**
   * Returns how many times {@link #connect()} has been invoked.
   *
   * @return the {@code connect()} invocation count.
   */
  public int connectCount() {
    return connectCount;
  }

  /**
   * Returns how many times {@link #close()} has been invoked.
   *
   * @return the {@code close()} invocation count.
   */
  public int closeCount() {
    return closeCount;
  }

  /**
   * Returns how many times {@link #disconnect()} has been invoked.
   *
   * @return the {@code disconnect()} invocation count.
   */
  public int disconnectCount() {
    return disconnectCount;
  }

  /**
   * Returns how many times {@link #closeConnection()} has been invoked.
   *
   * @return the {@code closeConnection()} invocation count.
   */
  public int closeConnectionCount() {
    return closeConnectionCount;
  }
}
