package com.digitalpetri.iec60870.cs104;

import com.digitalpetri.iec60870.Iec60870Exception;
import com.digitalpetri.iec60870.ProtocolStateException;
import com.digitalpetri.iec60870.ProtocolTimeoutException;
import com.digitalpetri.iec60870.SequenceNumberException;
import com.digitalpetri.iec60870.diagnostics.ConnectionDiagnostics;
import com.digitalpetri.iec60870.diagnostics.Direction;
import io.netty.buffer.ByteBuf;
import java.util.Objects;

/**
 * Receives frame-level and APCI diagnostic events from an IEC 60870-5-104 connection, in addition
 * to the connection-level events of {@link ConnectionDiagnostics}.
 *
 * <p>{@link Cs104Binding} reports every APDU it frames or deframes, decode failures, and the raw
 * frame trace. {@link ApciSession} reports a full {@code k} window, timer expiries, ignored
 * U-frames, and the protocol errors that make it close itself. Every method has an empty default,
 * and nothing is reported unless a listener is configured; the default is {@link #NONE}. {@link
 * ApciCounters} is a ready-made implementation, and {@link FrameTrace#logger(org.slf4j.Logger)}
 * writes a frame trace to a logger.
 *
 * <p><b>Threading.</b> Callbacks run on the thread that drives the event: a Netty I/O thread for
 * inbound frames, the scheduler thread for timers, or the thread that caused an outbound write.
 * {@link #onApduSent(ControlField, int)}, {@link #onWindowFull(int)}, {@link
 * #onTimerExpired(ApciTimer)}, {@link #onFrameIgnored(UFunction, String)}, and {@link
 * #onProtocolError(Iec60870Exception)} run while the session lock is held. For one connection,
 * frame callbacks arrive in wire order. A listener must return quickly, must not block, and must
 * not call back into the client, server, or session. The library catches and logs an exception
 * thrown by a listener.
 *
 * <p><b>Buffers.</b> {@link #onDecodeError(ByteBuf, RuntimeException)} and {@link
 * #onFrame(Direction, ByteBuf)} lend a read-only view of the frame for the duration of the call.
 * Its indexes are independent of the frame being decoded or written. Read it synchronously, copy
 * what you need, and never retain or release it.
 *
 * <pre>{@code
 * ApciCounters counters = new ApciCounters();
 *
 * Iec60870Client client = TcpIec104Client.builder()
 *     .host("10.0.0.5")
 *     .diagnostics(ApciDiagnostics.of(counters, FrameTrace.logger(traceLogger)))
 *     .build();
 *
 * // Later, from a health check on any thread.
 * ApciCounters.Snapshot snapshot = counters.snapshot();
 * }</pre>
 */
public interface ApciDiagnostics extends ConnectionDiagnostics {

  /** A listener that ignores every event. */
  ApciDiagnostics NONE = new ApciDiagnostics() {};

  /**
   * Reports an APDU that was framed and is about to be handed to the transport.
   *
   * @param control the APDU's control field.
   * @param frameLength the frame length in octets, including the start and length octets.
   */
  default void onApduSent(ControlField control, int frameLength) {}

  /**
   * Reports a received frame whose control field decoded, before its ASDU is decoded and before the
   * session processes it.
   *
   * <p>Every such frame is reported, including an I-frame whose ASDU then fails to decode, which is
   * followed by {@link #onDecodeError(ByteBuf, RuntimeException)}.
   *
   * @param control the APDU's control field.
   * @param frameLength the frame length in octets, including the start and length octets.
   */
  default void onApduReceived(ControlField control, int frameLength) {}

  /**
   * Reports that {@code k} sent I-frames are unacknowledged while ASDUs wait in the send queue.
   *
   * <p>Reported once per stall: again only after an acknowledgement has opened the window.
   *
   * @param queued the number of ASDUs waiting in the send queue.
   */
  default void onWindowFull(int queued) {}

  /**
   * Reports that an APCI timer elapsed.
   *
   * <p>A {@link ApciTimer#T1} expiry is followed by {@link #onProtocolError(Iec60870Exception)}
   * with a {@link ProtocolTimeoutException}, and the session closes.
   *
   * @param timer the timer that elapsed.
   */
  default void onTimerExpired(ApciTimer timer) {}

  /**
   * Reports a received U-frame that the session ignored, for example an unsolicited TESTFR con or a
   * STARTDT act received by a client.
   *
   * @param function the ignored U-frame function.
   * @param reason why the frame was ignored.
   */
  default void onFrameIgnored(UFunction function, String reason) {}

  /**
   * Reports a protocol error that makes the session close itself.
   *
   * <p>The cause is a {@link ProtocolTimeoutException} after {@code t1}, a {@link
   * SequenceNumberException} for an unexpected N(S) or an N(R) outside the outstanding range, or a
   * {@link ProtocolStateException} for an I-frame received while data transfer is stopped. Reported
   * before the session reports its close to the client or server.
   *
   * @param cause the error.
   */
  default void onProtocolError(Iec60870Exception cause) {}

  /**
   * Reports a received frame that failed to decode: a malformed frame or control field, or an
   * I-frame whose ASDU cannot be decoded.
   *
   * <p>Runs with the connection's logging context in the MDC, so a listener that logs here tags the
   * line with the connection.
   *
   * @param frame a read-only view of the whole frame, lent for the duration of the call.
   * @param cause the decode failure.
   */
  default void onDecodeError(ByteBuf frame, RuntimeException cause) {}

  /**
   * Reports whether {@link #onFrame(Direction, ByteBuf)} should receive the next frame.
   *
   * <p>Checked once per frame, so the trace can follow a runtime change such as a logger level.
   *
   * @return {@code true} to receive the raw frame trace.
   */
  default boolean traceFrames() {
    return false;
  }

  /**
   * Receives the octets of every frame sent or received while {@link #traceFrames()} returns {@code
   * true}.
   *
   * <p>An outbound frame is reported before it is handed to the transport, an inbound frame before
   * it is decoded. Runs with the connection's logging context in the MDC.
   *
   * @param direction whether the frame was sent or received.
   * @param frame a read-only view of the whole frame, lent for the duration of the call.
   */
  default void onFrame(Direction direction, ByteBuf frame) {}

  /**
   * Returns a listener that forwards every event to each of {@code listeners} in order.
   *
   * <p>An exception thrown by one listener is caught and logged, and the remaining listeners still
   * receive the event. A listener receives {@link #onFrame(Direction, ByteBuf)} only while its own
   * {@link #traceFrames()} returns {@code true}. Passing a single listener wraps it with that
   * protection; passing {@link #NONE} alone returns {@code NONE}.
   *
   * @param listeners the listeners to forward to.
   * @return the combined listener.
   */
  static ApciDiagnostics of(ApciDiagnostics... listeners) {
    Objects.requireNonNull(listeners, "listeners");
    if (listeners.length == 1) {
      ApciDiagnostics listener = Objects.requireNonNull(listeners[0], "listener");
      if (listener == NONE || listener instanceof GuardedApciDiagnostics) {
        return listener;
      }
    }
    return new GuardedApciDiagnostics(listeners);
  }

  /**
   * Adapts a connection-level listener, returning it unchanged if it already is an {@code
   * ApciDiagnostics}.
   *
   * @param listener the listener.
   * @return an {@code ApciDiagnostics} that forwards the connection-level events to {@code
   *     listener}.
   */
  static ApciDiagnostics from(ConnectionDiagnostics listener) {
    Objects.requireNonNull(listener, "listener");
    if (listener instanceof ApciDiagnostics apci) {
      return apci;
    }
    if (listener == ConnectionDiagnostics.NONE) {
      return NONE;
    }
    return new ConnectionDiagnosticsAdapter(listener);
  }
}
