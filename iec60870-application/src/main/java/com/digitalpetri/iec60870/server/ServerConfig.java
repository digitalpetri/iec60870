package com.digitalpetri.iec60870.server;

import com.digitalpetri.iec60870.OutboundQueuePolicy;
import com.digitalpetri.iec60870.ProtocolProfile;
import com.digitalpetri.iec60870.SessionSettings;
import com.digitalpetri.iec60870.point.TimeTagStyle;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import org.jspecify.annotations.Nullable;

/**
 * Immutable configuration for an {@link Iec60870Server}.
 *
 * <p>This configuration covers the protocol-layer behavior of the server: the wire profile, the
 * APCI flow-control parameters, the hosted stations, the request handler, the policy for a full
 * outbound queue, the bound on each connection's outbound queue, the time-tag style used when
 * reporting monitor data, the maximum number of concurrent connections, and the executor that
 * delivers events and runs handler callbacks. Transport concerns (host, port, TLS) are configured
 * on the transport, not here.
 *
 * <p>Build a configuration with the {@linkplain #builder() builder}; unset fields take sensible
 * defaults:
 *
 * <pre>{@code
 * ServerConfig config = ServerConfig.builder()
 *     .station(Station.builder(CommonAddress.of(1)).point(definition).build())
 *     .handler(myHandler)
 *     .build();
 * }</pre>
 *
 * @param protocolProfile the wire field widths.
 * @param sessionSettings the protocol-specific session settings (the APCI flow-control parameters
 *     for a 104 session).
 * @param stations the stations hosted by the server; each must have a distinct common address.
 * @param handler the handler that answers control-direction requests.
 * @param eventQueuePolicy the policy applied when a started connection's outbound queue is full.
 * @param maxOutboundQueue the bound on each started connection's outbound send queue, or {@code 0}
 *     for an unbounded queue.
 * @param outboundBlockTimeout the maximum time a publishing thread waits for outbound-queue
 *     capacity under the {@link OutboundQueuePolicy#BLOCK} policy before giving up on a value.
 * @param timeTagStyle the time-tag style used when reporting monitor data (interrogation answers
 *     and published updates).
 * @param maxConnections the maximum number of concurrent controlling-station connections.
 * @param callbackExecutor the executor used to deliver events and run handler callbacks.
 */
public record ServerConfig(
    ProtocolProfile protocolProfile,
    @Nullable SessionSettings sessionSettings,
    List<Station> stations,
    ServerHandler handler,
    OutboundQueuePolicy eventQueuePolicy,
    int maxOutboundQueue,
    Duration outboundBlockTimeout,
    TimeTagStyle timeTagStyle,
    int maxConnections,
    Executor callbackExecutor) {

  /**
   * Validates the configuration and defensively copies the station list.
   *
   * @param protocolProfile the wire field widths.
   * @param sessionSettings the protocol-specific session settings (the APCI flow-control parameters
   *     for a 104 session).
   * @param stations the stations hosted by the server; each must have a distinct common address.
   * @param handler the handler that answers control-direction requests.
   * @param eventQueuePolicy the policy applied when a started connection's outbound queue is full.
   * @param maxOutboundQueue the bound on each started connection's outbound send queue, or {@code
   *     0} for an unbounded queue.
   * @param outboundBlockTimeout the maximum time a publishing thread waits for outbound-queue
   *     capacity under the {@link OutboundQueuePolicy#BLOCK} policy before giving up on a value.
   * @param timeTagStyle the time-tag style used when reporting monitor data (interrogation answers
   *     and published updates).
   * @param maxConnections the maximum number of concurrent controlling-station connections.
   * @param callbackExecutor the executor used to deliver events and run handler callbacks.
   * @throws NullPointerException if any non-primitive component is null.
   * @throws IllegalArgumentException if {@code maxConnections} is not positive, {@code
   *     maxOutboundQueue} is negative, or {@code outboundBlockTimeout} is not positive.
   */
  public ServerConfig {
    Objects.requireNonNull(protocolProfile, "protocolProfile");
    Objects.requireNonNull(stations, "stations");
    Objects.requireNonNull(handler, "handler");
    Objects.requireNonNull(eventQueuePolicy, "eventQueuePolicy");
    Objects.requireNonNull(outboundBlockTimeout, "outboundBlockTimeout");
    Objects.requireNonNull(timeTagStyle, "timeTagStyle");
    Objects.requireNonNull(callbackExecutor, "callbackExecutor");
    if (maxConnections < 1) {
      throw new IllegalArgumentException("maxConnections must be positive: " + maxConnections);
    }
    if (maxOutboundQueue < 0) {
      throw new IllegalArgumentException("maxOutboundQueue must be >= 0: " + maxOutboundQueue);
    }
    if (outboundBlockTimeout.isZero() || outboundBlockTimeout.isNegative()) {
      throw new IllegalArgumentException(
          "outboundBlockTimeout must be positive: " + outboundBlockTimeout);
    }
    stations = List.copyOf(stations);
  }

  /**
   * Returns a configuration builder seeded with the defaults.
   *
   * @return a new builder.
   */
  public static Builder builder() {
    return new Builder();
  }

  /**
   * A builder for {@link ServerConfig}.
   *
   * <p>Every setter except the stations and handler has a sensible default. The builder is not
   * thread-safe; build a configuration on one thread and share the immutable result.
   */
  public static final class Builder {

    private ProtocolProfile protocolProfile = ProtocolProfile.iec104Default();
    private @Nullable SessionSettings sessionSettings;
    private final List<Station> stations = new ArrayList<>();
    private ServerHandler handler = new ServerHandler() {};
    private OutboundQueuePolicy eventQueuePolicy = OutboundQueuePolicy.DEFAULT;
    private int maxOutboundQueue = 1000;
    private Duration outboundBlockTimeout = Duration.ofSeconds(15);
    private TimeTagStyle timeTagStyle = TimeTagStyle.CP56;
    private int maxConnections = 16;
    private Executor callbackExecutor = ForkJoinPool.commonPool();

    private Builder() {}

    /**
     * Sets the wire field widths.
     *
     * @param protocolProfile the protocol profile.
     * @return this builder.
     */
    public Builder protocolProfile(ProtocolProfile protocolProfile) {
      this.protocolProfile = Objects.requireNonNull(protocolProfile, "protocolProfile");
      return this;
    }

    /**
     * Sets the protocol-specific session settings (the APCI flow-control parameters for a 104
     * session).
     *
     * @param sessionSettings the session settings.
     * @return this builder.
     */
    public Builder sessionSettings(SessionSettings sessionSettings) {
      this.sessionSettings = Objects.requireNonNull(sessionSettings, "sessionSettings");
      return this;
    }

    /**
     * Adds a station to the server.
     *
     * @param station the station to host.
     * @return this builder.
     * @throws NullPointerException if {@code station} is null.
     */
    public Builder station(Station station) {
      this.stations.add(Objects.requireNonNull(station, "station"));
      return this;
    }

    /**
     * Adds several stations to the server.
     *
     * @param stations the stations to host.
     * @return this builder.
     * @throws NullPointerException if {@code stations} or any element is null.
     */
    public Builder stations(List<Station> stations) {
      Objects.requireNonNull(stations, "stations");
      for (Station station : stations) {
        station(station);
      }
      return this;
    }

    /**
     * Sets the handler that answers control-direction requests. Defaults to a handler that uses
     * every default behavior.
     *
     * @param handler the server handler.
     * @return this builder.
     */
    public Builder handler(ServerHandler handler) {
      this.handler = Objects.requireNonNull(handler, "handler");
      return this;
    }

    /**
     * Sets the policy applied when a started connection's outbound queue is full. Defaults to
     * {@link OutboundQueuePolicy#DEFAULT}.
     *
     * @param eventQueuePolicy the event-queue policy.
     * @return this builder.
     */
    public Builder eventQueuePolicy(OutboundQueuePolicy eventQueuePolicy) {
      this.eventQueuePolicy = Objects.requireNonNull(eventQueuePolicy, "eventQueuePolicy");
      return this;
    }

    /**
     * Sets the bound on each started connection's outbound send queue. Defaults to {@code 1000}; a
     * value of {@code 0} leaves the queue unbounded. When the queue reaches this bound the
     * configured {@link OutboundQueuePolicy} decides the fate of a newly published value.
     *
     * @param maxOutboundQueue the outbound queue bound, or {@code 0} for unbounded; must not be
     *     negative.
     * @return this builder.
     */
    public Builder maxOutboundQueue(int maxOutboundQueue) {
      this.maxOutboundQueue = maxOutboundQueue;
      return this;
    }

    /**
     * Sets the maximum time a publishing thread waits for outbound-queue capacity under the {@link
     * OutboundQueuePolicy#BLOCK} policy before giving up on a value. Defaults to {@code 15s}.
     *
     * @param outboundBlockTimeout the block-await timeout; must be positive.
     * @return this builder.
     */
    public Builder outboundBlockTimeout(Duration outboundBlockTimeout) {
      this.outboundBlockTimeout =
          Objects.requireNonNull(outboundBlockTimeout, "outboundBlockTimeout");
      return this;
    }

    /**
     * Sets the time-tag style used when reporting monitor data. Defaults to {@link
     * TimeTagStyle#CP56}.
     *
     * @param timeTagStyle the time-tag style.
     * @return this builder.
     */
    public Builder timeTagStyle(TimeTagStyle timeTagStyle) {
      this.timeTagStyle = Objects.requireNonNull(timeTagStyle, "timeTagStyle");
      return this;
    }

    /**
     * Sets the maximum number of concurrent controlling-station connections. Defaults to {@code
     * 16}.
     *
     * @param maxConnections the maximum number of connections; must be positive.
     * @return this builder.
     */
    public Builder maxConnections(int maxConnections) {
      this.maxConnections = maxConnections;
      return this;
    }

    /**
     * Sets the executor used to deliver events and run handler callbacks. Defaults to the common
     * {@link ForkJoinPool}.
     *
     * <p>Per-connection callbacks are serialized regardless of the executor's parallelism, but the
     * executor should preserve submission order for event delivery (a single-threaded executor is
     * the simplest such choice).
     *
     * @param callbackExecutor the callback executor.
     * @return this builder.
     */
    public Builder callbackExecutor(Executor callbackExecutor) {
      this.callbackExecutor = Objects.requireNonNull(callbackExecutor, "callbackExecutor");
      return this;
    }

    /**
     * Builds an immutable {@link ServerConfig} from the current builder state.
     *
     * @return the configuration.
     * @throws IllegalArgumentException if {@code maxConnections} is not positive or {@code
     *     maxOutboundQueue} is negative.
     */
    public ServerConfig build() {
      return new ServerConfig(
          protocolProfile,
          sessionSettings,
          stations,
          handler,
          eventQueuePolicy,
          maxOutboundQueue,
          outboundBlockTimeout,
          timeTagStyle,
          maxConnections,
          callbackExecutor);
    }
  }
}
