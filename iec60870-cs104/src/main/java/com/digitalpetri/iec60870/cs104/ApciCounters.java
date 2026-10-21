package com.digitalpetri.iec60870.cs104;

import com.digitalpetri.iec60870.Iec60870Exception;
import com.digitalpetri.iec60870.ProtocolStateException;
import com.digitalpetri.iec60870.SequenceNumberException;
import com.digitalpetri.iec60870.diagnostics.Direction;
import io.netty.buffer.ByteBuf;
import java.net.SocketAddress;
import java.time.Duration;
import java.util.concurrent.atomic.LongAdder;
import org.jspecify.annotations.Nullable;

/**
 * An {@link ApciDiagnostics} that counts frames, octets, timer expiries, errors, and connection
 * lifecycle events.
 *
 * <p>Every counter only increases. One instance may be shared by any number of connections, for
 * example all connections of a server, to keep totals, or created per connection. Updates and
 * {@link #snapshot()} are thread-safe and lock-free; each update costs one {@link LongAdder}
 * increment.
 *
 * <pre>{@code
 * ApciCounters counters = new ApciCounters();
 * Iec60870Client client = TcpIec104Client.builder().host("10.0.0.5").diagnostics(counters).build();
 *
 * ApciCounters.Snapshot snapshot = counters.snapshot();
 * long t1Expiries = snapshot.t1Expired();
 * }</pre>
 */
public final class ApciCounters implements ApciDiagnostics {

  private final LongAdder iFramesSent = new LongAdder();
  private final LongAdder iFramesReceived = new LongAdder();
  private final LongAdder sFramesSent = new LongAdder();
  private final LongAdder sFramesReceived = new LongAdder();
  private final LongAdder uFramesSent = new LongAdder();
  private final LongAdder uFramesReceived = new LongAdder();
  private final LongAdder bytesSent = new LongAdder();
  private final LongAdder bytesReceived = new LongAdder();
  private final LongAdder testFramesSent = new LongAdder();
  private final LongAdder testFramesReceived = new LongAdder();
  private final LongAdder t1Expired = new LongAdder();
  private final LongAdder t2Expired = new LongAdder();
  private final LongAdder t3Expired = new LongAdder();
  private final LongAdder windowFull = new LongAdder();
  private final LongAdder framesIgnored = new LongAdder();
  private final LongAdder sequenceErrors = new LongAdder();
  private final LongAdder stateErrors = new LongAdder();
  private final LongAdder decodeErrors = new LongAdder();
  private final LongAdder connectAttempts = new LongAdder();
  private final LongAdder connectFailures = new LongAdder();
  private final LongAdder connectionsOpened = new LongAdder();
  private final LongAdder connectionsClosed = new LongAdder();
  private final LongAdder reconnectsScheduled = new LongAdder();
  private final LongAdder inboundPauses = new LongAdder();
  private final LongAdder eventsDropped = new LongAdder();
  private final LongAdder inboundAsdusDropped = new LongAdder();
  private final LongAdder outboundAsdusDropped = new LongAdder();

  /** Creates a counter set with every counter at zero. */
  public ApciCounters() {}

  /**
   * Returns the current value of every counter.
   *
   * <p>Each counter is read once and includes every update that completed before this call started.
   * The counters are read one after another, so updates that run during the call may be included in
   * some fields and not in others.
   *
   * @return the snapshot.
   */
  public Snapshot snapshot() {
    return new Snapshot(
        iFramesSent.sum(),
        iFramesReceived.sum(),
        sFramesSent.sum(),
        sFramesReceived.sum(),
        uFramesSent.sum(),
        uFramesReceived.sum(),
        bytesSent.sum(),
        bytesReceived.sum(),
        testFramesSent.sum(),
        testFramesReceived.sum(),
        t1Expired.sum(),
        t2Expired.sum(),
        t3Expired.sum(),
        windowFull.sum(),
        framesIgnored.sum(),
        sequenceErrors.sum(),
        stateErrors.sum(),
        decodeErrors.sum(),
        connectAttempts.sum(),
        connectFailures.sum(),
        connectionsOpened.sum(),
        connectionsClosed.sum(),
        reconnectsScheduled.sum(),
        inboundPauses.sum(),
        eventsDropped.sum(),
        inboundAsdusDropped.sum(),
        outboundAsdusDropped.sum());
  }

  @Override
  public void onApduSent(ControlField control, int frameLength) {
    bytesSent.add(frameLength);
    if (control instanceof ControlField.TypeI) {
      iFramesSent.increment();
    } else if (control instanceof ControlField.TypeS) {
      sFramesSent.increment();
    } else if (control instanceof ControlField.TypeU u) {
      uFramesSent.increment();
      if (isTestFrame(u.function())) {
        testFramesSent.increment();
      }
    }
  }

  @Override
  public void onApduReceived(ControlField control, int frameLength) {
    bytesReceived.add(frameLength);
    if (control instanceof ControlField.TypeI) {
      iFramesReceived.increment();
    } else if (control instanceof ControlField.TypeS) {
      sFramesReceived.increment();
    } else if (control instanceof ControlField.TypeU u) {
      uFramesReceived.increment();
      if (isTestFrame(u.function())) {
        testFramesReceived.increment();
      }
    }
  }

  @Override
  public void onWindowFull(int queued) {
    windowFull.increment();
  }

  @Override
  public void onTimerExpired(ApciTimer timer) {
    switch (timer) {
      case T1 -> t1Expired.increment();
      case T2 -> t2Expired.increment();
      case T3 -> t3Expired.increment();
    }
  }

  @Override
  public void onFrameIgnored(UFunction function, String reason) {
    framesIgnored.increment();
  }

  @Override
  public void onProtocolError(Iec60870Exception cause) {
    // t1 timeouts are already counted by onTimerExpired.
    if (cause instanceof SequenceNumberException) {
      sequenceErrors.increment();
    } else if (cause instanceof ProtocolStateException) {
      stateErrors.increment();
    }
  }

  @Override
  public void onDecodeError(ByteBuf frame, RuntimeException cause) {
    decodeErrors.increment();
  }

  @Override
  public void onConnectAttempt(SocketAddress remote) {
    connectAttempts.increment();
  }

  @Override
  public void onConnected(SocketAddress local, SocketAddress remote) {
    connectionsOpened.increment();
  }

  @Override
  public void onConnectFailed(SocketAddress remote, Throwable cause) {
    connectFailures.increment();
  }

  @Override
  public void onConnectionAccepted(SocketAddress remote) {
    connectionsOpened.increment();
  }

  @Override
  public void onConnectionClosed(@Nullable Throwable cause) {
    connectionsClosed.increment();
  }

  @Override
  public void onReconnectScheduled(int attempt, Duration delay, Throwable cause) {
    reconnectsScheduled.increment();
  }

  @Override
  public void onInboundPaused(boolean paused) {
    if (paused) {
      inboundPauses.increment();
    }
  }

  @Override
  public void onEventDropped(Class<?> eventType) {
    eventsDropped.increment();
  }

  @Override
  public void onAsduDropped(Direction direction, String reason) {
    if (direction == Direction.IN) {
      inboundAsdusDropped.increment();
    } else {
      outboundAsdusDropped.increment();
    }
  }

  private static boolean isTestFrame(UFunction function) {
    return function == UFunction.TESTFR_ACT || function == UFunction.TESTFR_CON;
  }

  /**
   * The counter values at one point in time.
   *
   * @param iFramesSent I-format APDUs sent.
   * @param iFramesReceived I-format APDUs received, including those whose ASDU did not decode.
   * @param sFramesSent S-format APDUs sent.
   * @param sFramesReceived S-format APDUs received.
   * @param uFramesSent U-format APDUs sent.
   * @param uFramesReceived U-format APDUs received.
   * @param bytesSent octets of every APDU sent, including the start and length octets.
   * @param bytesReceived octets of every APDU received whose control field decoded.
   * @param testFramesSent TESTFR act and TESTFR con U-frames sent.
   * @param testFramesReceived TESTFR act and TESTFR con U-frames received.
   * @param t1Expired {@code t1} expiries; each one closed a connection.
   * @param t2Expired {@code t2} expiries; each one sent an S-frame.
   * @param t3Expired {@code t3} expiries; each one sent a TESTFR act unless one was outstanding.
   * @param windowFull stalls with {@code k} unacknowledged I-frames and ASDUs queued.
   * @param framesIgnored received U-frames the session ignored.
   * @param sequenceErrors connections closed for an unexpected N(S) or an invalid N(R).
   * @param stateErrors connections closed for an I-frame received while data transfer was stopped.
   * @param decodeErrors received frames or ASDUs that failed to decode.
   * @param connectAttempts client connection attempts, including automatic reconnects.
   * @param connectFailures failed client connection attempts and connections a server rejected.
   * @param connectionsOpened client connections established and server connections accepted.
   * @param connectionsClosed established connections that closed.
   * @param reconnectsScheduled automatic client connection attempts scheduled.
   * @param inboundPauses times a client paused inbound data for a slow event subscriber.
   * @param eventsDropped events discarded instead of delivered to a subscriber.
   * @param inboundAsdusDropped received ASDUs discarded without being processed.
   * @param outboundAsdusDropped queued ASDUs discarded by an overflow policy.
   */
  public record Snapshot(
      long iFramesSent,
      long iFramesReceived,
      long sFramesSent,
      long sFramesReceived,
      long uFramesSent,
      long uFramesReceived,
      long bytesSent,
      long bytesReceived,
      long testFramesSent,
      long testFramesReceived,
      long t1Expired,
      long t2Expired,
      long t3Expired,
      long windowFull,
      long framesIgnored,
      long sequenceErrors,
      long stateErrors,
      long decodeErrors,
      long connectAttempts,
      long connectFailures,
      long connectionsOpened,
      long connectionsClosed,
      long reconnectsScheduled,
      long inboundPauses,
      long eventsDropped,
      long inboundAsdusDropped,
      long outboundAsdusDropped) {}
}
