package com.digitalpetri.iec60870.cs101;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import com.digitalpetri.iec60870.testsupport.ManualScheduler;
import com.digitalpetri.iec60870.testsupport.RecordingEvents;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Regression tests proving the unbalanced master's global command queue does not suffer cross-slave
 * head-of-line blocking: a command at the queue head whose target slave currently cannot accept
 * user data — because it is DFC-back-pressured (or otherwise not ready) — must not starve queued
 * commands addressed to other slaves that <em>are</em> ready. {@link UnbalancedMasterEngine#pump()}
 * scans past the blocked head for the first deliverable command instead of stalling the whole queue
 * on it.
 *
 * <p>Each slave's data-flow-control (DFC) back-pressure is set deterministically by sending it one
 * user-data frame and acknowledging that frame with the DFC bit set, after which the slave stays
 * {@code AVAILABLE} but no further user data may be sent to it until its DFC bit clears. The engine
 * is driven through a {@link ManualScheduler} virtual clock and a recording {@code RecordingOutput}
 * plus {@link RecordingEvents}, so every emitted {@link Ft12Frame} is asserted with no real time
 * elapsing.
 */
class UnbalancedMasterCommandStarvationTest {

  // Primary (PRM=1) function codes the master sends.
  private static final int FC_USER_DATA = 3;

  // Secondary (PRM=0) function codes a slave returns.
  private static final int FC_ACK = 0;
  private static final int FC_STATUS_OF_LINK = 11;

  private static final int SLAVE_1 = 1;
  private static final int SLAVE_2 = 2;
  private static final int SLAVE_3 = 3;

  private ManualScheduler scheduler;
  private RecordingOutput output;
  private RecordingEvents events;

  @BeforeEach
  void setUp() {
    scheduler = new ManualScheduler();
    output = new RecordingOutput();
    events = new RecordingEvents();
  }

  private UnbalancedMasterEngine newMaster(List<Integer> slaveAddresses) {
    LinkSettings settings =
        LinkSettings.unbalanced()
            .slaveAddresses(slaveAddresses)
            .pollInterval(Duration.ofMillis(100_000)) // keep the poll cadence out of the way
            .build();
    return new UnbalancedMasterEngine(
        settings, scheduler, output, events, 0, OutboundQueuePolicy.DROP_OLDEST);
  }

  /**
   * The exact finding: with a back-pressured slave's command sitting at the queue head, a command
   * to a different, ready slave must still be delivered rather than stalling behind the blocked
   * head.
   */
  @Test
  void dfcBlockedHeadCommandDoesNotStarveACommandToAnotherReadySlave() {
    UnbalancedMasterEngine master = newMaster(List.of(SLAVE_1, SLAVE_2));
    bringUp(master, List.of(SLAVE_1, SLAVE_2));
    backPressure(master, SLAVE_2);

    // A command for the back-pressured slave 2 cannot be sent and sits at the queue head.
    master.sendAsdu(command(SLAVE_2, 20));
    assertEquals(
        0, output.frames().size(), "the command for the back-pressured slave must be held");
    assertEquals(1, master.pendingSendCount());

    // A command for the ready slave 1 must be scanned past the blocked head and delivered at once.
    master.sendAsdu(command(SLAVE_1, 10));
    assertEquals(1, output.frames().size(), "the command to the ready slave is not starved");
    assertUserData(output.frames().get(0), SLAVE_1);

    // The blocked slave-2 command remains queued (not dropped, not sent to the wrong slave).
    assertEquals(1, master.pendingSendCount(), "the blocked command stays queued behind");
    assertEquals(0, events.closedCount());
  }

  /**
   * The finding's failure scenario verbatim: slave 2 asserts DFC and a command for slave 2 is at
   * the head; commands behind it for slaves 1 and 3 must each be delivered to their ready slave
   * rather than starved until slave 2's DFC clears, while the slave-2 command waits its turn.
   */
  @Test
  void blockedHeadCommandDoesNotStarveCommandsToOtherReadySlaves() {
    UnbalancedMasterEngine master = newMaster(List.of(SLAVE_1, SLAVE_2, SLAVE_3));
    bringUp(master, List.of(SLAVE_1, SLAVE_2, SLAVE_3));
    backPressure(master, SLAVE_2);

    // Head: a command for the back-pressured slave 2; behind it, commands for ready slaves 1 and 3.
    master.sendAsdu(command(SLAVE_2, 20));
    master.sendAsdu(command(SLAVE_1, 10)); // delivered now (slave 2's head is scanned past)
    master.sendAsdu(command(SLAVE_3, 30)); // queued behind while the bus carries slave 1's frame

    assertEquals(1, output.frames().size(), "only one transaction is outstanding on the bus");
    assertUserData(output.frames().get(0), SLAVE_1);
    assertEquals(2, master.pendingSendCount(), "the slave-2 and slave-3 commands wait their turn");

    // Acknowledging slave 1 frees the bus; the slave-3 command is delivered, skipping slave 2.
    master.onFrame(new Ft12Frame.SingleChar());
    assertEquals(2, output.frames().size());
    assertUserData(output.frames().get(1), SLAVE_3);

    // Acknowledging slave 3 frees the bus; only the back-pressured slave-2 command is left, so
    // nothing more is sent — it is held, not starved-out or misrouted.
    master.onFrame(new Ft12Frame.SingleChar());
    assertEquals(2, output.frames().size(), "no frame is ever sent to the back-pressured slave 2");
    assertEquals(1, master.pendingSendCount(), "the slave-2 command remains queued");
    assertEquals(0, events.closedCount());
  }

  // --- Fixtures --------------------------------------------------------------------------------

  /**
   * Drives the master through {@code onConnected}/{@code startDataTransfer} and the full per-slave
   * bring-up of each configured secondary (in listed order), leaving them all available and the
   * captured frames cleared.
   *
   * @param master the engine to bring up.
   * @param addresses the configured slave link addresses, in bring-up order.
   */
  private void bringUp(UnbalancedMasterEngine master, List<Integer> addresses) {
    master.onConnected();
    master.startDataTransfer();
    for (int address : addresses) {
      master.onFrame(secondaryFixed(FC_STATUS_OF_LINK, address, false, false));
      master.onFrame(secondaryFixed(FC_ACK, address, false, false));
    }
    output.clear();
  }

  /**
   * Puts a slave into DFC back-pressure: sends it a single user-data frame and acknowledges that
   * frame with the data-flow-control bit set, then clears the captured frames. The slave stays
   * available, but no further user data may be sent to it until a later response clears its DFC
   * bit.
   *
   * @param master the engine under test.
   * @param slaveAddress the slave to back-pressure.
   */
  private void backPressure(UnbalancedMasterEngine master, int slaveAddress) {
    master.sendAsdu(command(slaveAddress, 1));
    assertEquals(1, output.frames().size(), "the priming user-data frame to set up DFC");
    master.onFrame(secondaryFixed(FC_ACK, slaveAddress, false, true)); // ack carrying DFC=1
    output.clear();
  }

  /**
   * Asserts that the frame is a primary send/confirm user-data (FC3) frame to the given slave.
   *
   * @param frame the emitted frame.
   * @param linkAddress the expected link address.
   */
  private static void assertUserData(Ft12Frame frame, int linkAddress) {
    Ft12Frame.Variable variable = assertInstanceOf(Ft12Frame.Variable.class, frame);
    assertTrue(variable.control().prm(), "expected a primary frame");
    assertEquals(FC_USER_DATA, variable.control().functionCode());
    assertEquals(linkAddress, variable.linkAddress());
  }

  /**
   * Builds a secondary (response) fixed frame from a slave with the given access-demand and
   * data-flow-control bits; DIR is not significant to dispatch and is left {@code false}.
   *
   * @param functionCode the secondary function code.
   * @param linkAddress the responding slave's link address.
   * @param acd the access-demand bit.
   * @param dfc the data-flow-control bit.
   * @return the inbound fixed-length frame.
   */
  private static Ft12Frame secondaryFixed(
      int functionCode, int linkAddress, boolean acd, boolean dfc) {
    return new Ft12Frame.FixedLength(
        LinkControlField.secondary(false, acd, dfc, functionCode), linkAddress);
  }

  /**
   * Builds a minimal read-command ASDU stamped with the given common address and information-object
   * address.
   *
   * @param commonAddress the common address selecting the target slave.
   * @param ioa the information-object address.
   * @return the ASDU.
   */
  private static Asdu command(int commonAddress, int ioa) {
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
