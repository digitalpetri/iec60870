package com.digitalpetri.iec104.transport.tcp;

import com.digitalpetri.iec104.ProtocolProfile;
import com.digitalpetri.iec104.TlsOptions;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.EventLoopGroup;
import java.net.SocketAddress;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;

/**
 * Immutable transport-layer configuration for a {@link NettyClientTransport}.
 *
 * <p>This holder carries everything the Netty client transport needs to open and frame a single
 * outgoing connection: the remote endpoint, an optional local bind address, the wire profile,
 * optional TLS, an optional shared {@link EventLoopGroup}, and an optional {@link Bootstrap}
 * customizer for advanced socket tuning. Protocol-layer concerns (APCI settings, originator
 * address) belong on the core {@code ClientConfig}, not here.
 *
 * <p>Construct instances through {@link #builder(String, int)}; the optional accessors return empty
 * {@link Optional}s when a value was not supplied.
 *
 * @param host the remote host name or address to connect to.
 * @param port the remote TCP port to connect to.
 * @param localBind the local address to bind the outgoing socket to, or {@code null} for any.
 * @param profile the protocol profile that governs ASDU field widths.
 * @param tlsOptions the TLS options, or {@code null} for a plaintext connection.
 * @param sharedEventLoopGroup an externally-owned {@link EventLoopGroup} to run the channel on, or
 *     {@code null} to let the transport create and own a private group.
 * @param bootstrapCustomizer a hook to mutate the {@link Bootstrap} before connecting (socket
 *     options, allocators, and the like), or {@code null} for none.
 */
public record NettyClientTransportConfig(
    String host,
    int port,
    @Nullable SocketAddress localBind,
    ProtocolProfile profile,
    @Nullable TlsOptions tlsOptions,
    @Nullable EventLoopGroup sharedEventLoopGroup,
    @Nullable Consumer<Bootstrap> bootstrapCustomizer) {

  /**
   * Validates the configuration.
   *
   * @param host the remote host name or address to connect to.
   * @param port the remote TCP port to connect to.
   * @param localBind the local address to bind the outgoing socket to, or {@code null} for any.
   * @param profile the protocol profile that governs ASDU field widths.
   * @param tlsOptions the TLS options, or {@code null} for a plaintext connection.
   * @param sharedEventLoopGroup an externally-owned {@link EventLoopGroup} to run the channel on,
   *     or {@code null} to let the transport create and own a private group.
   * @param bootstrapCustomizer a hook to mutate the {@link Bootstrap} before connecting (socket
   *     options, allocators, and the like), or {@code null} for none.
   * @throws NullPointerException if {@code host} or {@code profile} is null.
   * @throws IllegalArgumentException if {@code port} is not in the range {@code 1..65535}.
   */
  public NettyClientTransportConfig {
    Objects.requireNonNull(host, "host");
    Objects.requireNonNull(profile, "profile");
    if (port < 1 || port > 65535) {
      throw new IllegalArgumentException("port must be in 1..65535: " + port);
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
    private ProtocolProfile profile = ProtocolProfile.iec104Default();
    private @Nullable TlsOptions tlsOptions;
    private @Nullable EventLoopGroup sharedEventLoopGroup;
    private @Nullable Consumer<Bootstrap> bootstrapCustomizer;

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
     * Sets the protocol profile. Defaults to {@link ProtocolProfile#iec104Default()}.
     *
     * @param profile the protocol profile.
     * @return this builder.
     */
    public Builder profile(ProtocolProfile profile) {
      this.profile = Objects.requireNonNull(profile, "profile");
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
     * Builds an immutable {@link NettyClientTransportConfig}.
     *
     * @return the configuration.
     */
    public NettyClientTransportConfig build() {
      return new NettyClientTransportConfig(
          host, port, localBind, profile, tlsOptions, sharedEventLoopGroup, bootstrapCustomizer);
    }
  }
}
