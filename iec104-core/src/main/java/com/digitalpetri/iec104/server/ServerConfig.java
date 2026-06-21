package com.digitalpetri.iec104.server;

import com.digitalpetri.iec104.ApciSettings;
import com.digitalpetri.iec104.ProtocolProfile;
import com.digitalpetri.iec104.codec.MutableTypeCodecRegistry;
import com.digitalpetri.iec104.codec.TypeCodecRegistry;
import com.digitalpetri.iec104.point.TimeTagStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;

/**
 * Immutable configuration for an {@link Iec104Server}.
 *
 * <p>This configuration covers the protocol-layer behavior of the server: the wire profile, the
 * APCI flow-control parameters, the hosted stations, the request handler, the policy for a full
 * outbound queue, the time-tag style used when reporting monitor data, the maximum number of
 * concurrent connections, the executor that delivers events and runs handler callbacks, and the
 * codec registry for private TypeIDs. Transport concerns (host, port, TLS) are configured on the
 * transport, not here.
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
 * @param apciSettings the APCI flow-control parameters.
 * @param stations the stations hosted by the server; each must have a distinct common address.
 * @param handler the handler that answers control-direction requests.
 * @param eventQueuePolicy the policy applied when a started connection's outbound queue is full.
 * @param timeTagStyle the time-tag style used when reporting monitor data (interrogation answers
 *     and published updates).
 * @param maxConnections the maximum number of concurrent controlling-station connections.
 * @param callbackExecutor the executor used to deliver events and run handler callbacks.
 * @param typeCodecRegistry the registry of codecs for private or uncommon TypeIDs.
 */
public record ServerConfig(
    ProtocolProfile protocolProfile,
    ApciSettings apciSettings,
    List<Station> stations,
    ServerHandler handler,
    EventQueuePolicy eventQueuePolicy,
    TimeTagStyle timeTagStyle,
    int maxConnections,
    Executor callbackExecutor,
    TypeCodecRegistry typeCodecRegistry) {

  /**
   * Validates the configuration and defensively copies the station list.
   *
   * @param protocolProfile the wire field widths.
   * @param apciSettings the APCI flow-control parameters.
   * @param stations the stations hosted by the server; each must have a distinct common address.
   * @param handler the handler that answers control-direction requests.
   * @param eventQueuePolicy the policy applied when a started connection's outbound queue is full.
   * @param timeTagStyle the time-tag style used when reporting monitor data (interrogation answers
   *     and published updates).
   * @param maxConnections the maximum number of concurrent controlling-station connections.
   * @param callbackExecutor the executor used to deliver events and run handler callbacks.
   * @param typeCodecRegistry the registry of codecs for private or uncommon TypeIDs.
   * @throws NullPointerException if any non-primitive component is null.
   * @throws IllegalArgumentException if {@code maxConnections} is not positive.
   */
  public ServerConfig {
    Objects.requireNonNull(protocolProfile, "protocolProfile");
    Objects.requireNonNull(apciSettings, "apciSettings");
    Objects.requireNonNull(stations, "stations");
    Objects.requireNonNull(handler, "handler");
    Objects.requireNonNull(eventQueuePolicy, "eventQueuePolicy");
    Objects.requireNonNull(timeTagStyle, "timeTagStyle");
    Objects.requireNonNull(callbackExecutor, "callbackExecutor");
    Objects.requireNonNull(typeCodecRegistry, "typeCodecRegistry");
    if (maxConnections < 1) {
      throw new IllegalArgumentException("maxConnections must be positive: " + maxConnections);
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
    private ApciSettings apciSettings = ApciSettings.defaults();
    private final List<Station> stations = new ArrayList<>();
    private ServerHandler handler = new ServerHandler() {};
    private EventQueuePolicy eventQueuePolicy = EventQueuePolicy.DEFAULT;
    private TimeTagStyle timeTagStyle = TimeTagStyle.CP56;
    private int maxConnections = 16;
    private Executor callbackExecutor = ForkJoinPool.commonPool();
    private TypeCodecRegistry typeCodecRegistry = new MutableTypeCodecRegistry();

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
     * Sets the APCI flow-control parameters.
     *
     * @param apciSettings the APCI settings.
     * @return this builder.
     */
    public Builder apciSettings(ApciSettings apciSettings) {
      this.apciSettings = Objects.requireNonNull(apciSettings, "apciSettings");
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
     * {@link EventQueuePolicy#DEFAULT}.
     *
     * @param eventQueuePolicy the event-queue policy.
     * @return this builder.
     */
    public Builder eventQueuePolicy(EventQueuePolicy eventQueuePolicy) {
      this.eventQueuePolicy = Objects.requireNonNull(eventQueuePolicy, "eventQueuePolicy");
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
     * Sets the registry of codecs for private or uncommon TypeIDs. Defaults to an empty registry.
     *
     * @param typeCodecRegistry the codec registry.
     * @return this builder.
     */
    public Builder typeCodecRegistry(TypeCodecRegistry typeCodecRegistry) {
      this.typeCodecRegistry = Objects.requireNonNull(typeCodecRegistry, "typeCodecRegistry");
      return this;
    }

    /**
     * Builds an immutable {@link ServerConfig} from the current builder state.
     *
     * @return the configuration.
     * @throws IllegalArgumentException if {@code maxConnections} is not positive.
     */
    public ServerConfig build() {
      return new ServerConfig(
          protocolProfile,
          apciSettings,
          stations,
          handler,
          eventQueuePolicy,
          timeTagStyle,
          maxConnections,
          callbackExecutor,
          typeCodecRegistry);
    }
  }
}
