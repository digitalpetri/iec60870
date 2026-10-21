package com.digitalpetri.iec60870.cs104;

import com.digitalpetri.iec60870.Iec60870Exception;
import com.digitalpetri.iec60870.diagnostics.Direction;
import io.netty.buffer.ByteBuf;
import java.net.SocketAddress;
import java.time.Duration;
import java.util.Objects;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Forwards events to several listeners, catching and logging what each one throws.
 *
 * <p>The per-frame callbacks loop directly instead of going through a lambda, so a configured
 * listener costs no allocation per frame.
 */
final class GuardedApciDiagnostics implements ApciDiagnostics {

  private static final Logger LOGGER = LoggerFactory.getLogger(ApciDiagnostics.class);

  private final ApciDiagnostics[] listeners;

  GuardedApciDiagnostics(ApciDiagnostics[] listeners) {
    this.listeners = listeners.clone();
    for (ApciDiagnostics listener : this.listeners) {
      Objects.requireNonNull(listener, "listener");
    }
  }

  @Override
  public void onApduSent(ControlField control, int frameLength) {
    for (ApciDiagnostics listener : listeners) {
      try {
        listener.onApduSent(control, frameLength);
      } catch (RuntimeException e) {
        warn(listener, e);
      }
    }
  }

  @Override
  public void onApduReceived(ControlField control, int frameLength) {
    for (ApciDiagnostics listener : listeners) {
      try {
        listener.onApduReceived(control, frameLength);
      } catch (RuntimeException e) {
        warn(listener, e);
      }
    }
  }

  @Override
  public boolean traceFrames() {
    for (ApciDiagnostics listener : listeners) {
      if (traces(listener)) {
        return true;
      }
    }
    return false;
  }

  @Override
  public void onFrame(Direction direction, ByteBuf frame) {
    for (ApciDiagnostics listener : listeners) {
      if (!traces(listener)) {
        continue;
      }
      try {
        listener.onFrame(direction, lend(frame));
      } catch (RuntimeException e) {
        warn(listener, e);
      }
    }
  }

  @Override
  public void onDecodeError(ByteBuf frame, RuntimeException cause) {
    for (ApciDiagnostics listener : listeners) {
      try {
        listener.onDecodeError(lend(frame), cause);
      } catch (RuntimeException e) {
        warn(listener, e);
      }
    }
  }

  @Override
  public void onWindowFull(int queued) {
    forEach(l -> l.onWindowFull(queued));
  }

  @Override
  public void onTimerExpired(ApciTimer timer) {
    forEach(l -> l.onTimerExpired(timer));
  }

  @Override
  public void onFrameIgnored(UFunction function, String reason) {
    forEach(l -> l.onFrameIgnored(function, reason));
  }

  @Override
  public void onProtocolError(Iec60870Exception cause) {
    forEach(l -> l.onProtocolError(cause));
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

  /** Gives each of several listeners its own indexes, so one cannot shift another's reads. */
  private ByteBuf lend(ByteBuf frame) {
    return listeners.length == 1 ? frame : frame.duplicate();
  }

  private static boolean traces(ApciDiagnostics listener) {
    try {
      return listener.traceFrames();
    } catch (RuntimeException e) {
      warn(listener, e);
      return false;
    }
  }

  private void forEach(Consumer<ApciDiagnostics> event) {
    for (ApciDiagnostics listener : listeners) {
      try {
        event.accept(listener);
      } catch (RuntimeException e) {
        warn(listener, e);
      }
    }
  }

  private static void warn(ApciDiagnostics listener, RuntimeException e) {
    LOGGER.warn("diagnostics listener {} threw", listener, e);
  }
}
