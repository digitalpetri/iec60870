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
import com.digitalpetri.iec60870.asdu.object.ReadCommand;
import com.digitalpetri.iec60870.test.common.ManualScheduler;
import com.digitalpetri.iec60870.test.common.RecordingEvents;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link UnbalancedMasterEngine}, the unbalanced FT1.2 master (primary) link state
 * machine: per-slave request-status/reset bring-up, the cyclic class-2 poll cycle round-robined
 * across the configured secondaries, access-demand (ACD) escalation to a class-1 drain, individual
 * addressing of user data by the ASDU's common address, the per-secondary frame-count-bit (FCB)
 * sequence, per-slave degradation on a confirm timeout that leaves the session open, and the
 * broadcast send/no-reply path.
 *
 * <p>The engine is driven through a {@link ManualScheduler} virtual clock and a recording {@link
 * RecordingOutput}/{@link RecordingEvents} so every emitted {@link Ft12Frame} and lifecycle event
 * is asserted deterministically with no real time elapsing. Expected behavior is derived from the
 * unbalanced FT1.2 design (a global window of one across the shared bus, a per-secondary FCB that
 * starts at {@code 1} after each slave's reset, and a per-slave — not per-session — reaction to a
 * dead secondary), not from the production engine's internals.
 */
class UnbalancedMasterEngineTest {

  /** Two configured secondaries (link addresses 1 and 2) polled on a {@code 1000 ms} cadence. */
  private static final LinkSettings SETTINGS =
      LinkSettings.unbalanced()
          .slaveAddresses(List.of(1, 2))
          .pollInterval(Duration.ofMillis(1000))
          .build();

  private static final long POLL_MILLIS = 1000;
  private static final long CONFIRM_MILLIS = 200;
  private static final long REPEAT_MILLIS = 1000;

  // Primary (PRM=1) function codes the master sends.
  private static final int FC_RESET_REMOTE_LINK = 0;
  private static final int FC_USER_DATA = 3;
  private static final int FC_USER_DATA_NO_REPLY = 4;
  private static final int FC_REQUEST_STATUS_OF_LINK = 9;
  private static final int FC_REQUEST_CLASS_1_DATA = 10;
  private static final int FC_REQUEST_CLASS_2_DATA = 11;

  // Secondary (PRM=0) function codes a slave returns.
  private static final int FC_ACK = 0;
  private static final int FC_RESPOND_USER_DATA = 8;
  private static final int FC_STATUS_OF_LINK = 11;

  private static final int SLAVE_1 = 1;
  private static final int SLAVE_2 = 2;
  private static final int BROADCAST = 255;

  private ManualScheduler scheduler;
  private RecordingOutput output;
  private RecordingEvents events;

  @BeforeEach
  void setUp() {
    scheduler = new ManualScheduler();
    output = new RecordingOutput();
    events = new RecordingEvents();
  }

  private UnbalancedMasterEngine newMaster() {
    return newMaster(SETTINGS);
  }

  private UnbalancedMasterEngine newMaster(LinkSettings settings) {
    return new UnbalancedMasterEngine(
        settings, scheduler, output, events, 0, OutboundQueuePolicy.DROP_OLDEST);
  }

  // --- Per-slave bring-up ----------------------------------------------------------------------

  @Test
  void bringsUpEachSlaveInTurnWithRequestStatusResetAndAck() {
    UnbalancedMasterEngine master = newMaster();
    master.onConnected();

    // startDataTransfer arms the poller and begins bring-up of the first slave with a
    // request-status-of-link (FC9) addressed to slave 1.
    master.startDataTransfer();
    assertEquals(1, output.frames().size());
    Ft12Frame.FixedLength fc9a =
        assertInstanceOf(Ft12Frame.FixedLength.class, output.frames().get(0));
    assertTrue(fc9a.control().prm(), "request-status-of-link is a primary frame");
    assertEquals(FC_REQUEST_STATUS_OF_LINK, fc9a.control().functionCode());
    assertEquals(SLAVE_1, fc9a.linkAddress());

    // Slave 1's status-of-link (FC11) prompts a reset-of-remote-link (FC0) to slave 1.
    master.onFrame(secondaryFixed(FC_STATUS_OF_LINK, SLAVE_1));
    assertEquals(2, output.frames().size());
    Ft12Frame.FixedLength fc0a =
        assertInstanceOf(Ft12Frame.FixedLength.class, output.frames().get(1));
    assertEquals(FC_RESET_REMOTE_LINK, fc0a.control().functionCode());
    assertEquals(SLAVE_1, fc0a.linkAddress());

    // Slave 1's acknowledgement makes it available; the master moves on to bring up slave 2 (FC9).
    master.onFrame(secondaryFixed(FC_ACK, SLAVE_1));
    assertEquals(3, output.frames().size());
    Ft12Frame.FixedLength fc9b =
        assertInstanceOf(Ft12Frame.FixedLength.class, output.frames().get(2));
    assertEquals(FC_REQUEST_STATUS_OF_LINK, fc9b.control().functionCode());
    assertEquals(SLAVE_2, fc9b.linkAddress());

    // Slave 2's status-of-link prompts its reset-of-remote-link (FC0) to slave 2.
    master.onFrame(secondaryFixed(FC_STATUS_OF_LINK, SLAVE_2));
    assertEquals(4, output.frames().size());
    Ft12Frame.FixedLength fc0b =
        assertInstanceOf(Ft12Frame.FixedLength.class, output.frames().get(3));
    assertEquals(FC_RESET_REMOTE_LINK, fc0b.control().functionCode());
    assertEquals(SLAVE_2, fc0b.linkAddress());

    // Slave 2's acknowledgement completes bring-up; the bus is idle until the poll cadence fires.
    master.onFrame(secondaryFixed(FC_ACK, SLAVE_2));
    assertEquals(4, output.frames().size(), "both slaves available: nothing further until a poll");
    assertEquals(0, events.closedCount());
  }

  // --- Class-2 poll cycle ----------------------------------------------------------------------

  @Test
  void classTwoPollCycleReachesEachSlaveInTurn() {
    UnbalancedMasterEngine master = newMaster();
    bringUpAll(master);

    // The first poll tick requests class-2 data (FC11, FCV=1) from slave 1.
    scheduler.advance(POLL_MILLIS, TimeUnit.MILLISECONDS);
    assertEquals(1, output.frames().size());
    Ft12Frame.FixedLength poll1 =
        assertInstanceOf(Ft12Frame.FixedLength.class, output.frames().get(0));
    assertEquals(FC_REQUEST_CLASS_2_DATA, poll1.control().functionCode());
    assertTrue(poll1.control().fcv(), "a class poll is an FCV=1 frame");
    assertEquals(SLAVE_1, poll1.linkAddress());

    // Slave 1 answers with no data (the 0xE5 short ack), freeing the bus.
    master.onFrame(new Ft12Frame.SingleChar());

    // The next poll tick round-robins to slave 2.
    scheduler.advance(POLL_MILLIS, TimeUnit.MILLISECONDS);
    assertEquals(2, output.frames().size());
    Ft12Frame.FixedLength poll2 =
        assertInstanceOf(Ft12Frame.FixedLength.class, output.frames().get(1));
    assertEquals(FC_REQUEST_CLASS_2_DATA, poll2.control().functionCode());
    assertEquals(SLAVE_2, poll2.linkAddress());
  }

  // --- ACD escalation --------------------------------------------------------------------------

  @Test
  void accessDemandResponseEscalatesToClassOneRequest() {
    UnbalancedMasterEngine master = newMaster();
    bringUpAll(master);

    // Poll slave 1 for class-2 data.
    scheduler.advance(POLL_MILLIS, TimeUnit.MILLISECONDS);
    assertEquals(1, output.frames().size());
    Ft12Frame.FixedLength poll =
        assertInstanceOf(Ft12Frame.FixedLength.class, output.frames().get(0));
    assertEquals(FC_REQUEST_CLASS_2_DATA, poll.control().functionCode());
    assertEquals(SLAVE_1, poll.linkAddress());

    // Slave 1 returns user data (FC8) with the access-demand bit set: the ASDU is delivered and the
    // master immediately escalates to a request-class-1 (FC10) drain of the same slave.
    Asdu spontaneous = asdu(SLAVE_1, 77);
    master.onFrame(secondaryUserData(SLAVE_1, true, false, spontaneous));

    assertEquals(1, events.asdus().size(), "the FC8 ASDU is delivered through onAsdu");
    assertEquals(spontaneous, events.asdus().get(0));

    assertEquals(2, output.frames().size());
    Ft12Frame.FixedLength class1 =
        assertInstanceOf(Ft12Frame.FixedLength.class, output.frames().get(1));
    assertEquals(FC_REQUEST_CLASS_1_DATA, class1.control().functionCode());
    assertTrue(class1.control().fcv(), "a class-1 drain is an FCV=1 frame");
    assertEquals(SLAVE_1, class1.linkAddress());
  }

  // --- Individual addressing -------------------------------------------------------------------

  @Test
  void userDataIsAddressedToTheSlaveMatchingTheCommonAddress() {
    UnbalancedMasterEngine master = newMaster();
    bringUpAll(master);

    // An ASDU whose common address is 2 is sent as send/confirm user data (FC3) to slave 2.
    Asdu command = asdu(SLAVE_2, 33);
    master.sendAsdu(command);

    assertEquals(1, output.frames().size());
    Ft12Frame.Variable user = assertInstanceOf(Ft12Frame.Variable.class, output.frames().get(0));
    assertTrue(user.control().prm());
    assertTrue(user.control().fcv(), "send/confirm user data is an FCV=1 frame");
    assertEquals(FC_USER_DATA, user.control().functionCode());
    assertEquals(SLAVE_2, user.linkAddress());
    assertEquals(command, user.asdu());
  }

  // --- Per-slave FCB sequence ------------------------------------------------------------------

  @Test
  void frameCountBitTogglesPerSlaveAcrossConfirmedTransactions() {
    UnbalancedMasterEngine master = newMaster();
    bringUpAll(master);

    // Two confirmed user-data transactions to slave 1 toggle that slave's FCB from 1 to 0.
    master.sendAsdu(asdu(SLAVE_1, 10));
    master.onFrame(new Ft12Frame.SingleChar()); // ack -> toggle slave 1's FCB
    master.sendAsdu(asdu(SLAVE_1, 11));
    master.onFrame(new Ft12Frame.SingleChar()); // ack -> toggle slave 1's FCB back
    // Slave 2's FCB is independent: its first user-data frame after reset still carries FCB=1.
    master.sendAsdu(asdu(SLAVE_2, 20));

    assertEquals(3, output.frames().size());
    Ft12Frame.Variable first = assertInstanceOf(Ft12Frame.Variable.class, output.frames().get(0));
    Ft12Frame.Variable second = assertInstanceOf(Ft12Frame.Variable.class, output.frames().get(1));
    Ft12Frame.Variable third = assertInstanceOf(Ft12Frame.Variable.class, output.frames().get(2));

    assertEquals(SLAVE_1, first.linkAddress());
    assertTrue(first.control().fcb(), "slave 1's first user-data frame after reset carries FCB=1");
    assertEquals(SLAVE_1, second.linkAddress());
    assertFalse(
        second.control().fcb(), "the next confirmed frame to the same slave toggles its FCB to 0");
    assertEquals(SLAVE_2, third.linkAddress());
    assertTrue(
        third.control().fcb(), "each slave keeps its own FCB sequence; slave 2 starts at FCB=1");
  }

  // --- Per-slave degradation on a confirm timeout ----------------------------------------------

  @Test
  void confirmTimeoutDegradesOnlyTheOffendingSlaveAndKeepsTheSessionOpen() {
    // A long poll interval keeps the poll timer out of the way; a single retry shortens the run.
    LinkSettings settings =
        LinkSettings.unbalanced()
            .slaveAddresses(List.of(SLAVE_1, SLAVE_2))
            .pollInterval(Duration.ofMillis(100_000))
            .maxRetries(1)
            .build();
    UnbalancedMasterEngine master = newMaster(settings);
    bringUpAll(master);

    // User data to slave 1 goes unacknowledged.
    master.sendAsdu(asdu(SLAVE_1, 10));
    assertEquals(1, output.frames().size());
    Ft12Frame.Variable sent = assertInstanceOf(Ft12Frame.Variable.class, output.frames().get(0));
    assertEquals(SLAVE_1, sent.linkAddress());

    // The confirm timeout retransmits once; the session stays open.
    scheduler.advance(CONFIRM_MILLIS, TimeUnit.MILLISECONDS);
    assertEquals(2, output.frames().size(), "an unacknowledged frame is retransmitted");
    assertEquals(0, events.closedCount());

    // The repeat timeout exhausts the single retry: slave 1 is degraded, but the session never
    // self-closes on a protocol timeout (one dead slave must not kill the bus).
    scheduler.advance(REPEAT_MILLIS, TimeUnit.MILLISECONDS);
    assertEquals(0, events.closedCount(), "a per-slave timeout must not close the session");

    // Only slave 1 is degraded: a command for it is now dropped, while slave 2 still accepts data.
    master.sendAsdu(asdu(SLAVE_1, 11));
    assertEquals(2, output.frames().size(), "commands for the degraded slave are dropped");

    master.sendAsdu(asdu(SLAVE_2, 12));
    assertEquals(3, output.frames().size(), "the rest of the bus keeps working");
    Ft12Frame.Variable toSlave2 =
        assertInstanceOf(Ft12Frame.Variable.class, output.frames().get(2));
    assertEquals(FC_USER_DATA, toSlave2.control().functionCode());
    assertEquals(SLAVE_2, toSlave2.linkAddress());
    assertEquals(0, events.closedCount());
  }

  // --- Broadcast -------------------------------------------------------------------------------

  @Test
  void broadcastIsSentAsSendNoReplyWithNoAcknowledgement() {
    UnbalancedMasterEngine master = newMaster();
    bringUpAll(master);

    // An ASDU addressed to the broadcast address is sent as send/no-reply (FC4, FCV=0) and is not
    // acknowledged, so the bus is left free.
    Asdu broadcast = asdu(BROADCAST, 44);
    master.sendAsdu(broadcast);

    assertEquals(1, output.frames().size());
    Ft12Frame.Variable noReply = assertInstanceOf(Ft12Frame.Variable.class, output.frames().get(0));
    assertTrue(noReply.control().prm());
    assertEquals(FC_USER_DATA_NO_REPLY, noReply.control().functionCode());
    assertFalse(noReply.control().fcv(), "send/no-reply awaits no acknowledgement");
    assertEquals(BROADCAST, noReply.linkAddress());
    assertEquals(broadcast, noReply.asdu());
    assertEquals(0, master.pendingSendCount());

    // No transaction is outstanding after the broadcast: a following command transmits at once.
    master.sendAsdu(asdu(SLAVE_1, 45));
    assertEquals(2, output.frames().size(), "the bus stays free after a broadcast");
    Ft12Frame.Variable user = assertInstanceOf(Ft12Frame.Variable.class, output.frames().get(1));
    assertEquals(FC_USER_DATA, user.control().functionCode());
    assertEquals(SLAVE_1, user.linkAddress());
  }

  // --- Fixtures --------------------------------------------------------------------------------

  /**
   * Drives the master through {@code onConnected} and the full per-slave bring-up of both
   * configured secondaries, leaving both available and the captured frames cleared.
   *
   * @param master the engine to bring up.
   */
  private void bringUpAll(UnbalancedMasterEngine master) {
    master.onConnected();
    master.startDataTransfer();
    master.onFrame(secondaryFixed(FC_STATUS_OF_LINK, SLAVE_1));
    master.onFrame(secondaryFixed(FC_ACK, SLAVE_1));
    master.onFrame(secondaryFixed(FC_STATUS_OF_LINK, SLAVE_2));
    master.onFrame(secondaryFixed(FC_ACK, SLAVE_2));
    output.clear();
  }

  /**
   * Builds a secondary (response) fixed frame from a slave; DIR is not significant to dispatch and
   * is left {@code false} (unbalanced secondary frames carry RES).
   *
   * @param functionCode the secondary function code.
   * @param linkAddress the responding slave's link address.
   * @return the inbound fixed-length frame.
   */
  private static Ft12Frame secondaryFixed(int functionCode, int linkAddress) {
    return new Ft12Frame.FixedLength(
        LinkControlField.secondary(false, false, false, functionCode), linkAddress);
  }

  /**
   * Builds a secondary respond-user-data (FC8) frame carrying an ASDU, with the given access-demand
   * and data-flow-control bits.
   *
   * @param linkAddress the responding slave's link address.
   * @param acd the access-demand bit.
   * @param dfc the data-flow-control bit.
   * @param asdu the carried ASDU.
   * @return the inbound variable-length frame.
   */
  private static Ft12Frame secondaryUserData(int linkAddress, boolean acd, boolean dfc, Asdu asdu) {
    return new Ft12Frame.Variable(
        LinkControlField.secondary(false, acd, dfc, FC_RESPOND_USER_DATA), linkAddress, asdu);
  }

  /**
   * Builds a minimal read-command ASDU stamped with the given common address and information-object
   * address.
   *
   * @param commonAddress the common address selecting the target slave.
   * @param ioa the information-object address.
   * @return the ASDU.
   */
  private static Asdu asdu(int commonAddress, int ioa) {
    InformationObject object = new ReadCommand(InformationObjectAddress.of(ioa));
    return new Asdu(
        AsduType.C_RD_NA_1,
        false,
        Cause.REQUEST,
        false,
        false,
        OriginatorAddress.none(),
        CommonAddress.of(commonAddress),
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
