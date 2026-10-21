package com.digitalpetri.iec60870.cs104;

import com.digitalpetri.iec60870.diagnostics.ConnectionDiagnostics;
import com.digitalpetri.iec60870.diagnostics.Direction;
import java.net.SocketAddress;
import java.time.Duration;
import org.jspecify.annotations.Nullable;

/** Presents a connection-level listener as an {@link ApciDiagnostics} without frame callbacks. */
final class ConnectionDiagnosticsAdapter implements ApciDiagnostics {

  private final ConnectionDiagnostics delegate;

  ConnectionDiagnosticsAdapter(ConnectionDiagnostics delegate) {
    this.delegate = delegate;
  }

  @Override
  public void onConnectAttempt(SocketAddress remote) {
    delegate.onConnectAttempt(remote);
  }

  @Override
  public void onConnected(SocketAddress local, SocketAddress remote) {
    delegate.onConnected(local, remote);
  }

  @Override
  public void onConnectFailed(SocketAddress remote, Throwable cause) {
    delegate.onConnectFailed(remote, cause);
  }

  @Override
  public void onConnectionAccepted(SocketAddress remote) {
    delegate.onConnectionAccepted(remote);
  }

  @Override
  public void onConnectionClosed(@Nullable Throwable cause) {
    delegate.onConnectionClosed(cause);
  }

  @Override
  public void onReconnectScheduled(int attempt, Duration delay, Throwable cause) {
    delegate.onReconnectScheduled(attempt, delay, cause);
  }

  @Override
  public void onInboundPaused(boolean paused) {
    delegate.onInboundPaused(paused);
  }

  @Override
  public void onEventDropped(Class<?> eventType) {
    delegate.onEventDropped(eventType);
  }

  @Override
  public void onAsduDropped(Direction direction, String reason) {
    delegate.onAsduDropped(direction, reason);
  }
}
