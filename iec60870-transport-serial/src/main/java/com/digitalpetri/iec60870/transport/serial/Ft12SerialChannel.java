package com.digitalpetri.iec60870.transport.serial;

import com.fazecast.jSerialComm.SerialPort;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.Unpooled;
import java.io.IOException;
import java.util.Locale;
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
 * <p>Reads run on the reader thread; {@link #write(ByteBuf)} runs on the caller's thread. A channel
 * is single-use: {@link #close()} stops the reader and closes the port, and a new channel must be
 * created to reconnect.
 */
class Ft12SerialChannel {

  private static final Logger LOGGER = LoggerFactory.getLogger(Ft12SerialChannel.class);

  /** Size of the reusable read buffer; comfortably larger than the 261-octet max FT1.2 frame. */
  private static final int READ_BUFFER_SIZE = 1024;

  /** Time to wait for the reader thread to exit when closing. */
  private static final long READER_JOIN_TIMEOUT_MILLIS = 2000;

  private final Consumer<ByteBuf> onFrame;
  private final Consumer<@Nullable Throwable> onLoss;

  private final AtomicBoolean lossSignaled = new AtomicBoolean(false);

  private volatile boolean running = false;
  private volatile @Nullable SerialPort port;
  private volatile @Nullable Thread readerThread;

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
        (int) config.readTimeout().toMillis(),
        (int) config.writeTimeout().toMillis());

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

    this.port = serialPort;
    Ft12Deframer deframer =
        new Ft12Deframer(config.linkAddressLength(), ByteBufAllocator.DEFAULT, onFrame);
    this.running = true;

    Thread thread =
        new Thread(
            () -> readLoop(serialPort, deframer), "iec60870-serial-reader-" + config.portName());
    thread.setDaemon(true);
    this.readerThread = thread;
    thread.start();
  }

  /**
   * Indicates whether the underlying serial port is currently open.
   *
   * @return {@code true} if the port is open, {@code false} otherwise.
   */
  boolean isOpen() {
    SerialPort serialPort = port;
    return serialPort != null && serialPort.isOpen();
  }

  /**
   * Writes one complete frame to the serial line and releases it.
   *
   * <p>The caller must not release {@code frame}; this method releases it whether the write
   * succeeds or fails.
   *
   * @param frame the whole frame to send; ownership transfers to this channel.
   * @throws IOException if the port is not open or the write does not complete.
   */
  void write(ByteBuf frame) throws IOException {
    try {
      SerialPort serialPort = port;
      if (serialPort == null || !serialPort.isOpen()) {
        throw new IOException("serial port is not open");
      }

      int length = frame.readableBytes();
      byte[] bytes = new byte[length];
      frame.getBytes(frame.readerIndex(), bytes);

      int written = serialPort.writeBytes(bytes, length);
      if (written != length) {
        throw new IOException("serial write incomplete (" + written + "/" + length + ")");
      }
    } finally {
      frame.release();
    }
  }

  /**
   * Stops the reader thread, closes the port, and signals connection loss with a {@code null} cause
   * (an orderly close) if loss has not already been signaled.
   */
  void close() {
    running = false;

    SerialPort serialPort = port;
    if (serialPort != null) {
      try {
        serialPort.closePort(); // unblocks the reader thread's pending read
      } catch (RuntimeException e) {
        LOGGER.debug("error closing serial port", e);
      }
    }

    Thread thread = readerThread;
    if (thread != null && thread != Thread.currentThread()) {
      try {
        thread.join(READER_JOIN_TIMEOUT_MILLIS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }

    signalLoss(null);
  }

  private void readLoop(SerialPort serialPort, Ft12Deframer deframer) {
    byte[] buffer = new byte[READ_BUFFER_SIZE];
    try {
      while (running) {
        int read;
        try {
          read = serialPort.readBytes(buffer, buffer.length);
        } catch (RuntimeException e) {
          if (running) {
            signalLoss(e);
          }
          return;
        }

        if (read < 0) {
          if (running) {
            signalLoss(new IOException("serial read failed (code " + read + ")"));
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
            signalLoss(e);
          }
          return;
        }
      }
    } finally {
      deframer.reset();
    }
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
