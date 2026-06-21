package com.digitalpetri.iec104.client;

import com.digitalpetri.iec104.ApciSettings;
import com.digitalpetri.iec104.ProtocolProfile;
import com.digitalpetri.iec104.address.OriginatorAddress;
import com.digitalpetri.iec104.catalog.PointCatalog;
import com.digitalpetri.iec104.codec.MutableTypeCodecRegistry;
import com.digitalpetri.iec104.codec.TypeCodecRegistry;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import org.jspecify.annotations.Nullable;

/**
 * Immutable configuration for an {@link Iec104Client}.
 *
 * <p>This configuration covers the protocol-layer behavior of the client: the wire profile, the
 * APCI flow-control parameters, the originator address, an optional point catalog, whether to start
 * data transfer automatically on connect, the request and command timeouts, the executor that
 * delivers events and completes blocking calls, and the codec registry for private TypeIDs.
 * Transport concerns (host, port, TLS) are configured on the transport, not here.
 *
 * <p>Build a configuration with the {@linkplain #builder() builder}; unset fields take sensible
 * defaults:
 *
 * <pre>{@code
 * ClientConfig config = ClientConfig.builder()
 *     .startDataTransferOnConnect(true)
 *     .commandTimeout(Duration.ofSeconds(10))
 *     .build();
 * }</pre>
 *
 * @param protocolProfile the wire field widths.
 * @param apciSettings the APCI flow-control parameters.
 * @param originatorAddress the originator address placed in control-direction ASDUs.
 * @param pointCatalog the optional point catalog describing known points, or {@code null} if none.
 * @param startDataTransferOnConnect whether {@link Iec104Client#connect()} also starts data
 *     transfer.
 * @param commandTimeout the maximum time to await a command confirmation.
 * @param requestTimeout the maximum time to await an interrogation, read, or clock-sync response.
 * @param callbackExecutor the executor used to deliver events and complete blocking calls.
 * @param typeCodecRegistry the registry of codecs for private or uncommon TypeIDs.
 */
public record ClientConfig(
    ProtocolProfile protocolProfile,
    ApciSettings apciSettings,
    OriginatorAddress originatorAddress,
    @Nullable PointCatalog pointCatalog,
    boolean startDataTransferOnConnect,
    Duration commandTimeout,
    Duration requestTimeout,
    Executor callbackExecutor,
    TypeCodecRegistry typeCodecRegistry) {

  /**
   * Validates the components.
   *
   * @param protocolProfile the wire field widths.
   * @param apciSettings the APCI flow-control parameters.
   * @param originatorAddress the originator address placed in control-direction ASDUs.
   * @param pointCatalog the optional point catalog describing known points, or {@code null} if
   *     none.
   * @param startDataTransferOnConnect whether {@link Iec104Client#connect()} also starts data
   *     transfer.
   * @param commandTimeout the maximum time to await a command confirmation.
   * @param requestTimeout the maximum time to await an interrogation, read, or clock-sync response.
   * @param callbackExecutor the executor used to deliver events and complete blocking calls.
   * @param typeCodecRegistry the registry of codecs for private or uncommon TypeIDs.
   * @throws NullPointerException if any non-nullable component is null.
   * @throws IllegalArgumentException if {@code commandTimeout} or {@code requestTimeout} is zero or
   *     negative.
   */
  public ClientConfig {
    Objects.requireNonNull(protocolProfile, "protocolProfile");
    Objects.requireNonNull(apciSettings, "apciSettings");
    Objects.requireNonNull(originatorAddress, "originatorAddress");
    Objects.requireNonNull(commandTimeout, "commandTimeout");
    Objects.requireNonNull(requestTimeout, "requestTimeout");
    Objects.requireNonNull(callbackExecutor, "callbackExecutor");
    Objects.requireNonNull(typeCodecRegistry, "typeCodecRegistry");
    requirePositive(commandTimeout, "commandTimeout");
    requirePositive(requestTimeout, "requestTimeout");
  }

  /**
   * Returns the configured point catalog, if any.
   *
   * @return the point catalog, or an empty {@link Optional} if none was configured.
   */
  public Optional<PointCatalog> pointCatalogOptional() {
    return Optional.ofNullable(pointCatalog);
  }

  /**
   * Returns a configuration builder seeded with the defaults.
   *
   * @return a new builder.
   */
  public static Builder builder() {
    return new Builder();
  }

  private static void requirePositive(Duration duration, String name) {
    if (duration.isZero() || duration.isNegative()) {
      throw new IllegalArgumentException(name + " must be positive: " + duration);
    }
  }

  /**
   * A builder for {@link ClientConfig}.
   *
   * <p>Every setter has a sensible default, so a usable configuration can be built with no calls at
   * all. The builder is not thread-safe; build a configuration on one thread and share the
   * immutable result.
   */
  public static final class Builder {

    private ProtocolProfile protocolProfile = ProtocolProfile.iec104Default();
    private ApciSettings apciSettings = ApciSettings.defaults();
    private OriginatorAddress originatorAddress = OriginatorAddress.none();
    private @Nullable PointCatalog pointCatalog;
    private boolean startDataTransferOnConnect = true;
    private Duration commandTimeout = Duration.ofSeconds(10);
    private Duration requestTimeout = Duration.ofSeconds(30);
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
     * Sets the originator address placed in control-direction ASDUs.
     *
     * @param originatorAddress the originator address.
     * @return this builder.
     */
    public Builder originatorAddress(OriginatorAddress originatorAddress) {
      this.originatorAddress = Objects.requireNonNull(originatorAddress, "originatorAddress");
      return this;
    }

    /**
     * Sets the point catalog describing known points.
     *
     * @param pointCatalog the point catalog, or {@code null} to clear it.
     * @return this builder.
     */
    public Builder pointCatalog(@Nullable PointCatalog pointCatalog) {
      this.pointCatalog = pointCatalog;
      return this;
    }

    /**
     * Sets whether {@link Iec104Client#connect()} also starts data transfer. Defaults to {@code
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
     * Sets the maximum time to await a command confirmation. Defaults to 10 seconds.
     *
     * @param commandTimeout the command timeout; must be positive.
     * @return this builder.
     */
    public Builder commandTimeout(Duration commandTimeout) {
      this.commandTimeout = Objects.requireNonNull(commandTimeout, "commandTimeout");
      return this;
    }

    /**
     * Sets the maximum time to await an interrogation, read, or clock-sync response. Defaults to 30
     * seconds.
     *
     * @param requestTimeout the request timeout; must be positive.
     * @return this builder.
     */
    public Builder requestTimeout(Duration requestTimeout) {
      this.requestTimeout = Objects.requireNonNull(requestTimeout, "requestTimeout");
      return this;
    }

    /**
     * Sets the executor used to deliver events and complete blocking calls. Defaults to the common
     * {@link ForkJoinPool}.
     *
     * <p>The executor is used to serialize event delivery, so it should preserve submission order
     * (a single-threaded executor is the simplest such choice).
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
     * Builds an immutable {@link ClientConfig} from the current builder state.
     *
     * @return the configuration.
     * @throws IllegalArgumentException if {@code commandTimeout} or {@code requestTimeout} is not
     *     positive.
     */
    public ClientConfig build() {
      return new ClientConfig(
          protocolProfile,
          apciSettings,
          originatorAddress,
          pointCatalog,
          startDataTransferOnConnect,
          commandTimeout,
          requestTimeout,
          callbackExecutor,
          typeCodecRegistry);
    }
  }
}
