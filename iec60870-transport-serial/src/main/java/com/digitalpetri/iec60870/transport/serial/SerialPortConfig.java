package com.digitalpetri.iec60870.transport.serial;

import java.time.Duration;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Immutable configuration for a serial octet transport: the physical port parameters plus the one
 * FT1.2 framing knob the incremental deframer needs.
 *
 * <p>The defaults describe a standard IEC 60870-5-101 link ({@code 9600} baud, 8 data bits, even
 * parity, 1 stop bit — "8E1"). Build instances with {@link #builder(String)}:
 *
 * <pre>{@code
 * SerialPortConfig config =
 *     SerialPortConfig.builder("/dev/ttyUSB0")
 *         .baudRate(19200)
 *         .linkAddressLength(2)
 *         .build();
 * }</pre>
 *
 * @param portName the system port name to open, for example {@code "/dev/ttyUSB0"} or {@code
 *     "COM3"}; must not be blank.
 * @param baudRate the symbol rate in bits per second; must be positive.
 * @param dataBits the number of data bits per character, in the range 5 to 8.
 * @param parity the parity scheme; FT1.2 links require {@link Parity#EVEN}.
 * @param stopBits the number of stop bits, either 1 or 2.
 * @param linkAddressLength the FT1.2 fixed-frame link-address width in octets, in the range 0 to 2,
 *     used only to size fixed-length frames during deframing. It is a plain {@code int} so this
 *     transport stays protocol-agnostic and depends on no link-layer module.
 * @param readTimeout the maximum inactivity before a blocking read returns; must be at least 1 ms
 *     and no more than {@link Integer#MAX_VALUE} ms (the driver takes it as int milliseconds).
 * @param writeTimeout the maximum time a blocking write may take; must be at least 1 ms and no more
 *     than {@link Integer#MAX_VALUE} ms (the driver takes it as int milliseconds).
 * @param rs485 the RS-485 half-duplex turnaround parameters, or {@code null} (the default) to leave
 *     the port in ordinary RS-232 / full-duplex mode; when non-null, RS-485 mode is enabled on the
 *     port.
 */
public record SerialPortConfig(
    String portName,
    int baudRate,
    int dataBits,
    Parity parity,
    int stopBits,
    int linkAddressLength,
    Duration readTimeout,
    Duration writeTimeout,
    @Nullable Rs485Options rs485) {

  /** The parity scheme applied to each transmitted and received character. */
  public enum Parity {
    /** No parity bit. */
    NONE,
    /** Even parity, required by FT1.2 (the "E" in 8E1). */
    EVEN,
    /** Odd parity. */
    ODD
  }

  /**
   * Validates the serial and framing parameters.
   *
   * @throws IllegalArgumentException if {@code portName} is blank, {@code baudRate} is not
   *     positive, {@code dataBits} is outside 5 to 8, {@code stopBits} is outside 1 to 2, {@code
   *     linkAddressLength} is outside 0 to 2, or either timeout is outside 1 ms to {@link
   *     Integer#MAX_VALUE} ms.
   * @throws NullPointerException if {@code parity}, {@code readTimeout}, or {@code writeTimeout} is
   *     {@code null}.
   */
  public SerialPortConfig {
    if (portName.isBlank()) {
      throw new IllegalArgumentException("portName must not be blank");
    }
    if (baudRate <= 0) {
      throw new IllegalArgumentException("baudRate must be > 0: " + baudRate);
    }
    if (dataBits < 5 || dataBits > 8) {
      throw new IllegalArgumentException("dataBits must be in 5..8: " + dataBits);
    }
    Objects.requireNonNull(parity, "parity");
    if (stopBits < 1 || stopBits > 2) {
      throw new IllegalArgumentException("stopBits must be in 1..2: " + stopBits);
    }
    if (linkAddressLength < 0 || linkAddressLength > 2) {
      throw new IllegalArgumentException("linkAddressLength must be in 0..2: " + linkAddressLength);
    }
    requireTimeout(readTimeout, "readTimeout");
    requireTimeout(writeTimeout, "writeTimeout");
  }

  /**
   * Creates a builder for the given port, pre-populated with the standard FT1.2 8E1 defaults:
   * {@code baudRate=9600}, {@code dataBits=8}, {@code parity=EVEN}, {@code stopBits=1}, {@code
   * linkAddressLength=1}, {@code readTimeout=100ms}, and {@code writeTimeout=1000ms}.
   *
   * @param portName the system port name to open, for example {@code "/dev/ttyUSB0"} or {@code
   *     "COM3"}.
   * @return a new builder seeded with the defaults.
   */
  public static Builder builder(String portName) {
    return new Builder(portName);
  }

  private static void requireTimeout(Duration value, String name) {
    Objects.requireNonNull(value, name);
    if (value.isZero() || value.isNegative()) {
      throw new IllegalArgumentException(name + " must be positive: " + value);
    }
    // The serial driver takes the timeout as an int of milliseconds, so a sub-millisecond value
    // would truncate to 0 (block-indefinitely) and a multi-day value would overflow int to a
    // negative. Pin the timeout to the representable, non-degenerate millisecond range here.
    long millis = value.toMillis();
    if (millis < 1) {
      throw new IllegalArgumentException(name + " must be at least 1 ms: " + value);
    }
    if (millis > Integer.MAX_VALUE) {
      throw new IllegalArgumentException(
          name + " must be at most " + Integer.MAX_VALUE + " ms: " + value);
    }
  }

  /**
   * A fluent builder for {@link SerialPortConfig} that starts from the standard FT1.2 8E1 defaults
   * and overrides only the parameters a caller cares about.
   */
  public static final class Builder {

    private final String portName;
    private int baudRate = 9600;
    private int dataBits = 8;
    private Parity parity = Parity.EVEN;
    private int stopBits = 1;
    private int linkAddressLength = 1;
    private Duration readTimeout = Duration.ofMillis(100);
    private Duration writeTimeout = Duration.ofMillis(1000);
    private @Nullable Rs485Options rs485;

    private Builder(String portName) {
      this.portName = portName;
    }

    /**
     * Sets the symbol rate in bits per second.
     *
     * @param baudRate the baud rate; must be positive.
     * @return this builder.
     */
    public Builder baudRate(int baudRate) {
      this.baudRate = baudRate;
      return this;
    }

    /**
     * Sets the number of data bits per character.
     *
     * @param dataBits the data bits, in the range 5 to 8.
     * @return this builder.
     */
    public Builder dataBits(int dataBits) {
      this.dataBits = dataBits;
      return this;
    }

    /**
     * Sets the parity scheme.
     *
     * @param parity the parity; FT1.2 links require {@link Parity#EVEN}.
     * @return this builder.
     */
    public Builder parity(Parity parity) {
      this.parity = parity;
      return this;
    }

    /**
     * Sets the number of stop bits.
     *
     * @param stopBits the stop bits, either 1 or 2.
     * @return this builder.
     */
    public Builder stopBits(int stopBits) {
      this.stopBits = stopBits;
      return this;
    }

    /**
     * Sets the FT1.2 link-address width used to size fixed-length frames during deframing.
     *
     * @param linkAddressLength the link-address width in octets, in the range 0 to 2.
     * @return this builder.
     */
    public Builder linkAddressLength(int linkAddressLength) {
      this.linkAddressLength = linkAddressLength;
      return this;
    }

    /**
     * Sets the maximum inactivity tolerated before a blocking read returns.
     *
     * @param readTimeout the read timeout; must be at least 1 ms and no more than {@link
     *     Integer#MAX_VALUE} ms.
     * @return this builder.
     */
    public Builder readTimeout(Duration readTimeout) {
      this.readTimeout = readTimeout;
      return this;
    }

    /**
     * Sets the maximum time a blocking write may take.
     *
     * @param writeTimeout the write timeout; must be at least 1 ms and no more than {@link
     *     Integer#MAX_VALUE} ms.
     * @return this builder.
     */
    public Builder writeTimeout(Duration writeTimeout) {
      this.writeTimeout = writeTimeout;
      return this;
    }

    /**
     * Enables RS-485 half-duplex mode and sets its turnaround parameters. Defaults to {@code null},
     * which leaves the port in ordinary RS-232 / full-duplex mode.
     *
     * @param rs485 the RS-485 options, or {@code null} to disable RS-485 mode.
     * @return this builder.
     */
    public Builder rs485(@Nullable Rs485Options rs485) {
      this.rs485 = rs485;
      return this;
    }

    /**
     * Builds the immutable configuration.
     *
     * @return a validated {@link SerialPortConfig}.
     * @throws IllegalArgumentException if any configured value is out of range.
     */
    public SerialPortConfig build() {
      return new SerialPortConfig(
          portName,
          baudRate,
          dataBits,
          parity,
          stopBits,
          linkAddressLength,
          readTimeout,
          writeTimeout,
          rs485);
    }
  }
}
