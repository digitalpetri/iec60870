package com.digitalpetri.iec60870.diagnostics;

import java.net.SocketAddress;
import java.time.Duration;
import java.util.Objects;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Forwards events to several listeners, catching and logging what each one throws. */
final class GuardedConnectionDiagnostics implements ConnectionDiagnostics {

  private static final Logger LOGGER = LoggerFactory.getLogger(ConnectionDiagnostics.class);

  private final ConnectionDiagnostics[] listeners;

  GuardedConnectionDiagnostics(ConnectionDiagnostics[] listeners) {
    this.listeners = listeners.clone();
    for (ConnectionDiagnostics listener : this.listeners) {
      Objects.requireNonNull(listener, "listener");
    }
  }

  @Override
  public void onConnectAttempt(SocketAddress remote) {
    forEach(l -> l.onConnectAttempt(remote));
  }

  @Override
  public void onConnected(SocketAddress local, SocketAddress remote) {
    forEach(l -> l.onConnected(local, remote));
  }

  @Override
  public void onConnectFailed(SocketAddress remote, Throwable cause) {
    forEach(l -> l.onConnectFailed(remote, cause));
  }

  @Override
  public void onConnectionAccepted(SocketAddress remote) {
    forEach(l -> l.onConnectionAccepted(remote));
  }

  @Override
  public void onConnectionClosed(@Nullable Throwable cause) {
    forEach(l -> l.onConnectionClosed(cause));
  }

  @Override
  public void onReconnectScheduled(int attempt, Duration delay, Throwable cause) {
    forEach(l -> l.onReconnectScheduled(attempt, delay, cause));
  }

  @Override
  public void onInboundPaused(boolean paused) {
    forEach(l -> l.onInboundPaused(paused));
  }

  @Override
  public void onEventDropped(Class<?> eventType) {
    forEach(l -> l.onEventDropped(eventType));
  }

  @Override
  public void onAsduDropped(Direction direction, String reason) {
    forEach(l -> l.onAsduDropped(direction, reason));
  }

  private void forEach(Consumer<ConnectionDiagnostics> event) {
    for (ConnectionDiagnostics listener : listeners) {
      try {
        event.accept(listener);
      } catch (RuntimeException e) {
        LOGGER.warn("diagnostics listener {} threw", listener, e);
      }
    }
  }
}
