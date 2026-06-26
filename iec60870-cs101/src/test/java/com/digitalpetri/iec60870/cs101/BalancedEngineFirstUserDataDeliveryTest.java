package com.digitalpetri.iec60870.cs101;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;

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
 * Regression tests for {@link BalancedEngine}'s secondary handling of the <em>first</em> FCV=1
 * user-data frame.
 *
 * <p>A balanced peer that sends user data before (or without) a standard link reset — because the
 * reset frame was lost, or the peer starts its frame count bit at {@code 0} — sends its first FCV=1
 * send/confirm frame with {@code FCB=false}, which equals the engine's default {@code expectedFcb}.
 * The engine must treat that first frame as new and deliver the carried ASDU rather than
 * misclassifying it as a retransmission (which would acknowledge the peer while silently discarding
 * the application data). A frame is a retransmission only when a prior response is actually cached
 * to replay, mirroring {@code UnbalancedSlaveEngine.isRetransmission()}.
 *
 * <p>Behavior is driven by feeding inbound primary frames through {@link
 * BalancedEngine#onFrame(Ft12Frame)} and observing the {@link RecordingOutput} frames and {@link
 * RecordingEvents} callbacks; the {@link ManualScheduler} only satisfies the construction contract.
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

  // Primary (PRM=1) function code the peer sends for send/confirm user data (FCV=1).
  private static final int FC_SEND_CONFIRM_USER_DATA = 3;

  // Secondary (PRM=0) positive-acknowledgement function code the engine emits in response.
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

  private BalancedEngine newEngine() {
    return new BalancedEngine(
        Ft12LinkLayer.Role.CLIENT,
        SETTINGS,
        scheduler,
        output,
        events,
        0,
        OutboundQueuePolicy.DROP_OLDEST);
  }

  @Test
  void firstUserDataFrameWithDefaultFcbIsDeliveredNotDroppedAsRetransmission() {
    BalancedEngine engine = newEngine();
    engine.onConnected();

    // The peer never reset the link, so its first FCV=1 send/confirm frame carries FCB=false, which
    // equals the engine's default expectedFcb. With no response cached, this is a NEW frame: the
    // ASDU must be delivered and the frame positively acknowledged.
    Asdu command = commandAsdu(10);
    engine.onFrame(primaryUserData(false, command));

    assertEquals(List.of(command), events.asdus(), "the first user-data ASDU must be delivered");
    assertEquals(1, output.frames().size());
    Ft12Frame.FixedLength ack =
        assertInstanceOf(Ft12Frame.FixedLength.class, output.frames().get(0));
    assertFalse(ack.control().prm(), "the acknowledgement is a secondary frame");
    assertEquals(
        FC_ACK, ack.control().functionCode(), "the first frame is positively acknowledged");
    assertEquals(LINK_ADDRESS, ack.linkAddress());
  }

  @Test
  void retransmissionOfTheFirstFrameReplaysWithoutRedelivery() {
    BalancedEngine engine = newEngine();
    engine.onConnected();

    // First FCV=1 frame (FCB=false): delivered and acknowledged, the ack now cached.
    Asdu command = commandAsdu(20);
    engine.onFrame(primaryUserData(false, command));
    assertEquals(List.of(command), events.asdus());
    assertEquals(1, output.frames().size());

    // A genuine retransmission carries the same unchanged FCB=false; now that a response is cached
    // it is a replay: the cached ack is re-sent and the ASDU is NOT delivered a second time.
    engine.onFrame(primaryUserData(false, command));
    assertEquals(1, events.asdus().size(), "a retransmission must not be delivered a second time");
    assertEquals(2, output.frames().size());
    assertSame(
        output.frames().get(0),
        output.frames().get(1),
        "a retransmission replays the cached response instance");

    // A toggled FCB=true is a genuinely new frame: deliver the next ASDU and acknowledge it.
    Asdu next = commandAsdu(21);
    engine.onFrame(primaryUserData(true, next));
    assertEquals(List.of(command, next), events.asdus());
    assertEquals(3, output.frames().size());
  }

  // --- Fixtures --------------------------------------------------------------------------------

  /** Builds an inbound FCV=1 send/confirm user-data frame from the peer with the given FCB. */
  private static Ft12Frame primaryUserData(boolean fcb, Asdu asdu) {
    return new Ft12Frame.Variable(
        LinkControlField.primary(false, fcb, true, FC_SEND_CONFIRM_USER_DATA), LINK_ADDRESS, asdu);
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
