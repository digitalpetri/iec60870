package com.digitalpetri.iec60870.tcp;

import com.digitalpetri.iec60870.ProtocolProfile;
import com.digitalpetri.iec60870.TlsOptions;
import com.digitalpetri.iec60870.UndecodableAsduPolicy;
import com.digitalpetri.iec60870.address.OriginatorAddress;
import com.digitalpetri.iec60870.client.ClientConfig;
import com.digitalpetri.iec60870.client.DefaultIec60870Client;
import com.digitalpetri.iec60870.client.Iec60870Client;
import com.digitalpetri.iec60870.client.ReconnectPolicy;
import com.digitalpetri.iec60870.client.TimeTagMatch;
import com.digitalpetri.iec60870.cs101.Cs101Binding;
import com.digitalpetri.iec60870.cs101.LinkSettings;
import com.digitalpetri.iec60870.point.TimePolicy;
import com.digitalpetri.iec60870.transport.tcp.Ft12FrameDecoder;
import com.digitalpetri.iec60870.transport.tcp.NettyClientTransport;
import com.digitalpetri.iec60870.transport.tcp.NettyClientTransportConfig;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.EventLoopGroup;
import java.net.SocketAddress;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;

/**
 * User-facing entry point that builds a TCP/TLS {@link Iec60870Client} that speaks IEC 60870-5-101
 * (FT1.2) over the connection.
 *
 * <p>This is the optional 101-over-TCP peer of {@code TcpIec104Client}: it reuses the same Netty
 * octet transport but installs an {@link Ft12FrameDecoder} in place of the 104 frame decoder, so
 * the link layer on the wire is the FT1.2 link layer assembled by {@link Cs101Binding} rather than
 * the 104 APCI session. Transport wiring (host, port, TLS, event loops) lives here because it must
 * not appear in core; {@link Builder#build()} constructs a {@link NettyClientTransport} plus a
 * {@link DefaultIec60870Client} and returns the core {@link Iec60870Client} interface, whose every
 * protocol method matches the 104 client exactly.
 *
 * <p>The builder holds no link-layer wiring itself: it delegates the full FT1.2 link-engine plus
 * framing assembly to {@link Cs101Binding}, so no {@code Ft12LinkLayer} is constructed and no
 * framer is invoked here.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * try (Iec60870Client client = TcpIec101Client.builder()
 *         .host("127.0.0.1")
 *         .port(2404)
 *         .linkSettings(LinkSettings.balanced().linkAddress(1).build())
 *         .startDataTransferOnConnect(true)
 *         .build()) {
 *     client.connect();
 *     InterrogationResult snapshot = client.interrogate(CommonAddress.of(1));
 *     CommandResult result = client.commands().single(point, true);
 * }
 * }</pre>
 *
 * <p>For a TLS connection, supply {@link Builder#tls(TlsOptions)} built from a configured {@link
 * javax.net.ssl.SSLContext}; {@link Iec60870Client#connect()} then completes only after the TLS
 * handshake succeeds. When {@link Builder#startDataTransferOnConnect(boolean) start-on-connect} is
 * enabled (the default), {@code connect()} also drives the FT1.2 link-reset bring-up before
 * completing.
 */
public final class TcpIec101Client {

  private TcpIec101Client() {}

  /**
   * Returns a new builder seeded with typical IEC 60870-5-101 defaults.
   *
   * @return a new builder.
   */
  public static Builder builder() {
    return new Builder();
  }

  /**
   * A builder for a TCP/TLS-backed IEC 60870-5-101 {@link Iec60870Client}.
   *
   * <p>Transport knobs ({@code host}, {@code port}, {@code localBind}, {@code connectTimeout},
   * {@code tls}, {@code eventLoopGroup}, {@code bootstrapCustomizer}) configure the Netty
   * transport; protocol knobs ({@code profile}, {@code linkSettings}, {@code originatorAddress},
   * {@code startDataTransferOnConnect}, {@code timePolicy}, {@code commandTimeTagMatch}, {@code
   * callbackExecutor}) configure the core client. The builder is not thread-safe.
   */
  public static final class Builder {

    private String host = "localhost";
    private int port = 2404;
    private @Nullable SocketAddress localBind;
    private Duration connectTimeout = Duration.ofSeconds(30);
    private ProtocolProfile profile = ProtocolProfile.iec101Default();
    private LinkSettings linkSettings = LinkSettings.balanced().build();
    private @Nullable TlsOptions tls;
    private boolean startDataTransferOnConnect = true;
    private OriginatorAddress originatorAddress = OriginatorAddress.none();
    private @Nullable Executor callbackExecutor;
    private int eventBacklogLimit = ClientConfig.DEFAULT_EVENT_BACKLOG_LIMIT;
    private ReconnectPolicy reconnectPolicy = ReconnectPolicy.defaults();
    private UndecodableAsduPolicy undecodableAsduPolicy = UndecodableAsduPolicy.DEFAULT;
    private TimePolicy timePolicy = TimePolicy.utc();
    private TimeTagMatch commandTimeTagMatch = TimeTagMatch.INSTANT;
    private @Nullable EventLoopGroup eventLoopGroup;
    private @Nullable Consumer<Bootstrap> bootstrapCustomizer;

    private Builder() {}

    /**
     * Sets the remote host. Defaults to {@code "localhost"}.
     *
     * @param host the remote host name or address.
     * @return this builder.
     */
    public Builder host(String host) {
      this.host = Objects.requireNonNull(host, "host");
      return this;
    }

    /**
     * Sets the remote TCP port. Defaults to {@code 2404}, the registered IEC 60870-5-104 port;
     * 101-over-TCP has no registered port, so set this to match the peer.
     *
     * @param port the remote port.
     * @return this builder.
     */
    public Builder port(int port) {
      this.port = port;
      return this;
    }

    /**
     * Sets the local address to bind the outgoing socket to.
     *
     * @param localBind the local bind address, or {@code null} for any.
     * @return this builder.
     */
    public Builder localBind(@Nullable SocketAddress localBind) {
      this.localBind = localBind;
      return this;
    }

    /**
     * Sets the TCP connection-establishment timeout applied to each connect attempt. Defaults to
     * {@code 30} seconds. A {@link #bootstrapCustomizer(Consumer) bootstrap customizer} runs after
     * this option is applied and may override it.
     *
     * @param connectTimeout the connect timeout; must be positive.
     * @return this builder.
     */
    public Builder connectTimeout(Duration connectTimeout) {
      this.connectTimeout = Objects.requireNonNull(connectTimeout, "connectTimeout");
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
     * Secures the connection with TLS.
     *
     * <p>When set, {@link Iec60870Client#connect()} completes only after the TLS handshake
     * succeeds.
     *
     * @param tls the TLS options, or {@code null} for a plaintext connection.
     * @return this builder.
     */
    public Builder tls(@Nullable TlsOptions tls) {
      this.tls = tls;
      return this;
    }

    /**
     * Sets whether every connection the client establishes, through {@link
     * Iec60870Client#connect()} or an automatic reconnect, also starts data transfer by driving the
     * FT1.2 link-reset bring-up. Defaults to {@code true}.
     *
     * @param startDataTransferOnConnect whether to start data transfer on connect.
     * @return this builder.
     */
    public Builder startDataTransferOnConnect(boolean startDataTransferOnConnect) {
      this.startDataTransferOnConnect = startDataTransferOnConnect;
      return this;
    }

    /**
     * Sets the originator address placed in control-direction ASDUs. Defaults to {@link
     * OriginatorAddress#none()}.
     *
     * @param originatorAddress the originator address.
     * @return this builder.
     */
    public Builder originatorAddress(OriginatorAddress originatorAddress) {
      this.originatorAddress = Objects.requireNonNull(originatorAddress, "originatorAddress");
      return this;
    }

    /**
     * Sets what the link layer does with a received ASDU it cannot decode. Defaults to {@link
     * UndecodableAsduPolicy#REPORT_AND_CONTINUE}.
     *
     * <p>Under the default {@link UndecodableAsduPolicy#REPORT_AND_CONTINUE}, an intact variable
     * frame whose ASDU cannot be decoded is acknowledged and reported as an undecodable ASDU.
     * {@link UndecodableAsduPolicy#STRICT} discards it without an acknowledgement instead.
     *
     * @param undecodableAsduPolicy the undecodable-ASDU policy.
     * @return this builder.
     */
    public Builder undecodableAsduPolicy(UndecodableAsduPolicy undecodableAsduPolicy) {
      this.undecodableAsduPolicy =
          Objects.requireNonNull(undecodableAsduPolicy, "undecodableAsduPolicy");
      return this;
    }

    /**
     * Sets the rules that convert time tags: received monitor objects, command time tags, and clock
     * synchronization. Defaults to {@link TimePolicy#utc()}.
     *
     * @param timePolicy the time policy.
     * @return this builder.
     * @see ClientConfig.Builder#timePolicy(TimePolicy)
     */
    public Builder timePolicy(TimePolicy timePolicy) {
      this.timePolicy = Objects.requireNonNull(timePolicy, "timePolicy");
      return this;
    }

    /**
     * Sets how the time tag of a command's echo is compared with the tag the client sent. Defaults
     * to {@link TimeTagMatch#INSTANT}.
     *
     * @param commandTimeTagMatch the time-tag match mode.
     * @return this builder.
     * @see ClientConfig.Builder#commandTimeTagMatch(TimeTagMatch)
     */
    public Builder commandTimeTagMatch(TimeTagMatch commandTimeTagMatch) {
      this.commandTimeTagMatch = Objects.requireNonNull(commandTimeTagMatch, "commandTimeTagMatch");
      return this;
    }

    /**
     * Sets the executor that delivers events and completes blocking calls. Defaults to the core
     * client's default executor.
     *
     * @param callbackExecutor the callback executor.
     * @return this builder.
     */
    public Builder callbackExecutor(Executor callbackExecutor) {
      this.callbackExecutor = Objects.requireNonNull(callbackExecutor, "callbackExecutor");
      return this;
    }

    /**
     * Sets the event backlog at which the client pauses inbound data. Defaults to {@value
     * ClientConfig#DEFAULT_EVENT_BACKLOG_LIMIT}.
     *
     * <p>The event stream never drops events. The IEC 60870-5-101 link layer does not yet pause
     * inbound data, so the limit only sizes the event buffer: a subscriber that falls far enough
     * behind to fill it makes the client close the connection with a cause rather than drop events.
     * See {@link ClientConfig.Builder#eventBacklogLimit(int)}.
     *
     * @param eventBacklogLimit the backlog limit; must be in {@code 1..268435456}.
     * @return this builder.
     */
    public Builder eventBacklogLimit(int eventBacklogLimit) {
      this.eventBacklogLimit = eventBacklogLimit;
      return this;
    }

    /**
     * Sets when the client reconnects after a lost connection or a failed attempt. Defaults to
     * {@link ReconnectPolicy#defaults()}: exponential backoff from one second up to 32 seconds.
     *
     * <p>Every automatic attempt runs the same initialization as {@link Iec60870Client#connect()}.
     * Use {@link ReconnectPolicy#disabled()} to leave reconnecting to the application.
     *
     * @param reconnectPolicy the reconnect policy.
     * @return this builder.
     */
    public Builder reconnectPolicy(ReconnectPolicy reconnectPolicy) {
      this.reconnectPolicy = Objects.requireNonNull(reconnectPolicy, "reconnectPolicy");
      return this;
    }

    /**
     * Sets an externally-owned {@link EventLoopGroup} for the transport to use.
     *
     * <p>When supplied, the caller retains ownership; the transport does not shut it down.
     *
     * @param eventLoopGroup the shared event loop group.
     * @return this builder.
     */
    public Builder eventLoopGroup(EventLoopGroup eventLoopGroup) {
      this.eventLoopGroup = Objects.requireNonNull(eventLoopGroup, "eventLoopGroup");
      return this;
    }

    /**
     * Sets a customizer invoked on the {@link Bootstrap} before each connect attempt.
     *
     * <p>The customizer runs after the transport applies its own channel options, so it can
     * override them — including the {@code CONNECT_TIMEOUT_MILLIS} derived from {@link
     * #connectTimeout(Duration)}.
     *
     * @param bootstrapCustomizer the bootstrap customizer.
     * @return this builder.
     */
    public Builder bootstrapCustomizer(Consumer<Bootstrap> bootstrapCustomizer) {
      this.bootstrapCustomizer = Objects.requireNonNull(bootstrapCustomizer, "bootstrapCustomizer");
      return this;
    }

    /**
     * Builds the Netty transport and the core client and returns the {@link Iec60870Client}.
     *
     * @return the configured client.
     */
    public Iec60870Client build() {
      // Install the FT1.2 frame decoder in place of the 104 default; the link-address width sizes
      // its fixed-length frames. This is the only transport difference from the 104 client.
      int linkAddressLength = linkSettings.linkAddressLength();

      NettyClientTransportConfig transportConfig =
          NettyClientTransportConfig.builder(host, port)
              .localBind(localBind)
              .connectTimeout(connectTimeout)
              .tlsOptions(tls)
              .sharedEventLoopGroup(eventLoopGroup)
              .bootstrapCustomizer(bootstrapCustomizer)
              .frameDecoderFactory(() -> new Ft12FrameDecoder(linkAddressLength))
              .build();

      NettyClientTransport transport = new NettyClientTransport(transportConfig);

      ClientConfig.Builder clientConfigBuilder =
          ClientConfig.builder()
              .protocolProfile(profile)
              .sessionSettings(linkSettings)
              .originatorAddress(originatorAddress)
              .startDataTransferOnConnect(startDataTransferOnConnect)
              .timePolicy(timePolicy)
              .commandTimeTagMatch(commandTimeTagMatch);
      if (callbackExecutor != null) {
        clientConfigBuilder.callbackExecutor(callbackExecutor);
      }
      clientConfigBuilder.eventBacklogLimit(eventBacklogLimit);
      clientConfigBuilder.reconnectPolicy(reconnectPolicy);

      // The builder is the sole 101-over-TCP assembly point but holds no Ft12Frame<->octet wiring
      // itself: it delegates the full Ft12LinkLayer + framing assembly to Cs101Binding.
      Cs101Binding binding = new Cs101Binding(linkSettings, profile, null, undecodableAsduPolicy);

      return new DefaultIec60870Client(
          transport,
          clientConfigBuilder.build(),
          (events, scheduler) -> binding.bindClient(transport, events, scheduler));
    }
  }
}
