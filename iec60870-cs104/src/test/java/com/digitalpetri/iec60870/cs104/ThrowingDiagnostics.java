package com.digitalpetri.iec60870.cs104;

import com.digitalpetri.iec60870.Iec60870Exception;
import com.digitalpetri.iec60870.diagnostics.Direction;
import io.netty.buffer.ByteBuf;
import java.net.SocketAddress;
import org.jspecify.annotations.Nullable;

/** An {@link ApciDiagnostics} that throws from every callback. */
final class ThrowingDiagnostics implements ApciDiagnostics {
  @Override
  public void onApduSent(ControlField control, int frameLength) {
    throw new IllegalStateException("onApduSent");
  }

  @Override
  public void onApduReceived(ControlField control, int frameLength) {
    throw new IllegalStateException("onApduReceived");
  }

  @Override
  public void onWindowFull(int queued) {
    throw new IllegalStateException("onWindowFull");
  }

  @Override
  public void onTimerExpired(ApciTimer timer) {
    throw new IllegalStateException("onTimerExpired");
  }

  @Override
  public void onFrameIgnored(UFunction function, String reason) {
    throw new IllegalStateException("onFrameIgnored");
  }

  @Override
  public void onProtocolError(Iec60870Exception cause) {
    throw new IllegalStateException("onProtocolError");
  }

  @Override
  public void onDecodeError(ByteBuf frame, RuntimeException cause) {
    throw new IllegalStateException("onDecodeError");
  }

  @Override
  public boolean traceFrames() {
    throw new IllegalStateException("traceFrames");
  }

  @Override
  public void onAsduDropped(Direction direction, String reason) {
    throw new IllegalStateException("onAsduDropped");
  }

  @Override
  public void onConnectAttempt(SocketAddress remote) {
    throw new IllegalStateException("onConnectAttempt");
  }

  @Override
  public void onConnected(SocketAddress local, SocketAddress remote) {
    throw new IllegalStateException("onConnected");
  }

  @Override
  public void onConnectFailed(SocketAddress remote, Throwable cause) {
    throw new IllegalStateException("onConnectFailed");
  }

  @Override
  public void onConnectionClosed(@Nullable Throwable cause) {
    throw new IllegalStateException("onConnectionClosed");
  }

  @Override
  public void onFrame(Direction direction, ByteBuf frame) {
    throw new IllegalStateException("onFrame");
  }
}
