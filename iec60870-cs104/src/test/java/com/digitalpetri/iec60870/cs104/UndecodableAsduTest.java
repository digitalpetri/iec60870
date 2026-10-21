package com.digitalpetri.iec60870.cs104;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.digitalpetri.iec60870.AsduDecodeException;
import com.digitalpetri.iec60870.OutboundQueuePolicy;
import com.digitalpetri.iec60870.ProtocolProfile;
import com.digitalpetri.iec60870.ProtocolStateException;
import com.digitalpetri.iec60870.SequenceNumberException;
import com.digitalpetri.iec60870.UndecodableAsduPolicy;
import com.digitalpetri.iec60870.UnsupportedAsduTypeException;
import com.digitalpetri.iec60870.asdu.Asdu;
import com.digitalpetri.iec60870.asdu.object.SinglePointWithCp56Time;
import com.digitalpetri.iec60870.asdu.time.Cp56Time2a;
import com.digitalpetri.iec60870.session.Session;
import com.digitalpetri.iec60870.test.common.ManualScheduler;
import com.digitalpetri.iec60870.test.common.RecordingClientTransport;
import com.digitalpetri.iec60870.test.common.RecordingEvents;
import com.digitalpetri.iec60870.test.common.RecordingServerConnection;
import com.digitalpetri.iec60870.transport.TransportListener;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.UnpooledByteBufAllocator;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * Tests the CS 104 handling of correctly framed I-frames whose ASDU cannot be decoded: the binding
 * decodes the APCI first, the session runs its sequence checks and acknowledges the frame, and the
 * octets are reported through {@link Session.Events#onUndecodableAsdu}. Framing errors and the
 * {@link UndecodableAsduPolicy#STRICT} policy still close the connection.
 *
 * <p>Payloads use the IEC 104 default profile (COT 2, CA 2, IOA 3 octets).
 */
class UndecodableAsduTest {

  private static final ProtocolProfile PROFILE = ProtocolProfile.iec104Default();
  private static final ByteBufAllocator ALLOC = UnpooledByteBufAllocator.DEFAULT;
  private static final long T1_MILLIS = ApciSettings.defaults().t1().toMillis();
  private static final long T2_MILLIS = ApciSettings.defaults().t2().toMillis();

  private static final String VALID_SINGLE_POINT = "01010300010064000001";

  /** The undecodable rows of the issue's trigger table, by label. */
  private static final Map<String, String> UNDECODABLE =
      Map.ofEntries(
          Map.entry("TypeID 136 (private)", "88010300010064000001"),
          Map.entry("TypeID 41 (reserved)", "29010300010064000001"),
          Map.entry("TypeID 120 F_FR_NA_1", "780103000100640000010203"),
          Map.entry("COT 48 (private)", "01013000010064000001"),
          Map.entry("COT 0 (unused)", "01010000010064000001"),
          Map.entry("COT 14 (reserved)", "01010e00010064000001"),
          Map.entry("SQ=1 IOA overflow", "018203000100ffffff0101"),
          Map.entry("VSQ count 2, one object", "0d02030001006400000000803f00"),
          Map.entry("float cut short", "0d0103000100640000000000"));

  private final ManualScheduler scheduler = new ManualScheduler();
  private final RecordingClientTransport transport = new RecordingClientTransport();
  private final RecordingEvents events = new RecordingEvents();

  // --- Report and continue ---------------------------------------------------------------------

  @Test
  void everyUndecodableTriggerIsAcknowledgedReportedAndFollowedByDelivery() {
    for (Map.Entry<String, String> trigger : UNDECODABLE.entrySet()) {
      String label = trigger.getKey();
      RecordingClientTransport transport = new RecordingClientTransport();
      RecordingEvents events = new RecordingEvents();
      ManualScheduler scheduler = new ManualScheduler();
      Session session = startedClient(transport, events, scheduler, UndecodableAsduPolicy.DEFAULT);
      int sentBefore = transport.sent().size();

      byte[] asdu = hex(trigger.getValue());
      feed(transport.listener(), 0, 0, asdu);

      assertEquals(1, events.undecodableAsdus().size(), label);
      assertArrayEquals(asdu, events.undecodableAsdus().get(0).asdu(), label);
      assertTrue(
          events.undecodableAsdus().get(0).cause() instanceof AsduDecodeException
              || events.undecodableAsdus().get(0).cause() instanceof UnsupportedAsduTypeException,
          label);
      assertTrue(events.asdus().isEmpty(), label);
      assertEquals(0, events.closedCount(), label);

      // Acknowledged within t2 with N(R)=1.
      scheduler.advance(T2_MILLIS, TimeUnit.MILLISECONDS);
      List<ControlField> sent = sentControls(transport);
      assertEquals(sentBefore + 1, sent.size(), label);
      assertEquals(new ControlField.TypeS(1), sent.get(sent.size() - 1), label);

      // The next valid I-frame on the same connection is delivered.
      feed(transport.listener(), 1, 0, hex(VALID_SINGLE_POINT));
      assertEquals(1, events.asdus().size(), label);
      assertEquals(0, events.closedCount(), label);
      session.close();
    }
  }

  @Test
  void undecodableFrameCountsTowardTheWAcknowledgementThreshold() {
    startedClient(transport, events, scheduler, UndecodableAsduPolicy.DEFAULT);
    int w = ApciSettings.defaults().w().intValue();

    for (int ns = 0; ns < w; ns++) {
      feed(transport.listener(), ns, 0, hex(UNDECODABLE.get("TypeID 136 (private)")));
    }

    List<ControlField> sent = sentControls(transport);
    assertEquals(new ControlField.TypeS(w), sent.get(sent.size() - 1));
    assertEquals(w, events.undecodableAsdus().size());
  }

  @Test
  void undecodableFrameProcessesItsReceiveSequenceNumber() {
    Session session = startedClient(transport, events, scheduler, UndecodableAsduPolicy.DEFAULT);
    session.sendAsdu(validAsdu());

    // The undecodable frame acknowledges our one outstanding I-frame with N(R)=1.
    feed(transport.listener(), 0, 1, hex(UNDECODABLE.get("COT 48 (private)")));
    scheduler.advance(T1_MILLIS, TimeUnit.MILLISECONDS);

    assertEquals(0, events.closedCount(), "N(R)=1 must have cancelled the t1 deadline");
    assertEquals(1, events.undecodableAsdus().size());
  }

  @Test
  void undecodableFrameWithWrongSendSequenceNumberStillCloses() {
    startedClient(transport, events, scheduler, UndecodableAsduPolicy.DEFAULT);

    feed(transport.listener(), 5, 0, hex(UNDECODABLE.get("TypeID 136 (private)")));

    assertEquals(1, events.closedCount());
    assertInstanceOf(SequenceNumberException.class, events.lastCloseCause());
    assertTrue(events.undecodableAsdus().isEmpty());
  }

  @Test
  void undecodableFrameBeforeStartDataTransferStillCloses() {
    Cs104Binding binding =
        new Cs104Binding(ApciSettings.defaults(), PROFILE, null, UndecodableAsduPolicy.DEFAULT);
    Session session = binding.bindClient(transport, events, scheduler);
    session.onConnected();

    feed(transport.listener(), 0, 0, hex(UNDECODABLE.get("TypeID 136 (private)")));

    assertEquals(1, events.closedCount());
    assertInstanceOf(ProtocolStateException.class, events.lastCloseCause());
    assertTrue(events.undecodableAsdus().isEmpty());
  }

  @Test
  void invalidTimeTagNoLongerPreventsDelivery() {
    startedClient(transport, events, scheduler, UndecodableAsduPolicy.DEFAULT);

    // M_SP_TB_1, SPI=1, CP56Time2a all zero with IV=1.
    feed(transport.listener(), 0, 0, hex("1e01030001006400000100008000000000"));

    assertTrue(events.undecodableAsdus().isEmpty());
    assertEquals(1, events.asdus().size());
    var object = (SinglePointWithCp56Time) events.asdus().get(0).objects().get(0);
    assertTrue(object.on());
    assertEquals(new Cp56Time2a(0, 0, 0, 0, 0, 0, 0, true, false, true), object.time());
  }

  // --- Strict ------------------------------------------------------------------------------------

  @Test
  void strictPolicyClosesWithoutAcknowledging() {
    startedClient(transport, events, scheduler, UndecodableAsduPolicy.STRICT);
    int sentBefore = transport.sent().size();

    feed(transport.listener(), 0, 0, hex(UNDECODABLE.get("TypeID 136 (private)")));

    assertEquals(1, events.closedCount());
    assertInstanceOf(UnsupportedAsduTypeException.class, events.lastCloseCause());
    assertEquals(1, transport.closeConnectionCount());
    assertTrue(events.undecodableAsdus().isEmpty());
    scheduler.advance(T2_MILLIS, TimeUnit.MILLISECONDS);
    assertEquals(sentBefore, transport.sent().size(), "no S-frame may acknowledge the frame");
  }

  // --- Framing errors ----------------------------------------------------------------------------

  @Test
  void framingErrorsStillCloseTheConnection() {
    Map<String, String> framingErrors =
        Map.of(
            "bad START octet", "6904" + "01000000",
            "length below 4", "6803" + "010000",
            "undefined U-format function", "6804" + "03000000",
            "S-frame with a body", "6805" + "0100000001",
            "U-frame with a body", "6805" + "4300000001",
            "I-frame with an empty body", "6804" + "00000000");
    for (Map.Entry<String, String> error : framingErrors.entrySet()) {
      RecordingClientTransport transport = new RecordingClientTransport();
      RecordingEvents events = new RecordingEvents();
      startedClient(transport, events, new ManualScheduler(), UndecodableAsduPolicy.DEFAULT);

      feedRaw(transport.listener(), hex(error.getValue()));

      assertEquals(1, events.closedCount(), error.getKey());
      assertInstanceOf(AsduDecodeException.class, events.lastCloseCause(), error.getKey());
      assertEquals(1, transport.closeConnectionCount(), error.getKey());
      assertTrue(events.undecodableAsdus().isEmpty(), error.getKey());
    }
  }

  @Test
  void decodeHeaderLeavesReaderAtTheAsdu() {
    ByteBuf frame = ApduFramer.encode(new ControlField.TypeI(7, 3), hex("88010300"), ALLOC);
    try {
      ApduFramer.Header header = ApduFramer.decodeHeader(frame);
      assertEquals(new ControlField.TypeI(7, 3), header.control());
      assertEquals(4, header.asduLength());
      assertArrayEquals(hex("88010300"), ByteBufUtil.getBytes(frame));
    } finally {
      frame.release();
    }
  }

  @Test
  void rawEncodeRejectsEmptyAndOversizedAsdus() {
    ControlField.TypeI control = new ControlField.TypeI(0, 0);
    assertThrows(
        IllegalArgumentException.class, () -> ApduFramer.encode(control, new byte[0], ALLOC));
    assertThrows(
        IllegalArgumentException.class, () -> ApduFramer.encode(control, new byte[250], ALLOC));
    ApduFramer.encode(control, new byte[249], ALLOC).release();
  }

  // --- Raw sends ---------------------------------------------------------------------------------

  @Test
  void serverSendsRawAsduInSequence() {
    RecordingServerConnection connection = new RecordingServerConnection();
    Cs104Binding binding = new Cs104Binding(ApciSettings.defaults(), PROFILE);
    Session session =
        binding.bindServer(connection, events, scheduler, 0, OutboundQueuePolicy.DEFAULT);
    session.onConnected();
    feed(connection.listener(), new ControlField.TypeU(UFunction.STARTDT_ACT));

    byte[] mirror = hex("88014300010064000001");
    CompletableFuture<Void> write = session.sendRawAsduAsync(mirror).toCompletableFuture();
    session.sendAsdu(validAsdu());

    assertTrue(write.isDone() && !write.isCompletedExceptionally());
    List<ByteBuf> iFrames = new ArrayList<>();
    for (ByteBuf frame : connection.sent()) {
      if (ApduFramer.decodeHeader(frame.duplicate()).control() instanceof ControlField.TypeI) {
        iFrames.add(frame);
      }
    }
    assertEquals(2, iFrames.size());
    ByteBuf raw = iFrames.get(0).duplicate();
    assertEquals(new ControlField.TypeI(0, 0), ApduFramer.decodeHeader(raw).control());
    assertArrayEquals(mirror, ByteBufUtil.getBytes(raw));
    assertEquals(
        new ControlField.TypeI(1, 0),
        ApduFramer.decodeHeader(iFrames.get(1).duplicate()).control());
  }

  @Test
  void rawSendWithoutOutputSupportFailsWithoutConsumingASequenceNumber() {
    List<Apdu> written = new ArrayList<>();
    ApciSession session =
        new ApciSession(
            ApciSession.Role.CLIENT, ApciSettings.defaults(), scheduler, written::add, events);
    session.onConnected();

    CompletableFuture<Void> write = session.sendRawAsduAsync(hex("88010300")).toCompletableFuture();
    session.sendAsdu(validAsdu());

    CompletionException failure = assertThrows(CompletionException.class, write::join);
    assertInstanceOf(UnsupportedOperationException.class, failure.getCause());
    assertEquals(1, written.size());
    assertEquals(new ControlField.TypeI(0, 0), written.get(0).control());
    assertEquals(0, events.closedCount());
  }

  // --- Helpers -----------------------------------------------------------------------------------

  private static Session startedClient(
      RecordingClientTransport transport,
      RecordingEvents events,
      ManualScheduler scheduler,
      UndecodableAsduPolicy policy) {
    Cs104Binding binding = new Cs104Binding(ApciSettings.defaults(), PROFILE, null, policy);
    Session session = binding.bindClient(transport, events, scheduler);
    session.onConnected();
    session.startDataTransfer();
    feed(transport.listener(), new ControlField.TypeU(UFunction.STARTDT_CON));
    return session;
  }

  private static void feed(@Nullable TransportListener listener, int ns, int nr, byte[] asdu) {
    feedFrame(listener, ApduFramer.encode(new ControlField.TypeI(ns, nr), asdu, ALLOC));
  }

  private static void feed(@Nullable TransportListener listener, ControlField control) {
    feedFrame(listener, ApduFramer.encode(new Apdu(control, null), PROFILE, ALLOC));
  }

  private static void feedRaw(@Nullable TransportListener listener, byte[] octets) {
    feedFrame(listener, ALLOC.buffer().writeBytes(octets));
  }

  private static void feedFrame(@Nullable TransportListener listener, ByteBuf frame) {
    assertNotNull(listener);
    try {
      listener.onFrame(frame);
    } finally {
      frame.release();
    }
  }

  private static List<ControlField> sentControls(RecordingClientTransport transport) {
    List<ControlField> controls = new ArrayList<>();
    for (ByteBuf frame : transport.sent()) {
      controls.add(ApduFramer.decodeHeader(frame.duplicate()).control());
    }
    return controls;
  }

  private static byte[] hex(String hex) {
    return HexFormat.of().parseHex(hex);
  }

  private static Asdu validAsdu() {
    ByteBuf buffer = ALLOC.buffer().writeBytes(hex(VALID_SINGLE_POINT));
    try {
      return Asdu.Serde.decode(PROFILE, buffer);
    } finally {
      buffer.release();
    }
  }
}
