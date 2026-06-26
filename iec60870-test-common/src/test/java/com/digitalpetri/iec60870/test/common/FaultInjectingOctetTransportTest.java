package com.digitalpetri.iec60870.test.common;

import static com.digitalpetri.iec60870.test.common.FaultInjectingOctetTransport.Direction.CLIENT_TO_SERVER;
import static com.digitalpetri.iec60870.test.common.FaultInjectingOctetTransport.Direction.SERVER_TO_CLIENT;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.digitalpetri.iec60870.transport.ClientTransport;
import com.digitalpetri.iec60870.transport.ServerTransport;
import com.digitalpetri.iec60870.transport.ServerTransportConnection;
import com.digitalpetri.iec60870.transport.TransportListener;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * Exercises every fault path of {@link FaultInjectingOctetTransport}, asserting both the observable
 * behavior (a dropped frame never arrives, a corrupted frame arrives mutated, {@code kill()} fires
 * {@code onConnectionLost}, hold/release controls ordering, stall/resume queues then drains) and
 * strict reference-count discipline: every sent frame is released exactly once.
 *
 * <p>Frames are allocated with {@link Unpooled#buffer()} so the test can assert {@code refCnt() ==
 * 0} after each send, catching any leak directly rather than relying solely on the GC-driven {@link
 * ParanoidLeakDetection} PARANOID detector (which is also active for this module).
 */
class FaultInjectingOctetTransportTest {

  /** Records each inbound frame's bytes synchronously, never retaining the buffer. */
  private static final class RecordingListener implements TransportListener {
    private final List<byte[]> frames = new ArrayList<>();
    private int connectionLost;

    @Override
    public void onFrame(ByteBuf frame) {
      byte[] bytes = new byte[frame.readableBytes()];
      frame.getBytes(frame.readerIndex(), bytes);
      frames.add(bytes);
    }

    @Override
    public void onConnectionLost(@Nullable Throwable cause) {
      connectionLost++;
    }
  }

  /** Wires up a connected fault-injecting pair with recording listeners on each leg. */
  private static final class Harness {
    final FaultInjectingOctetTransport transport = new FaultInjectingOctetTransport();
    final ClientTransport client = transport.client();
    final ServerTransport server = transport.server();
    final RecordingListener clientListener = new RecordingListener();
    final RecordingListener serverListener = new RecordingListener();
    final ServerTransportConnection connection;

    Harness() {
      client.setListener(clientListener);
      AtomicReference<@Nullable ServerTransportConnection> ref = new AtomicReference<>();
      server.setConnectionHandler(
          conn -> {
            ref.set(conn);
            conn.setListener(serverListener);
          });
      server.bind().toCompletableFuture().join();
      client.connect().toCompletableFuture().join();
      ServerTransportConnection accepted = ref.get();
      assertNotNull(accepted, "server should have accepted the connection");
      connection = accepted;
    }
  }

  /** A fresh, single-reference buffer carrying {@code bytes}. */
  private static ByteBuf buf(int... bytes) {
    ByteBuf b = Unpooled.buffer(bytes.length);
    for (int v : bytes) {
      b.writeByte(v);
    }
    return b;
  }

  /**
   * Sends one frame client->server, joining the stage, and asserts the caller frame was released.
   */
  private static void sendToServer(Harness h, ByteBuf frame) {
    h.client.send(frame).toCompletableFuture().join();
    assertEquals(0, frame.refCnt(), "caller frame must be released exactly once");
  }

  @Test
  void relaysNormallyAndReleasesFrames() {
    Harness h = new Harness();
    ByteBuf frame = buf(0x68, 0x04, 0x07, 0x00, 0x00, 0x00);
    sendToServer(h, frame);

    assertEquals(1, h.serverListener.frames.size());
    assertArrayEquals(
        new byte[] {0x68, 0x04, 0x07, 0x00, 0x00, 0x00}, h.serverListener.frames.get(0));

    h.transport.drainAndRelease();
  }

  @Test
  void dropNextSwallowsAndReleasesWithoutDelivery() {
    Harness h = new Harness();
    h.transport.dropNext(CLIENT_TO_SERVER, 2);

    ByteBuf f1 = buf(0x01);
    ByteBuf f2 = buf(0x02);
    ByteBuf f3 = buf(0x03);
    sendToServer(h, f1);
    sendToServer(h, f2);
    sendToServer(h, f3);

    // First two dropped, third delivered.
    assertEquals(1, h.serverListener.frames.size());
    assertArrayEquals(new byte[] {0x03}, h.serverListener.frames.get(0));

    h.transport.drainAndRelease();
  }

  @Test
  void corruptNextDeliversMutatedCopyAndReleasesOriginal() {
    Harness h = new Harness();
    // Flip the third byte to drive a decoder/sequence-error path on the peer.
    h.transport.corruptNext(
        CLIENT_TO_SERVER,
        copy -> {
          copy.setByte(2, copy.getByte(2) ^ 0xFF);
          return copy;
        });

    ByteBuf frame = buf(0x68, 0x04, 0x07, 0x00, 0x00, 0x00);
    sendToServer(h, frame);

    assertEquals(1, h.serverListener.frames.size());
    byte[] got = h.serverListener.frames.get(0);
    assertEquals((byte) (0x07 ^ 0xFF), got[2], "third byte should be flipped");

    h.transport.drainAndRelease();
  }

  @Test
  void corruptNextCanTruncateToShortFrame() {
    Harness h = new Harness();
    // Return a fresh, shorter buffer to model a short read; the copy passed in must be released.
    h.transport.corruptNext(
        CLIENT_TO_SERVER,
        copy -> {
          ByteBuf truncated = copy.copy(copy.readerIndex(), 3);
          copy.release();
          return truncated;
        });

    ByteBuf frame = buf(0x68, 0x04, 0x07, 0x00, 0x00, 0x00);
    sendToServer(h, frame);

    assertEquals(1, h.serverListener.frames.size());
    assertEquals(3, h.serverListener.frames.get(0).length, "frame should be truncated to 3 bytes");

    h.transport.drainAndRelease();
  }

  @Test
  void duplicateNextDeliversFrameTwice() {
    Harness h = new Harness();
    h.transport.duplicateNext(CLIENT_TO_SERVER);

    ByteBuf frame = buf(0xAA, 0xBB);
    sendToServer(h, frame);

    assertEquals(2, h.serverListener.frames.size(), "frame should be delivered twice");
    assertArrayEquals(new byte[] {(byte) 0xAA, (byte) 0xBB}, h.serverListener.frames.get(0));
    assertArrayEquals(new byte[] {(byte) 0xAA, (byte) 0xBB}, h.serverListener.frames.get(1));

    // A subsequent frame is delivered only once (duplicate is one-shot).
    sendToServer(h, buf(0xCC));
    assertEquals(3, h.serverListener.frames.size());

    h.transport.drainAndRelease();
  }

  @Test
  void holdAndReleaseControlsDeliveryOrder() {
    Harness h = new Harness();

    // Hold the first frame, then send a second normally: the second arrives first.
    h.transport.holdNext(CLIENT_TO_SERVER);
    sendToServer(h, buf(0x01));
    sendToServer(h, buf(0x02));

    assertEquals(1, h.serverListener.frames.size(), "held frame should not have arrived yet");
    assertArrayEquals(new byte[] {0x02}, h.serverListener.frames.get(0));

    // Now release the held frame: it arrives after the later one (reordering).
    assertTrue(h.transport.release(CLIENT_TO_SERVER));
    assertEquals(2, h.serverListener.frames.size());
    assertArrayEquals(new byte[] {0x01}, h.serverListener.frames.get(1));

    // No more held frames.
    assertFalse(h.transport.release(CLIENT_TO_SERVER));

    h.transport.drainAndRelease();
  }

  @Test
  void partitionDropsOneDirectionUntilHealed() {
    Harness h = new Harness();
    h.transport.partition(CLIENT_TO_SERVER);

    sendToServer(h, buf(0x01));
    sendToServer(h, buf(0x02));
    assertEquals(0, h.serverListener.frames.size(), "partitioned direction delivers nothing");

    // Other direction is unaffected.
    ByteBuf reply = buf(0x99);
    h.connection.send(reply).toCompletableFuture().join();
    assertEquals(0, reply.refCnt());
    assertEquals(1, h.clientListener.frames.size());

    h.transport.heal(CLIENT_TO_SERVER);
    sendToServer(h, buf(0x03));
    assertEquals(1, h.serverListener.frames.size(), "delivery resumes after heal");
    assertArrayEquals(new byte[] {0x03}, h.serverListener.frames.get(0));

    h.transport.drainAndRelease();
  }

  @Test
  void stallQueuesOutboundAndResumeDrainsInOrder() {
    Harness h = new Harness();
    h.transport.stall(CLIENT_TO_SERVER);

    sendToServer(h, buf(0x01));
    sendToServer(h, buf(0x02));
    sendToServer(h, buf(0x03));
    assertEquals(0, h.serverListener.frames.size(), "stalled writes must not be delivered");

    h.transport.resume(CLIENT_TO_SERVER);
    assertEquals(3, h.serverListener.frames.size(), "resume drains all queued frames");
    assertArrayEquals(new byte[] {0x01}, h.serverListener.frames.get(0));
    assertArrayEquals(new byte[] {0x02}, h.serverListener.frames.get(1));
    assertArrayEquals(new byte[] {0x03}, h.serverListener.frames.get(2));

    h.transport.drainAndRelease();
  }

  @Test
  void killFiresConnectionLostOnBothPeers() {
    Harness h = new Harness();

    h.transport.kill();

    assertEquals(1, h.serverListener.connectionLost, "server peer should be notified once");
    assertEquals(1, h.clientListener.connectionLost, "client peer should be notified once");
    assertFalse(h.client.isConnected(), "transport should be disconnected after kill");

    // Subsequent sends fail and still release the caller frame.
    ByteBuf frame = buf(0x01);
    CompletionException ex =
        assertThrows(
            CompletionException.class, () -> h.client.send(frame).toCompletableFuture().join());
    assertNotNull(ex.getCause());
    assertEquals(0, frame.refCnt(), "frame must be released even when send fails");
  }

  @Test
  void killReleasesHeldFramesWithoutLeaking() {
    Harness h = new Harness();
    h.transport.holdNext(CLIENT_TO_SERVER);
    sendToServer(h, buf(0x01));
    h.transport.stall(SERVER_TO_CLIENT);
    ByteBuf reply = buf(0x02);
    h.connection.send(reply).toCompletableFuture().join();
    assertEquals(0, reply.refCnt());

    // kill() must discard and release queued frames in both directions (no LEAK).
    h.transport.kill();

    assertEquals(0, h.serverListener.frames.size());
    assertEquals(0, h.clientListener.frames.size());
  }

  @Test
  void serverToClientDirectionIndependentlyFaulted() {
    Harness h = new Harness();
    h.transport.dropNext(SERVER_TO_CLIENT, 1);

    ByteBuf dropped = buf(0x10);
    h.connection.send(dropped).toCompletableFuture().join();
    assertEquals(0, dropped.refCnt());
    assertEquals(0, h.clientListener.frames.size(), "server->client frame should be dropped");

    ByteBuf delivered = buf(0x20);
    h.connection.send(delivered).toCompletableFuture().join();
    assertEquals(0, delivered.refCnt());
    assertEquals(1, h.clientListener.frames.size());
    assertArrayEquals(new byte[] {0x20}, h.clientListener.frames.get(0));

    h.transport.drainAndRelease();
  }
}
