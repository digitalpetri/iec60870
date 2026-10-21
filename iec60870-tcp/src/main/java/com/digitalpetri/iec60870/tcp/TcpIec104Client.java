package com.digitalpetri.iec60870.tcp;

import com.digitalpetri.iec60870.ProtocolProfile;
import com.digitalpetri.iec60870.TlsOptions;
import com.digitalpetri.iec60870.UndecodableAsduPolicy;
import com.digitalpetri.iec60870.address.OriginatorAddress;
import com.digitalpetri.iec60870.client.ClientConfig;
import com.digitalpetri.iec60870.client.CommandCompletion;
import com.digitalpetri.iec60870.client.DefaultIec60870Client;
import com.digitalpetri.iec60870.client.Iec60870Client;
import com.digitalpetri.iec60870.client.ReconnectPolicy;
import com.digitalpetri.iec60870.client.TimeTagMatch;
import com.digitalpetri.iec60870.cs104.ApciDiagnostics;
import com.digitalpetri.iec60870.cs104.ApciSettings;
import com.digitalpetri.iec60870.cs104.Cs104Binding;
import com.digitalpetri.iec60870.diagnostics.LoggingContext;
import com.digitalpetri.iec60870.point.TimePolicy;
import com.digitalpetri.iec60870.transport.tcp.NettyClientTransport;
import com.digitalpetri.iec60870.transport.tcp.NettyClientTransportConfig;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.EventLoopGroup;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;

/**
 * User-facing entry point that builds a TCP/TLS {@link Iec60870Client}.
 *
 * <p>This is the transport-module factory the proposed API sketches as {@code
 * Iec60870Client.builder().remote(host, port).tls(..)}. Because transport wiring (host, port, TLS,
 * event loops) must not appear in core, those knobs live here; {@link Builder#build()} constructs a
 * {@link NettyClientTransport} plus a {@link DefaultIec60870Client} and returns the core {@link
 * Iec60870Client} interface, whose every protocol method matches the proposed API exactly.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * try (Iec60870Client client = TcpIec104Client.builder()
 *         .host("127.0.0.1")
 *         .port(2404)
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
 * handshake succeeds.
 */
public final class TcpIec104Client {

  private TcpIec104Client() {}

  /**
   * Returns a new builder seeded with the standard IEC 60870-5-104 defaults.
   *
   * @return a new builder.
   */
  public static Builder builder() {
    return new Builder();
  }

  /**
   * A builder for a TCP/TLS-backed {@link Iec60870Client}.
   *
   * <p>Transport knobs ({@code host}, {@code port}, {@code localBind}, {@code tls}, {@code
   * eventLoopGroup}, {@code bootstrapCustomizer}) configure the Netty transport; protocol knobs
   * ({@code profile}, {@code apci}, {@code originatorAddress}, {@code startDataTransferOnConnect},
   * {@code timePolicy}, {@code commandTimeTagMatch}, the command completion and selection settings,
   * {@code callbackExecutor}) configure the core client. The builder is not thread-safe.
   */
  public static final class Builder {

    private String host = "localhost";
    private int port = 2404;
    private @Nullable SocketAddress localBind;
    private ProtocolProfile profile = ProtocolProfile.iec104Default();
    private ApciSettings apci = ApciSettings.defaults();
    private @Nullable TlsOptions tls;
    private boolean startDataTransferOnConnect = true;
    private OriginatorAddress originatorAddress = OriginatorAddress.none();
    private @Nullable Executor callbackExecutor;
    private int eventBacklogLimit = ClientConfig.DEFAULT_EVENT_BACKLOG_LIMIT;
    private ReconnectPolicy reconnectPolicy = ReconnectPolicy.defaults();
    private UndecodableAsduPolicy undecodableAsduPolicy = UndecodableAsduPolicy.DEFAULT;
    private TimePolicy timePolicy = TimePolicy.utc();
    private TimeTagMatch commandTimeTagMatch = TimeTagMatch.INSTANT;
    private CommandCompletion commandCompletion = CommandCompletion.CONFIRMATION;
    private CommandCompletion setpointCompletion = CommandCompletion.CONFIRMATION;
    private Duration commandTerminationTimeout = Duration.ofSeconds(60);
    private Duration selectionTimeout = Duration.ofSeconds(30);
    private @Nullable EventLoopGroup eventLoopGroup;
    private @Nullable Consumer<Bootstrap> bootstrapCustomizer;
    private ApciDiagnostics diagnostics = ApciDiagnostics.NONE;
    private Map<String, String> loggingContext = Map.of();

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
     * Sets the remote TCP port. Defaults to {@code 2404}, the registered IEC 104 port.
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
     * Sets the wire field widths. Defaults to {@link ProtocolProfile#iec104Default()}.
     *
     * @param profile the protocol profile.
     * @return this builder.
     */
    public Builder profile(ProtocolProfile profile) {
      this.profile = Objects.requireNonNull(profile, "profile");
      return this;
    }

    /**
     * Sets the APCI flow-control parameters. Defaults to {@link ApciSettings#defaults()}.
     *
     * <p>{@code t0} is applied as the TCP connection-establishment timeout for each connect
     * attempt; a {@link #bootstrapCustomizer(Consumer) bootstrap customizer} runs afterward and may
     * override it.
     *
     * @param apci the APCI settings.
     * @return this builder.
     */
    public Builder apci(ApciSettings apci) {
      this.apci = Objects.requireNonNull(apci, "apci");
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
     * Iec60870Client#connect()} or an automatic reconnect, also starts data transfer. Defaults to
     * {@code true}.
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
     * <p>Under the default {@link UndecodableAsduPolicy#REPORT_AND_CONTINUE}, a correctly framed
     * I-frame whose ASDU cannot be decoded is acknowledged and reported as an undecodable ASDU, and
     * the connection stays open. {@link UndecodableAsduPolicy#STRICT} closes the connection instead
     * without acknowledging the frame.
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
     * Sets when single, double, and regulating step commands complete. Defaults to {@link
     * CommandCompletion#CONFIRMATION}.
     *
     * @param commandCompletion the completion policy.
     * @return this builder.
     * @see ClientConfig.Builder#commandCompletion(CommandCompletion)
     */
    public Builder commandCompletion(CommandCompletion commandCompletion) {
      this.commandCompletion = Objects.requireNonNull(commandCompletion, "commandCompletion");
      return this;
    }

    /**
     * Sets when set-point and bit-string commands complete. Defaults to {@link
     * CommandCompletion#CONFIRMATION}.
     *
     * @param setpointCompletion the completion policy.
     * @return this builder.
     * @see ClientConfig.Builder#setpointCompletion(CommandCompletion)
     */
    public Builder setpointCompletion(CommandCompletion setpointCompletion) {
      this.setpointCompletion = Objects.requireNonNull(setpointCompletion, "setpointCompletion");
      return this;
    }

    /**
     * Sets how long a command awaits ACT_TERM after the positive ACT_CON of its final phase.
     * Defaults to 60 seconds.
     *
     * @param commandTerminationTimeout the termination timeout; must be positive.
     * @return this builder.
     * @see ClientConfig.Builder#commandTerminationTimeout(Duration)
     */
    public Builder commandTerminationTimeout(Duration commandTerminationTimeout) {
      this.commandTerminationTimeout =
          Objects.requireNonNull(commandTerminationTimeout, "commandTerminationTimeout");
      return this;
    }

    /**
     * Sets how long a {@link com.digitalpetri.iec60870.client.SelectedCommand} may be held without
     * execute or cancel. Defaults to 30 seconds.
     *
     * @param selectionTimeout the selection timeout; must be positive.
     * @return this builder.
     * @see ClientConfig.Builder#selectionTimeout(Duration)
     */
    public Builder selectionTimeout(Duration selectionTimeout) {
      this.selectionTimeout = Objects.requireNonNull(selectionTimeout, "selectionTimeout");
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
     * <p>The event stream never drops events. Once the slowest subscriber falls this many events
     * behind, the client stops reading from the connection until the subscriber catches up to half
     * the limit. See {@link ClientConfig.Builder#eventBacklogLimit(int)}.
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
     * #apci(ApciSettings) t0}.
     *
     * @param bootstrapCustomizer the bootstrap customizer.
     * @return this builder.
     */
    public Builder bootstrapCustomizer(Consumer<Bootstrap> bootstrapCustomizer) {
      this.bootstrapCustomizer = Objects.requireNonNull(bootstrapCustomizer, "bootstrapCustomizer");
      return this;
    }

    /**
     * Sets the diagnostics listener for the connection. Defaults to {@link ApciDiagnostics#NONE}.
     *
     * <p>The one listener receives the transport's connect attempts and their outcomes, the
     * session's frame, timer, and protocol-error events, and the client's closed connections,
     * reconnects, and inbound pauses, for the first connection and every reconnect. Combine several
     * listeners with {@link ApciDiagnostics#of(ApciDiagnostics...)}.
     *
     * <pre>{@code
     * ApciCounters counters = new ApciCounters();
     * Iec60870Client client = TcpIec104Client.builder()
     *     .host("10.0.0.5")
     *     .diagnostics(ApciDiagnostics.of(counters, FrameTrace.logger(traceLogger)))
     *     .loggingContext(Map.of("device", "rtu-5"))
     *     .build();
     * }</pre>
     *
     * @param diagnostics the diagnostics listener.
     * @return this builder.
     */
    public Builder diagnostics(ApciDiagnostics diagnostics) {
      this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
      return this;
    }

    /**
     * Sets MDC entries that the library sets around its log lines for this connection. Defaults to
     * none.
     *
     * <p>The client always adds {@code iec60870.remote} with the configured {@code host:port},
     * unless {@code loggingContext} sets that key itself.
     *
     * @param loggingContext the MDC entries.
     * @return this builder.
     */
    public Builder loggingContext(Map<String, String> loggingContext) {
      this.loggingContext = Map.copyOf(loggingContext);
      return this;
    }

    /**
     * Builds the Netty transport and the core client and returns the {@link Iec60870Client}.
     *
     * @return the configured client.
     */
    public Iec60870Client build() {
      Map<String, String> context =
          LoggingContext.of(loggingContext)
              .withRemote(InetSocketAddress.createUnresolved(host, port))
              .entries();

      NettyClientTransportConfig transportConfig =
          NettyClientTransportConfig.builder(host, port)
              .localBind(localBind)
              .connectTimeout(apci.t0())
              .tlsOptions(tls)
              .sharedEventLoopGroup(eventLoopGroup)
              .bootstrapCustomizer(bootstrapCustomizer)
              .diagnostics(diagnostics)
              .loggingContext(context)
              .build();

      NettyClientTransport transport = new NettyClientTransport(transportConfig);

      ClientConfig.Builder clientConfigBuilder =
          ClientConfig.builder()
              .protocolProfile(profile)
              .sessionSettings(apci)
              .originatorAddress(originatorAddress)
              .startDataTransferOnConnect(startDataTransferOnConnect)
              .timePolicy(timePolicy)
              .commandTimeTagMatch(commandTimeTagMatch)
              .commandCompletion(commandCompletion)
              .setpointCompletion(setpointCompletion)
              .commandTerminationTimeout(commandTerminationTimeout)
              .selectionTimeout(selectionTimeout);
      if (callbackExecutor != null) {
        clientConfigBuilder.callbackExecutor(callbackExecutor);
      }
      clientConfigBuilder.eventBacklogLimit(eventBacklogLimit);
      clientConfigBuilder.reconnectPolicy(reconnectPolicy);
      clientConfigBuilder.diagnostics(diagnostics);
      clientConfigBuilder.loggingContext(context);

      // The builder is the sole 104 assembly point but holds no Apdu<->octet wiring itself: it
      // delegates the full ApciSession + framing assembly to Cs104Binding (see its Javadoc).
      Cs104Binding binding =
          new Cs104Binding(apci, profile, null, undecodableAsduPolicy, diagnostics, context);

      return new DefaultIec60870Client(
          transport,
          clientConfigBuilder.build(),
          (events, scheduler) -> binding.bindClient(transport, events, scheduler));
    }
  }
}
