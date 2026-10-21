package com.digitalpetri.iec60870.client;

import com.digitalpetri.iec60870.ProtocolProfile;
import com.digitalpetri.iec60870.SessionSettings;
import com.digitalpetri.iec60870.address.OriginatorAddress;
import com.digitalpetri.iec60870.catalog.PointCatalog;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import org.jspecify.annotations.Nullable;

/**
 * Immutable configuration for an {@link Iec60870Client}.
 *
 * <p>This configuration covers the protocol-layer behavior of the client: the wire profile, the
 * APCI flow-control parameters, the originator address, an optional point catalog, whether to start
 * data transfer automatically on connect, the request and command timeouts, the bound on the
 * objects a single interrogation will accumulate, the executor that delivers events and completes
 * blocking calls, the event backlog at which the client pauses inbound data, and the policy that
 * reconnects after a loss. Transport concerns (host, port, TLS) are configured on the transport,
 * not here.
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
 * @param sessionSettings the protocol-specific session settings (the APCI flow-control parameters
 *     for a 104 session).
 * @param originatorAddress the originator address placed in control-direction ASDUs.
 * @param pointCatalog the optional point catalog describing known points, or {@code null} if none.
 * @param startDataTransferOnConnect whether every connection the client establishes, including
 *     automatic reconnects, also starts data transfer.
 * @param commandTimeout the maximum time to await a command confirmation.
 * @param requestTimeout the maximum time to await an interrogation, read, or clock-sync response.
 * @param maxInterrogationResponseObjects the bound on the number of information objects a single
 *     interrogation will accumulate before the request is failed, or {@code 0} for no bound.
 * @param callbackExecutor the executor used to deliver events and complete blocking calls.
 * @param eventBacklogLimit the number of undelivered events behind the slowest subscriber at which
 *     the client pauses inbound data.
 * @param reconnectPolicy when the client reconnects after a lost connection or a failed attempt.
 */
public record ClientConfig(
    ProtocolProfile protocolProfile,
    @Nullable SessionSettings sessionSettings,
    OriginatorAddress originatorAddress,
    @Nullable PointCatalog pointCatalog,
    boolean startDataTransferOnConnect,
    Duration commandTimeout,
    Duration requestTimeout,
    int maxInterrogationResponseObjects,
    Executor callbackExecutor,
    int eventBacklogLimit,
    ReconnectPolicy reconnectPolicy) {

  /**
   * Validates the components.
   *
   * @param protocolProfile the wire field widths.
   * @param sessionSettings the protocol-specific session settings (the APCI flow-control parameters
   *     for a 104 session).
   * @param originatorAddress the originator address placed in control-direction ASDUs.
   * @param pointCatalog the optional point catalog describing known points, or {@code null} if
   *     none.
   * @param startDataTransferOnConnect whether every connection the client establishes, including
   *     automatic reconnects, also starts data transfer.
   * @param commandTimeout the maximum time to await a command confirmation.
   * @param requestTimeout the maximum time to await an interrogation, read, or clock-sync response.
   * @param maxInterrogationResponseObjects the bound on the number of information objects a single
   *     interrogation will accumulate before the request is failed, or {@code 0} for no bound.
   * @param callbackExecutor the executor used to deliver events and complete blocking calls.
   * @param eventBacklogLimit the number of undelivered events behind the slowest subscriber at
   *     which the client pauses inbound data.
   * @param reconnectPolicy when the client reconnects after a lost connection or a failed attempt.
   * @throws NullPointerException if any non-nullable component is null.
   * @throws IllegalArgumentException if {@code commandTimeout} or {@code requestTimeout} is zero or
   *     negative, {@code maxInterrogationResponseObjects} is negative, or {@code eventBacklogLimit}
   *     is not in {@code 1..268435456}.
   */
  public ClientConfig {
    Objects.requireNonNull(protocolProfile, "protocolProfile");
    Objects.requireNonNull(originatorAddress, "originatorAddress");
    Objects.requireNonNull(commandTimeout, "commandTimeout");
    Objects.requireNonNull(requestTimeout, "requestTimeout");
    Objects.requireNonNull(callbackExecutor, "callbackExecutor");
    Objects.requireNonNull(reconnectPolicy, "reconnectPolicy");
    requirePositive(commandTimeout, "commandTimeout");
    requirePositive(requestTimeout, "requestTimeout");
    if (maxInterrogationResponseObjects < 0) {
      throw new IllegalArgumentException(
          "maxInterrogationResponseObjects must be >= 0: " + maxInterrogationResponseObjects);
    }
    if (eventBacklogLimit < 1 || eventBacklogLimit > EventStream.MAX_BACKLOG_LIMIT) {
      throw new IllegalArgumentException(
          "eventBacklogLimit must be in 1.."
              + EventStream.MAX_BACKLOG_LIMIT
              + ": "
              + eventBacklogLimit);
    }
  }

  /** The default {@linkplain Builder#eventBacklogLimit(int) event backlog limit}. */
  public static final int DEFAULT_EVENT_BACKLOG_LIMIT = 4096;

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
    private @Nullable SessionSettings sessionSettings;
    private OriginatorAddress originatorAddress = OriginatorAddress.none();
    private @Nullable PointCatalog pointCatalog;
    private boolean startDataTransferOnConnect = true;
    private Duration commandTimeout = Duration.ofSeconds(10);
    private Duration requestTimeout = Duration.ofSeconds(30);
    private int maxInterrogationResponseObjects = 1_000_000;
    private Executor callbackExecutor = ForkJoinPool.commonPool();
    private int eventBacklogLimit = DEFAULT_EVENT_BACKLOG_LIMIT;
    private ReconnectPolicy reconnectPolicy = ReconnectPolicy.defaults();

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
     * Sets the protocol-specific session settings (the APCI flow-control parameters for a 104
     * session).
     *
     * @param sessionSettings the session settings.
     * @return this builder.
     */
    public Builder sessionSettings(SessionSettings sessionSettings) {
      this.sessionSettings = Objects.requireNonNull(sessionSettings, "sessionSettings");
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
     * Sets whether every connection the client establishes, through {@link
     * Iec60870Client#connect()} or an automatic reconnect, also starts data transfer. Defaults to
     * {@code true}.
     *
     * <p>A standby link in a redundancy group sets this to {@code false} and calls {@link
     * Iec60870Client#startDataTransfer()} once it is chosen to carry data.
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
     * Sets the bound on the number of information objects a single interrogation will accumulate
     * before its response collection is treated as a fault and the request is failed. Defaults to
     * {@code 1_000_000}; a value of {@code 0} leaves the accumulation unbounded.
     *
     * <p>IEC 60870-5 places no aggregate limit on the objects an interrogation may report, so a
     * peer that confirms the interrogation but never sends an activation termination could
     * otherwise accumulate objects in memory without bound. The request timeout bounds the time
     * window; this bounds the volume within it. The default sits far above any realistic
     * single-station interrogation, so it bounds memory without rejecting legitimate responses.
     *
     * @param maxInterrogationResponseObjects the accumulation bound, or {@code 0} for unbounded;
     *     must not be negative.
     * @return this builder.
     */
    public Builder maxInterrogationResponseObjects(int maxInterrogationResponseObjects) {
      this.maxInterrogationResponseObjects = maxInterrogationResponseObjects;
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
     * Sets the event backlog at which the client pauses inbound data. Defaults to {@value
     * #DEFAULT_EVENT_BACKLOG_LIMIT}.
     *
     * <p>The event stream never drops an event for a slow subscriber. Instead, once the slowest
     * subscriber of {@link Iec60870Client#events()} has this many published events it has not yet
     * consumed, the client asks its session to stop taking in user data, and the peer's own flow
     * control holds it back. Input resumes when the backlog falls to half the limit. On IEC
     * 60870-5-104 the client stops reading from the connection, so the backlog can exceed the limit
     * by at most the events of the peer's {@code k} unacknowledged I-frames. A pause longer than
     * the peer's {@code t1} ends in a connection close reported through {@link
     * ClientEvent.ConnectionClosed} with a cause.
     *
     * <p>A session that cannot pause (the IEC 60870-5-101 link layers do not yet) relies on the
     * buffer alone. If a subscriber's backlog reaches the hard capacity, the smallest power of two
     * at or above {@code 2 * eventBacklogLimit + 8192} events less a reserve for lifecycle events,
     * the client closes the connection with an {@link
     * com.digitalpetri.iec60870.EventBacklogExceededException} cause instead of dropping events.
     * Until that subscriber catches up, connecting and starting or stopping data transfer fail with
     * the same exception, so the lifecycle events they publish always fit.
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
     * {@link ReconnectPolicy#defaults()}.
     *
     * <p>Every automatic attempt runs the same initialization as {@link Iec60870Client#connect()},
     * including {@code STARTDT} when {@link #startDataTransferOnConnect(boolean)} is set. Use
     * {@link ReconnectPolicy#disabled()} to leave reconnecting to the application.
     *
     * @param reconnectPolicy the reconnect policy.
     * @return this builder.
     */
    public Builder reconnectPolicy(ReconnectPolicy reconnectPolicy) {
      this.reconnectPolicy = Objects.requireNonNull(reconnectPolicy, "reconnectPolicy");
      return this;
    }

    /**
     * Builds an immutable {@link ClientConfig} from the current builder state.
     *
     * @return the configuration.
     * @throws IllegalArgumentException if {@code commandTimeout} or {@code requestTimeout} is not
     *     positive, or {@code eventBacklogLimit} is out of range.
     */
    public ClientConfig build() {
      return new ClientConfig(
          protocolProfile,
          sessionSettings,
          originatorAddress,
          pointCatalog,
          startDataTransferOnConnect,
          commandTimeout,
          requestTimeout,
          maxInterrogationResponseObjects,
          callbackExecutor,
          eventBacklogLimit,
          reconnectPolicy);
    }
  }
}
