package com.digitalpetri.iec60870.session;

import com.digitalpetri.iec60870.asdu.Asdu;
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
   * Sends an application ASDU, honoring the session's flow-control window.
   *
   * <p>If the ASDU cannot be transmitted immediately it is queued and sent later when the window
   * opens or data transfer starts. Queued ASDUs are sent in submission order.
   *
   * @param asdu the application ASDU to send.
   */
  void sendAsdu(Asdu asdu);

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
  }
}
