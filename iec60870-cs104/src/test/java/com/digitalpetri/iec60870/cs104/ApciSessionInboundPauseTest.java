package com.digitalpetri.iec60870.cs104;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.digitalpetri.iec60870.ProtocolTimeoutException;
import com.digitalpetri.iec60870.address.CommonAddress;
import com.digitalpetri.iec60870.address.InformationObjectAddress;
import com.digitalpetri.iec60870.address.OriginatorAddress;
import com.digitalpetri.iec60870.asdu.Asdu;
import com.digitalpetri.iec60870.asdu.AsduType;
import com.digitalpetri.iec60870.asdu.Cause;
import com.digitalpetri.iec60870.asdu.element.Qds;
import com.digitalpetri.iec60870.asdu.object.MeasuredValueShortFloat;
import com.digitalpetri.iec60870.test.common.ManualScheduler;
import com.digitalpetri.iec60870.test.common.RecordingEvents;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class ApciSessionInboundPauseTest {

  private final ManualScheduler scheduler = new ManualScheduler();
  private final RecordingEvents events = new RecordingEvents();
  private final List<Apdu> sent = new ArrayList<>();
  private final List<Boolean> readPaused = new ArrayList<>();
  private final ApciSession session =
      new ApciSession(
          ApciSession.Role.CLIENT,
          ApciSettings.defaults(),
          scheduler,
          new ApciSession.Output() {
            @Override
            public void send(Apdu apdu) {
              sent.add(apdu);
            }

            @Override
            public void setReadPaused(boolean paused) {
              readPaused.add(paused);
            }
          },
          events);

  @Test
  void pauseAndResumeReachTheTransport() {
    start();
    session.setInboundPaused(true);
    session.setInboundPaused(false);

    assertEquals(List.of(true, false), readPaused);
  }

  @Test
  void resumeBeforeT1KeepsSessionOpen() {
    start();
    session.setInboundPaused(true);
    advance(14);
    session.setInboundPaused(false);

    advance(5);
    assertNull(events.lastCloseCause());
  }

  @Test
  void pauseLastingT1ClosesWithTimeout() {
    start();
    session.setInboundPaused(true);
    advance(14);
    assertNull(events.lastCloseCause());

    advance(1);
    ProtocolTimeoutException cause =
        assertInstanceOf(ProtocolTimeoutException.class, events.lastCloseCause());
    assertTrue(cause.getMessage().contains("paused"), cause.getMessage());
  }

  @Test
  void reconnectCancelsPauseDeadline() {
    start();
    session.setInboundPaused(true);
    advance(10);
    start();

    advance(10);
    assertNull(events.lastCloseCause());
  }

  @Test
  void framesReadWhilePausedAreStillAcknowledgedWithinW() {
    start();
    session.setInboundPaused(true);
    int before = sent.size();

    for (int i = 0; i < 8; i++) {
      session.onApdu(new Apdu(new ControlField.TypeI(i, 0), measured(i)));
    }

    assertEquals(8, events.asdus().size());
    assertEquals(List.of(new ControlField.TypeS(8)), controls(sent.subList(before, sent.size())));
  }

  @Test
  void framesReadWhilePausedAreStillAcknowledgedWithinT2() {
    start();
    session.setInboundPaused(true);
    session.onApdu(new Apdu(new ControlField.TypeI(0, 0), measured(0)));
    int before = sent.size();

    advance(10);

    assertEquals(List.of(new ControlField.TypeS(1)), controls(sent.subList(before, sent.size())));
  }

  private static List<ControlField> controls(List<Apdu> apdus) {
    List<ControlField> controls = new ArrayList<>();
    for (Apdu apdu : apdus) {
      controls.add(apdu.control());
    }
    return controls;
  }

  private static Asdu measured(int value) {
    return new Asdu(
        AsduType.M_ME_NC_1,
        false,
        Cause.SPONTANEOUS,
        false,
        false,
        OriginatorAddress.none(),
        CommonAddress.of(1),
        List.of(
            new MeasuredValueShortFloat(
                InformationObjectAddress.of(100),
                value,
                new Qds(false, false, false, false, false))));
  }

  private void start() {
    session.onConnected();
    session.startDataTransfer();
    session.onApdu(new Apdu(new ControlField.TypeU(UFunction.STARTDT_CON), null));
  }

  private void advance(int seconds) {
    scheduler.advance(seconds, TimeUnit.SECONDS);
  }
}
