package com.digitalpetri.iec60870.session;

import com.digitalpetri.iec60870.Iec60870Exception;
import com.digitalpetri.iec60870.asdu.Asdu;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.jspecify.annotations.Nullable;

/**
 * The protocol-neutral link/session state machine that carries {@link Asdu}s between a transport
 * connection and the application layer.
 *
 * <p>A {@code Session} owns the per-connection flow control and data-transfer lifecycle for one
 * transport connection. It is shaped entirely in terms of {@link Asdu}: application data is sent
 * with {@link #sendAsdu(Asdu)}, delivered ASDUs and lifecycle transitions are reported through the
 * supplied {@link Events}, and the wire frame type of any concrete implementation never escapes its
 * protocol module. The IEC 60870-5-104 implementation ({@code ApciSession}) realizes this contract
 * over its APCI I/S/U frame machinery; a future IEC 60870-5-101 link layer would realize it over
 * FT1.2 framing.
 *
 * <p><b>Lifecycle.</b> Call {@link #onConnected()} once the transport is up to reset the session
 * for a fresh connection; send application data with {@link #sendAsdu(Asdu)}; and call {@link
 * #close()} when the transport goes away. {@link #close()} is idempotent.
 *
 * <p><b>Roles.</b> A session is either an initiator (CLIENT role) or a responder (SERVER role).
 * {@link #startDataTransfer()} and {@link #stopDataTransfer()} drive the data-transfer lifecycle
 * and are valid only on an initiator/CLIENT-role session; a responder/SERVER-role implementation
 * throws {@link IllegalStateException} from those methods, because the responder follows the peer's
 * activation rather than initiating it.
 *
 * <p>The {@link Events} callbacks may be invoked while internal locks are held; implementations
 * must not call back into the session and should not block.
 */
public interface Session {

  /**
   * Resets the session for a freshly established transport connection.
   *
   * <p>Sequence and lifecycle state return to their connection defaults; data transfer returns to
   * the stopped default.
   */
  void onConnected();

  /**
   * Starts user-data transfer (initiator/CLIENT role only).
   *
   * <p>Returns a stage that completes when data transfer has started, or completes exceptionally on
   * timeout, session close, or misuse.
   *
   * @return a stage that completes when data transfer has started.
   * @throws IllegalStateException if called on a responder/SERVER-role session.
   */
  CompletionStage<Void> startDataTransfer();

  /**
   * Stops user-data transfer (initiator/CLIENT role only).
   *
   * <p>Returns a stage that completes when data transfer has stopped, or completes exceptionally on
   * timeout, session close, or misuse.
   *
   * @return a stage that completes when data transfer has stopped.
   * @throws IllegalStateException if called on a responder/SERVER-role session.
   */
  CompletionStage<Void> stopDataTransfer();

  /**
   * Reports whether user-data transfer is currently started.
   *
   * @return {@code true} if data transfer is in effect.
   */
  boolean isDataTransferStarted();

  /**
   * Submits a discardable application ASDU, honoring the session's flow-control window.
   *
   * <p>If the ASDU cannot be transmitted immediately it is queued and sent later when the window
   * opens or data transfer starts. The configured overflow policy may discard it. Use {@link
   * #sendAsduAsync(Asdu)} when the caller needs a write completion or protection from overflow
   * eviction.
   *
   * @param asdu the application ASDU to send.
   */
  void sendAsdu(Asdu asdu);

  /**
   * Sends an ASDU without allowing outbound overflow policies to discard it.
   *
   * <p>The stage completes when the underlying transport finishes the first write, not when the
   * peer acknowledges or processes it. It remains pending while flow control queues the ASDU, and
   * fails on queue rejection, encoding/write failure, close, or connection reset before completion.
   * A full bounded queue evicts its oldest discardable ASDU, or rejects this submission if every
   * queued ASDU is reliable.
   *
   * <p>Completions may run under the session lock. Dependent work must be dispatched to an executor
   * before blocking or calling back into the session. Legacy implementations must override this
   * method to support tracked sends; the default fails without submitting the ASDU.
   *
   * @param asdu the application ASDU to send.
   * @return the transport-write completion.
   */
  default CompletionStage<Void> sendAsduAsync(Asdu asdu) {
    return CompletableFuture.failedFuture(
        new UnsupportedOperationException("session does not support tracked sends"));
  }

  /**
   * Sends ASDU octets exactly as given, without encoding them through the typed ASDU model.
   *
   * <p>This is the path for an ASDU the typed model cannot represent, such as a negative mirror of
   * a received ASDU whose type identification or cause of transmission is undefined. The octets
   * start at the type identification and must match the session's protocol profile; the session
   * does not validate them. Ordering, flow control, and completion follow {@link
   * #sendAsduAsync(Asdu)}, and the entry is protected from overflow eviction in the same way.
   *
   * <p>The default fails without sending. Sessions that cannot carry raw octets keep that default.
   *
   * @param asdu the complete ASDU octets; the session takes a copy.
   * @return the transport-write completion, or a stage failed with {@link
   *     UnsupportedOperationException} if this session does not support raw sends.
   */
  default CompletionStage<Void> sendRawAsduAsync(byte[] asdu) {
    return CompletableFuture.failedFuture(
        new UnsupportedOperationException("session does not support raw ASDU sends"));
  }

  /**
   * Awaits free capacity in the outbound send queue, off the session's hot path, for a blocking
   * overflow policy.
   *
   * @param timeoutMillis the maximum time to wait, in milliseconds.
   * @return {@code true} if capacity is available (or the queue is unbounded / session closed),
   *     {@code false} if the wait timed out with the queue still full.
   * @throws InterruptedException if the current thread is interrupted while waiting.
   */
  boolean awaitSendCapacity(long timeoutMillis) throws InterruptedException;

  /**
   * Returns the number of ASDUs currently waiting in the outbound send queue (not yet handed to the
   * transport).
   *
   * @return the pending send-queue depth.
   */
  int pendingSendCount();

  /**
   * Pauses or resumes inbound user data using the link layer's own flow control.
   *
   * <p>While paused, the session stops taking in new user data, so the peer's own flow control
   * throttles it. Data already received is still delivered and acknowledged as usual, so a few more
   * ASDUs may arrive after the call. A pause lasting longer than the peer's acknowledgement timeout
   * can end in a connection close. The pause applies to the current connection; {@link
   * #onConnected()} starts a new connection unpaused.
   *
   * <p>May be called from any thread, including from within an {@link Events} callback. The default
   * does nothing, which leaves inbound data unthrottled.
   *
   * @param paused {@code true} to pause inbound user data, {@code false} to resume it.
   */
  default void setInboundPaused(boolean paused) {}

  /**
   * Closes the session.
   *
   * <p>Cancels any timers, fails any pending data-transfer future, and marks the session closed.
   * This method is idempotent.
   */
  void close();

  /** Callbacks for application ASDUs and lifecycle transitions produced by the session. */
  interface Events {

    /**
     * Delivers an application ASDU received from the peer.
     *
     * <p>May be invoked while internal locks are held; implementations must not block or re-enter
     * the session.
     *
     * @param asdu the received ASDU.
     */
    void onAsdu(Asdu asdu);

    /**
     * Reports a received ASDU that the session accepted at the link layer but could not decode.
     *
     * <p>The frame carrying it was correctly framed and passed the link layer's sequence checks, so
     * the session acknowledged it like any other and stays open. {@link #onAsdu(Asdu)} is not
     * invoked for it. Causes include an undefined or private type identification, a type with no
     * registered codec, an undefined cause of transmission, and malformed information objects.
     *
     * <p>May be invoked while internal locks are held; implementations must not block or re-enter
     * the session. The default ignores the ASDU.
     *
     * @param asdu the raw ASDU octets, starting at the type identification; the array belongs to
     *     the receiver.
     * @param cause the decode failure, an {@link com.digitalpetri.iec60870.AsduDecodeException} or
     *     an {@link com.digitalpetri.iec60870.UnsupportedAsduTypeException}.
     */
    default void onUndecodableAsdu(byte[] asdu, Iec60870Exception cause) {}

    /**
     * Reports a change in the data-transfer state.
     *
     * <p>May be invoked while internal locks are held; implementations must not block or re-enter
     * the session.
     *
     * @param started {@code true} when data transfer has started, {@code false} when it has
     *     stopped.
     */
    void onDataTransferStateChanged(boolean started);

    /**
     * Reports that the session has closed itself because of a protocol error or timeout.
     *
     * <p>Invoked at most once. The transport should be torn down in response; the session has
     * already cancelled its timers.
     *
     * @param cause the reason for closure, or {@code null} for an orderly close.
     */
    void onClosed(@Nullable Throwable cause);

    /**
     * Reports that the underlying transport connection was lost (peer drop, send failure, or I/O
     * error) rather than the session self-closing on a protocol error or timeout.
     *
     * <p>Distinguished from {@link #onClosed(Throwable)} so a facade can react differently: a
     * self-initiated {@code onClosed} means the protocol layer gave up and the transport should be
     * torn down, whereas a transport-reported {@code onConnectionLost} should leave a
     * persistent/reconnecting transport free to re-establish the connection rather than being
     * explicitly disconnected. The default delegates to {@link #onClosed(Throwable)}, preserving
     * the behavior of consumers that do not draw the distinction.
     *
     * @param cause the reason for the loss, or {@code null} for an orderly close.
     */
    default void onConnectionLost(@Nullable Throwable cause) {
      onClosed(cause);
    }
  }
}
