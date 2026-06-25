package com.digitalpetri.iec60870;

import java.util.Objects;
import javax.net.ssl.SSLContext;

/**
 * Immutable TLS configuration applied by the transport layer when securing a connection.
 *
 * <p>This holder carries the {@link SSLContext} that supplies the key and trust material along with
 * a flag indicating whether the peer must present a certificate (mutual authentication). It
 * contains no transport-specific types; the transport module translates it into the concrete engine
 * it uses.
 *
 * <p>Instances are created through {@link #builder(SSLContext)}:
 *
 * <pre>{@code
 * TlsOptions options = TlsOptions.builder(sslContext)
 *     .clientAuthRequired(true)
 *     .build();
 * }</pre>
 */
public final class TlsOptions {

  private final SSLContext sslContext;

  private final boolean clientAuthRequired;

  private final boolean verifyHostname;

  private TlsOptions(SSLContext sslContext, boolean clientAuthRequired, boolean verifyHostname) {
    this.sslContext = sslContext;
    this.clientAuthRequired = clientAuthRequired;
    this.verifyHostname = verifyHostname;
  }

  /**
   * Returns the {@link SSLContext} that supplies the key and trust material for the connection.
   *
   * @return the SSL context.
   */
  public SSLContext sslContext() {
    return sslContext;
  }

  /**
   * Returns whether the peer is required to present a certificate (mutual authentication).
   *
   * <p>On a server this requires client authentication; on a client it has no effect.
   *
   * @return {@code true} if client authentication is required, {@code false} otherwise.
   */
  public boolean clientAuthRequired() {
    return clientAuthRequired;
  }

  /**
   * Returns whether the client must verify that the server certificate matches the host it dialed.
   *
   * <p>On a client this enables JDK endpoint identification (the {@code HTTPS} algorithm) so a
   * certificate valid for a different host is rejected during the handshake; on a server it has no
   * effect. Enabled by default.
   *
   * @return {@code true} if client hostname verification is enabled, {@code false} otherwise.
   */
  public boolean verifyHostname() {
    return verifyHostname;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof TlsOptions that)) {
      return false;
    }
    return clientAuthRequired == that.clientAuthRequired
        && verifyHostname == that.verifyHostname
        && sslContext.equals(that.sslContext);
  }

  @Override
  public int hashCode() {
    return Objects.hash(sslContext, clientAuthRequired, verifyHostname);
  }

  @Override
  public String toString() {
    return "TlsOptions{clientAuthRequired="
        + clientAuthRequired
        + ", verifyHostname="
        + verifyHostname
        + '}';
  }

  /**
   * Creates a builder seeded with the given SSL context and client authentication disabled.
   *
   * @param sslContext the SSL context that supplies the key and trust material; must not be null.
   * @return a new builder.
   * @throws NullPointerException if {@code sslContext} is null.
   */
  public static Builder builder(SSLContext sslContext) {
    return new Builder(sslContext);
  }

  /**
   * Builder for {@link TlsOptions}.
   *
   * <p>A builder is single-use per configuration but may be reused to produce several instances. It
   * is not thread-safe.
   */
  public static final class Builder {

    private final SSLContext sslContext;

    private boolean clientAuthRequired = false;

    private boolean verifyHostname = true;

    private Builder(SSLContext sslContext) {
      this.sslContext = Objects.requireNonNull(sslContext, "sslContext");
    }

    /**
     * Sets whether the peer must present a certificate (mutual authentication).
     *
     * @param clientAuthRequired {@code true} to require client authentication, {@code false}
     *     otherwise.
     * @return this builder.
     */
    public Builder clientAuthRequired(boolean clientAuthRequired) {
      this.clientAuthRequired = clientAuthRequired;
      return this;
    }

    /**
     * Sets whether the client verifies that the server certificate matches the dialed host.
     *
     * <p>Enabled by default; disabling it turns off JDK endpoint identification on the client and
     * has no effect on a server.
     *
     * @param verifyHostname {@code true} to verify the server hostname, {@code false} to disable
     *     verification.
     * @return this builder.
     */
    public Builder verifyHostname(boolean verifyHostname) {
      this.verifyHostname = verifyHostname;
      return this;
    }

    /**
     * Builds an immutable {@link TlsOptions} from the current builder state.
     *
     * @return the configured TLS options.
     */
    public TlsOptions build() {
      return new TlsOptions(sslContext, clientAuthRequired, verifyHostname);
    }
  }
}
