package com.digitalpetri.iec60870.transport.serial;

import com.fazecast.jSerialComm.SerialPort;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.Unpooled;
import java.io.IOException;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Owns one {@code jSerialComm} serial port, a dedicated reader thread, and an {@link Ft12Deframer},
 * bridging the byte-oriented serial line to whole-frame callbacks.
 *
 * <p>This is the single point where the {@code com.fazecast.jSerialComm} driver is used; it is a
 * package-private helper shared by {@link SerialClientTransport} and {@link
 * SerialServerConnection}, and no driver type appears in its API. {@link #open(SerialPortConfig)}
 * configures the port as 8E1 (or as configured), opens it, and starts a reader thread that does
 * semi-blocking bulk reads and feeds the deframer; each assembled frame is delivered to the {@code
 * onFrame} consumer (which owns and need not release it). When the line is lost or the port is
 * closed, the {@code onLoss} consumer is invoked exactly once.
 *
 * <p>Reads run on the reader thread. Outbound frames are drained by a dedicated single-threaded
 * writer: {@link #write(ByteBuf)} only enqueues a frame on the caller's thread and returns
 * promptly, so it never blocks the caller on a slow or wedged serial write (the FT1.2 link layer
 * invokes it while holding its engine lock, which must not block). The writer preserves frame order
 * and surfaces a failed write as connection loss. A channel is single-use: {@link #close()} stops
 * the reader and writer and closes the port, and a new channel must be created to reconnect.
 */
class Ft12SerialChannel {

  private static final Logger LOGGER = LoggerFactory.getLogger(Ft12SerialChannel.class);

  /** Size of the reusable read buffer; comfortably larger than the 261-octet max FT1.2 frame. */
  private static final int READ_BUFFER_SIZE = 1024;

  /** Time to wait for the reader thread to exit when closing. */
  private static final long READER_JOIN_TIMEOUT_MILLIS = 2000;

  /** Time to wait for the writer thread to exit when closing. */
  private static final long WRITER_JOIN_TIMEOUT_MILLIS = 2000;

  /**
   * Bound on the outbound frame queue drained by the writer thread. The FT1.2 link layer above
   * keeps only a small window of frames outstanding, so this is reached only if the writer thread
   * wedges on a stuck port; an over-capacity {@link #write(ByteBuf)} then fails fast rather than
   * blocking.
   */
  private static final int OUTBOUND_QUEUE_CAPACITY = 256;

  private final Consumer<ByteBuf> onFrame;
  private final Consumer<@Nullable Throwable> onLoss;

  private final AtomicBoolean lossSignaled = new AtomicBoolean(false);

  /** Outbound frames awaiting the writer thread; each carried buffer is owned by this channel. */
  private final BlockingQueue<ByteBuf> writeQueue =
      new LinkedBlockingQueue<>(OUTBOUND_QUEUE_CAPACITY);

  private volatile boolean running = false;
  private volatile @Nullable SerialLine line;
  private volatile @Nullable Thread readerThread;
  private volatile @Nullable Thread writerThread;

  /**
   * Creates a channel bound to the given callbacks. The port is not opened until {@link
   * #open(SerialPortConfig)} is called.
   *
   * @param onFrame invoked with each inbound whole frame; the frame is owned by the channel and
   *     valid only for the duration of the call.
   * @param onLoss invoked exactly once when the connection is lost or closed, with the failure
   *     cause or {@code null} for an orderly close.
   */
  Ft12SerialChannel(Consumer<ByteBuf> onFrame, Consumer<@Nullable Throwable> onLoss) {
    this.onFrame = onFrame;
    this.onLoss = onLoss;
  }

  /**
   * Opens and configures the serial port and starts the reader thread.
   *
   * @param config the serial and FT1.2 framing parameters.
   * @throws IOException if the port cannot be resolved or opened.
   * @throws IllegalStateException if this channel has already been opened.
   */
  void open(SerialPortConfig config) throws IOException {
    if (running) {
      throw new IllegalStateException("channel already open");
    }

    SerialPort serialPort;
    try {
      serialPort = SerialPort.getCommPort(config.portName());
    } catch (RuntimeException e) {
      throw new IOException("invalid serial port: " + config.portName(), e);
    }

    serialPort.setComPortParameters(
        config.baudRate(),
        config.dataBits(),
        mapStopBits(config.stopBits()),
        mapParity(config.parity()));
    serialPort.setComPortTimeouts(
        SerialPort.TIMEOUT_READ_SEMI_BLOCKING | SerialPort.TIMEOUT_WRITE_BLOCKING,
        toTimeoutMillis(config.readTimeout()),
        toTimeoutMillis(config.writeTimeout()));

    Rs485Options rs485 = config.rs485();
    if (rs485 != null) {
      // Enable RS-485 mode and hand the half-duplex turnaround to the driver. The parameters beyond
      // the first are effective only on Linux (see Rs485Options); the JVM never times turnaround.
      // The driver returns false when the platform cannot apply RS-485 mode (e.g. macOS), in which
      // case the caller must provide auto-direction hardware; surface that rather than failing.
      boolean applied =
          serialPort.setRs485ModeParameters(
              true,
              rs485.rtsActiveHigh(),
              rs485.enableTermination(),
              rs485.rxDuringTx(),
              rs485.delayBeforeSendMicros(),
              rs485.delayAfterSendMicros());
      if (!applied) {
        LOGGER.warn(
            "RS-485 mode was requested but the driver could not apply it on this platform for "
                + "port {}; ensure the adapter provides automatic direction control",
            config.portName());
      }
    }

    if (!serialPort.openPort()) {
      throw new IOException("failed to open serial port: " + config.portName());
    }

    warnIfHighLatencyAdapter(serialPort);

    SerialLine serialLine = new JSerialCommLine(serialPort);
    this.line = serialLine;
    Ft12Deframer deframer =
        new Ft12Deframer(config.linkAddressLength(), ByteBufAllocator.DEFAULT, onFrame);
    this.running = true;

    Thread writer =
        new Thread(() -> writeLoop(serialLine), "iec60870-serial-writer-" + config.portName());
    writer.setDaemon(true);
    this.writerThread = writer;
    writer.start();

    Thread reader =
        new Thread(
            () -> readLoop(serialLine, deframer), "iec60870-serial-reader-" + config.portName());
    reader.setDaemon(true);
    this.readerThread = reader;
    reader.start();
  }

  /**
   * Indicates whether the underlying serial port is currently open.
   *
   * @return {@code true} if the port is open, {@code false} otherwise.
   */
  boolean isOpen() {
    SerialLine serialLine = line;
    return serialLine != null && serialLine.isOpen();
  }

  /**
   * Hands one complete frame to the writer thread for transmission and returns promptly.
   *
   * <p>The frame is enqueued (not written) on the caller's thread, so this method does not block on
   * the underlying serial write even when the OS write buffer is full or the adapter has wedged.
   * The dedicated writer thread drains the queue in order and performs the actual (potentially
   * blocking) serial write off the caller's thread; a failed write there is surfaced as connection
   * loss through {@code onLoss} rather than thrown back here. This matters because the FT1.2 link
   * layer calls {@code send} while holding its engine lock, whose callbacks must not block.
   *
   * <p>The caller must not release {@code frame}; ownership transfers to this channel, which
   * releases it whether it is written, dropped on a full queue, or discarded during teardown.
   *
   * @param frame the whole frame to send; ownership transfers to this channel.
   * @throws IOException if the port is not open or the outbound queue is full.
   */
  void write(ByteBuf frame) throws IOException {
    SerialLine serialLine = line;
    if (!running || serialLine == null || !serialLine.isOpen()) {
      frame.release();
      throw new IOException("serial port is not open");
    }

    if (!writeQueue.offer(frame)) {
      frame.release();
      throw new IOException("serial outbound queue is full");
    }

    // Guard against a teardown that raced in after the open() check above: if the writer thread has
    // already drained and exited, the frame we just enqueued would leak. Draining here is safe
    // because the queue's poll() is atomic, so each frame is released by exactly one drainer.
    if (!running) {
      drainAndReleaseQueue();
    }
  }

  /**
   * Stops the reader and writer threads, closes the port, and signals connection loss with a {@code
   * null} cause (an orderly close) if loss has not already been signaled.
   */
  void close() {
    teardown(null);
  }

  /**
   * Fully tears the channel down and surfaces the loss exactly once: clears {@code running}, closes
   * the line to release the OS file descriptor, stops the writer and reader threads, and releases
   * any unsent queued frames.
   *
   * <p>This is the single teardown path shared by the explicit {@link #close()}, by the reader
   * thread when it detects line loss, and by the writer thread when a serial write fails, so any
   * detected loss leaves the port closed and {@link #isOpen()} reporting {@code false} (rather than
   * leaking an open descriptor with a dead thread). It is idempotent and safe to call from any
   * thread: closing an already-closed line is a no-op, and {@link #signalLoss} fires {@code onLoss}
   * only on the first call. When invoked on the reader or writer thread itself (from a detected
   * loss) that thread's self-join is skipped to avoid deadlocking the thread on its own
   * termination. The writer thread is interrupted so it unblocks from a pending {@code take()} or
   * serial write; draining the queue after both threads have stopped releases every frame that was
   * never sent.
   *
   * @param cause the failure cause, or {@code null} for an orderly close.
   */
  private void teardown(@Nullable Throwable cause) {
    running = false;

    SerialLine serialLine = line;
    if (serialLine != null) {
      try {
        serialLine.close(); // releases the fd and unblocks the reader thread's pending read
      } catch (RuntimeException e) {
        LOGGER.debug("error closing serial port", e);
      }
    }

    Thread writer = writerThread;
    if (writer != null && writer != Thread.currentThread()) {
      writer.interrupt(); // unblock a pending take() or an in-progress write
      try {
        writer.join(WRITER_JOIN_TIMEOUT_MILLIS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }

    Thread reader = readerThread;
    if (reader != null && reader != Thread.currentThread()) {
      try {
        reader.join(READER_JOIN_TIMEOUT_MILLIS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }

    drainAndReleaseQueue();

    signalLoss(cause);
  }

  /**
   * Drains outbound frames in order and performs the actual serial write off the caller's thread.
   *
   * <p>Runs on the dedicated writer thread for the life of the channel. It blocks on {@link
   * BlockingQueue#take()} when idle (so {@link #write(ByteBuf)} stays non-blocking and low-latency)
   * and releases each frame after writing it. A failed or short write is surfaced as connection
   * loss via {@link #teardown}; an interrupt from teardown ends the loop. The {@code finally} drain
   * releases any frames still queued when the loop exits, so no buffer leaks on shutdown.
   *
   * @param serialLine the line to write to.
   */
  private void writeLoop(SerialLine serialLine) {
    try {
      while (running) {
        ByteBuf frame;
        try {
          frame = writeQueue.take();
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          return;
        }

        try {
          int length = frame.readableBytes();
          byte[] bytes = new byte[length];
          frame.getBytes(frame.readerIndex(), bytes);

          int written = serialLine.write(bytes, length);
          if (written != length) {
            if (running) {
              teardown(new IOException("serial write incomplete (" + written + "/" + length + ")"));
            }
            return;
          }
        } catch (RuntimeException e) {
          if (running) {
            teardown(e);
          }
          return;
        } finally {
          frame.release();
        }
      }
    } finally {
      drainAndReleaseQueue();
    }
  }

  /** Removes and releases every queued outbound frame; safe to call from any thread. */
  private void drainAndReleaseQueue() {
    ByteBuf frame;
    while ((frame = writeQueue.poll()) != null) {
      frame.release();
    }
  }

  private void readLoop(SerialLine serialLine, Ft12Deframer deframer) {
    byte[] buffer = new byte[READ_BUFFER_SIZE];
    try {
      while (running) {
        int read;
        try {
          read = serialLine.read(buffer, buffer.length);
        } catch (RuntimeException e) {
          if (running) {
            teardown(e);
          }
          return;
        }

        if (read < 0) {
          if (running) {
            teardown(new IOException("serial read failed (code " + read + ")"));
          }
          return;
        }
        if (read == 0) {
          continue; // semi-blocking read timed out with no data available
        }

        ByteBuf chunk = Unpooled.wrappedBuffer(buffer, 0, read);
        try {
          deframer.feed(chunk);
        } catch (RuntimeException e) {
          if (running) {
            teardown(e);
          }
          return;
        }
      }
    } finally {
      deframer.reset();
    }
  }

  /**
   * Drives the reader loop once against an injected {@link SerialLine} on the calling thread, for
   * deterministic testing of the loss/teardown paths without a real serial port.
   *
   * <p>The calling thread stands in for the reader thread, so a loss detected by {@code serialLine}
   * tears the channel down inline — closing the line, clearing {@code running}, and signaling loss
   * — without spawning a thread and without a self-join.
   *
   * @param serialLine the fake line whose reads drive the loop.
   */
  void runReaderLoopForTesting(SerialLine serialLine) {
    this.line = serialLine;
    this.readerThread = Thread.currentThread();
    this.running = true;
    Ft12Deframer deframer = new Ft12Deframer(1, ByteBufAllocator.DEFAULT, onFrame);
    readLoop(serialLine, deframer);
  }

  /**
   * Starts only the writer thread against an injected {@link SerialLine}, for deterministic testing
   * of the outbound write hand-off (queue enqueue, ordering, and write-failure-to-loss) without a
   * real serial port or a reader thread.
   *
   * <p>After this returns, {@link #write(ByteBuf)} enqueues frames that the writer drains, and
   * {@link #close()} stops and joins the writer exactly as in production.
   *
   * @param serialLine the fake line the writer thread writes to.
   */
  void startWriterForTesting(SerialLine serialLine) {
    this.line = serialLine;
    this.running = true;
    Thread writer = new Thread(() -> writeLoop(serialLine), "iec60870-serial-writer-test");
    writer.setDaemon(true);
    this.writerThread = writer;
    writer.start();
  }

  private void signalLoss(@Nullable Throwable cause) {
    if (lossSignaled.compareAndSet(false, true)) {
      try {
        onLoss.accept(cause);
      } catch (RuntimeException e) {
        LOGGER.warn("connection-loss callback threw", e);
      }
    }
  }

  /**
   * Logs a startup warning when the opened port looks like a high-latency USB-serial adapter (an
   * FTDI / FT232 family device), advising the operator to lower the OS latency timer so FT1.2
   * frames are not stalled in the driver.
   *
   * @param serialPort the freshly opened port to inspect.
   */
  private static void warnIfHighLatencyAdapter(SerialPort serialPort) {
    if (isHighLatencyAdapter(serialPort.getPortDescription())
        || isHighLatencyAdapter(serialPort.getDescriptivePortName())) {
      LOGGER.warn(
          "Detected an FTDI/FT232 USB-serial adapter ('{}'); its driver latency timer defaults to"
              + " ~16 ms, which delays delivery of short FT1.2 frames and can break link-layer"
              + " timing. Lower it to ~1 ms (Linux:"
              + " /sys/bus/usb-serial/devices/<port>/latency_timer; Windows: the FTDI driver's"
              + " advanced port settings) for timely FT1.2 framing.",
          serialPort.getDescriptivePortName());
    }
  }

  /**
   * Heuristically detects a known high-latency USB-serial adapter family from a port description or
   * name. FTDI-based adapters (matched case-insensitively on {@code FTDI} or {@code FT232}) ship
   * with a ~16 ms read latency timer by default, long enough to stall a short FT1.2 frame in the
   * driver.
   *
   * <p>Package-private and pure so it can be unit-tested without opening a real port.
   *
   * @param description the port description or descriptive name; may be {@code null} or blank.
   * @return {@code true} if {@code description} names a known high-latency adapter family.
   */
  static boolean isHighLatencyAdapter(@Nullable String description) {
    if (description == null || description.isBlank()) {
      return false;
    }
    String upper = description.toUpperCase(Locale.ROOT);
    return upper.contains("FTDI") || upper.contains("FT232");
  }

  /**
   * Minimal serial-line abstraction over the operations the reader loop and lifecycle need. The
   * production implementation ({@link JSerialCommLine}) wraps a {@code jSerialComm} {@link
   * SerialPort}; tests substitute a fake to drive the loss/teardown paths without a real OS port.
   */
  interface SerialLine {

    /**
     * Reads available octets into {@code buffer}, following {@code jSerialComm} semi-blocking
     * semantics: a positive count of octets read, {@code 0} on an idle timeout, or a negative code
     * on a read error.
     *
     * @param buffer the destination buffer.
     * @param length the maximum number of octets to read.
     * @return the number of octets read, {@code 0} on timeout, or a negative error code.
     * @throws RuntimeException if the underlying driver fails (for example, the line drops).
     */
    int read(byte[] buffer, int length);

    /**
     * Writes {@code length} octets from {@code data}.
     *
     * @param data the source buffer.
     * @param length the number of octets to write.
     * @return the number of octets actually written.
     */
    int write(byte[] data, int length);

    /**
     * Indicates whether the line is currently open.
     *
     * @return {@code true} if the line is open.
     */
    boolean isOpen();

    /** Closes the line, releasing the underlying OS resource. Idempotent. */
    void close();
  }

  /** The production {@link SerialLine}, delegating each operation to a {@code jSerialComm} port. */
  private static final class JSerialCommLine implements SerialLine {

    private final SerialPort serialPort;

    JSerialCommLine(SerialPort serialPort) {
      this.serialPort = serialPort;
    }

    @Override
    public int read(byte[] buffer, int length) {
      return serialPort.readBytes(buffer, length);
    }

    @Override
    public int write(byte[] data, int length) {
      return serialPort.writeBytes(data, length);
    }

    @Override
    public boolean isOpen() {
      return serialPort.isOpen();
    }

    @Override
    public void close() {
      serialPort.closePort();
    }
  }

  /**
   * Converts a timeout {@link Duration} to a positive {@code int} milliseconds suitable for {@code
   * jSerialComm}'s {@link SerialPort#setComPortTimeouts}, clamping defensively so the conversion
   * can never hand the native driver a degenerate timeout.
   *
   * <p>A sub-millisecond (or otherwise sub-{@code 1}-ms) duration is clamped up to {@code 1} ms,
   * because {@code jSerialComm} treats a read timeout of {@code 0} as block-indefinitely and would
   * stall the reader thread forever; a duration exceeding {@link Integer#MAX_VALUE} milliseconds is
   * clamped down to {@link Integer#MAX_VALUE} rather than overflowing {@code int} to a negative
   * value. {@link SerialPortConfig} already rejects out-of-range timeouts, so this is a second line
   * of defense at the driver boundary.
   *
   * @param timeout the configured timeout to convert.
   * @return the timeout in milliseconds, clamped to the range {@code 1} to {@link
   *     Integer#MAX_VALUE}.
   */
  static int toTimeoutMillis(Duration timeout) {
    long millis = timeout.toMillis();
    if (millis < 1) {
      return 1;
    }
    if (millis > Integer.MAX_VALUE) {
      return Integer.MAX_VALUE;
    }
    return (int) millis;
  }

  private static int mapParity(SerialPortConfig.Parity parity) {
    return switch (parity) {
      case NONE -> SerialPort.NO_PARITY;
      case EVEN -> SerialPort.EVEN_PARITY;
      case ODD -> SerialPort.ODD_PARITY;
    };
  }

  private static int mapStopBits(int stopBits) {
    return stopBits == 2 ? SerialPort.TWO_STOP_BITS : SerialPort.ONE_STOP_BIT;
  }
}
