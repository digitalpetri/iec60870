package com.digitalpetri.iec60870.cs101;

import com.digitalpetri.iec60870.OutboundQueuePolicy;
import com.digitalpetri.iec60870.asdu.Asdu;
import com.digitalpetri.iec60870.session.Session;
import java.util.Objects;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ScheduledExecutorService;

/**
 * The FT1.2 link layer for IEC 60870-5-101, the serial peer of the 104 {@code ApciSession}.
 *
 * <p>{@code Ft12LinkLayer} is a thin dispatcher: it selects an {@link Ft12Engine} by {@code (role,
 * mode)} at construction and forwards every {@link Session} call plus {@link #onFrame(Ft12Frame)}
 * to that engine. The link transmission procedure ({@link LinkSettings#mode() balanced or
 * unbalanced}) and the {@link Role role} therefore determine which state machine runs behind this
 * façade:
 *
 * <ul>
 *   <li>{@link LinkMode#BALANCED} — the symmetric point-to-point {@code BalancedEngine}, regardless
 *       of role;
 *   <li>{@link LinkMode#UNBALANCED} with {@link Role#CLIENT} — the master/primary polling engine;
 *   <li>{@link LinkMode#UNBALANCED} with {@link Role#SERVER} — the secondary/outstation engine.
 * </ul>
 *
 * <p>The engine is transport-agnostic — outbound frames are handed to the supplied {@link Output},
 * inbound frames are fed in via {@link #onFrame(Ft12Frame)}, and delivered application ASDUs plus
 * lifecycle events are reported through the supplied {@link Session.Events}. The link layer never
 * touches a serial port or a {@code ByteBuf} directly.
 *
 * <p><b>Lifecycle.</b> Call {@link #onConnected()} once the serial port is open; then feed every
 * inbound frame to {@link #onFrame(Ft12Frame)} and send application data with {@link
 * #sendAsdu(Asdu)}. A {@link Role#CLIENT} drives data-transfer bring-up with {@link
 * #startDataTransfer()}; a {@link Role#SERVER} follows the peer. Call {@link #close()} when the
 * transport goes away; it is idempotent.
 *
 * <p><b>Threading.</b> The selected engine guards all mutable state with a single internal lock and
 * its timer callbacks acquire that same lock, so a caller may invoke any method from any thread.
 * The {@link Output} and {@link Session.Events} callbacks are always invoked while that lock is
 * held; they must not call back into the link layer and should not block.
 */
public final class Ft12LinkLayer implements Session {

  /** The role a station plays in the FT1.2 balanced link-reset bring-up. */
  public enum Role {

    /** The controlling station: initiates the link-reset bring-up and sends DIR=1 frames. */
    CLIENT,

    /** The controlled station: reacts to the peer's link-reset and sends DIR=0 frames. */
    SERVER
  }

  /** Sink for FT1.2 frames the link layer wants to transmit on the underlying transport. */
  public interface Output {

    /**
     * Transmits a frame produced by the link layer.
     *
     * <p>Invoked while the link-layer lock is held; implementations must not block or re-enter the
     * link layer.
     *
     * @param frame the frame to send.
     */
    void send(Ft12Frame frame);
  }

  private final Ft12Engine delegate;

  /**
   * Creates a link layer with an unbounded outbound send queue.
   *
   * @param role the role this station plays: a {@link Role#CLIENT} controlling station or a {@link
   *     Role#SERVER} controlled station.
   * @param settings the FT1.2 link parameters; its {@linkplain LinkSettings#mode() mode} selects
   *     the balanced or unbalanced engine.
   * @param scheduler the executor used to schedule the engine's timers; callbacks run under the
   *     engine lock.
   * @param output the sink for outbound frames.
   * @param events the callbacks for delivered ASDUs and lifecycle transitions.
   * @throws IllegalArgumentException if {@code settings} is inconsistent with the selected engine.
   */
  public Ft12LinkLayer(
      Role role,
      LinkSettings settings,
      ScheduledExecutorService scheduler,
      Output output,
      Session.Events events) {

    this(role, settings, scheduler, output, events, 0, OutboundQueuePolicy.DROP_OLDEST);
  }

  /**
   * Creates a link layer with a bounded outbound send queue and an overflow policy.
   *
   * @param role the role this station plays: a {@link Role#CLIENT} controlling station or a {@link
   *     Role#SERVER} controlled station.
   * @param settings the FT1.2 link parameters; its {@linkplain LinkSettings#mode() mode} selects
   *     the balanced or unbalanced engine.
   * @param scheduler the executor used to schedule the engine's timers; callbacks run under the
   *     engine lock.
   * @param output the sink for outbound frames.
   * @param events the callbacks for delivered ASDUs and lifecycle transitions.
   * @param maxOutboundQueue the maximum number of ASDUs held in the send queue while the window is
   *     closed, or {@code 0} for an unbounded queue.
   * @param queuePolicy the action taken when a bounded queue overflows.
   * @throws IllegalArgumentException if {@code settings} is inconsistent with the selected engine,
   *     or if {@code maxOutboundQueue} is negative.
   */
  public Ft12LinkLayer(
      Role role,
      LinkSettings settings,
      ScheduledExecutorService scheduler,
      Output output,
      Session.Events events,
      int maxOutboundQueue,
      OutboundQueuePolicy queuePolicy) {

    Objects.requireNonNull(role, "role");
    Objects.requireNonNull(settings, "settings");

    this.delegate =
        switch (settings.mode()) {
          case BALANCED ->
              new BalancedEngine(
                  role, settings, scheduler, output, events, maxOutboundQueue, queuePolicy);
          case UNBALANCED ->
              switch (role) {
                case CLIENT ->
                    new UnbalancedMasterEngine(
                        settings, scheduler, output, events, maxOutboundQueue, queuePolicy);
                case SERVER ->
                    new UnbalancedSlaveEngine(
                        settings, scheduler, output, events, maxOutboundQueue, queuePolicy);
              };
        };
  }

  @Override
  public void onConnected() {
    delegate.onConnected();
  }

  @Override
  public CompletionStage<Void> startDataTransfer() {
    return delegate.startDataTransfer();
  }

  @Override
  public CompletionStage<Void> stopDataTransfer() {
    return delegate.stopDataTransfer();
  }

  @Override
  public boolean isDataTransferStarted() {
    return delegate.isDataTransferStarted();
  }

  @Override
  public void sendAsdu(Asdu asdu) {
    delegate.sendAsdu(asdu);
  }

  @Override
  public boolean awaitSendCapacity(long timeoutMillis) throws InterruptedException {
    return delegate.awaitSendCapacity(timeoutMillis);
  }

  @Override
  public int pendingSendCount() {
    return delegate.pendingSendCount();
  }

  @Override
  public void close() {
    delegate.close();
  }

  /**
   * Handles a single inbound FT1.2 frame by forwarding it to the selected engine.
   *
   * @param frame the inbound frame.
   */
  public void onFrame(Ft12Frame frame) {
    delegate.onFrame(frame);
  }
}
