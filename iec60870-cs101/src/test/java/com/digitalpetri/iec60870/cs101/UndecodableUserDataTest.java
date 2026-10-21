package com.digitalpetri.iec60870.cs101;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.digitalpetri.iec60870.AsduDecodeException;
import com.digitalpetri.iec60870.OutboundQueuePolicy;
import com.digitalpetri.iec60870.ProtocolProfile;
import com.digitalpetri.iec60870.UndecodableAsduPolicy;
import com.digitalpetri.iec60870.UnsupportedAsduTypeException;
import com.digitalpetri.iec60870.address.CommonAddress;
import com.digitalpetri.iec60870.address.InformationObjectAddress;
import com.digitalpetri.iec60870.address.OriginatorAddress;
import com.digitalpetri.iec60870.asdu.Asdu;
import com.digitalpetri.iec60870.asdu.AsduType;
import com.digitalpetri.iec60870.asdu.Cause;
import com.digitalpetri.iec60870.asdu.element.Qds;
import com.digitalpetri.iec60870.asdu.object.SinglePointInformation;
import com.digitalpetri.iec60870.session.Session;
import com.digitalpetri.iec60870.test.common.ManualScheduler;
import com.digitalpetri.iec60870.test.common.RecordingEvents;
import com.digitalpetri.iec60870.test.common.RecordingServerConnection;
import com.digitalpetri.iec60870.transport.TransportListener;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.UnpooledByteBufAllocator;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * Tests the CS 101 handling of an intact variable frame whose ASDU cannot be decoded: {@link
 * Ft12Framer} returns it as an {@link Ft12Frame.UndecodableVariable}, each engine runs its normal
 * frame-count and acknowledgement procedure for it and reports the octets through {@link
 * Session.Events#onUndecodableAsdu}, and {@link UndecodableAsduPolicy#STRICT} restores the legacy
 * drop.
 */
class UndecodableUserDataTest {

  private static final ProtocolProfile PROFILE = ProtocolProfile.iec104Default();
  private static final ByteBufAllocator ALLOC = UnpooledByteBufAllocator.DEFAULT;

  /** TypeID 136 (private range) single object under the IEC 104 default field widths. */
  private static final byte[] PRIVATE_TYPE = HexFormat.of().parseHex("88010300010064000001");

  private static final int FC_RESET_REMOTE_LINK = 0;
  private static final int FC_ACK = 0;
  private static final int FC_SEND_CONFIRM_USER_DATA = 3;
  private static final int FC_RESPOND_USER_DATA = 8;
  private static final int FC_STATUS_OF_LINK = 11;
  private static final int FC_REQUEST_CLASS_2_DATA = 11;

  private final ManualScheduler scheduler = new ManualScheduler();
  private final RecordingOutput output = new RecordingOutput();
  private final RecordingEvents events = new RecordingEvents();

  // --- Framer ------------------------------------------------------------------------------------

  @Test
  void framerReturnsIntactFrameWithUndecodableAsdu() {
    ByteBuf frame =
        variableFrame(LinkControlField.primary(true, true, true, 3), 1, 1, PRIVATE_TYPE);
    try {
      Ft12Frame decoded = Ft12Framer.decode(PROFILE, 1, frame);

      Ft12Frame.UndecodableVariable undecodable =
          assertInstanceOf(Ft12Frame.UndecodableVariable.class, decoded);
      assertEquals(1, undecodable.linkAddress());
      assertTrue(undecodable.control().fcb());
      assertArrayEquals(PRIVATE_TYPE, undecodable.asdu());
      assertInstanceOf(UnsupportedAsduTypeException.class, undecodable.cause());
      assertFalse(frame.isReadable());
    } finally {
      frame.release();
    }
  }

  @Test
  void framerStillRejectsABadChecksum() {
    ByteBuf frame =
        variableFrame(LinkControlField.primary(true, true, true, 3), 1, 1, PRIVATE_TYPE);
    frame.setByte(frame.writerIndex() - 2, frame.getByte(frame.writerIndex() - 2) + 1);
    try {
      assertThrows(AsduDecodeException.class, () -> Ft12Framer.decode(PROFILE, 1, frame));
    } finally {
      frame.release();
    }
  }

  @Test
  void framerDoesNotEncodeAnUndecodableFrame() {
    Ft12Frame frame =
        new Ft12Frame.UndecodableVariable(
            LinkControlField.primary(true, true, true, 3),
            1,
            PRIVATE_TYPE,
            new UnsupportedAsduTypeException("test"));
    assertThrows(IllegalArgumentException.class, () -> Ft12Framer.encode(frame, PROFILE, 1, ALLOC));
  }

  // --- Engines -----------------------------------------------------------------------------------

  @Test
  void balancedSecondaryAcknowledgesAndReportsOnceThenDeliversTheNextFrame() {
    BalancedEngine engine =
        new BalancedEngine(
            Ft12LinkLayer.Role.SERVER,
            LinkSettings.balanced().useSingleCharAck(false).build(),
            scheduler,
            output,
            events,
            0,
            OutboundQueuePolicy.DROP_OLDEST);
    engine.onConnected();
    engine.onFrame(
        new Ft12Frame.FixedLength(
            LinkControlField.primary(true, false, false, FC_RESET_REMOTE_LINK), 1));
    int framesAfterReset = output.frames.size();

    Ft12Frame undecodable = undecodableFrame(LinkControlField.primary(true, false, true, 3), 1);
    engine.onFrame(undecodable);

    assertEquals(1, events.undecodableAsdus().size());
    assertArrayEquals(PRIVATE_TYPE, events.undecodableAsdus().get(0).asdu());
    assertTrue(events.asdus().isEmpty());
    assertEquals(framesAfterReset + 1, output.frames.size());
    assertPositiveAck(output.frames.get(output.frames.size() - 1));

    // A retransmission (same FCB) replays the cached ack without reporting again.
    engine.onFrame(undecodable);
    assertEquals(1, events.undecodableAsdus().size());
    assertEquals(framesAfterReset + 2, output.frames.size());

    // The next frame (toggled FCB) is delivered normally.
    engine.onFrame(
        new Ft12Frame.Variable(LinkControlField.primary(true, true, true, 3), 1, sampleAsdu()));
    assertEquals(List.of(sampleAsdu()), events.asdus());
  }

  @Test
  void unbalancedSlaveAcknowledgesAndReportsSendConfirmUserData() {
    UnbalancedSlaveEngine engine =
        new UnbalancedSlaveEngine(
            LinkSettings.unbalanced().linkAddress(1).build(),
            scheduler,
            output,
            events,
            0,
            OutboundQueuePolicy.DROP_OLDEST);
    engine.onConnected();
    engine.onFrame(
        new Ft12Frame.FixedLength(
            LinkControlField.primary(false, false, false, FC_RESET_REMOTE_LINK), 1));
    int framesAfterReset = output.frames.size();

    engine.onFrame(
        undecodableFrame(
            LinkControlField.primary(false, true, true, FC_SEND_CONFIRM_USER_DATA), 1));

    assertEquals(1, events.undecodableAsdus().size());
    assertEquals(framesAfterReset + 1, output.frames.size());
    assertPositiveAck(output.frames.get(output.frames.size() - 1));
  }

  @Test
  void unbalancedMasterCompletesThePollAndReportsUndecodableResponse() {
    UnbalancedMasterEngine master =
        new UnbalancedMasterEngine(
            LinkSettings.unbalanced()
                .slaveAddresses(List.of(1))
                .pollInterval(Duration.ofMillis(1000))
                .build(),
            scheduler,
            output,
            events,
            0,
            OutboundQueuePolicy.DROP_OLDEST);
    master.onConnected();
    master.startDataTransfer();
    master.onFrame(secondaryFixed(FC_STATUS_OF_LINK));
    master.onFrame(secondaryFixed(FC_ACK));
    output.frames.clear();

    scheduler.advance(1000, TimeUnit.MILLISECONDS);
    Ft12Frame.FixedLength firstPoll = assertInstanceOf(Ft12Frame.FixedLength.class, last());
    assertEquals(FC_REQUEST_CLASS_2_DATA, firstPoll.control().functionCode());

    master.onFrame(
        undecodableFrame(LinkControlField.secondary(false, false, false, FC_RESPOND_USER_DATA), 1));
    assertEquals(1, events.undecodableAsdus().size());

    // The transaction completed: the next poll goes out on schedule with a toggled FCB.
    scheduler.advance(1000, TimeUnit.MILLISECONDS);
    Ft12Frame.FixedLength secondPoll = assertInstanceOf(Ft12Frame.FixedLength.class, last());
    assertEquals(FC_REQUEST_CLASS_2_DATA, secondPoll.control().functionCode());
    assertEquals(!firstPoll.control().fcb(), secondPoll.control().fcb());
    assertEquals(0, events.closedCount());
  }

  // --- Binding policy ----------------------------------------------------------------------------

  @Test
  void bindingReportsByDefaultAndDropsUnderStrict() {
    for (UndecodableAsduPolicy policy : UndecodableAsduPolicy.values()) {
      RecordingServerConnection connection = new RecordingServerConnection();
      RecordingEvents events = new RecordingEvents();
      LinkSettings link = LinkSettings.balanced().useSingleCharAck(false).build();
      Cs101Binding binding = new Cs101Binding(link, PROFILE, null, policy);
      Session session =
          binding.bindServer(
              connection, events, new ManualScheduler(), 0, OutboundQueuePolicy.DROP_OLDEST);
      session.onConnected();
      int linkAddressLength = link.linkAddressLength();
      int linkAddress = link.linkAddress();
      feed(
          connection.listener(),
          fixedFrame(
              LinkControlField.primary(true, false, false, FC_RESET_REMOTE_LINK),
              linkAddress,
              linkAddressLength));
      int sentAfterReset = connection.sent().size();

      feed(
          connection.listener(),
          variableFrame(
              LinkControlField.primary(true, false, true, 3),
              linkAddress,
              linkAddressLength,
              PRIVATE_TYPE));

      if (policy == UndecodableAsduPolicy.STRICT) {
        assertTrue(events.undecodableAsdus().isEmpty(), policy.name());
        assertEquals(sentAfterReset, connection.sent().size(), policy.name());
      } else {
        assertEquals(1, events.undecodableAsdus().size(), policy.name());
        assertEquals(sentAfterReset + 1, connection.sent().size(), policy.name());
      }
      assertEquals(0, events.closedCount(), policy.name());
    }
  }

  // --- Helpers -----------------------------------------------------------------------------------

  private Ft12Frame last() {
    return output.frames.get(output.frames.size() - 1);
  }

  private static void assertPositiveAck(Ft12Frame frame) {
    if (frame instanceof Ft12Frame.SingleChar) {
      return;
    }
    Ft12Frame.FixedLength fixed = assertInstanceOf(Ft12Frame.FixedLength.class, frame);
    assertFalse(fixed.control().prm());
    assertEquals(FC_ACK, fixed.control().functionCode());
  }

  private static Ft12Frame undecodableFrame(LinkControlField control, int linkAddress) {
    return new Ft12Frame.UndecodableVariable(
        control, linkAddress, PRIVATE_TYPE, new UnsupportedAsduTypeException("TypeID 136"));
  }

  private static Ft12Frame secondaryFixed(int functionCode) {
    return new Ft12Frame.FixedLength(
        LinkControlField.secondary(false, false, false, functionCode), 1);
  }

  private static Asdu sampleAsdu() {
    return new Asdu(
        AsduType.M_SP_NA_1,
        false,
        Cause.SPONTANEOUS,
        false,
        false,
        OriginatorAddress.none(),
        CommonAddress.of(1),
        List.of(
            new SinglePointInformation(
                InformationObjectAddress.of(100),
                true,
                new Qds(false, false, false, false, false))));
  }

  /** Builds a variable frame by hand so its ASDU octets bypass the typed encoder. */
  private static ByteBuf variableFrame(
      LinkControlField control, int linkAddress, int linkAddressLength, byte[] asdu) {
    int length = 1 + linkAddressLength + asdu.length;
    ByteBuf frame = ALLOC.buffer();
    frame.writeByte(0x68).writeByte(length).writeByte(length).writeByte(0x68);
    int userDataStart = frame.writerIndex();
    frame.writeByte(control.toOctet());
    for (int i = 0; i < linkAddressLength; i++) {
      frame.writeByte((linkAddress >> (8 * i)) & 0xFF);
    }
    frame.writeBytes(asdu);
    frame.writeByte(checksum(frame, userDataStart));
    frame.writeByte(0x16);
    return frame;
  }

  private static ByteBuf fixedFrame(
      LinkControlField control, int linkAddress, int linkAddressLength) {
    ByteBuf frame = ALLOC.buffer();
    frame.writeByte(0x10);
    int userDataStart = frame.writerIndex();
    frame.writeByte(control.toOctet());
    for (int i = 0; i < linkAddressLength; i++) {
      frame.writeByte((linkAddress >> (8 * i)) & 0xFF);
    }
    frame.writeByte(checksum(frame, userDataStart));
    frame.writeByte(0x16);
    return frame;
  }

  private static int checksum(ByteBuf frame, int from) {
    int sum = 0;
    for (int i = from; i < frame.writerIndex(); i++) {
      sum += frame.getUnsignedByte(i);
    }
    return sum & 0xFF;
  }

  private static void feed(@Nullable TransportListener listener, ByteBuf frame) {
    assertNotNull(listener);
    try {
      listener.onFrame(frame);
    } finally {
      frame.release();
    }
  }

  private static final class RecordingOutput implements Ft12LinkLayer.Output {

    private final List<Ft12Frame> frames = new ArrayList<>();

    @Override
    public void send(Ft12Frame frame) {
      frames.add(frame);
    }
  }
}
