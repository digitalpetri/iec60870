package com.digitalpetri.iec60870.cs101;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Regression tests for {@link UnbalancedSlaveEngine} link-address filtering on a shared multi-drop
 * bus.
 *
 * <p>On a real RS-485 multi-drop bus every secondary's UART receives every master frame, so the
 * outstation must compare the inbound FT1.2 link address against its own before acting. These tests
 * feed frames addressed to a <em>different</em> secondary and assert that the engine stays silent
 * and inert — no response frame, no delivered ASDU, no state change — so this station never answers
 * a poll, reset, or command meant for a peer (which on a real bus would execute a command on the
 * wrong outstation and collide an acknowledgement on the wire). They also assert that an
 * own-addressed frame is still processed and answered as before, and that an all-secondaries
 * broadcast frame is delivered to the application but never answered.
 *
 * <p>The slave is purely reactive, so every assertion is driven by feeding inbound primary frames
 * through {@link UnbalancedSlaveEngine#onFrame(Ft12Frame)} and observing the emitted frames and the
 * recorded events. A {@link ManualScheduler} satisfies the engine construction contract only; no
 * virtual time is advanced because the outstation schedules nothing.
 */
class SlaveLinkAddressFilteringTest {

  /** This outstation's own link address; every own-addressed frame targets it. */
  private static final int OWN_ADDRESS = 7;

  /** A different secondary's link address; frames addressed here must be ignored. */
  private static final int OTHER_ADDRESS = 8;

  /** The one-octet all-secondaries broadcast address (the unbalanced default). */
  private static final int BROADCAST_ADDRESS = 255;

  // Primary (PRM=1) function codes the master sends.
  private static final int FC_RESET_REMOTE_LINK = 0; // primary, FCV=0
  private static final int FC_SEND_CONFIRM_USER_DATA = 3; // primary, FCV=1
  private static final int FC_SEND_NO_REPLY_USER_DATA = 4; // primary, FCV=0 (broadcast)
  private static final int FC_REQUEST_USER_DATA_CLASS_2 = 11; // primary, FCV=1

  // Secondary (PRM=0) function code the slave emits in response.
  private static final int FC_ACK = 0;

  private ManualScheduler scheduler;
  private RecordingOutput output;
  private RecordingEvents events;

  @BeforeEach
  void setUp() {
    scheduler = new ManualScheduler();
    output = new RecordingOutput();
    events = new RecordingEvents();
  }

  private UnbalancedSlaveEngine newSlave() {
    // Disable the single-character ack so every acknowledgement is an explicit, address-stamped
    // frame whose link address can be asserted.
    LinkSettings settings =
        LinkSettings.unbalanced().linkAddress(OWN_ADDRESS).useSingleCharAck(false).build();
    return new UnbalancedSlaveEngine(
        settings, scheduler, output, events, 0, OutboundQueuePolicy.DROP_OLDEST);
  }

  // --- Frames for a different secondary are ignored --------------------------------------------

  @Test
  void resetForADifferentSlaveIsIgnored() {
    UnbalancedSlaveEngine slave = newSlave();
    slave.onConnected();

    // A reset-of-remote-link (FC0) addressed to another secondary must not bring this link up or
    // emit an acknowledgement.
    slave.onFrame(primaryFixed(FC_RESET_REMOTE_LINK, OTHER_ADDRESS, false, false));

    assertFalse(slave.isDataTransferStarted(), "the link must stay down");
    assertTrue(events.dataTransferChanges().isEmpty(), "no state change for another slave");
    assertEquals(0, output.frames().size(), "no response for another slave");
  }

  @Test
  void pollForADifferentSlaveIsIgnoredEvenWithBufferedData() {
    UnbalancedSlaveEngine slave = newSlave();
    slave.onConnected();

    // Buffer a class-2 ASDU, then poll a DIFFERENT secondary: the buffered data must not be sent
    // and must stay queued (the wrong outstation must not answer the poll).
    slave.sendAsdu(class2Asdu(20));
    slave.onFrame(primaryFixed(FC_REQUEST_USER_DATA_CLASS_2, OTHER_ADDRESS, true, true));

    assertEquals(0, output.frames().size(), "no response for another slave's poll");
    assertEquals(1, slave.pendingSendCount(), "this slave's data must not be dequeued");
  }

  @Test
  void commandForADifferentSlaveIsNotDeliveredOrAcked() {
    UnbalancedSlaveEngine slave = newSlave();
    slave.onConnected();
    // Bring this slave's link up with an own-addressed reset, then drop the bring-up ack.
    slave.onFrame(primaryFixed(FC_RESET_REMOTE_LINK, OWN_ADDRESS, false, false));
    output.clear();

    // A send/confirm command (FC3) addressed to another secondary must not deliver into this
    // station's application, nor emit a colliding acknowledgement.
    Asdu command = commandAsdu(30);
    slave.onFrame(primaryVariable(FC_SEND_CONFIRM_USER_DATA, OTHER_ADDRESS, true, true, command));

    assertTrue(events.asdus().isEmpty(), "a command for another slave must not be delivered");
    assertEquals(0, output.frames().size(), "a command for another slave must elicit no ack");
  }

  // --- Own-addressed frames are processed as before --------------------------------------------

  @Test
  void ownAddressedResetIsProcessedAndAnswered() {
    UnbalancedSlaveEngine slave = newSlave();
    slave.onConnected();

    // The same reset, now addressed to this slave's own link address, brings the link up and is
    // acknowledged with an own-address-stamped FC0 frame.
    slave.onFrame(primaryFixed(FC_RESET_REMOTE_LINK, OWN_ADDRESS, false, false));

    assertTrue(slave.isDataTransferStarted(), "an own-addressed reset brings the link up");
    assertEquals(
        List.of(Boolean.TRUE),
        events.dataTransferChanges(),
        "onDataTransferStateChanged(true) fires once");
    assertEquals(1, output.frames().size());
    Ft12Frame.FixedLength ack =
        assertInstanceOf(Ft12Frame.FixedLength.class, output.frames().get(0));
    assertFalse(ack.control().prm(), "the acknowledgement is a secondary frame");
    assertEquals(FC_ACK, ack.control().functionCode());
    assertEquals(OWN_ADDRESS, ack.linkAddress(), "the response carries this slave's address");
  }

  // --- Broadcast frames are delivered but never answered ---------------------------------------

  @Test
  void broadcastSendNoReplyIsDeliveredButNotAnswered() {
    UnbalancedSlaveEngine slave = newSlave();
    slave.onConnected();

    // A send/no-reply (FC4) broadcast addresses every secondary: the carried ASDU is delivered to
    // the application, but a broadcast service is unconfirmed so no response is emitted.
    Asdu broadcastCommand = commandAsdu(40);
    slave.onFrame(
        primaryVariable(
            FC_SEND_NO_REPLY_USER_DATA, BROADCAST_ADDRESS, false, false, broadcastCommand));

    assertEquals(List.of(broadcastCommand), events.asdus(), "the broadcast ASDU is delivered");
    assertEquals(0, output.frames().size(), "a broadcast must never be answered");
  }

  @Test
  void broadcastPollIsNotAnswered() {
    UnbalancedSlaveEngine slave = newSlave();
    slave.onConnected();

    // A confirmed service (here a class-2 poll) addressed to the broadcast address is meaningless
    // and must elicit no response, even with buffered data, so no reply collides on the bus.
    slave.sendAsdu(class2Asdu(50));
    slave.onFrame(primaryFixed(FC_REQUEST_USER_DATA_CLASS_2, BROADCAST_ADDRESS, true, true));

    assertEquals(0, output.frames().size(), "a broadcast poll must elicit no response");
    assertEquals(1, slave.pendingSendCount(), "buffered data must not be dequeued");
  }

  // --- Fixtures --------------------------------------------------------------------------------

  /** Builds an inbound primary fixed-length frame from the master with the given link address. */
  private static Ft12Frame primaryFixed(
      int functionCode, int linkAddress, boolean fcb, boolean fcv) {
    return new Ft12Frame.FixedLength(
        LinkControlField.primary(false, fcb, fcv, functionCode), linkAddress);
  }

  /** Builds an inbound primary variable-length (ASDU-carrying) frame with a given link address. */
  private static Ft12Frame primaryVariable(
      int functionCode, int linkAddress, boolean fcb, boolean fcv, Asdu asdu) {
    return new Ft12Frame.Variable(
        LinkControlField.primary(false, fcb, fcv, functionCode), linkAddress, asdu);
  }

  /** A periodic-cause ASDU, classified into the class-2 (cyclic) queue. */
  private static Asdu class2Asdu(int ioa) {
    return asdu(ioa, Cause.PERIODIC);
  }

  /** An activation-cause ASDU standing in for a command carried by an FC3/FC4 frame. */
  private static Asdu commandAsdu(int ioa) {
    return asdu(ioa, Cause.ACTIVATION);
  }

  private static Asdu asdu(int ioa, Cause cause) {
    InformationObject object =
        new SinglePointInformation(
            InformationObjectAddress.of(ioa), true, new Qds(false, false, false, false, false));
    return new Asdu(
        AsduType.M_SP_NA_1,
        false,
        cause,
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

    void clear() {
      frames.clear();
    }
  }
}
