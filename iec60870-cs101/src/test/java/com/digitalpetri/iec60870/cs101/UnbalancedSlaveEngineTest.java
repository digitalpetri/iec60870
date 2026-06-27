package com.digitalpetri.iec60870.cs101;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
import com.digitalpetri.iec60870.test.common.ManualScheduler;
import com.digitalpetri.iec60870.test.common.RecordingEvents;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link UnbalancedSlaveEngine}, the unbalanced FT1.2 outstation (secondary) state
 * machine: the buffering of application ASDUs into class-1/class-2 queues, the reset-driven link
 * bring-up, the rejection of send/confirm user data received before a link reset, the
 * class-1/class-2 poll responses with access-demand (ACD) escalation, the data-flow-control (DFC)
 * bit being pinned to {@code 0} (the outstation delivers inbound ASDUs synchronously with no
 * receive buffer, so it has no receive-side back-pressure to report per IEC 60870-5-2), FCB-keyed
 * retransmission replay, and the single-character versus full-frame acknowledgement selection.
 *
 * <p>The slave is purely reactive — it never initiates a transfer and arms no timers — so every
 * assertion is driven by feeding inbound primary frames through {@link
 * UnbalancedSlaveEngine#onFrame(Ft12Frame)} and observing the {@link RecordingOutput} frames and
 * {@link RecordingEvents} callbacks. A {@link ManualScheduler} is supplied only to satisfy the
 * engine's construction contract; no virtual time is advanced because the outstation schedules
 * nothing. Expected behavior is derived from the unbalanced secondary process (IEC 60870-5-101 §6,
 * §7.4.2, §8.4), not from the engine's internals.
 */
class UnbalancedSlaveEngineTest {

  /** The default unbalanced parameters: link address 1, single-character ack enabled. */
  private static final LinkSettings SETTINGS = LinkSettings.unbalanced().linkAddress(1).build();

  /** The link address used by {@link #SETTINGS} and carried by every emitted secondary frame. */
  private static final int LINK_ADDRESS = 1;

  // Primary (PRM=1) function codes the master sends (PRM context disambiguates FC0/FC9).
  private static final int FC_RESET_REMOTE_LINK = 0; // primary, FCV=0
  private static final int FC_SEND_CONFIRM_USER_DATA = 3; // primary, FCV=1
  private static final int FC_REQUEST_STATUS_OF_LINK = 9; // primary, FCV=0
  private static final int FC_REQUEST_USER_DATA_CLASS_1 = 10; // primary, FCV=1
  private static final int FC_REQUEST_USER_DATA_CLASS_2 = 11; // primary, FCV=1

  // Secondary (PRM=0) function codes the slave emits in response.
  private static final int FC_ACK = 0; // secondary, positive confirm
  private static final int FC_RESPOND_USER_DATA = 8; // secondary, carries an ASDU
  private static final int FC_STATUS_OF_LINK = 11; // secondary

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
    return newSlave(SETTINGS, 0, OutboundQueuePolicy.DROP_OLDEST);
  }

  private UnbalancedSlaveEngine newSlave(LinkSettings settings) {
    return newSlave(settings, 0, OutboundQueuePolicy.DROP_OLDEST);
  }

  private UnbalancedSlaveEngine newSlave(
      LinkSettings settings, int maxOutboundQueue, OutboundQueuePolicy policy) {
    return new UnbalancedSlaveEngine(settings, scheduler, output, events, maxOutboundQueue, policy);
  }

  // --- Buffering -------------------------------------------------------------------------------

  @Test
  void sendAsduBuffersWithoutEmittingAFrame() {
    UnbalancedSlaveEngine slave = newSlave();
    slave.onConnected();

    // The outstation never sends on its own: a published ASDU is buffered for a later poll.
    slave.sendAsdu(class2Asdu(10));
    assertEquals(0, output.frames().size(), "sendAsdu must buffer, not transmit");
    assertEquals(1, slave.pendingSendCount());

    slave.sendAsdu(class1Asdu(11));
    assertEquals(0, output.frames().size());
    assertEquals(2, slave.pendingSendCount(), "both class queues count toward the pending total");
  }

  // --- Reset bring-up --------------------------------------------------------------------------

  @Test
  void inboundResetBringsLinkAvailableAndAcks() {
    // Disable the single-character ack so the reset acknowledgement is an explicit FC0 frame.
    UnbalancedSlaveEngine slave = newSlave(unbalancedNoSingleChar());
    slave.onConnected();
    assertFalse(
        slave.isDataTransferStarted(), "the link is unavailable until the master resets it");

    // A reset-of-remote-link (FC0, primary) brings the link up and is positively acknowledged.
    slave.onFrame(primaryFixed(FC_RESET_REMOTE_LINK, false, false));

    assertTrue(slave.isDataTransferStarted());
    assertEquals(
        List.of(Boolean.TRUE),
        events.dataTransferChanges(),
        "onDataTransferStateChanged(true) must fire exactly once on the reset");
    assertEquals(1, output.frames().size());
    Ft12Frame.FixedLength ack =
        assertInstanceOf(Ft12Frame.FixedLength.class, output.frames().get(0));
    assertFalse(ack.control().prm(), "the reset acknowledgement is a secondary frame");
    assertEquals(FC_ACK, ack.control().functionCode());
    assertEquals(LINK_ADDRESS, ack.linkAddress());
  }

  // --- Class-2 poll ----------------------------------------------------------------------------

  @Test
  void requestClass2DequeuesAClass2AsduAsRespondUserData() {
    UnbalancedSlaveEngine slave = newSlave();
    slave.onConnected();

    Asdu cyclic = class2Asdu(20);
    slave.sendAsdu(cyclic);

    // A class-2 request (FC11, FCV=1) hands the buffered class-2 ASDU back as respond-user-data.
    slave.onFrame(primaryFixed(FC_REQUEST_USER_DATA_CLASS_2, true, true));

    assertEquals(1, output.frames().size());
    Ft12Frame.Variable fc8 = assertInstanceOf(Ft12Frame.Variable.class, output.frames().get(0));
    assertFalse(fc8.control().prm(), "a respond-user-data is a secondary frame");
    assertEquals(FC_RESPOND_USER_DATA, fc8.control().functionCode());
    assertEquals(cyclic, fc8.asdu());
    assertFalse(fc8.control().acd(), "no class-1 data is pending, so ACD is clear");
    assertEquals(0, slave.pendingSendCount());
  }

  @Test
  void class2PollWithoutClass2DataAnswersWithClass1AndSetsAcd() {
    UnbalancedSlaveEngine slave = newSlave();
    slave.onConnected();

    // Two spontaneous (class-1) ASDUs are buffered; the class-2 queue is empty.
    Asdu firstEvent = class1Asdu(30);
    slave.sendAsdu(firstEvent);
    slave.sendAsdu(class1Asdu(31));

    // A slave with no class-2 data may answer a class-2 request with class-1 data; the unanswered
    // class-1 ASDU that remains sets the access-demand bit so the master escalates.
    slave.onFrame(primaryFixed(FC_REQUEST_USER_DATA_CLASS_2, true, true));

    assertEquals(1, output.frames().size());
    Ft12Frame.Variable fc8 = assertInstanceOf(Ft12Frame.Variable.class, output.frames().get(0));
    assertEquals(FC_RESPOND_USER_DATA, fc8.control().functionCode());
    assertEquals(firstEvent, fc8.asdu(), "the class-2 poll drains the oldest class-1 ASDU");
    assertTrue(fc8.control().acd(), "a remaining class-1 ASDU sets ACD");
    assertEquals(1, slave.pendingSendCount());
  }

  // --- Class-1 poll + ACD ----------------------------------------------------------------------

  @Test
  void requestClass1DrainsAClass1AsduAsRespondUserData() {
    UnbalancedSlaveEngine slave = newSlave();
    slave.onConnected();

    Asdu event = class1Asdu(40);
    slave.sendAsdu(event);

    // A class-1 request (FC10, FCV=1) drains the buffered class-1 ASDU.
    slave.onFrame(primaryFixed(FC_REQUEST_USER_DATA_CLASS_1, true, true));

    assertEquals(1, output.frames().size());
    Ft12Frame.Variable fc8 = assertInstanceOf(Ft12Frame.Variable.class, output.frames().get(0));
    assertEquals(FC_RESPOND_USER_DATA, fc8.control().functionCode());
    assertEquals(event, fc8.asdu());
    assertFalse(fc8.control().acd(), "the last class-1 ASDU was drained, so ACD is clear");
    assertEquals(0, slave.pendingSendCount());
  }

  @Test
  void acdIsSetWhileClass1RemainsAndClearedWhenDrained() {
    UnbalancedSlaveEngine slave = newSlave();
    slave.onConnected();

    slave.sendAsdu(class1Asdu(50));
    slave.sendAsdu(class1Asdu(51));

    // First class-1 poll (FCB=1): one ASDU still remains, so ACD stays set.
    slave.onFrame(primaryFixed(FC_REQUEST_USER_DATA_CLASS_1, true, true));
    Ft12Frame.Variable first = assertInstanceOf(Ft12Frame.Variable.class, output.frames().get(0));
    assertTrue(first.control().acd(), "ACD is set while a class-1 ASDU remains");

    // Second class-1 poll with the toggled FCB (FCB=0) drains the last ASDU and clears ACD.
    slave.onFrame(primaryFixed(FC_REQUEST_USER_DATA_CLASS_1, false, true));
    Ft12Frame.Variable second = assertInstanceOf(Ft12Frame.Variable.class, output.frames().get(1));
    assertFalse(second.control().acd(), "ACD is cleared once the class-1 queue is drained");
    assertEquals(0, slave.pendingSendCount());
  }

  // --- DFC is never asserted from the transmit queues ------------------------------------------

  @Test
  void fullClass2QueueDoesNotAdvertiseDfc() {
    UnbalancedSlaveEngine slave = newSlave(SETTINGS, 1, OutboundQueuePolicy.DROP_OLDEST);
    slave.onConnected();

    // A single-slot class-2 queue is saturated; the second send drops the oldest and stays full.
    slave.sendAsdu(class2Asdu(60));
    slave.sendAsdu(class2Asdu(61));
    assertEquals(1, slave.pendingSendCount(), "the bounded queue stays at its limit");

    // Per IEC 60870-5-2 a secondary asserts DFC to report a full RECEIVE buffer; a saturated
    // outbound
    // (transmit) queue is not receive-side back-pressure, so the status-of-link (FC9 -> FC11)
    // response must NOT advertise DFC.
    slave.onFrame(primaryFixed(FC_REQUEST_STATUS_OF_LINK, false, false));
    Ft12Frame.FixedLength status =
        assertInstanceOf(Ft12Frame.FixedLength.class, output.frames().get(0));
    assertEquals(FC_STATUS_OF_LINK, status.control().functionCode());
    assertFalse(status.control().dfc(), "a saturated transmit queue must not advertise DFC");

    // The poll response that drains the queue likewise leaves DFC clear.
    slave.onFrame(primaryFixed(FC_REQUEST_USER_DATA_CLASS_2, true, true));
    Ft12Frame.Variable fc8 = assertInstanceOf(Ft12Frame.Variable.class, output.frames().get(1));
    assertEquals(FC_RESPOND_USER_DATA, fc8.control().functionCode());
    assertFalse(fc8.control().dfc(), "DFC stays 0 on the poll response as well");
  }

  @Test
  void fullClass1EventQueueAdvertisesAcdButNotDfc() {
    // The exact failure scenario from the finding: a slave that has buffered many spontaneous
    // events
    // (its class-1 / event queue is full) must advertise the pending data through ACD so the master
    // escalates to a class-1 poll, but it must NOT raise DFC. Driving DFC from a full transmit
    // queue
    // would wrongly tell a healthy master to withhold commands from a slave that merely has a lot
    // to
    // report.
    UnbalancedSlaveEngine slave = newSlave(SETTINGS, 1, OutboundQueuePolicy.DROP_OLDEST);
    slave.onConnected();

    slave.sendAsdu(class1Asdu(70));
    slave.sendAsdu(class1Asdu(71)); // saturates the single-slot class-1 queue
    assertEquals(1, slave.pendingSendCount(), "the bounded class-1 queue stays at its limit");

    slave.onFrame(primaryFixed(FC_REQUEST_STATUS_OF_LINK, false, false));
    Ft12Frame.FixedLength status =
        assertInstanceOf(Ft12Frame.FixedLength.class, output.frames().get(0));
    assertEquals(FC_STATUS_OF_LINK, status.control().functionCode());
    assertTrue(status.control().acd(), "pending class-1 data is advertised through ACD");
    assertFalse(status.control().dfc(), "a saturated transmit queue must not advertise DFC");
  }

  // --- FCB-keyed retransmission replay ---------------------------------------------------------

  @Test
  void retransmittedClass2RequestReplaysLastResponseWithoutDequeue() {
    UnbalancedSlaveEngine slave = newSlave();
    slave.onConnected();

    Asdu firstCyclic = class2Asdu(70);
    Asdu secondCyclic = class2Asdu(71);
    slave.sendAsdu(firstCyclic);
    slave.sendAsdu(secondCyclic);

    // First class-2 request (FCB=1) dequeues the head and caches the response.
    slave.onFrame(primaryFixed(FC_REQUEST_USER_DATA_CLASS_2, true, true));
    Ft12Frame.Variable response =
        assertInstanceOf(Ft12Frame.Variable.class, output.frames().get(0));
    assertEquals(firstCyclic, response.asdu());
    assertEquals(1, slave.pendingSendCount());

    // The same FCB is a retransmission: replay the cached frame verbatim and do NOT dequeue again.
    slave.onFrame(primaryFixed(FC_REQUEST_USER_DATA_CLASS_2, true, true));
    assertEquals(2, output.frames().size());
    assertSame(
        output.frames().get(0),
        output.frames().get(1),
        "a retransmission replays the cached response instance");
    assertEquals(1, slave.pendingSendCount(), "a retransmission must not dequeue a second ASDU");

    // A toggled FCB is a genuinely new request and dequeues the next buffered ASDU.
    slave.onFrame(primaryFixed(FC_REQUEST_USER_DATA_CLASS_2, false, true));
    Ft12Frame.Variable next = assertInstanceOf(Ft12Frame.Variable.class, output.frames().get(2));
    assertEquals(secondCyclic, next.asdu());
    assertEquals(0, slave.pendingSendCount());
  }

  // --- FC3 pre-reset gate ----------------------------------------------------------------------

  @Test
  void sendConfirmBeforeLinkResetIsRejectedNotDelivered() {
    UnbalancedSlaveEngine slave = newSlave();
    slave.onConnected();

    // The master sends user data without first resetting the link — a link-reset state-machine
    // bypass. The gate must hold: no ASDU is delivered, the link never becomes available, and a
    // not-reset secondary makes no reply (no positive ack such as a 0xE5 single char, and no frame
    // at all).
    Asdu command = commandAsdu(110);
    slave.onFrame(primaryVariable(FC_SEND_CONFIRM_USER_DATA, true, true, command));

    assertTrue(events.asdus().isEmpty(), "user data before a link reset must not be delivered");
    assertTrue(
        events.dataTransferChanges().isEmpty(), "the link never became available without a reset");
    assertTrue(
        output.frames().isEmpty(), "a not-reset secondary makes no reply to pre-reset user data");
  }

  @Test
  void sendConfirmIsDeliveredAfterAResetClearsTheGate() {
    UnbalancedSlaveEngine slave = newSlave();
    slave.onConnected();

    // A rejected pre-reset frame must neither deliver nor establish the FCB sequence or cache a
    // response, so the legitimate first post-reset frame is still treated as new.
    slave.onFrame(primaryVariable(FC_SEND_CONFIRM_USER_DATA, true, true, commandAsdu(120)));
    assertTrue(events.asdus().isEmpty());

    // Once the master resets the link, the first send/confirm is delivered and acknowledged.
    slave.onFrame(primaryFixed(FC_RESET_REMOTE_LINK, false, false));
    output.clear();

    Asdu command = commandAsdu(121);
    slave.onFrame(primaryVariable(FC_SEND_CONFIRM_USER_DATA, true, true, command));

    assertEquals(List.of(command), events.asdus(), "user data after a reset must be delivered");
    assertEquals(1, output.frames().size(), "the delivered frame is positively acknowledged");
  }

  // --- FC3 command delivery + acknowledgement form ---------------------------------------------

  @Test
  void sendConfirmDeliversOnceAndAcksWithSingleCharWhenIdle() {
    UnbalancedSlaveEngine slave = newSlave();
    slave.onConnected();
    // Bring the link up so the command is not treated as pre-reset.
    slave.onFrame(primaryFixed(FC_RESET_REMOTE_LINK, false, false));
    output.clear();

    Asdu command = commandAsdu(80);

    // A fresh send/confirm (FC3, FCB=1) delivers the carried ASDU once and acks with 0xE5, because
    // neither ACD nor DFC is set.
    slave.onFrame(primaryVariable(FC_SEND_CONFIRM_USER_DATA, true, true, command));
    assertEquals(List.of(command), events.asdus());
    assertEquals(1, output.frames().size());
    assertInstanceOf(
        Ft12Frame.SingleChar.class,
        output.frames().get(0),
        "with ACD==0 and DFC==0 the ack is the 0xE5 single-character frame");

    // The same FCB is a retransmission: replay the cached ack without re-delivering the ASDU.
    slave.onFrame(primaryVariable(FC_SEND_CONFIRM_USER_DATA, true, true, command));
    assertEquals(1, events.asdus().size(), "a retransmission must not be delivered a second time");
    assertEquals(2, output.frames().size());
    assertSame(output.frames().get(0), output.frames().get(1));
  }

  @Test
  void sendConfirmAckIsAFullFrameWhenAcdIsSet() {
    UnbalancedSlaveEngine slave = newSlave();
    slave.onConnected();
    slave.onFrame(primaryFixed(FC_RESET_REMOTE_LINK, false, false));
    output.clear();

    // A buffered class-1 ASDU sets ACD, which forces a full FC0 ack so the bit can be carried.
    slave.sendAsdu(class1Asdu(90));

    Asdu command = commandAsdu(91);
    slave.onFrame(primaryVariable(FC_SEND_CONFIRM_USER_DATA, true, true, command));

    assertEquals(List.of(command), events.asdus());
    assertEquals(1, output.frames().size());
    Ft12Frame.FixedLength ack =
        assertInstanceOf(
            Ft12Frame.FixedLength.class,
            output.frames().get(0),
            "ACD==1 forces a full FC0 ack rather than the 0xE5 single character");
    assertEquals(FC_ACK, ack.control().functionCode());
    assertFalse(ack.control().prm(), "the ack is a secondary frame");
    assertTrue(ack.control().acd(), "the pending class-1 ASDU is advertised through ACD");
  }

  @Test
  void sendConfirmAckStaysSingleCharWhenTheOutboundQueueIsFull() {
    UnbalancedSlaveEngine slave = newSlave(SETTINGS, 1, OutboundQueuePolicy.DROP_OLDEST);
    slave.onConnected();
    slave.onFrame(primaryFixed(FC_RESET_REMOTE_LINK, false, false));
    output.clear();

    // Saturate the bounded class-2 (transmit) queue. A full transmit queue is not receive-side
    // back-pressure, so it must NOT raise DFC and therefore must NOT force a full FC0 ack: with ACD
    // clear and DFC pinned to 0, the send/confirm is still acknowledged with the 0xE5 single char.
    slave.sendAsdu(class2Asdu(100));

    Asdu command = commandAsdu(101);
    slave.onFrame(primaryVariable(FC_SEND_CONFIRM_USER_DATA, true, true, command));

    assertEquals(List.of(command), events.asdus());
    assertEquals(1, output.frames().size());
    assertInstanceOf(
        Ft12Frame.SingleChar.class,
        output.frames().get(0),
        "a full outbound queue keeps DFC==0, so the ack remains the 0xE5 single character");
  }

  // --- SERVER-role lifecycle contract ----------------------------------------------------------

  @Test
  void startAndStopDataTransferThrow() {
    UnbalancedSlaveEngine slave = newSlave();

    assertThrows(IllegalStateException.class, slave::startDataTransfer);
    assertThrows(IllegalStateException.class, slave::stopDataTransfer);
  }

  // --- Fixtures --------------------------------------------------------------------------------

  /** Unbalanced settings with the single-character ack disabled, so every ack is a full frame. */
  private static LinkSettings unbalancedNoSingleChar() {
    return LinkSettings.unbalanced().linkAddress(LINK_ADDRESS).useSingleCharAck(false).build();
  }

  /** Builds an inbound primary fixed-length frame from the master with the given FCB/FCV. */
  private static Ft12Frame primaryFixed(int functionCode, boolean fcb, boolean fcv) {
    return new Ft12Frame.FixedLength(
        LinkControlField.primary(false, fcb, fcv, functionCode), LINK_ADDRESS);
  }

  /** Builds an inbound primary variable-length (ASDU-carrying) frame from the master. */
  private static Ft12Frame primaryVariable(int functionCode, boolean fcb, boolean fcv, Asdu asdu) {
    return new Ft12Frame.Variable(
        LinkControlField.primary(false, fcb, fcv, functionCode), LINK_ADDRESS, asdu);
  }

  /** A spontaneous-cause ASDU, classified into the class-1 (event) queue. */
  private static Asdu class1Asdu(int ioa) {
    return asdu(ioa, Cause.SPONTANEOUS);
  }

  /** A periodic-cause ASDU, classified into the class-2 (cyclic) queue. */
  private static Asdu class2Asdu(int ioa) {
    return asdu(ioa, Cause.PERIODIC);
  }

  /** An activation-cause ASDU standing in for a command carried by an FC3 send/confirm. */
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
