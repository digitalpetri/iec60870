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
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Regression tests for the fair bus scheduling of {@link UnbalancedMasterEngine} during per-slave
 * bring-up.
 *
 * <p>The unbalanced master shares one half-duplex bus across every configured secondary, so at most
 * one transaction is outstanding at a time. Bring-up of a not-yet-available slave must therefore
 * not monopolize that single-transaction bus: a single unresponsive slave being retried to its
 * timeout must not stall the bring-up <em>and</em> service (polls and commands) of a healthy slave
 * for the whole retransmit budget. These tests drive the engine through a {@link ManualScheduler}
 * virtual clock and assert that — with the dead slave listed first, exactly the {@code [deadSlave,
 * healthySlave]} ordering that previously starved the healthy slave — the healthy slave is brought
 * up and serviced while the dead slave is still being brought up, and that the dead slave is still
 * eventually degraded to the error state.
 */
class UnbalancedMasterFairSchedulingTest {

  private static final long CONFIRM_MILLIS = 200;
  private static final long REPEAT_MILLIS = 1000;

  // Primary (PRM=1) function codes the master sends.
  private static final int FC_RESET_REMOTE_LINK = 0;
  private static final int FC_USER_DATA = 3;
  private static final int FC_REQUEST_STATUS_OF_LINK = 9;

  // Secondary (PRM=0) function codes a slave returns, used to drive bring-up.
  private static final int FC_ACK = 0;
  private static final int FC_STATUS_OF_LINK = 11;

  /**
   * The first-listed slave, which never answers (its bring-up is what could monopolize the bus).
   */
  private static final int SLAVE_DEAD = 1;

  /** The second-listed slave, which answers promptly and must keep being serviced. */
  private static final int SLAVE_HEALTHY = 2;

  private ManualScheduler scheduler;
  private RecordingOutput output;
  private RecordingEvents events;

  @BeforeEach
  void setUp() {
    scheduler = new ManualScheduler();
    output = new RecordingOutput();
    events = new RecordingEvents();
  }

  private UnbalancedMasterEngine newMaster(LinkSettings settings) {
    return new UnbalancedMasterEngine(
        settings, scheduler, output, events, 0, OutboundQueuePolicy.DROP_OLDEST);
  }

  /**
   * With the dead slave listed first, the master must not spend the dead slave's whole retransmit
   * budget before the healthy slave is brought up and serviced. The healthy slave is brought up off
   * the bus the dead slave's first probe releases, gets a queued command delivered, and the dead
   * slave is left mid-bring-up (still UNRESET, never marked ERROR) throughout.
   */
  @Test
  void deadSlaveBringUpDoesNotStarveBringUpAndServiceOfHealthySlave() {
    LinkSettings settings =
        LinkSettings.unbalanced()
            .slaveAddresses(List.of(SLAVE_DEAD, SLAVE_HEALTHY))
            .pollInterval(Duration.ofMillis(100_000)) // keep the poll cadence out of the way
            .build();
    UnbalancedMasterEngine master = newMaster(settings);

    master.onConnected();
    master.startDataTransfer();

    // The first bring-up turn goes to the first configured slave (the dead one).
    assertEquals(1, output.frames().size());
    assertFixedPrimary(output.frames().get(0), FC_REQUEST_STATUS_OF_LINK, SLAVE_DEAD);

    // The dead slave never answers. On its first confirm timeout the bus is RELEASED to the next
    // slave instead of being held for the dead slave's whole retransmit budget, so the healthy
    // slave
    // gets its own request-status-of-link (FC9). (Before the fix the master would have
    // retransmitted
    // FC9 to the dead slave here.)
    scheduler.advance(CONFIRM_MILLIS, TimeUnit.MILLISECONDS);
    assertEquals(2, output.frames().size());
    assertFixedPrimary(output.frames().get(1), FC_REQUEST_STATUS_OF_LINK, SLAVE_HEALTHY);

    // Bring the healthy slave fully up (FC11 -> FC0 -> ack). After the ack the master re-probes the
    // still-not-available dead slave (lowest-priority bring-up), confirming the dead slave is being
    // worked, not abandoned.
    master.onFrame(secondaryFixed(FC_STATUS_OF_LINK, SLAVE_HEALTHY));
    assertEquals(3, output.frames().size());
    assertFixedPrimary(output.frames().get(2), FC_RESET_REMOTE_LINK, SLAVE_HEALTHY);

    master.onFrame(secondaryFixed(FC_ACK, SLAVE_HEALTHY));
    assertEquals(4, output.frames().size());
    assertFixedPrimary(output.frames().get(3), FC_REQUEST_STATUS_OF_LINK, SLAVE_DEAD);

    // A command for the now-available healthy slave is queued while the bus is busy probing the
    // dead
    // slave. It must reach the healthy slave promptly — not wait for the dead slave to exhaust its
    // budget and reach ERROR.
    output.clear();
    master.sendAsdu(command(SLAVE_HEALTHY, 50));
    assertEquals(
        0, output.frames().size(), "the command is queued while the bus probes the dead slave");
    assertEquals(1, master.pendingSendCount());

    // The dead slave's next probe times out; because there is now serviceable work for an available
    // slave, the bus is released and the command is delivered to the healthy slave as user data.
    scheduler.advance(CONFIRM_MILLIS, TimeUnit.MILLISECONDS);
    assertEquals(1, output.frames().size());
    assertUserData(output.frames().get(0), SLAVE_HEALTHY);
    assertEquals(0, master.pendingSendCount(), "the command was delivered, not left queued");

    // The dead slave was never marked ERROR while the healthy slave was being serviced: prove it is
    // still UNRESET by acknowledging the healthy command (freeing the bus, which re-probes the dead
    // slave) and then answering the dead slave's status request, which advances it to FC0 — a
    // transition only a slave still in bring-up makes.
    output.clear();
    master.onFrame(new Ft12Frame.SingleChar()); // ack the healthy command
    assertEquals(1, output.frames().size());
    assertFixedPrimary(output.frames().get(0), FC_REQUEST_STATUS_OF_LINK, SLAVE_DEAD);

    master.onFrame(secondaryFixed(FC_STATUS_OF_LINK, SLAVE_DEAD));
    assertEquals(2, output.frames().size());
    assertFixedPrimary(output.frames().get(1), FC_RESET_REMOTE_LINK, SLAVE_DEAD);

    assertEquals(
        0, events.closedCount(), "a per-slave bring-up timeout must not close the session");
  }

  /**
   * When no other work competes for the bus, a lone unresponsive slave is still retransmitted in
   * place on the confirm/repeat cadence and degraded to the error state after the retry budget is
   * exhausted, exactly as before — the fairness change must not weaken the eventual ERROR marking.
   */
  @Test
  void aLoneUnresponsiveSlaveIsStillRetriedOnTheRepeatCadenceAndMarkedError() {
    LinkSettings settings =
        LinkSettings.unbalanced()
            .slaveAddresses(List.of(SLAVE_DEAD))
            .pollInterval(Duration.ofMillis(100_000)) // keep the poll cadence out of the way
            .build(); // default maxRetries == 3
    UnbalancedMasterEngine master = newMaster(settings);

    master.onConnected();
    master.startDataTransfer();
    assertEquals(1, output.frames().size());
    assertFixedPrimary(output.frames().get(0), FC_REQUEST_STATUS_OF_LINK, SLAVE_DEAD);

    // With nothing else needing the bus, the lone slave is retransmitted in place: the first repeat
    // at the confirm timeout, then one per repeat timeout up to maxRetries.
    scheduler.advance(CONFIRM_MILLIS, TimeUnit.MILLISECONDS); // 1st retransmission
    scheduler.advance(REPEAT_MILLIS, TimeUnit.MILLISECONDS); // 2nd
    scheduler.advance(REPEAT_MILLIS, TimeUnit.MILLISECONDS); // 3rd
    assertEquals(4, output.frames().size(), "the initial FC9 plus maxRetries retransmissions");
    for (Ft12Frame frame : output.frames()) {
      assertFixedPrimary(frame, FC_REQUEST_STATUS_OF_LINK, SLAVE_DEAD);
    }

    // The final repeat exhausts the budget and degrades the slave; the bus and session stay open.
    scheduler.advance(REPEAT_MILLIS, TimeUnit.MILLISECONDS);
    assertEquals(
        0, events.closedCount(), "a per-slave bring-up timeout must not close the session");

    // A command for the now-degraded slave is answered locally with a negative confirmation, which
    // proves the slave reached ERROR.
    Asdu command = command(SLAVE_DEAD, 9);
    master.sendAsdu(command);
    assertEquals(4, output.frames().size(), "a command for the degraded slave is not framed");
    assertEquals(1, events.asdus().size(), "the rejection is delivered through onAsdu");
    Asdu rejection = events.asdus().get(0);
    assertTrue(rejection.negative(), "the rejection sets the P/N bit");
    assertEquals(Cause.UNKNOWN_COMMON_ADDRESS, rejection.cause());
  }

  // --- Fixtures --------------------------------------------------------------------------------

  /**
   * Asserts that the frame is a primary fixed-length frame with the given function code and link
   * address.
   *
   * @param frame the emitted frame.
   * @param functionCode the expected primary function code.
   * @param linkAddress the expected link address.
   */
  private static void assertFixedPrimary(Ft12Frame frame, int functionCode, int linkAddress) {
    Ft12Frame.FixedLength fixed = assertInstanceOf(Ft12Frame.FixedLength.class, frame);
    assertTrue(fixed.control().prm(), "expected a primary frame");
    assertEquals(functionCode, fixed.control().functionCode());
    assertEquals(linkAddress, fixed.linkAddress());
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
