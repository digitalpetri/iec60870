package com.digitalpetri.iec60870.transport.serial;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * Regression test for finding #5: when the reader thread detects line loss it must fully tear the
 * channel down — clear {@code running} and close the serial port to release the file descriptor —
 * rather than leaving the port open with a dead reader. Otherwise {@link
 * Ft12SerialChannel#isOpen()} keeps reporting {@code true} while the OS still holds the port busy,
 * so reconnecting to the same device is impossible until JVM exit.
 *
 * <p>The reader loop normally runs on its own thread reading a real {@code jSerialComm} port. These
 * tests drive it deterministically on the test thread against a fake {@link
 * Ft12SerialChannel.SerialLine} via {@link Ft12SerialChannel#runReaderLoopForTesting}, the tightest
 * seam that exercises the loss/teardown path without real serial hardware.
 */
class Ft12SerialChannelTeardownTest {

  @Test
  void readerThrowingClosesPortAndSignalsLossOnce() {
    RecordingLoss loss = new RecordingLoss();
    Ft12SerialChannel channel = new Ft12SerialChannel(frame -> {}, loss);

    RuntimeException glitch = new RuntimeException("simulated cable glitch");
    FakeSerialLine line = FakeSerialLine.throwingOnRead(glitch);

    channel.runReaderLoopForTesting(line);

    assertEquals(1, line.closeCount(), "reader-detected loss must close the port");
    assertFalse(line.isOpen(), "the line must be closed");
    assertFalse(channel.isOpen(), "isOpen() must reflect the dead reader, not stay true");
    assertEquals(1, loss.count(), "loss must be surfaced exactly once");
    assertSame(glitch, loss.cause(), "loss must carry the read failure cause");

    // A subsequent explicit close() must not re-surface the loss.
    channel.close();
    assertEquals(1, loss.count(), "loss must remain surfaced exactly once after close()");
  }

  @Test
  void readerNegativeReadCodeClosesPortAndSignalsLoss() {
    RecordingLoss loss = new RecordingLoss();
    Ft12SerialChannel channel = new Ft12SerialChannel(frame -> {}, loss);

    FakeSerialLine line = FakeSerialLine.returningOnRead(-1);

    channel.runReaderLoopForTesting(line);

    assertEquals(1, line.closeCount(), "reader-detected loss must close the port");
    assertFalse(channel.isOpen());
    assertEquals(1, loss.count());
    assertInstanceOf(IOException.class, loss.cause());
  }

  @Test
  void closeBeforeOpenSignalsLossExactlyOnce() {
    RecordingLoss loss = new RecordingLoss();
    Ft12SerialChannel channel = new Ft12SerialChannel(frame -> {}, loss);

    channel.close();
    channel.close();

    assertEquals(1, loss.count(), "an orderly close must surface loss exactly once");
    assertNull(loss.cause(), "an orderly close carries a null cause");
    assertFalse(channel.isOpen());
  }

  /** Records connection-loss notifications and the cause delivered to {@code onLoss}. */
  private static final class RecordingLoss implements Consumer<@Nullable Throwable> {

    private final AtomicInteger count = new AtomicInteger();
    private final AtomicReference<@Nullable Throwable> cause = new AtomicReference<>();

    @Override
    public void accept(@Nullable Throwable cause) {
      this.count.incrementAndGet();
      this.cause.set(cause);
    }

    int count() {
      return count.get();
    }

    @Nullable Throwable cause() {
      return cause.get();
    }
  }

  /**
   * A fake serial line that fails on the first read, standing in for a dropped cable, and records
   * whether the channel closed it.
   */
  private static final class FakeSerialLine implements Ft12SerialChannel.SerialLine {

    private final @Nullable RuntimeException readError;
    private final int readCode;
    private final AtomicInteger closeCount = new AtomicInteger();
    private volatile boolean open = true;

    private FakeSerialLine(@Nullable RuntimeException readError, int readCode) {
      this.readError = readError;
      this.readCode = readCode;
    }

    static FakeSerialLine throwingOnRead(RuntimeException error) {
      return new FakeSerialLine(error, 0);
    }

    static FakeSerialLine returningOnRead(int code) {
      return new FakeSerialLine(null, code);
    }

    @Override
    public int read(byte[] buffer, int length) {
      if (readError != null) {
        throw readError;
      }
      return readCode;
    }

    @Override
    public int write(byte[] data, int length) {
      return length;
    }

    @Override
    public boolean isOpen() {
      return open;
    }

    @Override
    public void close() {
      closeCount.incrementAndGet();
      open = false;
    }

    int closeCount() {
      return closeCount.get();
    }
  }
}
