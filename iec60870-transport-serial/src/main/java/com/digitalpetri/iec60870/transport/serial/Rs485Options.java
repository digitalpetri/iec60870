package com.digitalpetri.iec60870.transport.serial;

/**
 * RS-485 half-duplex turnaround parameters for a two-wire (or four-wire) multidrop line, modeling
 * the driver-level RS-485 mode knobs of the underlying serial driver.
 *
 * <p>On a two-wire RS-485 bus the transmitter and receiver share one differential pair, so the
 * driver must switch the line direction around every transmission: assert the transmit enable
 * (typically via the RTS pin) before shifting out the first bit, then release it after the last bit
 * has drained. These options describe that turnaround. Attaching an instance to a {@link
 * SerialPortConfig} enables RS-485 mode on the port; leaving it unset (the default) leaves the port
 * in ordinary RS-232 / full-duplex mode.
 *
 * <h2>Where turnaround happens</h2>
 *
 * <p>The direction switch is performed entirely at the driver / UART layer and is <b>never metered
 * by the JVM</b>. Application code must therefore <b>never</b> toggle {@code setRTS} / {@code
 * clearRTS} around {@code writeBytes} to fake half-duplex turnaround from Java: the JVM cannot time
 * the release to the final stop bit, so it will either clip the tail of the frame or hold the line
 * busy and collide with the peer's reply. Configure these driver parameters instead and let the
 * driver own the timing.
 *
 * <h2>Platform caveat</h2>
 *
 * <p>Only enabling RS-485 mode is portable. Every parameter modeled here <em>beyond</em> that
 * switch — {@link #rtsActiveHigh()}, {@link #enableTermination()}, {@link #rxDuringTx()}, {@link
 * #delayBeforeSendMicros()}, and {@link #delayAfterSendMicros()} — is effective <b>only on
 * Linux</b>, where the driver applies them through the kernel {@code SER_RS485} ioctl. On Windows
 * the driver maps RS-485 mode to basic auto-RTS only ({@code RTS_CONTROL_TOGGLE}) and <b>cannot</b>
 * set the turnaround delays or bus termination, so these fields are silently ignored there;
 * reliable high-speed RS-485 on Windows therefore requires an adapter with auto-direction hardware
 * (an adapter that senses transmission and drives the enable line itself, with no OS
 * configuration). On macOS RS-485 mode is generally unsupported by the OS drivers altogether.
 *
 * <p>Build instances with {@link #builder()}:
 *
 * <pre>{@code
 * Rs485Options rs485 =
 *     Rs485Options.builder()
 *         .rtsActiveHigh(true)
 *         .delayAfterSendMicros(500)
 *         .build();
 * }</pre>
 *
 * @param rtsActiveHigh whether the RTS line is driven high (logical 1) while transmitting and low
 *     while receiving; effective only on Linux.
 * @param enableTermination whether to enable on-board RS-485 bus termination on drivers that
 *     support it; effective only on Linux.
 * @param rxDuringTx whether the receiver stays enabled during transmission, so transmitted data is
 *     also read back (the local echo seen on some two-wire buses); effective only on Linux.
 * @param delayBeforeSendMicros the time in microseconds to wait after asserting transmit mode
 *     before shifting out the first data bit; must not be negative; effective only on Linux.
 * @param delayAfterSendMicros the time in microseconds to wait after the last data bit has drained
 *     before releasing transmit mode; must not be negative; effective only on Linux.
 */
public record Rs485Options(
    boolean rtsActiveHigh,
    boolean enableTermination,
    boolean rxDuringTx,
    int delayBeforeSendMicros,
    int delayAfterSendMicros) {

  /**
   * Validates the turnaround delays.
   *
   * @throws IllegalArgumentException if {@code delayBeforeSendMicros} or {@code
   *     delayAfterSendMicros} is negative.
   */
  public Rs485Options {
    if (delayBeforeSendMicros < 0) {
      throw new IllegalArgumentException(
          "delayBeforeSendMicros must not be negative: " + delayBeforeSendMicros);
    }
    if (delayAfterSendMicros < 0) {
      throw new IllegalArgumentException(
          "delayAfterSendMicros must not be negative: " + delayAfterSendMicros);
    }
  }

  /**
   * Creates a builder seeded with sensible RS-485 defaults: {@code rtsActiveHigh=true} (RTS
   * asserted high while transmitting), {@code enableTermination=false}, {@code rxDuringTx=false},
   * and both turnaround delays {@code 0} (let the driver turn the line around as soon as the UART
   * reports the frame drained).
   *
   * @return a new builder seeded with the defaults.
   */
  public static Builder builder() {
    return new Builder();
  }

  /**
   * A fluent builder for {@link Rs485Options} that starts from the default turnaround settings and
   * overrides only the parameters a caller cares about.
   */
  public static final class Builder {

    private boolean rtsActiveHigh = true;
    private boolean enableTermination = false;
    private boolean rxDuringTx = false;
    private int delayBeforeSendMicros = 0;
    private int delayAfterSendMicros = 0;

    private Builder() {}

    /**
     * Sets whether the RTS line is driven high while transmitting and low while receiving. Defaults
     * to {@code true}. Effective only on Linux.
     *
     * @param rtsActiveHigh whether RTS is active-high during transmission.
     * @return this builder.
     */
    public Builder rtsActiveHigh(boolean rtsActiveHigh) {
      this.rtsActiveHigh = rtsActiveHigh;
      return this;
    }

    /**
     * Sets whether on-board RS-485 bus termination is enabled. Defaults to {@code false}. Effective
     * only on Linux.
     *
     * @param enableTermination whether to enable bus termination.
     * @return this builder.
     */
    public Builder enableTermination(boolean enableTermination) {
      this.enableTermination = enableTermination;
      return this;
    }

    /**
     * Sets whether the receiver stays enabled during transmission, reading back transmitted data.
     * Defaults to {@code false}. Effective only on Linux.
     *
     * @param rxDuringTx whether to receive while transmitting.
     * @return this builder.
     */
    public Builder rxDuringTx(boolean rxDuringTx) {
      this.rxDuringTx = rxDuringTx;
      return this;
    }

    /**
     * Sets the time in microseconds to wait after asserting transmit mode before shifting out the
     * first data bit. Defaults to {@code 0}. Effective only on Linux.
     *
     * @param delayBeforeSendMicros the pre-send delay in microseconds; must not be negative.
     * @return this builder.
     */
    public Builder delayBeforeSendMicros(int delayBeforeSendMicros) {
      this.delayBeforeSendMicros = delayBeforeSendMicros;
      return this;
    }

    /**
     * Sets the time in microseconds to wait after the last data bit drains before releasing
     * transmit mode. Defaults to {@code 0}. Effective only on Linux.
     *
     * @param delayAfterSendMicros the post-send delay in microseconds; must not be negative.
     * @return this builder.
     */
    public Builder delayAfterSendMicros(int delayAfterSendMicros) {
      this.delayAfterSendMicros = delayAfterSendMicros;
      return this;
    }

    /**
     * Builds the immutable options.
     *
     * @return a validated {@link Rs485Options}.
     * @throws IllegalArgumentException if either turnaround delay is negative.
     */
    public Rs485Options build() {
      return new Rs485Options(
          rtsActiveHigh,
          enableTermination,
          rxDuringTx,
          delayBeforeSendMicros,
          delayAfterSendMicros);
    }
  }
}
