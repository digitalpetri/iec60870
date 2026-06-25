package com.digitalpetri.iec60870.transport.tcp;

import com.digitalpetri.iec60870.ProtocolProfile;
import com.digitalpetri.iec60870.TlsOptions;
import com.digitalpetri.iec60870.address.OriginatorAddress;
import com.digitalpetri.iec60870.client.ClientConfig;
import com.digitalpetri.iec60870.client.DefaultIec60870Client;
import com.digitalpetri.iec60870.client.Iec60870Client;
import com.digitalpetri.iec60870.cs104.ApciSession;
import com.digitalpetri.iec60870.cs104.ApciSettings;
import com.digitalpetri.iec60870.cs104.Apdu;
import com.digitalpetri.iec60870.cs104.ApduFramer;
import com.digitalpetri.iec60870.session.Session;
import com.digitalpetri.iec60870.transport.ClientTransport;
import com.digitalpetri.iec60870.transport.TransportListener;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.UnpooledByteBufAllocator;
import io.netty.channel.EventLoopGroup;
import java.net.SocketAddress;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
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
     * Sets whether {@link Iec60870Client#connect()} also starts data transfer. Defaults to {@code
     * true}.
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
     * Builds the Netty transport and the core client and returns the {@link Iec60870Client}.
     *
     * @return the configured client.
     */
    public Iec60870Client build() {
      NettyClientTransportConfig transportConfig =
          NettyClientTransportConfig.builder(host, port)
              .localBind(localBind)
              .connectTimeout(apci.t0())
              .tlsOptions(tls)
              .sharedEventLoopGroup(eventLoopGroup)
              .bootstrapCustomizer(bootstrapCustomizer)
              .build();

      NettyClientTransport transport = new NettyClientTransport(transportConfig);

      ClientConfig.Builder clientConfigBuilder =
          ClientConfig.builder()
              .protocolProfile(profile)
              .sessionSettings(apci)
              .originatorAddress(originatorAddress)
              .startDataTransferOnConnect(startDataTransferOnConnect);
      if (callbackExecutor != null) {
        clientConfigBuilder.callbackExecutor(callbackExecutor);
      }

      ProtocolProfile sessionProfile = profile;
      ApciSettings apciSettings = apci;

      // Transitional 104 assembly hosted in the builder: construct the ApciSession whose Output
      // frames APDUs and writes them as whole frames to the octet transport, register an octet
      // listener that deframes inbound frames into the session and routes a transport connection
      // loss to the facade's Session.Events.onClosed, and hand the assembled Session to the facade.
      // Phase 7 relocates this assembly into Cs104Binding.
      return new DefaultIec60870Client(
          transport,
          clientConfigBuilder.build(),
          (events, scheduler) ->
              assembleClientSession(transport, apciSettings, sessionProfile, scheduler, events));
    }

    private static Session assembleClientSession(
        ClientTransport transport,
        ApciSettings apciSettings,
        ProtocolProfile profile,
        ScheduledExecutorService scheduler,
        Session.Events events) {

      ByteBufAllocator alloc = UnpooledByteBufAllocator.DEFAULT;

      // The session is referenced by the Output and the listener below, both of which run only
      // after
      // construction; hold it in a one-element array so they can close it on a failed write or a
      // transport loss.
      ApciSession[] holder = new ApciSession[1];

      ApciSession session =
          new ApciSession(
              ApciSession.Role.CLIENT,
              apciSettings,
              scheduler,
              apdu -> {
                // Frame the APDU into a whole-frame ByteBuf and hand it to the octet transport,
                // which owns and releases the buffer. A failed write closes the session and routes
                // the loss to the facade through Session.Events.onClosed.
                ByteBuf frame = ApduFramer.encode(apdu, profile, alloc);
                transport
                    .send(frame)
                    .whenComplete(
                        (ignored, error) -> {
                          if (error != null) {
                            holder[0].close();
                            events.onClosed(error);
                          }
                        });
              },
              events);
      holder[0] = session;

      transport.setListener(
          new TransportListener() {
            @Override
            public void onFrame(ByteBuf frame) {
              Apdu apdu = ApduFramer.decode(profile, frame);
              session.onApdu(apdu);
            }

            @Override
            public void onConnectionLost(@Nullable Throwable cause) {
              session.close();
              events.onClosed(cause);
            }
          });

      return session;
    }
  }
}
