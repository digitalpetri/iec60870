package com.digitalpetri.iec60870.transport.tcp;

import com.digitalpetri.iec60870.ProtocolProfile;
import com.digitalpetri.iec60870.TlsOptions;
import com.digitalpetri.iec60870.cs101.Cs101Binding;
import com.digitalpetri.iec60870.cs101.LinkSettings;
import com.digitalpetri.iec60870.server.DefaultIec60870Server;
import com.digitalpetri.iec60870.server.Iec60870Server;
import com.digitalpetri.iec60870.server.ServerConfig;
import com.digitalpetri.iec60870.server.ServerHandler;
import com.digitalpetri.iec60870.server.Station;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.EventLoopGroup;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;

/**
 * User-facing entry point that builds a TCP/TLS {@link Iec60870Server} that speaks IEC 60870-5-101
 * (FT1.2) over accepted connections.
 *
 * <p>This is the optional 101-over-TCP peer of {@code TcpIec104Server}: it reuses the same Netty
 * octet transport but installs an {@link Ft12FrameDecoder} per accepted connection in place of the
 * 104 frame decoder, so each connection runs the FT1.2 link layer assembled by {@link Cs101Binding}
 * rather than the 104 APCI session. Transport wiring (bind address, port, TLS, event loops) lives
 * here because it must not appear in core; {@link Builder#build()} constructs a {@link
 * NettyServerTransport} plus a {@link DefaultIec60870Server} and returns the core {@link
 * Iec60870Server} interface.
 *
 * <p>The builder holds no link-layer wiring itself: it delegates the full per-connection FT1.2
 * link-engine plus framing assembly to {@link Cs101Binding}, so no {@code Ft12LinkLayer} is
 * constructed and no framer is invoked here.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * Station station = Station.builder(CommonAddress.of(1))
 *     .point(definition)
 *     .build();
 *
 * try (Iec60870Server server = TcpIec101Server.builder()
 *         .bindAddress("0.0.0.0")
 *         .port(2404)
 *         .linkSettings(LinkSettings.balanced().linkAddress(1).build())
 *         .addStation(station)
 *         .handler(myHandler)
 *         .build()) {
 *     server.start();
 *     server.publish(point, PointValue.scaled(42, Quality.good()), Cause.SPONTANEOUS);
 * }
 * }</pre>
 *
 * <p>For TLS, supply {@link Builder#tls(TlsOptions)} built from a configured {@link
 * javax.net.ssl.SSLContext}; set {@link TlsOptions.Builder#clientAuthRequired(boolean)} to demand a
 * client certificate, then read it per connection via {@code
 * ServerTransportConnection.peerCertificate()}.
 */
public final class TcpIec101Server {

  private TcpIec101Server() {}

  /**
   * Returns a new builder seeded with typical IEC 60870-5-101 defaults.
   *
   * @return a new builder.
   */
  public static Builder builder() {
    return new Builder();
  }

  /**
   * A builder for a TCP/TLS-backed IEC 60870-5-101 {@link Iec60870Server}.
   *
   * <p>Transport knobs ({@code bindAddress}, {@code port}, {@code tls}, {@code eventLoopGroup})
   * configure the Netty transport; protocol knobs ({@code profile}, {@code linkSettings}, {@code
   * addStation}, {@code handler}, {@code callbackExecutor}) configure the core server. {@code
   * maxConnections} bounds both the accepted channels and the core connection set. The builder is
   * not thread-safe.
   */
  public static final class Builder {

    private String bindAddress = "0.0.0.0";
    private int port = 2404;
    private ProtocolProfile profile = ProtocolProfile.iec101Default();
    private LinkSettings linkSettings = LinkSettings.balanced().build();
    private @Nullable TlsOptions tls;
    private final List<Station> stations = new ArrayList<>();
    private @Nullable ServerHandler handler;
    private int maxConnections = 16;
    private @Nullable Executor callbackExecutor;
    private @Nullable EventLoopGroup bossEventLoopGroup;
    private @Nullable EventLoopGroup workerEventLoopGroup;
    private @Nullable Consumer<ServerBootstrap> serverBootstrapCustomizer;

    private Builder() {}

    /**
     * Sets the local bind address. Defaults to {@code "0.0.0.0"} (all interfaces).
     *
     * @param bindAddress the local host name or address.
     * @return this builder.
     */
    public Builder bindAddress(String bindAddress) {
      this.bindAddress = Objects.requireNonNull(bindAddress, "bindAddress");
      return this;
    }

    /**
     * Sets the local TCP port. Defaults to {@code 2404}, the registered IEC 60870-5-104 port;
     * 101-over-TCP has no registered port, so set this to match the peer.
     *
     * @param port the local port.
     * @return this builder.
     */
    public Builder port(int port) {
      this.port = port;
      return this;
    }

    /**
     * Sets the wire field widths. Defaults to {@link ProtocolProfile#iec101Default()}, the IEC
     * 60870-5-101 profile {@code (1, 1, 2, 255)}.
     *
     * <p>IEC 60870-5-101 links commonly use narrower fields than 104 (a 1-octet cause of
     * transmission, a 1-octet common address, and a 2-octet information-object address); set a
     * matching {@link ProtocolProfile} when the peer expects different widths.
     *
     * @param profile the protocol profile.
     * @return this builder.
     */
    public Builder profile(ProtocolProfile profile) {
      this.profile = Objects.requireNonNull(profile, "profile");
      return this;
    }

    /**
     * Sets the FT1.2 link parameters. Defaults to {@link LinkSettings#balanced()
     * LinkSettings.balanced().build()}.
     *
     * <p>The link-address width carried by these settings sizes the FT1.2 frames the transport
     * deframes, so it is propagated to the transport's frame decoder automatically.
     *
     * @param linkSettings the link settings.
     * @return this builder.
     */
    public Builder linkSettings(LinkSettings linkSettings) {
      this.linkSettings = Objects.requireNonNull(linkSettings, "linkSettings");
      return this;
    }

    /**
     * Secures accepted connections with TLS.
     *
     * @param tls the TLS options, or {@code null} for plaintext.
     * @return this builder.
     */
    public Builder tls(@Nullable TlsOptions tls) {
      this.tls = tls;
      return this;
    }

    /**
     * Adds a station hosted by the server.
     *
     * @param station the station to host; each must have a distinct common address.
     * @return this builder.
     * @throws NullPointerException if {@code station} is null.
     */
    public Builder addStation(Station station) {
      this.stations.add(Objects.requireNonNull(station, "station"));
      return this;
    }

    /**
     * Sets the handler that answers control-direction requests. Defaults to the core server's
     * default handler when unset.
     *
     * @param handler the server handler.
     * @return this builder.
     */
    public Builder handler(ServerHandler handler) {
      this.handler = Objects.requireNonNull(handler, "handler");
      return this;
    }

    /**
     * Sets the maximum number of concurrent controlling-station connections. Defaults to {@code
     * 16}.
     *
     * @param maxConnections the connection cap; must be positive.
     * @return this builder.
     */
    public Builder maxConnections(int maxConnections) {
      this.maxConnections = maxConnections;
      return this;
    }

    /**
     * Sets the executor that delivers events and runs handler callbacks. Defaults to the core
     * server's default executor.
     *
     * @param callbackExecutor the callback executor.
     * @return this builder.
     */
    public Builder callbackExecutor(Executor callbackExecutor) {
      this.callbackExecutor = Objects.requireNonNull(callbackExecutor, "callbackExecutor");
      return this;
    }

    /**
     * Sets externally-owned boss and worker {@link EventLoopGroup}s for the transport.
     *
     * <p>When supplied, the caller retains ownership; the transport does not shut them down. The
     * same group may be passed for both roles.
     *
     * @param bossEventLoopGroup the acceptor group.
     * @param workerEventLoopGroup the I/O group.
     * @return this builder.
     */
    public Builder eventLoopGroup(
        EventLoopGroup bossEventLoopGroup, EventLoopGroup workerEventLoopGroup) {
      this.bossEventLoopGroup = Objects.requireNonNull(bossEventLoopGroup, "bossEventLoopGroup");
      this.workerEventLoopGroup =
          Objects.requireNonNull(workerEventLoopGroup, "workerEventLoopGroup");
      return this;
    }

    /**
     * Sets a single externally-owned {@link EventLoopGroup} used for both acceptor and I/O roles.
     *
     * <p>The caller retains ownership; the transport does not shut it down.
     *
     * @param eventLoopGroup the shared event loop group.
     * @return this builder.
     */
    public Builder eventLoopGroup(EventLoopGroup eventLoopGroup) {
      Objects.requireNonNull(eventLoopGroup, "eventLoopGroup");
      this.bossEventLoopGroup = eventLoopGroup;
      this.workerEventLoopGroup = eventLoopGroup;
      return this;
    }

    /**
     * Sets a customizer invoked on the {@link ServerBootstrap} before binding.
     *
     * @param serverBootstrapCustomizer the bootstrap customizer.
     * @return this builder.
     */
    public Builder serverBootstrapCustomizer(Consumer<ServerBootstrap> serverBootstrapCustomizer) {
      this.serverBootstrapCustomizer =
          Objects.requireNonNull(serverBootstrapCustomizer, "serverBootstrapCustomizer");
      return this;
    }

    /**
     * Builds the Netty transport and the core server and returns the {@link Iec60870Server}.
     *
     * @return the configured server.
     */
    public Iec60870Server build() {
      // Install the FT1.2 frame decoder per accepted connection in place of the 104 default; the
      // link-address width sizes its fixed-length frames. This is the only transport difference
      // from the 104 server.
      int linkAddressLength = linkSettings.linkAddressLength();

      NettyServerTransportConfig transportConfig =
          NettyServerTransportConfig.builder(bindAddress, port)
              .tlsOptions(tls)
              .bossEventLoopGroup(bossEventLoopGroup)
              .workerEventLoopGroup(workerEventLoopGroup)
              .maxConnections(maxConnections)
              .serverBootstrapCustomizer(serverBootstrapCustomizer)
              .frameDecoderFactory(() -> new Ft12FrameDecoder(linkAddressLength))
              .build();

      NettyServerTransport transport = new NettyServerTransport(transportConfig);

      ServerConfig.Builder serverConfigBuilder =
          ServerConfig.builder()
              .protocolProfile(profile)
              .sessionSettings(linkSettings)
              .stations(stations)
              .maxConnections(maxConnections);
      if (handler != null) {
        serverConfigBuilder.handler(handler);
      }
      if (callbackExecutor != null) {
        serverConfigBuilder.callbackExecutor(callbackExecutor);
      }
      ServerConfig serverConfig = serverConfigBuilder.build();

      // The builder is the sole 101-over-TCP assembly point but holds no Ft12Frame<->octet wiring
      // itself: Cs101Binding owns the full per-connection Ft12LinkLayer + framing assembly.
      Cs101Binding binding = new Cs101Binding(linkSettings, profile);

      // The outbound-queue bound and policy are passed as scalars rather than the ServerConfig
      // object so Cs101Binding stays free of an iec60870-application import.
      return new DefaultIec60870Server(
          transport,
          serverConfig,
          (connection, events, scheduler) ->
              binding.bindServer(
                  connection,
                  events,
                  scheduler,
                  serverConfig.maxOutboundQueue(),
                  serverConfig.eventQueuePolicy()));
    }
  }
}
