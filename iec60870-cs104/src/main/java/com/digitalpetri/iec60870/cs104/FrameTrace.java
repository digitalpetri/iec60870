package com.digitalpetri.iec60870.cs104;

import com.digitalpetri.iec60870.diagnostics.Direction;
import io.netty.buffer.ByteBuf;
import java.util.Objects;
import org.slf4j.Logger;

/**
 * Formats and logs IEC 60870-5-104 frames for a frame trace.
 *
 * <p>{@link #logger(Logger)} returns an {@link ApciDiagnostics} that writes one trace-level line
 * per frame, for example:
 *
 * <pre>
 * OUT I(N(S)=0 N(R)=0) 68 0e 00 00 00 00 64 01 06 00 01 00 00 00 00 14
 * IN U(TESTFR_CON) 68 04 83 00 00 00
 * </pre>
 *
 * <p>The trace runs only while the logger has trace enabled, so changing the logger level at
 * runtime turns it on and off. Each line is logged with the connection's logging context in the
 * MDC. Combine it with other listeners through {@link ApciDiagnostics#of(ApciDiagnostics...)}.
 */
public final class FrameTrace {

  private static final char[] HEX = "0123456789abcdef".toCharArray();

  private FrameTrace() {}

  /**
   * Returns a listener that logs every frame at trace level while {@code logger} has trace enabled.
   *
   * <p>When the logger is shared by several connections, the {@code iec60870.remote} MDC entry and
   * any configured {@code loggingContext} entries tell the lines apart.
   *
   * @param logger the logger to write to.
   * @return the frame-trace listener.
   */
  public static ApciDiagnostics logger(Logger logger) {
    Objects.requireNonNull(logger, "logger");
    return new ApciDiagnostics() {
      @Override
      public boolean traceFrames() {
        return logger.isTraceEnabled();
      }

      @Override
      public void onFrame(Direction direction, ByteBuf frame) {
        logger.trace(format(direction, frame));
      }

      @Override
      public String toString() {
        return "FrameTrace[" + logger.getName() + "]";
      }
    };
  }

  /**
   * Formats a frame as its direction, decoded control field, and octets in hex.
   *
   * <p>A frame too short to hold a control field, or whose control field does not decode, shows
   * {@code ?} in place of the control field. Reads the readable octets without moving the buffer's
   * indexes.
   *
   * @param direction whether the frame was sent or received.
   * @param frame the whole frame, starting at the start octet.
   * @return the formatted line.
   */
  public static String format(Direction direction, ByteBuf frame) {
    Objects.requireNonNull(direction, "direction");
    int start = frame.readerIndex();
    int length = frame.readableBytes();
    var line = new StringBuilder(16 + length * 3);
    line.append(direction).append(' ').append(control(frame, start, length));
    for (int i = 0; i < length; i++) {
      int octet = frame.getUnsignedByte(start + i);
      line.append(' ').append(HEX[octet >> 4]).append(HEX[octet & 0x0F]);
    }
    return line.toString();
  }

  /**
   * Describes a control field as {@code I(N(S)=n N(R)=n)}, {@code S(N(R)=n)}, or {@code
   * U(FUNCTION)}.
   *
   * @param control the control field.
   * @return the description.
   */
  public static String describe(ControlField control) {
    if (control instanceof ControlField.TypeI i) {
      return "I(N(S)=" + i.sendSequenceNumber() + " N(R)=" + i.receiveSequenceNumber() + ")";
    } else if (control instanceof ControlField.TypeS s) {
      return "S(N(R)=" + s.receiveSequenceNumber() + ")";
    } else {
      return "U(" + ((ControlField.TypeU) control).function() + ")";
    }
  }

  private static String control(ByteBuf frame, int start, int length) {
    if (length < 6) {
      return "?";
    }
    try {
      return describe(ControlField.Serde.decode(frame.slice(start + 2, 4)));
    } catch (RuntimeException e) {
      return "?";
    }
  }
}
