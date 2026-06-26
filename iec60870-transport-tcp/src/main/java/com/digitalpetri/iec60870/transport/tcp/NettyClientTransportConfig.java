package com.digitalpetri.iec60870.transport.tcp;

import com.digitalpetri.iec60870.TlsOptions;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.EventLoopGroup;
import io.netty.handler.codec.ByteToMessageDecoder;
import java.net.SocketAddress;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/**
 * Immutable transport-layer configuration for a {@link NettyClientTransport}.
 *
 * <p>This holder carries everything the Netty client transport needs to open and frame a single
 * outgoing connection: the remote endpoint, an optional local bind address, the connect timeout,
 * optional TLS, an optional shared {@link EventLoopGroup}, and an optional {@link Bootstrap}
 * customizer for advanced socket tuning. The octet transport is profile-agnostic — it frames whole
 * {@code ByteBuf}s and never parses an APDU — so the wire profile lives with the protocol binding,
 * not here. Protocol-layer concerns (APCI flow control, originator address) belong on the core
 * {@code ClientConfig}; the connect timeout is the transport-native projection of the IEC 104
 * {@code t0} parameter.
 *
 * <p>Construct instances through {@link #builder(String, int)}; the optional accessors return empty
 * {@link Optional}s when a value was not supplied.
 *
 * @param host the remote host name or address to connect to.
 * @param port the remote TCP port to connect to.
 * @param localBind the local address to bind the outgoing socket to, or {@code null} for any.
 * @param connectTimeout the TCP connection-establishment timeout (IEC 104 {@code t0}) applied to
 *     each connect attempt; must be positive.
 * @param tlsOptions the TLS options, or {@code null} for a plaintext connection.
 * @param sharedEventLoopGroup an externally-owned {@link EventLoopGroup} to run the channel on, or
 *     {@code null} to let the transport create and own a private group.
 * @param bootstrapCustomizer a hook to mutate the {@link Bootstrap} before connecting (socket
 *     options, allocators, and the like), or {@code null} for none.
 * @param frameDecoderFactory supplies the pipeline frame decoder, or {@code null} to use the
 *     default IEC 60870-5-104 frame decoder; supply a factory to carry an alternative wire framing
 *     (such as FT1.2 for 101-over-TCP) over the same Netty transport.
 */
public record NettyClientTransportConfig(
    String host,
    int port,
    @Nullable SocketAddress localBind,
    Duration connectTimeout,
    @Nullable TlsOptions tlsOptions,
    @Nullable EventLoopGroup sharedEventLoopGroup,
    @Nullable Consumer<Bootstrap> bootstrapCustomizer,
    @Nullable Supplier<ByteToMessageDecoder> frameDecoderFactory) {

  /**
   * Validates the configuration.
   *
   * @param host the remote host name or address to connect to.
   * @param port the remote TCP port to connect to.
   * @param localBind the local address to bind the outgoing socket to, or {@code null} for any.
   * @param connectTimeout the TCP connection-establishment timeout (IEC 104 {@code t0}) applied to
   *     each connect attempt; must be positive.
   * @param tlsOptions the TLS options, or {@code null} for a plaintext connection.
   * @param sharedEventLoopGroup an externally-owned {@link EventLoopGroup} to run the channel on,
   *     or {@code null} to let the transport create and own a private group.
   * @param bootstrapCustomizer a hook to mutate the {@link Bootstrap} before connecting (socket
   *     options, allocators, and the like), or {@code null} for none.
   * @param frameDecoderFactory supplies the pipeline frame decoder, or {@code null} to use the
   *     default IEC 60870-5-104 frame decoder.
   * @throws NullPointerException if {@code host} or {@code connectTimeout} is null.
   * @throws IllegalArgumentException if {@code port} is not in the range {@code 1..65535}, or
   *     {@code connectTimeout} is not positive.
   */
  public NettyClientTransportConfig {
    Objects.requireNonNull(host, "host");
    Objects.requireNonNull(connectTimeout, "connectTimeout");
    if (port < 1 || port > 65535) {
      throw new IllegalArgumentException("port must be in 1..65535: " + port);
    }
    if (connectTimeout.isZero() || connectTimeout.isNegative()) {
      throw new IllegalArgumentException("connectTimeout must be positive: " + connectTimeout);
    }
  }

  /**
   * Returns the local bind address, if one was configured.
   *
   * @return the local bind address, or an empty {@link Optional} for any local address.
   */
  public Optional<SocketAddress> localBindOptional() {
    return Optional.ofNullable(localBind);
  }

  /**
   * Returns the TLS options, if TLS is configured.
   *
   * @return the TLS options, or an empty {@link Optional} for a plaintext connection.
   */
  public Optional<TlsOptions> tlsOptionsOptional() {
    return Optional.ofNullable(tlsOptions);
  }

  /**
   * Returns the shared event loop group, if one was supplied.
   *
   * @return the shared {@link EventLoopGroup}, or an empty {@link Optional} when the transport owns
   *     its group.
   */
  public Optional<EventLoopGroup> sharedEventLoopGroupOptional() {
    return Optional.ofNullable(sharedEventLoopGroup);
  }

  /**
   * Returns the bootstrap customizer, if one was supplied.
   *
   * @return the {@link Bootstrap} customizer, or an empty {@link Optional} if none.
   */
  public Optional<Consumer<Bootstrap>> bootstrapCustomizerOptional() {
    return Optional.ofNullable(bootstrapCustomizer);
  }

  /**
   * Returns the frame-decoder factory, if one was supplied.
   *
   * @return the frame-decoder factory, or an empty {@link Optional} to use the default IEC
   *     60870-5-104 frame decoder.
   */
  public Optional<Supplier<ByteToMessageDecoder>> frameDecoderFactoryOptional() {
    return Optional.ofNullable(frameDecoderFactory);
  }

  /**
   * Returns a builder for the given remote endpoint.
   *
   * @param host the remote host name or address.
   * @param port the remote TCP port.
   * @return a new builder.
   */
  public static Builder builder(String host, int port) {
    return new Builder(host, port);
  }

  /**
   * A builder for {@link NettyClientTransportConfig}.
   *
   * <p>The builder is not thread-safe; build a configuration on one thread and share the immutable
   * result.
   */
  public static final class Builder {

    private final String host;
    private final int port;
    private @Nullable SocketAddress localBind;
    private Duration connectTimeout = Duration.ofSeconds(30);
    private @Nullable TlsOptions tlsOptions;
    private @Nullable EventLoopGroup sharedEventLoopGroup;
    private @Nullable Consumer<Bootstrap> bootstrapCustomizer;
    private @Nullable Supplier<ByteToMessageDecoder> frameDecoderFactory;

    private Builder(String host, int port) {
      this.host = Objects.requireNonNull(host, "host");
      this.port = port;
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
     * Sets the TCP connection-establishment timeout (IEC 104 {@code t0}) applied to each connect
     * attempt. Defaults to 30 seconds. A {@link #bootstrapCustomizer(Consumer) bootstrap
     * customizer} runs after this option is set and may override it.
     *
     * @param connectTimeout the connect timeout; must be positive.
     * @return this builder.
     */
    public Builder connectTimeout(Duration connectTimeout) {
      this.connectTimeout = Objects.requireNonNull(connectTimeout, "connectTimeout");
      return this;
    }

    /**
     * Sets the TLS options that secure the connection.
     *
     * @param tlsOptions the TLS options, or {@code null} for plaintext.
     * @return this builder.
     */
    public Builder tlsOptions(@Nullable TlsOptions tlsOptions) {
      this.tlsOptions = tlsOptions;
      return this;
    }

    /**
     * Sets an externally-owned event loop group for the transport to run the channel on.
     *
     * <p>When supplied, the transport does not shut the group down; the caller retains ownership.
     *
     * @param sharedEventLoopGroup the shared event loop group, or {@code null} for a private group.
     * @return this builder.
     */
    public Builder sharedEventLoopGroup(@Nullable EventLoopGroup sharedEventLoopGroup) {
      this.sharedEventLoopGroup = sharedEventLoopGroup;
      return this;
    }

    /**
     * Sets a customizer invoked on the {@link Bootstrap} before each connect attempt.
     *
     * @param bootstrapCustomizer the bootstrap customizer, or {@code null} for none.
     * @return this builder.
     */
    public Builder bootstrapCustomizer(@Nullable Consumer<Bootstrap> bootstrapCustomizer) {
      this.bootstrapCustomizer = bootstrapCustomizer;
      return this;
    }

    /**
     * Sets the factory that supplies the pipeline frame decoder.
     *
     * <p>Defaults to {@code null}, which installs the IEC 60870-5-104 frame decoder. Supply a
     * factory to carry an alternative wire framing (such as FT1.2 for 101-over-TCP) over the same
     * Netty transport; the rest of the pipeline is unchanged.
     *
     * @param frameDecoderFactory the frame-decoder factory, or {@code null} for the default 104
     *     frame decoder.
     * @return this builder.
     */
    public Builder frameDecoderFactory(
        @Nullable Supplier<ByteToMessageDecoder> frameDecoderFactory) {
      this.frameDecoderFactory = frameDecoderFactory;
      return this;
    }

    /**
     * Builds an immutable {@link NettyClientTransportConfig}.
     *
     * @return the configuration.
     */
    public NettyClientTransportConfig build() {
      return new NettyClientTransportConfig(
          host,
          port,
          localBind,
          connectTimeout,
          tlsOptions,
          sharedEventLoopGroup,
          bootstrapCustomizer,
          frameDecoderFactory);
    }
  }
}
