package com.digitalpetri.iec60870.transport.tcp;

import com.digitalpetri.iec60870.TlsOptions;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.EventLoopGroup;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;

/**
 * Immutable transport-layer configuration for a {@link NettyServerTransport}.
 *
 * <p>This holder carries everything the Netty server transport needs to bind a listening endpoint
 * and frame accepted connections: the bind host and port, optional TLS, optional externally-owned
 * boss and worker {@link EventLoopGroup}s, a connection cap, and an optional {@link
 * ServerBootstrap} customizer. The octet transport is profile-agnostic — it frames whole {@code
 * ByteBuf}s and never parses an APDU — so the wire profile lives with the protocol binding, not
 * here. Protocol-layer concerns (stations, handler) belong on the core {@code ServerConfig}, not
 * here.
 *
 * <p>Construct instances through {@link #builder(String, int)}.
 *
 * @param bindHost the local host name or address to bind the listening socket to.
 * @param port the local TCP port to listen on.
 * @param tlsOptions the TLS options, or {@code null} for plaintext connections.
 * @param bossEventLoopGroup an externally-owned acceptor {@link EventLoopGroup}, or {@code null} to
 *     let the transport create and own one.
 * @param workerEventLoopGroup an externally-owned I/O {@link EventLoopGroup}, or {@code null} to
 *     let the transport create and own one.
 * @param maxConnections the maximum number of concurrent accepted connections; further connections
 *     are closed immediately.
 * @param serverBootstrapCustomizer a hook to mutate the {@link ServerBootstrap} before binding, or
 *     {@code null} for none.
 */
public record NettyServerTransportConfig(
    String bindHost,
    int port,
    @Nullable TlsOptions tlsOptions,
    @Nullable EventLoopGroup bossEventLoopGroup,
    @Nullable EventLoopGroup workerEventLoopGroup,
    int maxConnections,
    @Nullable Consumer<ServerBootstrap> serverBootstrapCustomizer) {

  /**
   * Validates the configuration.
   *
   * @param bindHost the local host name or address to bind the listening socket to.
   * @param port the local TCP port to listen on.
   * @param tlsOptions the TLS options, or {@code null} for plaintext connections.
   * @param bossEventLoopGroup an externally-owned acceptor {@link EventLoopGroup}, or {@code null}
   *     to let the transport create and own one.
   * @param workerEventLoopGroup an externally-owned I/O {@link EventLoopGroup}, or {@code null} to
   *     let the transport create and own one.
   * @param maxConnections the maximum number of concurrent accepted connections; further
   *     connections are closed immediately.
   * @param serverBootstrapCustomizer a hook to mutate the {@link ServerBootstrap} before binding,
   *     or {@code null} for none.
   * @throws NullPointerException if {@code bindHost} is null.
   * @throws IllegalArgumentException if {@code port} is not in {@code 0..65535} or {@code
   *     maxConnections} is not positive.
   */
  public NettyServerTransportConfig {
    Objects.requireNonNull(bindHost, "bindHost");
    if (port < 0 || port > 65535) {
      throw new IllegalArgumentException("port must be in 0..65535: " + port);
    }
    if (maxConnections < 1) {
      throw new IllegalArgumentException("maxConnections must be positive: " + maxConnections);
    }
  }

  /**
   * Returns the TLS options, if TLS is configured.
   *
   * @return the TLS options, or an empty {@link Optional} for plaintext.
   */
  public Optional<TlsOptions> tlsOptionsOptional() {
    return Optional.ofNullable(tlsOptions);
  }

  /**
   * Returns the boss event loop group, if one was supplied.
   *
   * @return the acceptor {@link EventLoopGroup}, or an empty {@link Optional} when the transport
   *     owns its group.
   */
  public Optional<EventLoopGroup> bossEventLoopGroupOptional() {
    return Optional.ofNullable(bossEventLoopGroup);
  }

  /**
   * Returns the worker event loop group, if one was supplied.
   *
   * @return the I/O {@link EventLoopGroup}, or an empty {@link Optional} when the transport owns
   *     its group.
   */
  public Optional<EventLoopGroup> workerEventLoopGroupOptional() {
    return Optional.ofNullable(workerEventLoopGroup);
  }

  /**
   * Returns the server bootstrap customizer, if one was supplied.
   *
   * @return the {@link ServerBootstrap} customizer, or an empty {@link Optional} if none.
   */
  public Optional<Consumer<ServerBootstrap>> serverBootstrapCustomizerOptional() {
    return Optional.ofNullable(serverBootstrapCustomizer);
  }

  /**
   * Returns a builder for the given bind endpoint.
   *
   * @param bindHost the local host name or address.
   * @param port the local TCP port.
   * @return a new builder.
   */
  public static Builder builder(String bindHost, int port) {
    return new Builder(bindHost, port);
  }

  /**
   * A builder for {@link NettyServerTransportConfig}.
   *
   * <p>The builder is not thread-safe; build a configuration on one thread and share the immutable
   * result.
   */
  public static final class Builder {

    private final String bindHost;
    private final int port;
    private @Nullable TlsOptions tlsOptions;
    private @Nullable EventLoopGroup bossEventLoopGroup;
    private @Nullable EventLoopGroup workerEventLoopGroup;
    private int maxConnections = 16;
    private @Nullable Consumer<ServerBootstrap> serverBootstrapCustomizer;

    private Builder(String bindHost, int port) {
      this.bindHost = Objects.requireNonNull(bindHost, "bindHost");
      this.port = port;
    }

    /**
     * Sets the TLS options that secure accepted connections.
     *
     * @param tlsOptions the TLS options, or {@code null} for plaintext.
     * @return this builder.
     */
    public Builder tlsOptions(@Nullable TlsOptions tlsOptions) {
      this.tlsOptions = tlsOptions;
      return this;
    }

    /**
     * Sets an externally-owned acceptor event loop group.
     *
     * <p>When supplied, the transport does not shut the group down; the caller retains ownership.
     *
     * @param bossEventLoopGroup the boss group, or {@code null} for a private group.
     * @return this builder.
     */
    public Builder bossEventLoopGroup(@Nullable EventLoopGroup bossEventLoopGroup) {
      this.bossEventLoopGroup = bossEventLoopGroup;
      return this;
    }

    /**
     * Sets an externally-owned I/O event loop group.
     *
     * <p>When supplied, the transport does not shut the group down; the caller retains ownership.
     *
     * @param workerEventLoopGroup the worker group, or {@code null} for a private group.
     * @return this builder.
     */
    public Builder workerEventLoopGroup(@Nullable EventLoopGroup workerEventLoopGroup) {
      this.workerEventLoopGroup = workerEventLoopGroup;
      return this;
    }

    /**
     * Sets the maximum number of concurrent accepted connections. Defaults to {@code 16}.
     *
     * @param maxConnections the connection cap; must be positive.
     * @return this builder.
     */
    public Builder maxConnections(int maxConnections) {
      this.maxConnections = maxConnections;
      return this;
    }

    /**
     * Sets a customizer invoked on the {@link ServerBootstrap} before binding.
     *
     * @param serverBootstrapCustomizer the bootstrap customizer, or {@code null} for none.
     * @return this builder.
     */
    public Builder serverBootstrapCustomizer(
        @Nullable Consumer<ServerBootstrap> serverBootstrapCustomizer) {
      this.serverBootstrapCustomizer = serverBootstrapCustomizer;
      return this;
    }

    /**
     * Builds an immutable {@link NettyServerTransportConfig}.
     *
     * @return the configuration.
     */
    public NettyServerTransportConfig build() {
      return new NettyServerTransportConfig(
          bindHost,
          port,
          tlsOptions,
          bossEventLoopGroup,
          workerEventLoopGroup,
          maxConnections,
          serverBootstrapCustomizer);
    }
  }
}
