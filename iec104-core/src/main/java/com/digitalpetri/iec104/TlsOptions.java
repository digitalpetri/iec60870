package com.digitalpetri.iec104;

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

  private TlsOptions(SSLContext sslContext, boolean clientAuthRequired) {
    this.sslContext = sslContext;
    this.clientAuthRequired = clientAuthRequired;
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

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof TlsOptions that)) {
      return false;
    }
    return clientAuthRequired == that.clientAuthRequired && sslContext.equals(that.sslContext);
  }

  @Override
  public int hashCode() {
    return Objects.hash(sslContext, clientAuthRequired);
  }

  @Override
  public String toString() {
    return "TlsOptions{clientAuthRequired=" + clientAuthRequired + '}';
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
     * Builds an immutable {@link TlsOptions} from the current builder state.
     *
     * @return the configured TLS options.
     */
    public TlsOptions build() {
      return new TlsOptions(sslContext, clientAuthRequired);
    }
  }
}
