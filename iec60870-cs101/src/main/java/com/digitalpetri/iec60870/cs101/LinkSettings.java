package com.digitalpetri.iec60870.cs101;

import com.digitalpetri.iec60870.SessionSettings;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * FT1.2 link-layer parameters that govern addressing, acknowledgement style, and the stop-and-wait
 * timers of an IEC 60870-5-101 link.
 *
 * <p>The {@link #mode() mode} selects between the balanced point-to-point machine and the
 * unbalanced master/secondary machine. The {@link #linkAddress() link address} and its {@link
 * #linkAddressLength() length} identify the station on the link: in balanced mode the address may
 * be absent (length {@code 0}), while in unbalanced mode it is always present (length {@code 1} or
 * {@code 2}). The {@link #broadcastAddress() broadcast address} is the all-secondaries address used
 * by an unbalanced master and is ignored in balanced mode. {@link #useSingleCharAck()} controls
 * whether a positive acknowledgement is emitted as the short {@code 0xE5} single-character frame
 * rather than a fixed-length frame. The three durations are the stop-and-wait timers:
 *
 * <ul>
 *   <li>{@code confirmTimeout} — how long the primary waits for an acknowledgement of a sent frame
 *       before repeating it.
 *   <li>{@code repeatTimeout} — the spacing between repeated transmissions of an unacknowledged
 *       frame.
 *   <li>{@code linkStateTimeout} — the idle interval after which the link status is polled.
 * </ul>
 *
 * <p>Build settings with {@link #balanced()} or {@link #unbalanced()}, both of which return a
 * {@link Builder} seeded with sensible defaults.
 *
 * @param mode the link mode that selects the balanced or unbalanced machine.
 * @param linkAddress the station's own (balanced) or target (unbalanced) link address; {@code 0}
 *     when absent, which is only valid in balanced mode with a zero address length.
 * @param linkAddressLength the link-address field width in octets, in the range {@code 0..2};
 *     {@code 0} (an absent address) is balanced-only and unbalanced mode requires at least {@code
 *     1}.
 * @param broadcastAddress the unbalanced all-secondaries broadcast address ({@code 255} for a
 *     one-octet address, {@code 65535} for a two-octet address); ignored in balanced mode.
 * @param useSingleCharAck whether a positive acknowledgement is emitted as the {@code 0xE5}
 *     single-character frame.
 * @param confirmTimeout the time to wait for an acknowledgement before repeating a frame; must be
 *     positive.
 * @param repeatTimeout the spacing between repeated transmissions of an unacknowledged frame; must
 *     be positive.
 * @param maxRetries the maximum number of repeat transmissions of an unacknowledged frame; must be
 *     {@code >= 0}.
 * @param linkStateTimeout the idle interval after which the link status is polled; must be
 *     positive.
 * @param pollConfig the unbalanced master's polling configuration (the secondary station addresses
 *     to poll and the poll cadence), or {@code null} when absent; meaningful only in {@link
 *     LinkMode#UNBALANCED} mode and ignored for a balanced link.
 */
public record LinkSettings(
    LinkMode mode,
    int linkAddress,
    int linkAddressLength,
    int broadcastAddress,
    boolean useSingleCharAck,
    Duration confirmTimeout,
    Duration repeatTimeout,
    int maxRetries,
    Duration linkStateTimeout,
    @Nullable PollConfig pollConfig)
    implements SessionSettings {

  /**
   * The polling configuration an unbalanced master uses to cyclically poll its secondary stations.
   *
   * <p>An unbalanced (master/slave) link has a single primary station that owns the bus and polls
   * each configured secondary in turn; this record carries the set of secondary {@code
   * slaveAddresses} to poll and the {@code pollInterval} cadence between poll cycles. It is
   * meaningful only for an unbalanced master and is ignored on a balanced link.
   *
   * @param slaveAddresses the secondary station link addresses to poll, in poll order; copied
   *     defensively and may be empty.
   * @param pollInterval the cadence between class-2 poll cycles; must be positive.
   */
  public record PollConfig(List<Integer> slaveAddresses, Duration pollInterval) {

    /**
     * Validates and defensively copies the components.
     *
     * @param slaveAddresses the secondary station link addresses to poll, in poll order; copied
     *     defensively and may be empty.
     * @param pollInterval the cadence between class-2 poll cycles; must be positive.
     * @throws NullPointerException if {@code slaveAddresses} (or any element) or {@code
     *     pollInterval} is null.
     * @throws IllegalArgumentException if any slave address is negative, or if {@code pollInterval}
     *     is zero or negative.
     */
    public PollConfig {
      Objects.requireNonNull(slaveAddresses, "slaveAddresses");
      Objects.requireNonNull(pollInterval, "pollInterval");
      slaveAddresses = List.copyOf(slaveAddresses);
      for (int address : slaveAddresses) {
        if (address < 0) {
          throw new IllegalArgumentException("slave address must be >= 0: " + address);
        }
      }
      if (pollInterval.isZero() || pollInterval.isNegative()) {
        throw new IllegalArgumentException("pollInterval must be positive: " + pollInterval);
      }
    }
  }

  /**
   * Validates the components.
   *
   * @param mode the link mode that selects the balanced or unbalanced machine.
   * @param linkAddress the station's own (balanced) or target (unbalanced) link address; {@code 0}
   *     when absent, which is only valid in balanced mode with a zero address length.
   * @param linkAddressLength the link-address field width in octets, in the range {@code 0..2};
   *     {@code 0} (an absent address) is balanced-only and unbalanced mode requires at least {@code
   *     1}.
   * @param broadcastAddress the unbalanced all-secondaries broadcast address ({@code 255} for a
   *     one-octet address, {@code 65535} for a two-octet address); ignored in balanced mode.
   * @param useSingleCharAck whether a positive acknowledgement is emitted as the {@code 0xE5}
   *     single-character frame.
   * @param confirmTimeout the time to wait for an acknowledgement before repeating a frame; must be
   *     positive.
   * @param repeatTimeout the spacing between repeated transmissions of an unacknowledged frame;
   *     must be positive.
   * @param maxRetries the maximum number of repeat transmissions of an unacknowledged frame; must
   *     be {@code >= 0}.
   * @param linkStateTimeout the idle interval after which the link status is polled; must be
   *     positive.
   * @param pollConfig the unbalanced master's polling configuration, or {@code null} when absent;
   *     meaningful only in {@link LinkMode#UNBALANCED} mode and ignored for a balanced link.
   * @throws NullPointerException if {@code mode} or any duration is null.
   * @throws IllegalArgumentException if {@code linkAddressLength} is not in {@code 0..2}, if {@code
   *     mode} is {@link LinkMode#UNBALANCED} with a zero address length, if {@code linkAddress} is
   *     out of range for its length, if {@code broadcastAddress} is not in {@code 0..65535} or does
   *     not match the address length under {@link LinkMode#UNBALANCED}, if a {@code pollConfig}
   *     slave address is out of range for the address length or (under {@link LinkMode#UNBALANCED})
   *     equals the broadcast address, if {@code maxRetries} is negative, or if any duration is zero
   *     or negative.
   */
  public LinkSettings {
    Objects.requireNonNull(mode, "mode");
    Objects.requireNonNull(confirmTimeout, "confirmTimeout");
    Objects.requireNonNull(repeatTimeout, "repeatTimeout");
    Objects.requireNonNull(linkStateTimeout, "linkStateTimeout");

    if (linkAddressLength < 0 || linkAddressLength > 2) {
      throw new IllegalArgumentException("linkAddressLength must be in 0..2: " + linkAddressLength);
    }
    if (mode == LinkMode.UNBALANCED && linkAddressLength < 1) {
      throw new IllegalArgumentException(
          "UNBALANCED requires linkAddressLength >= 1: " + linkAddressLength);
    }

    if (linkAddress < 0) {
      throw new IllegalArgumentException("linkAddress must be >= 0: " + linkAddress);
    }
    switch (linkAddressLength) {
      case 0 -> {
        if (linkAddress != 0) {
          throw new IllegalArgumentException(
              "linkAddress must be 0 when linkAddressLength is 0: " + linkAddress);
        }
      }
      case 1 -> {
        if (linkAddress > 255) {
          throw new IllegalArgumentException(
              "linkAddress must be in 0..255 when linkAddressLength is 1: " + linkAddress);
        }
      }
      default -> {
        if (linkAddress > 65535) {
          throw new IllegalArgumentException(
              "linkAddress must be in 0..65535 when linkAddressLength is 2: " + linkAddress);
        }
      }
    }

    if (broadcastAddress < 0 || broadcastAddress > 65535) {
      throw new IllegalArgumentException(
          "broadcastAddress must be in 0..65535: " + broadcastAddress);
    }
    if (mode == LinkMode.UNBALANCED) {
      int expected = maxLinkAddress(linkAddressLength);
      if (broadcastAddress != expected) {
        throw new IllegalArgumentException(
            "broadcastAddress must be "
                + expected
                + " when UNBALANCED with linkAddressLength "
                + linkAddressLength
                + ": "
                + broadcastAddress);
      }
    }

    if (pollConfig != null) {
      int maxAddress = maxLinkAddress(linkAddressLength);
      for (int address : pollConfig.slaveAddresses()) {
        if (address > maxAddress) {
          throw new IllegalArgumentException(
              "slave address "
                  + address
                  + " is out of range for linkAddressLength "
                  + linkAddressLength
                  + " (0.."
                  + maxAddress
                  + ")");
        }
        if (mode == LinkMode.UNBALANCED && address == broadcastAddress) {
          throw new IllegalArgumentException(
              "slave address must not be the broadcast address "
                  + broadcastAddress
                  + ": "
                  + address);
        }
      }
    }

    if (maxRetries < 0) {
      throw new IllegalArgumentException("maxRetries must be >= 0: " + maxRetries);
    }
    requirePositive(confirmTimeout, "confirmTimeout");
    requirePositive(repeatTimeout, "repeatTimeout");
    requirePositive(linkStateTimeout, "linkStateTimeout");
  }

  private static void requirePositive(Duration duration, String name) {
    if (duration.isZero() || duration.isNegative()) {
      throw new IllegalArgumentException(name + " must be positive: " + duration);
    }
  }

  /**
   * Returns the maximum link address representable in {@code linkAddressLength} little-endian
   * octets.
   *
   * @param linkAddressLength the address width in octets, in the range {@code 0..2}.
   * @return the inclusive maximum address ({@code 0} for length {@code 0}, {@code 255} for length
   *     {@code 1}, {@code 65535} for length {@code 2}).
   */
  private static int maxLinkAddress(int linkAddressLength) {
    return (1 << (8 * linkAddressLength)) - 1;
  }

  /**
   * Returns a builder for {@linkplain LinkMode#BALANCED balanced} link settings, seeded with the
   * defaults.
   *
   * <p>The defaults are a one-octet link address of {@code 1}, single-character acknowledgements
   * enabled, a {@code 200 ms} confirm timeout, a {@code 1000 ms} repeat timeout, three retries, and
   * a {@code 5000 ms} link-state timeout.
   *
   * @return a new builder in balanced mode.
   */
  public static Builder balanced() {
    return new Builder(LinkMode.BALANCED);
  }

  /**
   * Returns a builder for {@linkplain LinkMode#UNBALANCED unbalanced} link settings, seeded with
   * the defaults.
   *
   * <p>The defaults are a one-octet link address of {@code 1}, a broadcast address of {@code 255},
   * single-character acknowledgements enabled, a {@code 200 ms} confirm timeout, a {@code 1000 ms}
   * repeat timeout, three retries, a {@code 5000 ms} link-state timeout, and a master poll
   * configuration with an empty slave list polled every {@code 1000 ms} (configure it with {@link
   * Builder#slaveAddresses(List)} and {@link Builder#pollInterval(Duration)}).
   *
   * @return a new builder in unbalanced mode.
   */
  public static Builder unbalanced() {
    return new Builder(LinkMode.UNBALANCED);
  }

  /**
   * A builder for {@link LinkSettings}.
   *
   * <p>Obtain a builder from {@link LinkSettings#balanced()} or {@link LinkSettings#unbalanced()};
   * the mode is fixed by that choice and every other field starts at a sensible default, so a
   * usable settings object can be built with no setter calls at all. The builder is not
   * thread-safe; build a settings object on one thread and share the immutable result.
   */
  public static final class Builder {

    private final LinkMode mode;
    private int linkAddress = 1;
    private int linkAddressLength = 1;
    private int broadcastAddress = 255;
    private boolean useSingleCharAck = true;
    private Duration confirmTimeout = Duration.ofMillis(200);
    private Duration repeatTimeout = Duration.ofMillis(1000);
    private int maxRetries = 3;
    private Duration linkStateTimeout = Duration.ofMillis(5000);
    private @Nullable PollConfig pollConfig;

    private Builder(LinkMode mode) {
      this.mode = mode;
      // Balanced links carry no poll configuration; an unbalanced master defaults to an empty
      // slave list polled every 1000 ms until the caller configures it.
      this.pollConfig =
          mode == LinkMode.UNBALANCED ? new PollConfig(List.of(), Duration.ofMillis(1000)) : null;
    }

    /**
     * Sets the station's own (balanced) or target (unbalanced) link address. Defaults to {@code 1}.
     *
     * @param linkAddress the link address; must be in range for the configured address length.
     * @return this builder.
     */
    public Builder linkAddress(int linkAddress) {
      this.linkAddress = linkAddress;
      return this;
    }

    /**
     * Sets the link-address field width in octets. Defaults to {@code 1}.
     *
     * @param linkAddressLength the address length, in the range {@code 0..2}; {@code 0} (an absent
     *     address) is balanced-only.
     * @return this builder.
     */
    public Builder linkAddressLength(int linkAddressLength) {
      this.linkAddressLength = linkAddressLength;
      return this;
    }

    /**
     * Sets the unbalanced all-secondaries broadcast address. Defaults to {@code 255}; ignored in
     * balanced mode.
     *
     * @param broadcastAddress the broadcast address ({@code 255} for a one-octet address, {@code
     *     65535} for a two-octet address).
     * @return this builder.
     */
    public Builder broadcastAddress(int broadcastAddress) {
      this.broadcastAddress = broadcastAddress;
      return this;
    }

    /**
     * Sets whether a positive acknowledgement is emitted as the {@code 0xE5} single-character
     * frame. Defaults to {@code true}.
     *
     * @param useSingleCharAck whether to use single-character acknowledgements.
     * @return this builder.
     */
    public Builder useSingleCharAck(boolean useSingleCharAck) {
      this.useSingleCharAck = useSingleCharAck;
      return this;
    }

    /**
     * Sets the time to wait for an acknowledgement before repeating a frame. Defaults to {@code 200
     * ms}.
     *
     * @param confirmTimeout the confirm timeout; must be positive.
     * @return this builder.
     */
    public Builder confirmTimeout(Duration confirmTimeout) {
      this.confirmTimeout = Objects.requireNonNull(confirmTimeout, "confirmTimeout");
      return this;
    }

    /**
     * Sets the spacing between repeated transmissions of an unacknowledged frame. Defaults to
     * {@code 1000 ms}.
     *
     * @param repeatTimeout the repeat timeout; must be positive.
     * @return this builder.
     */
    public Builder repeatTimeout(Duration repeatTimeout) {
      this.repeatTimeout = Objects.requireNonNull(repeatTimeout, "repeatTimeout");
      return this;
    }

    /**
     * Sets the maximum number of repeat transmissions of an unacknowledged frame. Defaults to
     * {@code 3}.
     *
     * @param maxRetries the maximum retry count; must be {@code >= 0}.
     * @return this builder.
     */
    public Builder maxRetries(int maxRetries) {
      this.maxRetries = maxRetries;
      return this;
    }

    /**
     * Sets the idle interval after which the link status is polled. Defaults to {@code 5000 ms}.
     *
     * @param linkStateTimeout the link-state timeout; must be positive.
     * @return this builder.
     */
    public Builder linkStateTimeout(Duration linkStateTimeout) {
      this.linkStateTimeout = Objects.requireNonNull(linkStateTimeout, "linkStateTimeout");
      return this;
    }

    /**
     * Sets the secondary station link addresses an unbalanced master polls, in poll order.
     *
     * <p>Populates the {@link PollConfig} carried by the built settings, preserving any {@linkplain
     * #pollInterval(Duration) poll interval} already configured (defaulting to {@code 1000 ms}).
     * Only meaningful for an {@linkplain LinkSettings#unbalanced() unbalanced} master; ignored on a
     * balanced link.
     *
     * @param slaveAddresses the secondary station link addresses to poll; copied defensively, may
     *     be empty, and each address must be {@code >= 0} and within range for the configured
     *     {@linkplain #linkAddressLength(int) address length} (the range is validated when {@link
     *     #build()} is called).
     * @return this builder.
     */
    public Builder slaveAddresses(List<Integer> slaveAddresses) {
      Objects.requireNonNull(slaveAddresses, "slaveAddresses");
      Duration interval = pollConfig != null ? pollConfig.pollInterval() : Duration.ofMillis(1000);
      this.pollConfig = new PollConfig(slaveAddresses, interval);
      return this;
    }

    /**
     * Sets the cadence between class-2 poll cycles for an unbalanced master.
     *
     * <p>Populates the {@link PollConfig} carried by the built settings, preserving any {@linkplain
     * #slaveAddresses(List) slave addresses} already configured (defaulting to an empty list). Only
     * meaningful for an {@linkplain LinkSettings#unbalanced() unbalanced} master; ignored on a
     * balanced link.
     *
     * @param pollInterval the poll cadence; must be positive.
     * @return this builder.
     */
    public Builder pollInterval(Duration pollInterval) {
      Objects.requireNonNull(pollInterval, "pollInterval");
      List<Integer> slaves = pollConfig != null ? pollConfig.slaveAddresses() : List.of();
      this.pollConfig = new PollConfig(slaves, pollInterval);
      return this;
    }

    /**
     * Builds an immutable {@link LinkSettings} from the current builder state.
     *
     * @return the settings.
     * @throws IllegalArgumentException if the configured values are inconsistent (see the {@link
     *     LinkSettings} constructor).
     */
    public LinkSettings build() {
      return new LinkSettings(
          mode,
          linkAddress,
          linkAddressLength,
          broadcastAddress,
          useSingleCharAck,
          confirmTimeout,
          repeatTimeout,
          maxRetries,
          linkStateTimeout,
          pollConfig);
    }
  }
}
