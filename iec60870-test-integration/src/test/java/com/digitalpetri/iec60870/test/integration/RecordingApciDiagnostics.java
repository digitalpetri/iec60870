package com.digitalpetri.iec60870.test.integration;

import com.digitalpetri.iec60870.Iec60870Exception;
import com.digitalpetri.iec60870.cs104.ApciDiagnostics;
import com.digitalpetri.iec60870.cs104.ApciTimer;
import com.digitalpetri.iec60870.cs104.ControlField;
import com.digitalpetri.iec60870.cs104.FrameTrace;
import com.digitalpetri.iec60870.cs104.UFunction;
import com.digitalpetri.iec60870.diagnostics.Direction;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import java.net.SocketAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** Records diagnostics callbacks as lines in a log shared with other recorders of a test. */
final class RecordingApciDiagnostics implements ApciDiagnostics {

  final List<String> log;
  final List<Throwable> connectFailures = Collections.synchronizedList(new ArrayList<>());
  final List<SocketAddress[]> connected = Collections.synchronizedList(new ArrayList<>());
  final List<byte[]> traced = Collections.synchronizedList(new ArrayList<>());
  final List<Boolean> tracedReadOnly = Collections.synchronizedList(new ArrayList<>());

  volatile boolean trace;

  RecordingApciDiagnostics() {
    this(Collections.synchronizedList(new ArrayList<>()));
  }

  RecordingApciDiagnostics(List<String> log) {
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
  public void onTimerExpired(ApciTimer timer) {
    log.add("timer " + timer);
  }

  @Override
  public void onFrameIgnored(UFunction function, String reason) {
    log.add("ignored " + function);
  }

  @Override
  public void onProtocolError(Iec60870Exception cause) {
    log.add("protocolError " + cause.getClass().getSimpleName());
  }

  @Override
  public boolean traceFrames() {
    return trace;
  }

  @Override
  public void onFrame(Direction direction, ByteBuf frame) {
    tracedReadOnly.add(frame.isReadOnly());
    traced.add(ByteBufUtil.getBytes(frame));
  }

  @Override
  public void onConnectAttempt(SocketAddress remote) {
    log.add("connectAttempt");
  }

  @Override
  public void onConnected(SocketAddress local, SocketAddress remote) {
    connected.add(new SocketAddress[] {local, remote});
    log.add("connected");
  }

  @Override
  public void onConnectFailed(SocketAddress remote, Throwable cause) {
    connectFailures.add(cause);
    log.add("connectFailed");
  }

  @Override
  public void onConnectionAccepted(SocketAddress remote) {
    log.add("accepted");
  }

  @Override
  public void onConnectionClosed(@Nullable Throwable cause) {
    log.add("connectionClosed");
  }

  @Override
  public void onReconnectScheduled(int attempt, Duration delay, Throwable cause) {
    log.add("reconnectScheduled " + attempt);
  }

  /** Returns the lines that start with {@code prefix}. */
  List<String> lines(String prefix) {
    synchronized (log) {
      return log.stream().filter(line -> line.startsWith(prefix)).toList();
    }
  }

  /** Returns the number of lines that start with {@code prefix}. */
  long count(String prefix) {
    return lines(prefix).size();
  }
}
