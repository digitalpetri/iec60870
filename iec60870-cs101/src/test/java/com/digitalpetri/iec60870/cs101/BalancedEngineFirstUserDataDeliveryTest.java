package com.digitalpetri.iec60870.cs101;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import com.digitalpetri.iec60870.test.common.ManualScheduler;
import com.digitalpetri.iec60870.test.common.RecordingEvents;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Regression tests for {@link BalancedEngine}'s secondary handling of an inbound FCV=1 user-data
 * frame, covering both the link-reset gate and the first post-reset frame's FCB classification.
 *
 * <p>FT1.2 gates user-data transfer behind the link-reset handshake (the serial analog of {@code
 * STARTDT}). A peer that sends a send/confirm user-data frame <em>before</em> resetting the link
 * has not established the secondary frame count bit (FCB) sequence, so the engine cannot
 * distinguish a fresh send from a replay. It must reject such a frame — delivering no ASDU and
 * emitting no positive acknowledgement — rather than accepting it leniently, which would let an
 * adversarial or malformed peer bypass the link-reset state machine and inject application data.
 *
 * <p>Once the link has been reset, the engine must treat the first FCV=1 frame as new and deliver
 * the carried ASDU even when its FCB equals the post-reset default {@code expectedFcb} (rather than
 * misclassifying it as a retransmission and silently discarding the application data). A frame is a
 * retransmission only when a prior response is actually cached to replay.
 *
 * <p>Behavior is driven by feeding inbound frames through {@link BalancedEngine#onFrame(Ft12Frame)}
 * and observing the {@link RecordingOutput} frames and {@link RecordingEvents} callbacks; the
 * {@link ManualScheduler} only satisfies the construction contract.
 */
class BalancedEngineFirstUserDataDeliveryTest {

  /**
   * Balanced parameters with the single-character ack disabled, so every positive acknowledgement
   * is an explicit secondary FC0 fixed-length frame that is straightforward to assert on. Link
   * address 1 is the balanced default.
   */
  private static final LinkSettings SETTINGS =
      LinkSettings.balanced().useSingleCharAck(false).build();

  private static final int LINK_ADDRESS = 1;

  // Primary (PRM=1) function codes the peer (a controlling CLIENT, DIR=1) sends.
  private static final int FC_RESET_REMOTE_LINK = 0;
  private static final int FC_SEND_CONFIRM_USER_DATA = 3;

  // Secondary (PRM=0) function codes the engine emits in response.
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

  /**
   * A SERVER station: the controlled peer that reaches the available state by receiving the
   * controlling station's reset-of-remote-link, which makes the pre-reset gate observable.
   */
  private BalancedEngine newEngine() {
    return new BalancedEngine(
        Ft12LinkLayer.Role.SERVER,
        SETTINGS,
        scheduler,
        output,
        events,
        0,
        OutboundQueuePolicy.DROP_OLDEST);
  }

  @Test
  void userDataBeforeLinkResetIsRejectedNotDelivered() {
    BalancedEngine engine = newEngine();
    engine.onConnected();

    // The peer sends user data without first resetting the link — a link-reset state-machine
    // bypass. The gate must hold: no ASDU is delivered, the data transfer state is never raised,
    // and a not-reset secondary makes no reply (no positive ack, and no frame at all).
    Asdu command = commandAsdu(10);
    engine.onFrame(primaryUserData(false, command));

    assertTrue(events.asdus().isEmpty(), "user data before a link reset must not be delivered");
    assertTrue(
        events.dataTransferChanges().isEmpty(), "the link never became available without a reset");
    assertTrue(
        output.frames().isEmpty(), "a not-reset secondary makes no reply to pre-reset user data");
  }

  @Test
  void firstUserDataFrameAfterResetWithDefaultFcbIsDeliveredNotDroppedAsRetransmission() {
    BalancedEngine engine = newEngine();
    engine.onConnected();

    // Bring the link up: the peer's reset-of-remote-link makes it available and the engine acks.
    engine.onFrame(primaryReset());
    assertEquals(List.of(Boolean.TRUE), events.dataTransferChanges());
    int framesAfterReset = output.frames().size();

    // The peer's first FCV=1 send/confirm frame carries FCB=false, which equals the post-reset
    // default expectedFcb. With no response cached, this is a NEW frame: the ASDU must be delivered
    // and the frame positively acknowledged.
    Asdu command = commandAsdu(10);
    engine.onFrame(primaryUserData(false, command));

    assertEquals(List.of(command), events.asdus(), "the first user-data ASDU must be delivered");
    assertEquals(framesAfterReset + 1, output.frames().size());
    Ft12Frame.FixedLength ack = lastFixed();
    assertFalse(ack.control().prm(), "the acknowledgement is a secondary frame");
    assertEquals(
        FC_ACK, ack.control().functionCode(), "the first frame is positively acknowledged");
    assertEquals(LINK_ADDRESS, ack.linkAddress());
  }

  @Test
  void retransmissionOfTheFirstFrameAfterResetReplaysWithoutRedelivery() {
    BalancedEngine engine = newEngine();
    engine.onConnected();
    engine.onFrame(primaryReset());
    int framesAfterReset = output.frames().size();

    // First FCV=1 frame (FCB=false): delivered and acknowledged, the ack now cached.
    Asdu command = commandAsdu(20);
    engine.onFrame(primaryUserData(false, command));
    assertEquals(List.of(command), events.asdus());
    assertEquals(framesAfterReset + 1, output.frames().size());

    // A genuine retransmission carries the same unchanged FCB=false; now that a response is cached
    // it is a replay: the cached ack is re-sent and the ASDU is NOT delivered a second time.
    engine.onFrame(primaryUserData(false, command));
    assertEquals(1, events.asdus().size(), "a retransmission must not be delivered a second time");
    assertEquals(framesAfterReset + 2, output.frames().size());
    assertSame(
        output.frames().get(framesAfterReset),
        output.frames().get(framesAfterReset + 1),
        "a retransmission replays the cached response instance");

    // A toggled FCB=true is a genuinely new frame: deliver the next ASDU and acknowledge it.
    Asdu next = commandAsdu(21);
    engine.onFrame(primaryUserData(true, next));
    assertEquals(List.of(command, next), events.asdus());
    assertEquals(framesAfterReset + 3, output.frames().size());
  }

  // --- Fixtures --------------------------------------------------------------------------------

  /** Builds an inbound FCV=1 send/confirm user-data frame from the controlling peer (DIR=1). */
  private static Ft12Frame primaryUserData(boolean fcb, Asdu asdu) {
    return new Ft12Frame.Variable(
        LinkControlField.primary(true, fcb, true, FC_SEND_CONFIRM_USER_DATA), LINK_ADDRESS, asdu);
  }

  /** An inbound reset-of-remote-link (FC0, FCV=0) primary frame from the controlling peer. */
  private static Ft12Frame primaryReset() {
    return new Ft12Frame.FixedLength(
        LinkControlField.primary(true, false, false, FC_RESET_REMOTE_LINK), LINK_ADDRESS);
  }

  /** Returns the most recently emitted fixed-length frame. */
  private Ft12Frame.FixedLength lastFixed() {
    for (int i = output.frames().size() - 1; i >= 0; i--) {
      if (output.frames().get(i) instanceof Ft12Frame.FixedLength fixed) {
        return fixed;
      }
    }
    throw new AssertionError("no fixed-length frame was emitted");
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
