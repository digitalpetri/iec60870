package com.digitalpetri.iec60870.cs104;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.digitalpetri.iec60870.ProtocolTimeoutException;
import com.digitalpetri.iec60870.address.CommonAddress;
import com.digitalpetri.iec60870.address.InformationObjectAddress;
import com.digitalpetri.iec60870.address.OriginatorAddress;
import com.digitalpetri.iec60870.asdu.Asdu;
import com.digitalpetri.iec60870.asdu.AsduType;
import com.digitalpetri.iec60870.asdu.Cause;
import com.digitalpetri.iec60870.asdu.object.ReadCommand;
import com.digitalpetri.iec60870.test.common.ManualScheduler;
import com.digitalpetri.iec60870.test.common.RecordingEvents;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class ApciSessionStopDataTransferTest {

  @Test
  void serverAcknowledgesReceivedFramesBeforeStopConfirmation() {
    var server = new Peer(ApciSession.Role.SERVER);
    server.start();
    server.session.onApdu(iFrame(0, 0, 1));

    server.session.onApdu(uFrame(UFunction.STOPDT_ACT));

    assertEquals(List.of(sFrame(1), uFrame(UFunction.STOPDT_CON)), server.output);
    assertFalse(server.session.isDataTransferStarted());
    server.scheduler.advance(10, TimeUnit.SECONDS);
    assertEquals(
        2, server.output.size(), "the pending t2 acknowledgement was discharged by STOPDT");
    assertNull(server.events.lastCloseCause());
  }

  @Test
  void serverWaitsForEverySentFrameBeforeConfirmingStopAndKeepsBacklogQueued() {
    var server = new Peer(ApciSession.Role.SERVER);
    server.start();
    int k = ApciSettings.defaults().k().intValue();
    for (int n = 0; n <= k; n++) {
      server.session.sendAsdu(asdu(n));
    }
    server.output.clear();

    server.session.onApdu(uFrame(UFunction.STOPDT_ACT));
    assertTrue(
        server.output.isEmpty(), "STOPDT con must wait for the sent I-frame acknowledgements");
    assertFalse(server.session.isDataTransferStarted());
    assertEquals(List.of(true, false), server.events.dataTransferChanges());

    server.session.onApdu(sFrame(k - 1));
    server.session.onApdu(uFrame(UFunction.STOPDT_ACT));
    assertTrue(
        server.output.isEmpty(),
        "a partial acknowledgement or repeated STOPDT must not finish the drain");
    assertEquals(1, server.session.pendingSendCount());

    server.session.onApdu(sFrame(k));
    assertEquals(List.of(uFrame(UFunction.STOPDT_CON)), server.output);
    assertEquals(List.of(true, false), server.events.dataTransferChanges());
    assertEquals(1, server.session.pendingSendCount());
    server.scheduler.advance(15, TimeUnit.SECONDS);
    assertNull(server.events.lastCloseCause(), "acknowledging the last I-frame must discharge t1");

    server.session.onApdu(uFrame(UFunction.STARTDT_ACT));
    assertEquals(
        List.of(uFrame(UFunction.STOPDT_CON), uFrame(UFunction.STARTDT_CON), iFrame(k, 0, k)),
        server.output);
  }

  @Test
  void serverRetainsAcknowledgementTimeoutWhileStopIsPending() {
    var server = new Peer(ApciSession.Role.SERVER);
    server.start();
    server.session.sendAsdu(asdu(1));
    server.output.clear();

    server.session.onApdu(uFrame(UFunction.STOPDT_ACT));
    server.scheduler.advance(15, TimeUnit.SECONDS);

    assertInstanceOf(ProtocolTimeoutException.class, server.events.lastCloseCause());
    assertTrue(server.output.isEmpty(), "an unacknowledged stop must not be confirmed");
  }

  @Test
  void serverDoesNotRestartWhileStopAcknowledgementsArePending() {
    var server = new Peer(ApciSession.Role.SERVER);
    server.start();
    server.session.sendAsdu(asdu(1));
    server.output.clear();
    server.session.onApdu(uFrame(UFunction.STOPDT_ACT));

    server.session.onApdu(uFrame(UFunction.STARTDT_ACT));
    server.session.sendAsdu(asdu(2));

    assertTrue(server.output.isEmpty());
    assertFalse(server.session.isDataTransferStarted());
    server.session.onApdu(sFrame(1));
    assertEquals(List.of(uFrame(UFunction.STOPDT_CON)), server.output);
  }

  @Test
  void clientHoldsQueuedAndNewFramesFromStopActivationUntilRestartConfirmation() {
    var client = new Peer(ApciSession.Role.CLIENT);
    client.start();
    int k = ApciSettings.defaults().k().intValue();
    for (int n = 0; n <= k; n++) {
      client.session.sendAsdu(asdu(n));
    }
    client.output.clear();

    CompletableFuture<Void> stop = client.session.stopDataTransfer().toCompletableFuture();
    assertSame(stop, client.session.stopDataTransfer());
    client.session.onApdu(sFrame(1));
    client.session.sendAsdu(asdu(k + 1));
    assertEquals(List.of(uFrame(UFunction.STOPDT_ACT)), client.output);
    assertEquals(2, client.session.pendingSendCount());
    assertFalse(stop.isDone());

    client.session.onApdu(sFrame(k));
    client.session.onApdu(uFrame(UFunction.STOPDT_CON));
    client.session.sendAsdu(asdu(k + 2));
    assertTrue(stop.isDone());
    assertFalse(stop.isCompletedExceptionally());
    assertEquals(List.of(uFrame(UFunction.STOPDT_ACT)), client.output);
    assertEquals(3, client.session.pendingSendCount());

    client.session.startDataTransfer();
    assertEquals(
        List.of(uFrame(UFunction.STOPDT_ACT), uFrame(UFunction.STARTDT_ACT)), client.output);
    client.session.onApdu(uFrame(UFunction.STARTDT_CON));
    assertEquals(
        List.of(
            uFrame(UFunction.STOPDT_ACT),
            uFrame(UFunction.STARTDT_ACT),
            iFrame(k, 0, k),
            iFrame(k + 1, 0, k + 1),
            iFrame(k + 2, 0, k + 2)),
        client.output);
    assertEquals(0, client.session.pendingSendCount());
    assertNull(client.events.lastCloseCause());
  }

  @Test
  void clientAcknowledgesReceivedFramesBeforeStopAndInFlightFramesImmediately() {
    var client = new Peer(ApciSession.Role.CLIENT);
    client.start();
    client.session.onApdu(iFrame(0, 0, 1));

    client.session.stopDataTransfer();
    assertEquals(List.of(sFrame(1), uFrame(UFunction.STOPDT_ACT)), client.output);
    client.session.onApdu(iFrame(1, 0, 2));
    assertEquals(List.of(sFrame(1), uFrame(UFunction.STOPDT_ACT), sFrame(2)), client.output);
    assertEquals(List.of(asdu(1), asdu(2)), client.events.asdus());
    assertNull(client.events.lastCloseCause());
  }

  @Test
  void peersDrainBothDirectionsWhenStopCrossesAnInFlightFrame() {
    var client = new Peer(ApciSession.Role.CLIENT);
    var server = new Peer(ApciSession.Role.SERVER);
    client.start();
    server.start();
    client.session.sendAsdu(asdu(1));
    server.session.onApdu(client.output.remove(0));
    server.session.sendAsdu(asdu(2));
    Apdu inFlight = server.output.remove(0);

    CompletableFuture<Void> stop = client.session.stopDataTransfer().toCompletableFuture();
    server.session.onApdu(client.output.remove(0));
    assertTrue(
        server.output.isEmpty(), "the server must await acknowledgement of its in-flight frame");

    client.session.onApdu(inFlight);
    assertEquals(List.of(sFrame(1)), client.output);
    server.session.onApdu(client.output.remove(0));
    assertEquals(List.of(uFrame(UFunction.STOPDT_CON)), server.output);
    client.session.onApdu(server.output.remove(0));

    assertTrue(stop.isDone());
    assertFalse(stop.isCompletedExceptionally());
    assertFalse(client.session.isDataTransferStarted());
    assertFalse(server.session.isDataTransferStarted());
    assertEquals(List.of(asdu(2)), client.events.asdus());
    assertEquals(List.of(asdu(1)), server.events.asdus());
    client.scheduler.advance(15, TimeUnit.SECONDS);
    server.scheduler.advance(15, TimeUnit.SECONDS);
    assertNull(client.events.lastCloseCause());
    assertNull(server.events.lastCloseCause());
    assertTrue(client.output.isEmpty());
    assertTrue(server.output.isEmpty());
  }

  @Test
  void reconnectClearsAnUnfinishedServerStop() {
    var server = new Peer(ApciSession.Role.SERVER);
    server.start();
    server.session.sendAsdu(asdu(1));
    server.session.onApdu(uFrame(UFunction.STOPDT_ACT));
    server.session.close();
    server.session.onConnected();
    server.start();

    server.session.sendAsdu(asdu(2));
    assertEquals(List.of(iFrame(0, 0, 2)), server.output);
    assertNull(server.events.lastCloseCause());
  }

  private static Asdu asdu(int ioa) {
    return new Asdu(
        AsduType.C_RD_NA_1,
        false,
        Cause.REQUEST,
        false,
        false,
        OriginatorAddress.none(),
        CommonAddress.of(1),
        List.of(new ReadCommand(InformationObjectAddress.of(ioa))));
  }

  private static Apdu iFrame(int ns, int nr, int ioa) {
    return new Apdu(new ControlField.TypeI(ns, nr), asdu(ioa));
  }

  private static Apdu sFrame(int nr) {
    return new Apdu(new ControlField.TypeS(nr), null);
  }

  private static Apdu uFrame(UFunction function) {
    return new Apdu(new ControlField.TypeU(function), null);
  }

  private static final class Peer {
    private final ApciSession.Role role;
    private final ManualScheduler scheduler = new ManualScheduler();
    private final List<Apdu> output = new ArrayList<>();
    private final RecordingEvents events = new RecordingEvents();
    private final ApciSession session;

    private Peer(ApciSession.Role role) {
      this.role = role;
      session = new ApciSession(role, ApciSettings.defaults(), scheduler, output::add, events);
      session.onConnected();
    }

    private void start() {
      if (role == ApciSession.Role.CLIENT) {
        session.startDataTransfer();
        session.onApdu(uFrame(UFunction.STARTDT_CON));
      } else {
        session.onApdu(uFrame(UFunction.STARTDT_ACT));
      }
      output.clear();
    }
  }
}
