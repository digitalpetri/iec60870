package com.digitalpetri.iec60870.cs104;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.digitalpetri.iec60870.ProtocolProfile;
import com.digitalpetri.iec60870.address.CommonAddress;
import com.digitalpetri.iec60870.address.InformationObjectAddress;
import com.digitalpetri.iec60870.address.OriginatorAddress;
import com.digitalpetri.iec60870.asdu.Asdu;
import com.digitalpetri.iec60870.asdu.AsduType;
import com.digitalpetri.iec60870.asdu.Cause;
import com.digitalpetri.iec60870.asdu.InformationObject;
import com.digitalpetri.iec60870.asdu.element.Qds;
import com.digitalpetri.iec60870.asdu.object.SinglePointInformation;
import com.digitalpetri.iec60870.session.Session;
import com.digitalpetri.iec60870.test.common.ManualScheduler;
import com.digitalpetri.iec60870.test.common.RecordingClientTransport;
import com.digitalpetri.iec60870.test.common.RecordingEvents;
import com.digitalpetri.iec60870.transport.TransportListener;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.UnpooledByteBufAllocator;
import java.util.Collections;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import org.joou.UShort;
import org.junit.jupiter.api.Test;

class ApciTrackedSendEncodingTest {
  private static final ProtocolProfile PROFILE = ProtocolProfile.iec104Default();

  @Test
  void immediateVoidEncodingFailureThrowsAndLeavesSessionUsable() {
    try (var harness = new Harness()) {
      assertThrows(IllegalArgumentException.class, () -> harness.session.sendAsdu(asdu(100)));
      harness.assertNoOutstandingWrite();
      harness.session.sendAsdu(asdu(1));
      harness.assertWrittenSequence(0, 0);
    }
  }

  @Test
  void immediateAsyncEncodingFailureFailsOnlyThatSubmission() {
    try (var harness = new Harness()) {
      CompletableFuture<Void> failed =
          assertDoesNotThrow(() -> harness.session.sendAsduAsync(asdu(100)).toCompletableFuture());
      assertEncodingFailure(failed);
      harness.assertNoOutstandingWrite();
      CompletableFuture<Void> valid = harness.session.sendAsduAsync(asdu(1)).toCompletableFuture();
      assertTrue(valid.isDone());
      assertFalse(valid.isCompletedExceptionally());
      harness.assertWrittenSequence(0, 0);
    }
  }

  @Test
  void queuedEncodingFailureDoesNotBlockTheFollowingValidWrite() {
    try (var harness = new Harness()) {
      harness.session.sendAsdu(asdu(1));
      CompletableFuture<Void> oversized =
          harness.session.sendAsduAsync(asdu(100)).toCompletableFuture();
      CompletableFuture<Void> valid = harness.session.sendAsduAsync(asdu(1)).toCompletableFuture();
      assertFalse(oversized.isDone());
      assertFalse(valid.isDone());
      assertEquals(2, harness.session.pendingSendCount());

      harness.acknowledge(1);

      assertEncodingFailure(oversized);
      assertTrue(valid.isDone());
      assertFalse(valid.isCompletedExceptionally());
      assertEquals(0, harness.session.pendingSendCount());
      assertEquals(0, harness.events.closedCount());
      harness.assertWrittenSequence(1, 1);
      harness.acknowledge(2);
      harness.clock.advance(16, TimeUnit.SECONDS);
      assertEquals(
          0, harness.events.closedCount(), "rejected ASDU must not leave an acknowledgement timer");
    }
  }

  private static void assertEncodingFailure(CompletableFuture<Void> result) {
    assertTrue(result.isCompletedExceptionally());
    CompletionException failure = assertThrows(CompletionException.class, result::join);
    assertInstanceOf(IllegalArgumentException.class, failure.getCause());
  }

  private static Asdu asdu(int objectCount) {
    InformationObject object =
        new SinglePointInformation(
            InformationObjectAddress.of(1), true, new Qds(false, false, false, false, false));
    return new Asdu(
        AsduType.M_SP_NA_1,
        false,
        Cause.SPONTANEOUS,
        false,
        false,
        OriginatorAddress.none(),
        CommonAddress.of(1),
        Collections.nCopies(objectCount, object));
  }

  private static final class Harness implements AutoCloseable {
    private final RecordingClientTransport transport = new RecordingClientTransport();
    private final RecordingEvents events = new RecordingEvents();
    private final ManualScheduler clock = new ManualScheduler();
    private final Session session;

    Harness() {
      ApciSettings defaults = ApciSettings.defaults();
      var settings =
          new ApciSettings(
              UShort.valueOf(1),
              UShort.valueOf(1),
              defaults.t0(),
              defaults.t1(),
              defaults.t2(),
              defaults.t3());
      session = new Cs104Binding(settings, PROFILE).bindClient(transport, events, clock);
      session.onConnected();
    }

    void assertNoOutstandingWrite() {
      assertEquals(0, events.closedCount());
      assertTrue(transport.sent().isEmpty());
      assertEquals(0, session.pendingSendCount());
      assertEquals(1, clock.pendingTaskCount(), "only the idle timer should remain");
      clock.advance(16, TimeUnit.SECONDS);
      assertEquals(0, events.closedCount());
    }

    void assertWrittenSequence(int index, int sequence) {
      Apdu frame = ApduFramer.decode(PROFILE, transport.sent().get(index).duplicate());
      assertEquals(new ControlField.TypeI(sequence, 0), frame.control());
      assertEquals(asdu(1), frame.asdu());
    }

    void acknowledge(int sequence) {
      TransportListener listener = transport.listener();
      assertNotNull(listener);
      ByteBuf frame =
          ApduFramer.encode(
              new Apdu(new ControlField.TypeS(sequence), null),
              PROFILE,
              UnpooledByteBufAllocator.DEFAULT);
      try {
        assertDoesNotThrow(() -> listener.onFrame(frame));
      } finally {
        frame.release();
      }
    }

    @Override
    public void close() {
      session.close();
      transport.sent().forEach(ByteBuf::release);
    }
  }
}
