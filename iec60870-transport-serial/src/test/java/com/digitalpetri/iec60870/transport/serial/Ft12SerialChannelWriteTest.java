package com.digitalpetri.iec60870.transport.serial;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * Regression test for finding #15: the outbound serial write must not block the caller. The FT1.2
 * link layer calls {@code send} (and so {@link Ft12SerialChannel#write(ByteBuf)}) while holding its
 * engine lock, whose callbacks must not block; a {@code TIMEOUT_WRITE_BLOCKING} serial write can
 * otherwise stall under that lock for up to the write timeout when the OS write buffer is full,
 * delaying every confirm/poll/retransmit timer that shares the scheduler.
 *
 * <p>The fix hands the actual write to a dedicated writer thread draining a bounded queue, so
 * {@link Ft12SerialChannel#write(ByteBuf)} only enqueues and returns promptly. These tests drive
 * that writer deterministically against a fake {@link Ft12SerialChannel.SerialLine} via {@link
 * Ft12SerialChannel#startWriterForTesting}, without a real serial port, asserting prompt return,
 * preserved frame order, and that a failed write surfaces as connection loss.
 */
class Ft12SerialChannelWriteTest {

  @Test
  void writeDoesNotBlockTheCallerWhenTheSerialWriteIsSlow() {
    Ft12SerialChannel channel = new Ft12SerialChannel(frame -> {}, cause -> {});

    // A gate that is never opened during the timed section: a write performed inline on the caller
    // would block on it forever, whereas an enqueue-and-return hand-off must complete promptly.
    CountDownLatch gate = new CountDownLatch(1);
    FakeWriterLine line = FakeWriterLine.blockingOnWrite(gate);
    channel.startWriterForTesting(line);

    assertTimeoutPreemptively(
        Duration.ofSeconds(2),
        () -> {
          for (int i = 0; i < 5; i++) {
            channel.write(Unpooled.wrappedBuffer(new byte[] {(byte) i}));
          }
        },
        "write() must not block the caller while the serial write is stalled");

    // Closing interrupts the blocked writer and releases the frame it held plus any still queued.
    channel.close();
  }

  @Test
  void writerDrainsFramesInEnqueueOrder() throws IOException, InterruptedException {
    int frameCount = 8;
    CountDownLatch written = new CountDownLatch(frameCount);
    Ft12SerialChannel channel = new Ft12SerialChannel(frame -> {}, cause -> {});

    FakeWriterLine line = FakeWriterLine.recording(written);
    channel.startWriterForTesting(line);

    for (int i = 0; i < frameCount; i++) {
      channel.write(Unpooled.wrappedBuffer(new byte[] {(byte) i}));
    }

    assertTrue(written.await(5, TimeUnit.SECONDS), "every frame must reach the serial line");

    List<byte[]> writes = line.writes();
    assertEquals(frameCount, writes.size());
    for (int i = 0; i < frameCount; i++) {
      assertEquals(
          (byte) i, writes.get(i)[0], "the writer must drain frames in their enqueue order");
    }

    channel.close();
  }

  @Test
  void failedWriteSurfacesAsConnectionLoss() throws IOException, InterruptedException {
    RecordingLoss loss = new RecordingLoss();
    Ft12SerialChannel channel = new Ft12SerialChannel(frame -> {}, loss);

    // A line whose write reports a short count (0 of the requested length) stands in for a wedged
    // adapter; the writer thread must surface that as connection loss rather than swallow it.
    FakeWriterLine line = FakeWriterLine.shortWriting();
    channel.startWriterForTesting(line);

    ByteBuf frame = Unpooled.wrappedBuffer(new byte[] {0x10, 0x20});
    channel.write(frame);

    assertTrue(
        loss.await(5, TimeUnit.SECONDS), "a failed serial write must surface as connection loss");
    assertEquals(1, loss.count());
    assertInstanceOf(IOException.class, loss.cause());
    assertFalse(channel.isOpen(), "a failed write must leave the port closed");

    // A subsequent explicit close() must not re-surface the loss.
    channel.close();
    assertEquals(1, loss.count(), "loss must remain surfaced exactly once after close()");
  }

  /** Records connection-loss notifications and lets a test await the first one. */
  private static final class RecordingLoss implements Consumer<@Nullable Throwable> {

    private final CountDownLatch latch = new CountDownLatch(1);
    private final AtomicInteger count = new AtomicInteger();
    private final AtomicReference<@Nullable Throwable> cause = new AtomicReference<>();

    @Override
    public void accept(@Nullable Throwable cause) {
      this.cause.set(cause);
      this.count.incrementAndGet();
      this.latch.countDown();
    }

    boolean await(long timeout, TimeUnit unit) throws InterruptedException {
      return latch.await(timeout, unit);
    }

    int count() {
      return count.get();
    }

    @Nullable Throwable cause() {
      return cause.get();
    }
  }

  /**
   * A fake serial line that records each write and can optionally block on a gate or report a short
   * write, standing in for a slow or wedged adapter without a real OS port. Reads are never driven
   * (no reader thread is started in these tests).
   */
  private static final class FakeWriterLine implements Ft12SerialChannel.SerialLine {

    private final List<byte[]> writes = Collections.synchronizedList(new ArrayList<>());
    private final @Nullable CountDownLatch gate;
    private final @Nullable CountDownLatch writeSignal;
    private final boolean shortWrite;
    private volatile boolean open = true;

    private FakeWriterLine(
        @Nullable CountDownLatch gate, @Nullable CountDownLatch writeSignal, boolean shortWrite) {
      this.gate = gate;
      this.writeSignal = writeSignal;
      this.shortWrite = shortWrite;
    }

    static FakeWriterLine blockingOnWrite(CountDownLatch gate) {
      return new FakeWriterLine(gate, null, false);
    }

    static FakeWriterLine recording(CountDownLatch writeSignal) {
      return new FakeWriterLine(null, writeSignal, false);
    }

    static FakeWriterLine shortWriting() {
      return new FakeWriterLine(null, null, true);
    }

    @Override
    public int read(byte[] buffer, int length) {
      return 0; // no reader thread is started in these tests
    }

    @Override
    public int write(byte[] data, int length) {
      writes.add(Arrays.copyOf(data, length));
      if (writeSignal != null) {
        writeSignal.countDown();
      }
      if (gate != null) {
        try {
          gate.await(); // unblocked only by an interrupt from teardown
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      }
      return shortWrite ? 0 : length;
    }

    @Override
    public boolean isOpen() {
      return open;
    }

    @Override
    public void close() {
      open = false;
    }

    List<byte[]> writes() {
      return writes;
    }
  }
}
