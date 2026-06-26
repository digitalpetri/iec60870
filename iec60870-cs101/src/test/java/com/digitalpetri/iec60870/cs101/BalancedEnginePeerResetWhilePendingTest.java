package com.digitalpetri.iec60870.cs101;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.digitalpetri.iec60870.OutboundQueuePolicy;
import com.digitalpetri.iec60870.address.CommonAddress;
import com.digitalpetri.iec60870.address.InformationObjectAddress;
import com.digitalpetri.iec60870.address.OriginatorAddress;
import com.digitalpetri.iec60870.asdu.Asdu;
import com.digitalpetri.iec60870.asdu.AsduType;
import com.digitalpetri.iec60870.asdu.Cause;
import com.digitalpetri.iec60870.asdu.InformationObject;
import com.digitalpetri.iec60870.asdu.element.Qds;
import com.digitalpetri.iec60870.asdu.object.SinglePointInformation;
import com.digitalpetri.iec60870.testsupport.ManualScheduler;
import com.digitalpetri.iec60870.testsupport.RecordingEvents;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * Regression tests for {@link BalancedEngine}'s handling of a reset-of-remote-link that arrives
 * from the peer while a user-data frame is still in flight (peer reconnect/restart mid-stream).
 *
 * <p>A reset-of-remote-link re-initializes the link in both directions, discarding the peer's
 * secondary dedup state. If the engine left its outstanding {@code FC3} transaction pending across
 * that boundary, two faults followed: the send queue stayed wedged behind a confirmation that could
 * no longer come, and the confirm timer eventually retransmitted the now stale-FCB frame to a
 * freshly reset peer — which, no longer recognizing it as a retransmission, delivered the carried
 * ASDU a second time. {@link BalancedEngine} must instead drop the stranded transaction: cancel its
 * confirm timer, clear the pending slot, and never retransmit the stale frame, while still flushing
 * any never-transmitted ASDUs queued behind it with fresh FCBs.
 *
 * <p>Behavior is driven by feeding inbound frames through {@link BalancedEngine#onFrame(Ft12Frame)}
 * and observing the {@link RecordingOutput} frames and {@link RecordingEvents} callbacks on a
 * {@link ManualScheduler} virtual clock.
 */
class BalancedEnginePeerResetWhilePendingTest {

  /**
   * Balanced parameters with the single-character ack disabled, so every positive acknowledgement
   * is an explicit secondary FC0 fixed-length frame that is straightforward to assert on.
   */
  private static final LinkSettings SETTINGS =
      LinkSettings.balanced().useSingleCharAck(false).build();

  private static final int LINK_ADDRESS = 1;

  // Primary (PRM=1) reset-of-remote-link function code (FCV=0).
  private static final int FC_RESET_REMOTE_LINK = 0;

  // Secondary (PRM=0) positive-acknowledgement function code.
  private static final int FC_ACK = 0;

  @Test
  void peerResetWhilePendingDropsTheInFlightFrameWithoutWedgingOrStaleRetransmit() {
    ManualScheduler scheduler = new ManualScheduler();
    RecordingOutput output = new RecordingOutput();
    RecordingEvents events = new RecordingEvents();
    // A SERVER reaches the available state by receiving the peer's reset-of-remote-link, which
    // keeps
    // the link bring-up to a single inbound frame.
    BalancedEngine engine = newEngine(Ft12LinkLayer.Role.SERVER, scheduler, output, events);
    engine.onConnected();

    // Bring the link up: the peer's reset makes it available and the engine acknowledges.
    engine.onFrame(primaryReset());
    assertEquals(List.of(Boolean.TRUE), events.dataTransferChanges());

    // Send and confirm one ASDU so the primary FCB toggles to false; the next in-flight frame then
    // carries a frame count bit (false) distinct from the post-reset frame count bit (true), which
    // makes a stale retransmit visibly distinguishable from a fresh post-reset send.
    engine.sendAsdu(commandAsdu(1));
    engine.onFrame(secondaryAck());

    // Send a second ASDU and leave it in flight (no acknowledgement): FC3 with FCB=false.
    Asdu inFlight = commandAsdu(2);
    engine.sendAsdu(inFlight);
    Ft12Frame.Variable inFlightFrame = lastVariable(output);
    assertFalse(inFlightFrame.control().fcb(), "the in-flight frame carries the toggled FCB=false");
    assertSame(inFlight, inFlightFrame.asdu());
    int framesBeforeReset = output.frames().size();

    // The peer resets the link while the FC3 frame is still outstanding.
    engine.onFrame(primaryReset());

    // The reset is acknowledged, and the stranded transaction is dropped rather than left pending:
    // the send queue is empty (not wedged) and no further user-data frame was emitted for it.
    Ft12Frame.FixedLength resetAck =
        assertInstanceOf(
            Ft12Frame.FixedLength.class, output.frames().get(output.frames().size() - 1));
    assertFalse(resetAck.control().prm(), "the reset is positively acknowledged");
    assertEquals(FC_ACK, resetAck.control().functionCode());
    assertEquals(framesBeforeReset + 1, output.frames().size(), "only the reset-ack was emitted");
    assertEquals(0, engine.pendingSendCount(), "the send queue is not wedged");

    // Driving the confirm/repeat timers past the point a stale retransmit chain would have
    // completed
    // (and short of the idle keep-alive) produces no retransmission of the dropped frame: no new
    // frame is emitted at all, so neither user-data frame already sent is repeated.
    int framesAfterReset = output.frames().size();
    int variablesAfterReset = variableCount(output);
    scheduler.advance(4000, TimeUnit.MILLISECONDS);
    assertEquals(
        framesAfterReset, output.frames().size(), "the stale frame is never retransmitted");
    assertEquals(
        variablesAfterReset, variableCount(output), "no user-data frame is re-sent on a timer");

    // A fresh ASDU submitted after the reset flushes immediately (queue not wedged) with the fresh
    // post-reset FCB (true), and carries the new ASDU — never a resend of the dropped one.
    Asdu afterReset = commandAsdu(3);
    engine.sendAsdu(afterReset);
    Ft12Frame.Variable resumedFrame = lastVariable(output);
    assertSame(afterReset, resumedFrame.asdu(), "the dropped ASDU is not re-sent");
    assertTrue(resumedFrame.control().fcb(), "the resumed frame carries the fresh post-reset FCB");
    assertTrue(events.asdus().isEmpty(), "a primary station does not deliver its own data");
  }

  /**
   * End-to-end guard against the duplicate delivery, with the peer modeled as a second engine: the
   * peer delivers the in-flight ASDU once, then restarts and resets the link with its dedup state
   * wiped. Because the local engine drops (rather than retransmits) its stranded frame, the peer is
   * never handed the ASDU a second time.
   */
  @Test
  void peerResetDoesNotCauseDuplicateDeliveryOnTheSecondarySide() {
    ManualScheduler localScheduler = new ManualScheduler();
    RecordingOutput localOut = new RecordingOutput();
    RecordingEvents localEvents = new RecordingEvents();
    BalancedEngine local =
        newEngine(Ft12LinkLayer.Role.SERVER, localScheduler, localOut, localEvents);

    ManualScheduler peerScheduler = new ManualScheduler();
    RecordingOutput peerOut = new RecordingOutput();
    RecordingEvents peerEvents = new RecordingEvents();
    BalancedEngine peer = newEngine(Ft12LinkLayer.Role.CLIENT, peerScheduler, peerOut, peerEvents);

    local.onConnected();
    peer.onConnected();

    // Bring the link up: the peer (CLIENT) drives FC9 -> FC11 -> FC0 -> ack.
    peer.startDataTransfer();
    local.onFrame(peerOut.frames().get(0)); // FC9 -> local replies FC11
    peer.onFrame(localOut.frames().get(0)); // FC11 -> peer sends FC0 reset
    local.onFrame(peerOut.frames().get(1)); // FC0 reset -> local available, acks
    peer.onFrame(localOut.frames().get(1)); // ack -> peer bring-up complete
    assertTrue(local.isDataTransferStarted());
    assertTrue(peer.isDataTransferStarted());

    // The local station sends an ASDU; the peer delivers it once and acknowledges, but the
    // acknowledgement is LOST, so the local frame stays in flight.
    Asdu inFlight = commandAsdu(7);
    local.sendAsdu(inFlight);
    Ft12Frame localUserData = localOut.frames().get(2); // FC3 user data
    peer.onFrame(localUserData);
    assertEquals(List.of(inFlight), peerEvents.asdus(), "the peer delivers the ASDU once");
    // peerOut now holds the peer's ack at index 2 — it is dropped (never delivered to the local
    // station), so the local station never confirms its in-flight frame.

    // The peer restarts (its dedup state is wiped) and re-initializes the link.
    peer.onConnected();
    peer.startDataTransfer();
    local.onFrame(peerOut.frames().get(3)); // FC9 -> local replies FC11
    peer.onFrame(localOut.frames().get(3)); // FC11 -> peer sends FC0 reset
    local.onFrame(peerOut.frames().get(4)); // FC0 reset while local's FC3 is still in flight
    peer.onFrame(localOut.frames().get(4)); // ack -> peer bring-up complete

    // Drive the local confirm/repeat timers: a buggy engine would now retransmit the stale FC3
    // frame
    // to the freshly reset peer, which would deliver the ASDU a second time. Relay anything the
    // local
    // station emits to the peer to surface such a duplicate.
    int alreadyRelayed = localOut.frames().size();
    localScheduler.advance(4000, TimeUnit.MILLISECONDS);
    for (int i = alreadyRelayed; i < localOut.frames().size(); i++) {
      peer.onFrame(localOut.frames().get(i));
    }

    assertEquals(
        List.of(inFlight), peerEvents.asdus(), "the peer must not receive a duplicate delivery");
    assertEquals(1, variableCount(localOut), "the in-flight frame is dropped, never retransmitted");
  }

  // --- Fixtures --------------------------------------------------------------------------------

  private static BalancedEngine newEngine(
      Ft12LinkLayer.Role role,
      ManualScheduler scheduler,
      RecordingOutput output,
      RecordingEvents events) {
    return new BalancedEngine(
        role, SETTINGS, scheduler, output, events, 0, OutboundQueuePolicy.DROP_OLDEST);
  }

  /** An inbound reset-of-remote-link (FC0, FCV=0) primary frame from the peer. */
  private static Ft12Frame primaryReset() {
    return new Ft12Frame.FixedLength(
        LinkControlField.primary(true, false, false, FC_RESET_REMOTE_LINK), LINK_ADDRESS);
  }

  /** An inbound positive acknowledgement (secondary FC0) from the peer. */
  private static Ft12Frame secondaryAck() {
    return new Ft12Frame.FixedLength(
        LinkControlField.secondary(true, false, false, FC_ACK), LINK_ADDRESS);
  }

  /** Returns the most recently emitted variable-length (user-data) frame. */
  private static Ft12Frame.Variable lastVariable(RecordingOutput output) {
    for (int i = output.frames().size() - 1; i >= 0; i--) {
      if (output.frames().get(i) instanceof Ft12Frame.Variable variable) {
        return variable;
      }
    }
    throw new AssertionError("no variable-length frame was emitted");
  }

  /** Counts the variable-length (user-data) frames emitted so far. */
  private static int variableCount(RecordingOutput output) {
    int count = 0;
    for (Ft12Frame frame : output.frames()) {
      if (frame instanceof Ft12Frame.Variable) {
        count++;
      }
    }
    return count;
  }

  /** An activation-cause ASDU standing in for a command carried by an FC3 send/confirm. */
  private static Asdu commandAsdu(int ioa) {
    InformationObject object =
        new SinglePointInformation(
            InformationObjectAddress.of(ioa), true, new Qds(false, false, false, false, false));
    return new Asdu(
        AsduType.M_SP_NA_1,
        false,
        Cause.ACTIVATION,
        false,
        false,
        OriginatorAddress.none(),
        CommonAddress.of(1),
        List.of(object));
  }

  /** Records every {@link Ft12Frame} the engine emits, in send order. */
  private static final class RecordingOutput implements Ft12LinkLayer.Output {

    private final List<Ft12Frame> frames = new ArrayList<>();

    @Override
    public void send(Ft12Frame frame) {
      frames.add(frame);
    }

    List<Ft12Frame> frames() {
      return frames;
    }
  }
}
