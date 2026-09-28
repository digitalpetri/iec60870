package com.digitalpetri.iec60870.cs104;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.digitalpetri.iec60870.ProtocolProfile;
import com.digitalpetri.iec60870.ProtocolTimeoutException;
import com.digitalpetri.iec60870.address.CommonAddress;
import com.digitalpetri.iec60870.address.InformationObjectAddress;
import com.digitalpetri.iec60870.address.OriginatorAddress;
import com.digitalpetri.iec60870.asdu.Asdu;
import com.digitalpetri.iec60870.asdu.AsduType;
import com.digitalpetri.iec60870.asdu.Cause;
import com.digitalpetri.iec60870.asdu.object.ReadCommand;
import com.digitalpetri.iec60870.session.Session;
import com.digitalpetri.iec60870.test.common.ManualScheduler;
import com.digitalpetri.iec60870.test.common.RecordingClientTransport;
import com.digitalpetri.iec60870.test.common.RecordingEvents;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.UnpooledByteBufAllocator;
import java.util.Collections;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ApciSessionSendFailureTest {

  private static final ProtocolProfile PROFILE = ProtocolProfile.iec104Default();

  private final ManualScheduler scheduler = new ManualScheduler();
  private final RecordingClientTransport transport = new RecordingClientTransport();
  private final RecordingEvents events = new RecordingEvents();
  private final Session session =
      new Cs104Binding(ApciSettings.defaults(), PROFILE).bindClient(transport, events, scheduler);

  @AfterEach
  void tearDown() {
    session.close();
    transport.sent().forEach(ByteBuf::release);
  }

  @Test
  void encodingFailureDoesNotLeaveAnAcknowledgementDeadline() {
    session.onConnected();
    assertThrows(IllegalArgumentException.class, () -> session.sendAsdu(asdu(100)));

    assertEquals(0, transport.sent().size(), "the oversized frame never reached the transport");
    assertEquals(1, scheduler.pendingTaskCount(), "only the idle timer remains");
    advance(15);
    assertNull(events.lastCloseCause());
  }

  @Test
  void encodingFailureDoesNotConsumeAcknowledgementOfNextSuccessfulSend() {
    session.onConnected();
    assertThrows(IllegalArgumentException.class, () -> session.sendAsdu(asdu(100)));
    advance(5);
    session.sendAsdu(asdu(1));

    Apdu sent = ApduFramer.decode(PROFILE, transport.sent().get(0).duplicate());
    assertEquals(new ControlField.TypeI(0, 0), sent.control());
    acknowledge(1);
    advance(15);

    assertNull(events.lastCloseCause(), "the only transmitted I-frame was acknowledged");
  }

  @Test
  void encodingFailurePreservesEarlierOutstandingDeadline() {
    session.onConnected();
    session.sendAsdu(asdu(1));
    advance(10);
    assertThrows(IllegalArgumentException.class, () -> session.sendAsdu(asdu(100)));

    advance(4);
    assertNull(events.lastCloseCause());
    advance(1);
    assertInstanceOf(ProtocolTimeoutException.class, events.lastCloseCause());
  }

  @Test
  void startOutputExceptionDoesNotLeaveAnAcknowledgementDeadline() {
    assertUFrameOutputExceptionCancelsDeadline(UFunction.STARTDT_ACT);
  }

  @Test
  void stopOutputExceptionDoesNotLeaveAnAcknowledgementDeadline() {
    assertUFrameOutputExceptionCancelsDeadline(UFunction.STOPDT_ACT);
  }

  @Test
  void testOutputExceptionDoesNotLeaveAnAcknowledgementDeadline() {
    assertUFrameOutputExceptionCancelsDeadline(UFunction.TESTFR_ACT);
  }

  private void assertUFrameOutputExceptionCancelsDeadline(UFunction activation) {
    ApciSession local =
        new ApciSession(
            ApciSession.Role.CLIENT,
            ApciSettings.defaults(),
            scheduler,
            apdu -> {
              if (apdu.control().equals(new ControlField.TypeU(activation))) {
                throw new IllegalArgumentException("output rejected the activation");
              }
            },
            events);
    local.onConnected();
    try {
      switch (activation) {
        case STARTDT_ACT -> assertThrows(IllegalArgumentException.class, local::startDataTransfer);
        case STOPDT_ACT -> {
          local.startDataTransfer();
          local.onApdu(new Apdu(new ControlField.TypeU(UFunction.STARTDT_CON), null));
          assertThrows(IllegalArgumentException.class, local::stopDataTransfer);
        }
        case TESTFR_ACT -> assertThrows(RuntimeException.class, () -> advance(20));
        default -> throw new AssertionError("not an activation: " + activation);
      }
      assertEquals(
          activation == UFunction.TESTFR_ACT ? 0 : 1,
          scheduler.pendingTaskCount(),
          "no t1 task remains for the rejected activation");
      advance(15);
      assertNull(events.lastCloseCause());
    } finally {
      local.close();
    }
  }

  private static Asdu asdu(int objectCount) {
    return new Asdu(
        AsduType.C_RD_NA_1,
        false,
        Cause.REQUEST,
        false,
        false,
        OriginatorAddress.none(),
        CommonAddress.of(1),
        Collections.nCopies(objectCount, new ReadCommand(InformationObjectAddress.of(1))));
  }

  private void acknowledge(int nr) {
    assertNotNull(transport.listener());
    ByteBuf frame =
        ApduFramer.encode(
            new Apdu(new ControlField.TypeS(nr), null), PROFILE, UnpooledByteBufAllocator.DEFAULT);
    try {
      transport.listener().onFrame(frame);
    } finally {
      frame.release();
    }
  }

  private void advance(int seconds) {
    scheduler.advance(seconds, TimeUnit.SECONDS);
  }
}
