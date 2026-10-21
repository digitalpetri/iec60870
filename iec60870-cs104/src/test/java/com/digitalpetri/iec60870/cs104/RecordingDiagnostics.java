package com.digitalpetri.iec60870.cs104;

import com.digitalpetri.iec60870.Iec60870Exception;
import com.digitalpetri.iec60870.diagnostics.Direction;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Records every diagnostics callback as a line in a shared log, so tests can assert both what was
 * reported and its order relative to other recorded events.
 */
final class RecordingDiagnostics implements ApciDiagnostics {

  final List<String> log;
  final List<Iec60870Exception> protocolErrors = Collections.synchronizedList(new ArrayList<>());
  final List<byte[]> decodeErrorFrames = Collections.synchronizedList(new ArrayList<>());
  final List<RuntimeException> decodeErrorCauses = Collections.synchronizedList(new ArrayList<>());
  final List<byte[]> tracedFrames = Collections.synchronizedList(new ArrayList<>());
  final List<Boolean> tracedReadOnly = Collections.synchronizedList(new ArrayList<>());

  volatile boolean trace;

  RecordingDiagnostics() {
    this(Collections.synchronizedList(new ArrayList<>()));
  }

  RecordingDiagnostics(List<String> log) {
    this.log = log;
  }

  @Override
  public void onApduSent(ControlField control, int frameLength) {
    log.add("sent " + FrameTrace.describe(control) + " " + frameLength);
  }

  @Override
  public void onApduReceived(ControlField control, int frameLength) {
    log.add("received " + FrameTrace.describe(control) + " " + frameLength);
  }

  @Override
  public void onWindowFull(int queued) {
    log.add("windowFull " + queued);
  }

  @Override
  public void onTimerExpired(ApciTimer timer) {
    log.add("timer " + timer);
  }

  @Override
  public void onFrameIgnored(UFunction function, String reason) {
    log.add("ignored " + function);
  }

  @Override
  public void onProtocolError(Iec60870Exception cause) {
    protocolErrors.add(cause);
    log.add("protocolError " + cause.getClass().getSimpleName());
  }

  @Override
  public void onDecodeError(ByteBuf frame, RuntimeException cause) {
    decodeErrorFrames.add(ByteBufUtil.getBytes(frame));
    decodeErrorCauses.add(cause);
    log.add("decodeError " + cause.getClass().getSimpleName());
  }

  @Override
  public boolean traceFrames() {
    return trace;
  }

  @Override
  public void onFrame(Direction direction, ByteBuf frame) {
    tracedReadOnly.add(frame.isReadOnly());
    // Read through the indexes, as a listener would, to prove they are independent.
    byte[] octets = new byte[frame.readableBytes()];
    frame.readBytes(octets);
    tracedFrames.add(octets);
    log.add("frame " + direction + " " + octets.length);
  }

  @Override
  public void onAsduDropped(Direction direction, String reason) {
    log.add("asduDropped " + direction);
  }

  @Override
  public void onConnectionClosed(@Nullable Throwable cause) {
    log.add("connectionClosed");
  }

  @Override
  public void onConnected(SocketAddress local, SocketAddress remote) {
    log.add("connected");
  }

  /** Returns the log lines that start with {@code prefix}. */
  List<String> lines(String prefix) {
    synchronized (log) {
      return log.stream().filter(line -> line.startsWith(prefix)).toList();
    }
  }
}
