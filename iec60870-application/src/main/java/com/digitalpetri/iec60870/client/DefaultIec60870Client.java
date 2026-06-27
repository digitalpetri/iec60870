package com.digitalpetri.iec60870.client;

import com.digitalpetri.iec60870.ConnectionClosedException;
import com.digitalpetri.iec60870.Iec60870Exception;
import com.digitalpetri.iec60870.NegativeConfirmationException;
import com.digitalpetri.iec60870.ProtocolTimeoutException;
import com.digitalpetri.iec60870.RequestInProgressException;
import com.digitalpetri.iec60870.address.CommonAddress;
import com.digitalpetri.iec60870.address.InformationObjectAddress;
import com.digitalpetri.iec60870.address.PointAddress;
import com.digitalpetri.iec60870.asdu.Asdu;
import com.digitalpetri.iec60870.asdu.AsduType;
import com.digitalpetri.iec60870.asdu.Cause;
import com.digitalpetri.iec60870.asdu.InformationObject;
import com.digitalpetri.iec60870.asdu.element.QualifierOfInterrogation;
import com.digitalpetri.iec60870.asdu.object.ClockSynchronizationCommand;
import com.digitalpetri.iec60870.asdu.object.InterrogationCommand;
import com.digitalpetri.iec60870.asdu.object.ReadCommand;
import com.digitalpetri.iec60870.asdu.time.Cp56Time2a;
import com.digitalpetri.iec60870.point.MonitorMapping;
import com.digitalpetri.iec60870.point.PointValueExtraction;
import com.digitalpetri.iec60870.session.Session;
import com.digitalpetri.iec60870.transport.ClientTransport;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.SubmissionPublisher;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BiFunction;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The default {@link Iec60870Client} implementation, driving an injected {@link Session}.
 *
 * <p>The client speaks to its {@link Session} purely in terms of {@link Asdu}s: it sends
 * application data with {@link Session#sendAsdu(Asdu)}, drives the data-transfer lifecycle with
 * {@link Session#startDataTransfer()}/{@link Session#stopDataTransfer()}, and receives inbound
 * ASDUs and lifecycle transitions through a {@link Session.Events} sink it owns. The
 * protocol-specific session implementation and its wire framing are assembled outside this class
 * and supplied through the session factory; the facade itself contains no wire-frame or
 * transport-framing logic.
 *
 * <p>The client also owns a {@link SubmissionPublisher} for {@link ClientEvent} delivery on the
 * configured callback executor and a request-correlation layer that matches activation
 * confirmations and interrogation responses to the blocking and asynchronous calls that issued
 * them.
 *
 * <p>The instance is thread-safe. Construct it with a transport, a {@link ClientConfig}, and a
 * session factory that builds the {@link Session} wired to the facade's {@link Session.Events}.
 */
public final class DefaultIec60870Client implements Iec60870Client {

  private static final Logger LOGGER = LoggerFactory.getLogger(DefaultIec60870Client.class);

  /** Conventional information object address for station-level commands (interrogation, clock). */
  private static final InformationObjectAddress ZERO_ADDRESS = InformationObjectAddress.of(0);

  /**
   * Default upper bound on the number of information objects a single interrogation will accumulate
   * before its response collection is treated as a fault and the request is failed.
   *
   * <p>IEC 60870-5 places no aggregate limit on the objects an interrogation may report, so a peer
   * that confirms the interrogation but never sends an activation termination could otherwise
   * stream objects into one in-memory list without bound. This value sits far above any realistic
   * single-station interrogation, so it bounds memory without rejecting legitimate responses.
   */
  private static final int DEFAULT_MAX_INTERROGATION_RESPONSE_OBJECTS = 1_000_000;

  private final ClientTransport transport;
  private final ClientConfig config;
  private final int maxInterrogationResponseObjects;

  private final ScheduledExecutorService scheduler;
  private final boolean ownsScheduler;

  private final Executor callbackExecutor;
  private final SubmissionPublisher<ClientEvent> publisher;
  private final Session session;
  private final CommandService commandService;

  private final ReentrantLock lock = new ReentrantLock();
  private final List<PendingRequest> pending = new ArrayList<>();

  /**
   * Guards against publishing {@link ClientEvent.ConnectionClosed} more than once per connection.
   */
  private final AtomicBoolean connectionClosedPublished = new AtomicBoolean(true);

  /**
   * Creates a client over the given transport and configuration, building its {@link Session}
   * through the supplied factory.
   *
   * <p>The factory receives the facade's {@link Session.Events} sink and returns a session wired to
   * deliver inbound ASDUs, data-transfer transitions, and self-initiated closes to it, and is given
   * the facade's owned scheduler so the protocol session and the facade share one timer thread. The
   * protocol assembly (the protocol-specific session and its wire framing) lives in the factory,
   * not in this class. The client creates an internal single-thread scheduler for request timeouts.
   * Call {@link #connect()} to establish the connection.
   *
   * @param transport the transport whose connection lifecycle the client drives.
   * @param config the client configuration.
   * @param sessionFactory builds the session wired to the facade's {@link Session.Events}, using
   *     the supplied scheduler.
   * @throws NullPointerException if any argument is null.
   */
  public DefaultIec60870Client(
      ClientTransport transport,
      ClientConfig config,
      BiFunction<Session.Events, ScheduledExecutorService, Session> sessionFactory) {
    this(transport, config, sessionFactory, null);
  }

  /**
   * Creates a client over the given transport and configuration, building its {@link Session}
   * through the supplied factory and using an injected scheduler.
   *
   * <p>The factory receives the facade's {@link Session.Events} sink and the scheduler and returns
   * a session wired to them. When {@code scheduler} is {@code null} an internal single-thread
   * scheduler is created and shut down on {@link #close()}; otherwise the supplied scheduler is
   * used and is never shut down by the client. A test may inject a deterministic scheduler here.
   *
   * @param transport the transport whose connection lifecycle the client drives.
   * @param config the client configuration.
   * @param sessionFactory builds the session wired to the facade's {@link Session.Events}, using
   *     the supplied scheduler.
   * @param scheduler the scheduler for request timeouts, or {@code null} to create an internal one.
   * @throws NullPointerException if {@code transport}, {@code config}, or {@code sessionFactory} is
   *     null.
   */
  public DefaultIec60870Client(
      ClientTransport transport,
      ClientConfig config,
      BiFunction<Session.Events, ScheduledExecutorService, Session> sessionFactory,
      @Nullable ScheduledExecutorService scheduler) {
    this(transport, config, sessionFactory, scheduler, DEFAULT_MAX_INTERROGATION_RESPONSE_OBJECTS);
  }

  /**
   * Creates a client with an injected scheduler and an explicit cap on the number of information
   * objects a single interrogation will accumulate before its response is treated as a fault.
   *
   * <p>Package-private; the public constructors apply {@link
   * #DEFAULT_MAX_INTERROGATION_RESPONSE_OBJECTS}. A test injects a small cap here to exercise the
   * bounding behavior without streaming a realistic volume of objects.
   *
   * @param transport the transport whose connection lifecycle the client drives.
   * @param config the client configuration.
   * @param sessionFactory builds the session wired to the facade's {@link Session.Events}, using
   *     the supplied scheduler.
   * @param scheduler the scheduler for request timeouts, or {@code null} to create an internal one.
   * @param maxInterrogationResponseObjects the maximum number of objects one interrogation
   *     accumulates before the request is failed.
   * @throws NullPointerException if {@code transport}, {@code config}, or {@code sessionFactory} is
   *     null.
   */
  DefaultIec60870Client(
      ClientTransport transport,
      ClientConfig config,
      BiFunction<Session.Events, ScheduledExecutorService, Session> sessionFactory,
      @Nullable ScheduledExecutorService scheduler,
      int maxInterrogationResponseObjects) {

    this.transport = Objects.requireNonNull(transport, "transport");
    this.config = Objects.requireNonNull(config, "config");
    Objects.requireNonNull(sessionFactory, "sessionFactory");
    this.maxInterrogationResponseObjects = maxInterrogationResponseObjects;

    if (scheduler != null) {
      this.scheduler = scheduler;
      this.ownsScheduler = false;
    } else {
      this.scheduler = Executors.newSingleThreadScheduledExecutor(new SchedulerThreadFactory());
      this.ownsScheduler = true;
    }
    this.callbackExecutor = config.callbackExecutor();
    this.publisher = new SubmissionPublisher<>(callbackExecutor, Flow.defaultBufferSize());

    // The facade owns the Session.Events sink; the factory wires the session to it. The factory
    // routes inbound frames to the session and transport-level connection loss to
    // Session.Events.onClosed, so the facade learns of every close through that single sink.
    this.session =
        Objects.requireNonNull(
            sessionFactory.apply(new SessionEvents(), this.scheduler), "session");

    this.commandService = new DefaultCommandService();
  }

  // --- Iec60870Client: lifecycle ----------------------------------------------------------------

  @Override
  public void connect() {
    await(connectAsync());
  }

  @Override
  public CompletionStage<Void> connectAsync() {
    return transport
        .connect()
        .thenCompose(
            ignored -> {
              // Arm the one-shot ConnectionClosed guard for this connection.
              connectionClosedPublished.set(false);
              session.onConnected();
              publish(new ClientEvent.ConnectionOpened());
              if (config.startDataTransferOnConnect()) {
                return session.startDataTransfer();
              }
              return CompletableFuture.completedFuture(null);
            });
  }

  @Override
  public void startDataTransfer() {
    await(startDataTransferAsync());
  }

  @Override
  public CompletionStage<Void> startDataTransferAsync() {
    return session.startDataTransfer();
  }

  @Override
  public void stopDataTransfer() {
    await(stopDataTransferAsync());
  }

  @Override
  public CompletionStage<Void> stopDataTransferAsync() {
    return session.stopDataTransfer();
  }

  @Override
  public Flow.Publisher<ClientEvent> events() {
    return publisher;
  }

  @Override
  public boolean isConnected() {
    return transport.isConnected();
  }

  @Override
  public void close() {
    failAllPending(new ConnectionClosedException("client closed"));
    session.close();
    try {
      transport.disconnect();
    } catch (RuntimeException e) {
      LOGGER.debug("transport disconnect failed during close", e);
    }
    publisher.close();
    if (ownsScheduler) {
      scheduler.shutdownNow();
    }
  }

  // --- Iec60870Client: interrogation ------------------------------------------------------------

  @Override
  public InterrogationResult interrogate(CommonAddress station) {
    return await(interrogateAsync(station));
  }

  @Override
  public InterrogationResult interrogate(CommonAddress station, QualifierOfInterrogation qoi) {
    return await(interrogateAsync(station, qoi));
  }

  @Override
  public CompletionStage<InterrogationResult> interrogateAsync(CommonAddress station) {
    return interrogateAsync(station, QualifierOfInterrogation.STATION);
  }

  @Override
  public CompletionStage<InterrogationResult> interrogateAsync(
      CommonAddress station, QualifierOfInterrogation qoi) {

    Objects.requireNonNull(station, "station");
    Objects.requireNonNull(qoi, "qoi");

    var command = new InterrogationCommand(ZERO_ADDRESS, qoi);
    Asdu asdu =
        new Asdu(
            AsduType.C_IC_NA_1,
            false,
            Cause.ACTIVATION,
            false,
            false,
            config.originatorAddress(),
            station,
            List.of(command));

    CompletableFuture<InterrogationResult> future = new CompletableFuture<>();
    PendingInterrogation request =
        new PendingInterrogation(station, future, maxInterrogationResponseObjects);
    if (!register(request)) {
      future.completeExceptionally(
          alreadyInFlight("interrogation of common address " + station.value()));
      return future;
    }
    armAndSend(request, asdu, config.requestTimeout());
    return future;
  }

  // --- Iec60870Client: read ---------------------------------------------------------------------

  @Override
  public List<InformationObject> read(PointAddress point) {
    return await(readAsync(point));
  }

  @Override
  public CompletionStage<List<InformationObject>> readAsync(PointAddress point) {
    Objects.requireNonNull(point, "point");

    var command = new ReadCommand(point.objectAddress());
    Asdu asdu =
        new Asdu(
            AsduType.C_RD_NA_1,
            false,
            Cause.REQUEST,
            false,
            false,
            config.originatorAddress(),
            point.commonAddress(),
            List.of(command));

    CompletableFuture<List<InformationObject>> future = new CompletableFuture<>();
    PendingRead request = new PendingRead(point, future);
    if (!register(request)) {
      future.completeExceptionally(alreadyInFlight("read of " + point));
      return future;
    }
    armAndSend(request, asdu, config.requestTimeout());
    return future;
  }

  // --- Iec60870Client: commands -----------------------------------------------------------------

  @Override
  public CommandService commands() {
    return commandService;
  }

  // --- Iec60870Client: clock sync ---------------------------------------------------------------

  @Override
  public void synchronizeClock(CommonAddress station, Instant time) {
    await(synchronizeClockAsync(station, time));
  }

  @Override
  public CompletionStage<Void> synchronizeClockAsync(CommonAddress station, Instant time) {
    Objects.requireNonNull(station, "station");
    Objects.requireNonNull(time, "time");

    var command =
        new ClockSynchronizationCommand(ZERO_ADDRESS, Cp56Time2a.from(time, ZoneOffset.UTC));
    Asdu asdu =
        new Asdu(
            AsduType.C_CS_NA_1,
            false,
            Cause.ACTIVATION,
            false,
            false,
            config.originatorAddress(),
            station,
            List.of(command));

    CompletableFuture<Asdu> confirmation = new CompletableFuture<>();
    // Clock sync is a single station-wide operation at IOA 0 with no select/execute phases, so a
    // header-only activation confirmation (no echoed time object) still correlates.
    PendingConfirmation request =
        new PendingConfirmation(AsduType.C_CS_NA_1, station, ZERO_ADDRESS, false, confirmation);
    CompletableFuture<Void> result = new CompletableFuture<>();
    confirmation.whenComplete(
        (ack, error) -> {
          if (error != null) {
            result.completeExceptionally(error);
          } else if (ack.negative()) {
            result.completeExceptionally(new NegativeConfirmationException(ack.cause(), ack));
          } else {
            // CompletableFuture<Void> can only be completed with null; the @NotNull on
            // complete(T) does not account for the Void type argument.
            //noinspection ConstantConditions
            result.complete(null);
          }
        });
    if (!register(request)) {
      result.completeExceptionally(
          alreadyInFlight("clock synchronization of common address " + station.value()));
      return result;
    }
    armAndSend(request, asdu, config.commandTimeout());
    return result;
  }

  // --- Iec60870Client: raw send -----------------------------------------------------------------

  @Override
  public void send(Asdu asdu) {
    await(sendAsync(asdu));
  }

  @Override
  public CompletionStage<Void> sendAsync(Asdu asdu) {
    Objects.requireNonNull(asdu, "asdu");
    return submitToSession(asdu);
  }

  // --- Session output / input -----------------------------------------------------------------

  /**
   * Hands an outbound application ASDU to the session and returns a stage for the write.
   *
   * @param asdu the ASDU to send.
   * @return a stage that completes once the ASDU has been queued/sent, or completes exceptionally
   *     if the session is closed.
   */
  private CompletionStage<Void> submitToSession(Asdu asdu) {
    try {
      session.sendAsdu(asdu);
      return CompletableFuture.completedFuture(null);
    } catch (RuntimeException e) {
      return CompletableFuture.failedFuture(e);
    }
  }

  /**
   * Dispatches an ASDU delivered by the session: correlates it to a pending request, then publishes
   * the high-level events.
   *
   * <p>Invoked under the session lock, so it must not re-enter the session. It only completes
   * futures and publishes events (both of which run later on the callback executor).
   *
   * @param asdu the received ASDU.
   */
  private void onAsduReceived(Asdu asdu) {
    publish(new ClientEvent.AsduReceived(asdu));

    boolean consumed = correlate(asdu);

    if (!consumed && asdu.negative()) {
      publish(new ClientEvent.NegativeConfirmation(asdu, asdu.cause()));
    }

    if (isMonitorCause(asdu.cause())) {
      for (InformationObject object : asdu.objects()) {
        Optional<PointValueExtraction> extraction = MonitorMapping.extract(object);
        if (extraction.isEmpty()) {
          continue;
        }
        PointValueExtraction value = extraction.get();
        PointAddress address = new PointAddress(asdu.commonAddress(), object.address());
        publish(
            new ClientEvent.PointUpdated(
                address, value.value(), asdu.type(), asdu.cause(), value.timestamp()));
      }
    }
  }

  /**
   * Offers an ASDU to the pending requests, letting the first one that recognizes it consume it.
   *
   * <p>List mutation and future completion happen here, off the request implementations, so a
   * request's {@link PendingRequest#accept(Asdu)} never mutates the registry while it is iterated.
   *
   * @param asdu the received ASDU.
   * @return {@code true} if a pending request consumed the ASDU.
   */
  private boolean correlate(Asdu asdu) {
    PendingRequest finished = null;
    PendingRequest.Outcome outcome = PendingRequest.Outcome.IGNORED;
    boolean consumed = false;

    lock.lock();
    try {
      for (PendingRequest request : pending) {
        PendingRequest.Outcome o = request.accept(asdu);
        if (o == PendingRequest.Outcome.IGNORED) {
          continue;
        }
        consumed = true;
        if (o == PendingRequest.Outcome.COMPLETED || o == PendingRequest.Outcome.FAILED) {
          finished = request;
          outcome = o;
          pending.remove(request);
        }
        break;
      }
    } finally {
      lock.unlock();
    }

    if (finished != null) {
      PendingRequest request = finished;
      PendingRequest.Outcome finalOutcome = outcome;
      request.cancelTimeout();
      // Complete off the I/O thread and off the session lock: a SELECT_BEFORE_OPERATE
      // continuation re-enters session.sendAsdu, which must observe the already-advanced V(R) and
      // must not run under the session lock.
      callbackExecutor.execute(
          () -> {
            if (finalOutcome == PendingRequest.Outcome.COMPLETED) {
              request.deliver();
            } else {
              request.deliverFailure();
            }
          });
    }
    return consumed;
  }

  /**
   * Registers a pending request unless another request with the same correlation key is already in
   * flight.
   *
   * <p>IEC 60870-5-104 carries no per-request identifier; responses are matched to outstanding
   * requests by their ASDU fields, so two requests whose responses {@linkplain
   * PendingRequest#conflictsWith(PendingRequest) overlap} would compete for the same responses and
   * be indistinguishable on the wire. The standard expects the controlling station to serialize
   * such requests ("the initialization of an identical station interrogation of the same source
   * before the previous one is terminated is normally locked by the controlling station", IEC
   * 60870-5-101 §7.4.5); this client enforces that by refusing the conflicting request. Requests to
   * different targets (a read of one point, a command to another, an interrogation of a different
   * station) do not conflict and register freely, so legitimate parallelism is preserved.
   *
   * <p>The conflict check and the registration share a single lock acquisition, so two callers
   * racing to register conflicting requests cannot both succeed. The in-flight set is the {@link
   * #pending} list itself, which every terminal path already maintains, so there is no parallel
   * state that could drift out of sync.
   *
   * @param request the pending request to register.
   * @return {@code true} if the request was registered, {@code false} if a conflicting request was
   *     already in flight.
   */
  private boolean register(PendingRequest request) {
    lock.lock();
    try {
      for (PendingRequest existing : pending) {
        // Check both directions so the conflict is detected regardless of which request registered
        // first, without relying on every conflictsWith override being symmetric.
        if (request.conflictsWith(existing) || existing.conflictsWith(request)) {
          return false;
        }
      }
      pending.add(request);
      return true;
    } finally {
      lock.unlock();
    }
  }

  /**
   * Builds the exception that rejects a request whose target already has a request in flight.
   *
   * @param target a short description of the request and its target, for the detail message.
   * @return the exception to fail the rejected request's future with.
   */
  private static RequestInProgressException alreadyInFlight(String target) {
    return new RequestInProgressException(target + " already in flight");
  }

  /**
   * Arms the timeout for an already-registered request and sends its activation ASDU.
   *
   * @param request the pending request, already added to {@link #pending}.
   * @param asdu the ASDU to send.
   * @param timeout the timeout for the request.
   */
  private void armAndSend(PendingRequest request, Asdu asdu, Duration timeout) {
    ScheduledFuture<?> timeoutHandle =
        scheduler.schedule(
            () -> timeoutRequest(request), timeout.toMillis(), TimeUnit.MILLISECONDS);
    request.setTimeoutHandle(timeoutHandle);

    submitToSession(asdu)
        .whenComplete(
            (ignored, error) -> {
              if (error != null) {
                removePending(request);
                callbackExecutor.execute(() -> request.fail(error));
              }
            });
  }

  /**
   * Times out a pending request that has not completed, removing it and failing its future.
   *
   * @param request the request to time out.
   */
  private void timeoutRequest(PendingRequest request) {
    if (removePending(request)) {
      callbackExecutor.execute(
          () ->
              request.fail(new ProtocolTimeoutException("request timed out awaiting a response")));
    }
  }

  /**
   * Removes a pending request from the registry and cancels its timeout.
   *
   * @param request the request to remove.
   * @return {@code true} if the request was still registered.
   */
  private boolean removePending(PendingRequest request) {
    boolean removed;
    lock.lock();
    try {
      removed = pending.remove(request);
    } finally {
      lock.unlock();
    }
    if (removed) {
      request.cancelTimeout();
    }
    return removed;
  }

  /**
   * Fails every pending request with the given cause.
   *
   * @param cause the failure to deliver.
   */
  private void failAllPending(Throwable cause) {
    List<PendingRequest> snapshot;
    lock.lock();
    try {
      snapshot = new ArrayList<>(pending);
      pending.clear();
    } finally {
      lock.unlock();
    }
    for (PendingRequest request : snapshot) {
      request.cancelTimeout();
      callbackExecutor.execute(() -> request.fail(cause));
    }
  }

  /**
   * Returns the number of in-flight requests awaiting a correlated response.
   *
   * <p>Package-private; exposed for tests that verify timed-out, failed, or completed requests are
   * removed from the registry and do not leak.
   *
   * @return the count of currently pending requests.
   */
  int pendingRequestCount() {
    lock.lock();
    try {
      return pending.size();
    } finally {
      lock.unlock();
    }
  }

  /**
   * Publishes a {@link ClientEvent.ConnectionClosed} exactly once per connection.
   *
   * <p>Every close — a self-initiated session close (protocol error or timeout) and a transport
   * connection loss routed by the session assembly — arrives through {@link
   * SessionEvents#onClosed(Throwable)}. Should both fire (for example a protocol-error close
   * followed by the transport's own loss notification), only the first wins, so the real cause is
   * not masked by a later {@code null}.
   *
   * @param cause the failure that closed the connection, or {@code null} for an orderly close.
   */
  private void publishConnectionClosed(@Nullable Throwable cause) {
    if (connectionClosedPublished.compareAndSet(false, true)) {
      publish(new ClientEvent.ConnectionClosed(cause));
    }
  }

  // --- Helpers --------------------------------------------------------------------------------

  /**
   * Publishes a client event to subscribers, dropping it if no subscriber can accept it.
   *
   * @param event the event to publish.
   */
  private void publish(ClientEvent event) {
    if (publisher.isClosed()) {
      return;
    }
    publisher.offer(event, (subscriber, dropped) -> false);
  }

  /**
   * Blocks until a stage completes, translating its failure to a typed exception.
   *
   * @param stage the stage to wait on.
   * @param <T> the result type.
   * @return the stage's result.
   * @throws Iec60870Exception if the stage completed exceptionally with a protocol failure.
   */
  private static <T> T await(CompletionStage<T> stage) {
    try {
      return stage.toCompletableFuture().join();
    } catch (CompletionException e) {
      throw unwrap(e.getCause());
    } catch (CancellationException e) {
      throw new ConnectionClosedException("request cancelled", e);
    }
  }

  /**
   * Translates a stage failure to an {@link Iec60870Exception}.
   *
   * @param cause the failure cause.
   * @return the typed exception to throw.
   */
  private static RuntimeException unwrap(@Nullable Throwable cause) {
    if (cause instanceof Iec60870Exception iec) {
      return iec;
    }
    if (cause instanceof RuntimeException runtime) {
      return runtime;
    }
    return new Iec60870Exception(
        cause != null ? cause.getMessage() : "request failed",
        cause != null ? cause : new IllegalStateException("unknown failure"));
  }

  /**
   * Reports whether a cause of transmission marks an ASDU as monitored process data.
   *
   * @param cause the cause of transmission.
   * @return {@code true} for spontaneous, periodic, request, or interrogation-response causes.
   */
  private static boolean isMonitorCause(Cause cause) {
    return switch (cause) {
      case PERIODIC,
          BACKGROUND_SCAN,
          SPONTANEOUS,
          REQUEST,
          RETURN_REMOTE,
          RETURN_LOCAL,
          INTERROGATED_BY_STATION,
          INTERROGATED_BY_GROUP_1,
          INTERROGATED_BY_GROUP_2,
          INTERROGATED_BY_GROUP_3,
          INTERROGATED_BY_GROUP_4,
          INTERROGATED_BY_GROUP_5,
          INTERROGATED_BY_GROUP_6,
          INTERROGATED_BY_GROUP_7,
          INTERROGATED_BY_GROUP_8,
          INTERROGATED_BY_GROUP_9,
          INTERROGATED_BY_GROUP_10,
          INTERROGATED_BY_GROUP_11,
          INTERROGATED_BY_GROUP_12,
          INTERROGATED_BY_GROUP_13,
          INTERROGATED_BY_GROUP_14,
          INTERROGATED_BY_GROUP_15,
          INTERROGATED_BY_GROUP_16,
          REQUESTED_BY_GENERAL_COUNTER,
          REQUESTED_BY_GROUP_1_COUNTER,
          REQUESTED_BY_GROUP_2_COUNTER,
          REQUESTED_BY_GROUP_3_COUNTER,
          REQUESTED_BY_GROUP_4_COUNTER ->
          true;
      default -> false;
    };
  }

  /**
   * Returns the command type family of an ASDU type, used to correlate command confirmations.
   *
   * @param type the ASDU type.
   * @return the canonical untimed command type for the family, or the type itself if it is not a
   *     command.
   */
  private static AsduType commandFamily(AsduType type) {
    return switch (type) {
      case C_SC_NA_1, C_SC_TA_1 -> AsduType.C_SC_NA_1;
      case C_DC_NA_1, C_DC_TA_1 -> AsduType.C_DC_NA_1;
      case C_RC_NA_1, C_RC_TA_1 -> AsduType.C_RC_NA_1;
      case C_SE_NA_1, C_SE_TA_1 -> AsduType.C_SE_NA_1;
      case C_SE_NB_1, C_SE_TB_1 -> AsduType.C_SE_NB_1;
      case C_SE_NC_1, C_SE_TC_1 -> AsduType.C_SE_NC_1;
      case C_BO_NA_1, C_BO_TA_1 -> AsduType.C_BO_NA_1;
      default -> type;
    };
  }

  // --- Pending request hierarchy --------------------------------------------------------------

  /**
   * A request awaiting one or more correlated responses from the controlled station.
   *
   * <p>{@link #accept(Asdu)} classifies an incoming ASDU and, when it belongs to this request,
   * updates the request's internal state and returns whether the request is now done. The driver in
   * {@link #correlate(Asdu)} owns all registry mutation and future completion, so {@code accept}
   * must not touch the registry. {@link #deliver()} and {@link #deliverFailure()} complete the
   * request's future and run off the client lock.
   */
  private abstract static class PendingRequest {

    /** The result of offering an ASDU to a pending request. */
    enum Outcome {
      /** The ASDU does not belong to this request. */
      IGNORED,
      /** The ASDU belongs to this request, which still awaits more. */
      ACCEPTED,
      /** The ASDU completes this request successfully. */
      COMPLETED,
      /** The ASDU completes this request with a failure (for example a negative confirmation). */
      FAILED
    }

    private @Nullable ScheduledFuture<?> timeoutHandle;

    /**
     * Reports whether this request and {@code other} would compete for the same responses.
     *
     * <p>IEC 60870-5-104 carries no per-request identifier, so two requests whose responses overlap
     * cannot both be in flight without their responses being misattributed; the client refuses to
     * register a second conflicting request (see {@link #register(PendingRequest)}). Requests of
     * different kinds never conflict; requests of the same kind conflict when they correlate on the
     * same target — the same common address and object.
     *
     * @param other another pending request.
     * @return {@code true} if {@code other} would compete with this request for responses.
     */
    abstract boolean conflictsWith(PendingRequest other);

    /**
     * Offers an ASDU to this request and reports the outcome.
     *
     * @param asdu the received ASDU.
     * @return the outcome of offering {@code asdu} to this request.
     */
    abstract Outcome accept(Asdu asdu);

    /** Completes this request's future with its collected result. Invoked off the client lock. */
    abstract void deliver();

    /** Completes this request's future exceptionally with its recorded failure. */
    abstract void deliverFailure();

    /**
     * Completes this request's future exceptionally with an externally-supplied cause (timeout,
     * disconnect, or send failure).
     *
     * @param cause the failure to deliver.
     */
    abstract void fail(Throwable cause);

    void setTimeoutHandle(ScheduledFuture<?> handle) {
      this.timeoutHandle = handle;
    }

    void cancelTimeout() {
      ScheduledFuture<?> handle = timeoutHandle;
      if (handle != null) {
        handle.cancel(false);
      }
    }
  }

  /** A request awaiting a single activation confirmation, matched by command family / CA / IOA. */
  private static final class PendingConfirmation extends PendingRequest {

    private final AsduType family;
    private final CommonAddress station;
    private final InformationObjectAddress objectAddress;
    private final boolean requireAddressedObject;
    private final CompletableFuture<Asdu> future;
    private @Nullable Asdu confirmation;

    PendingConfirmation(
        AsduType family,
        CommonAddress station,
        InformationObjectAddress objectAddress,
        boolean requireAddressedObject,
        CompletableFuture<Asdu> future) {
      this.requireAddressedObject = requireAddressedObject;
      this.family = commandFamily(family);
      this.station = station;
      this.objectAddress = objectAddress;
      this.future = future;
    }

    @Override
    boolean conflictsWith(PendingRequest other) {
      // Two confirmations conflict only when they address the same station and object within the
      // same command family; their responses would otherwise be indistinguishable.
      return other instanceof PendingConfirmation that
          && family == that.family
          && station.equals(that.station)
          && objectAddress.equals(that.objectAddress);
    }

    @Override
    Outcome accept(Asdu asdu) {
      if (commandFamily(asdu.type()) != family) {
        return Outcome.IGNORED;
      }
      if (!asdu.commonAddress().equals(station)) {
        return Outcome.IGNORED;
      }
      // A positive confirmation carries (de)activation-confirmation cause. A negative confirmation
      // (P/N=1) may instead carry an error cause (for example UNKNOWN_INFORMATION_OBJECT_ADDRESS),
      // which a handler-driven rejection on the controlled station emits; correlate it too so the
      // caller observes a non-positive CommandResult rather than a timeout.
      boolean confirmationCause =
          asdu.cause() == Cause.ACTIVATION_CONFIRMATION
              || asdu.cause() == Cause.DEACTIVATION_CONFIRMATION;
      if (!confirmationCause && !asdu.negative()) {
        return Outcome.IGNORED;
      }
      // A command activation confirmation (positive or negative) mirrors the command back, carrying
      // its single information object and that object's IOA. For commands (requireAddressedObject),
      // require the addressed object to be present and matching: an empty confirmation is unbound
      // and
      // must not correlate, or a same-family, same-station peer could complete the wrong pending
      // command or advance a select-before-operate sequence without ever naming the addressed
      // point.
      // Clock synchronization (a single station-wide operation at IOA 0, with no select/execute
      // phases) does not require the echoed object, so a header-only confirmation still completes
      // it;
      // when it does carry an object the IOA must still match.
      boolean ioaMismatch =
          !asdu.objects().isEmpty() && !asdu.objects().get(0).address().equals(objectAddress);
      if (ioaMismatch || (requireAddressedObject && asdu.objects().isEmpty())) {
        return Outcome.IGNORED;
      }
      this.confirmation = asdu;
      return Outcome.COMPLETED;
    }

    @Override
    void deliver() {
      future.complete(Objects.requireNonNull(confirmation));
    }

    @Override
    void deliverFailure() {
      // PendingConfirmation never records a failure outcome; a negative confirmation is still a
      // successful correlation and is surfaced via the (negative) confirming ASDU.
      future.completeExceptionally(new ConnectionClosedException("confirmation lost"));
    }

    @Override
    void fail(Throwable cause) {
      future.completeExceptionally(cause);
    }
  }

  /** A request awaiting interrogation confirmation then monitor objects until ACT_TERM. */
  private static final class PendingInterrogation extends PendingRequest {

    private final CommonAddress station;
    private final CompletableFuture<InterrogationResult> future;
    private final int maxObjects;
    private final List<InformationObject> collected = new ArrayList<>();
    private boolean confirmed;
    private boolean overflowed;
    private @Nullable Asdu negativeConfirmation;

    PendingInterrogation(
        CommonAddress station, CompletableFuture<InterrogationResult> future, int maxObjects) {
      this.station = station;
      this.future = future;
      this.maxObjects = maxObjects;
    }

    @Override
    boolean conflictsWith(PendingRequest other) {
      // An interrogation correlates on the common address alone (accept() ignores the qualifier of
      // interrogation), so two interrogations conflict when they target the same station.
      return other instanceof PendingInterrogation that && station.equals(that.station);
    }

    @Override
    Outcome accept(Asdu asdu) {
      if (!asdu.commonAddress().equals(station)) {
        return Outcome.IGNORED;
      }
      if (asdu.type() == AsduType.C_IC_NA_1) {
        // A negative confirmation may carry an error cause (for example UNKNOWN_COMMON_ADDRESS)
        // rather than ACTIVATION_CONFIRMATION when the controlled station declines the request;
        // treat any negative C_IC_NA_1 confirmation as the rejection.
        if (asdu.negative()) {
          negativeConfirmation = asdu;
          return Outcome.FAILED;
        }
        return switch (asdu.cause()) {
          case ACTIVATION_CONFIRMATION -> {
            confirmed = true;
            yield Outcome.ACCEPTED;
          }
          case ACTIVATION_TERMINATION -> Outcome.COMPLETED;
          default -> Outcome.IGNORED;
        };
      }
      if (confirmed && isInterrogationResponse(asdu.cause())) {
        // Bound the accumulated response: a peer that confirms the interrogation but never sends
        // ACT_TERM could otherwise stream objects into this list without limit (heap exhaustion).
        // The request timeout bounds the time window; this bounds the volume within it.
        if (collected.size() + asdu.objects().size() > maxObjects) {
          overflowed = true;
          return Outcome.FAILED;
        }
        collected.addAll(asdu.objects());
        return Outcome.ACCEPTED;
      }
      return Outcome.IGNORED;
    }

    @Override
    void deliver() {
      future.complete(new InterrogationResult(station, collected, true));
    }

    @Override
    void deliverFailure() {
      if (overflowed) {
        future.completeExceptionally(
            new Iec60870Exception(
                "interrogation response exceeded the maximum of "
                    + maxObjects
                    + " objects without an activation termination"));
        return;
      }
      Asdu asdu = Objects.requireNonNull(negativeConfirmation);
      future.completeExceptionally(new NegativeConfirmationException(asdu.cause(), asdu));
    }

    @Override
    void fail(Throwable cause) {
      future.completeExceptionally(cause);
    }

    private static boolean isInterrogationResponse(Cause cause) {
      return switch (cause) {
        case INTERROGATED_BY_STATION,
            INTERROGATED_BY_GROUP_1,
            INTERROGATED_BY_GROUP_2,
            INTERROGATED_BY_GROUP_3,
            INTERROGATED_BY_GROUP_4,
            INTERROGATED_BY_GROUP_5,
            INTERROGATED_BY_GROUP_6,
            INTERROGATED_BY_GROUP_7,
            INTERROGATED_BY_GROUP_8,
            INTERROGATED_BY_GROUP_9,
            INTERROGATED_BY_GROUP_10,
            INTERROGATED_BY_GROUP_11,
            INTERROGATED_BY_GROUP_12,
            INTERROGATED_BY_GROUP_13,
            INTERROGATED_BY_GROUP_14,
            INTERROGATED_BY_GROUP_15,
            INTERROGATED_BY_GROUP_16 ->
            true;
        default -> false;
      };
    }
  }

  /** A request awaiting the response objects to a read command (C_RD_NA_1), matched by CA / IOA. */
  private static final class PendingRead extends PendingRequest {

    private final PointAddress point;
    private final CompletableFuture<List<InformationObject>> future;
    private @Nullable List<InformationObject> objects;
    private @Nullable Asdu negativeConfirmation;

    PendingRead(PointAddress point, CompletableFuture<List<InformationObject>> future) {
      this.point = point;
      this.future = future;
    }

    @Override
    boolean conflictsWith(PendingRequest other) {
      return other instanceof PendingRead that && point.equals(that.point);
    }

    @Override
    Outcome accept(Asdu asdu) {
      if (!asdu.commonAddress().equals(point.commonAddress())) {
        return Outcome.IGNORED;
      }
      // A negative read confirmation (C_RD_NA_1 with P/N=1) ends the request.
      if (asdu.type() == AsduType.C_RD_NA_1) {
        if (asdu.negative()) {
          negativeConfirmation = asdu;
          return Outcome.FAILED;
        }
        return Outcome.IGNORED;
      }
      // A read response carries cause REQUEST; a spontaneous/periodic update on the same CA+IOA
      // must not complete the read early. Gate on the cause before matching the addressed object.
      if (asdu.cause() != Cause.REQUEST) {
        return Outcome.IGNORED;
      }
      // Otherwise correlate by the addressed object. Deliver only the matching object(s) rather
      // than the whole ASDU, so a response carrying several objects does not hand unrelated points
      // back to this read. Test for a match first so an unrelated response (the common case on this
      // hot path) allocates nothing.
      boolean matches =
          asdu.objects().stream().anyMatch(o -> o.address().equals(point.objectAddress()));
      if (matches) {
        this.objects =
            asdu.objects().stream().filter(o -> o.address().equals(point.objectAddress())).toList();
        return Outcome.COMPLETED;
      }
      return Outcome.IGNORED;
    }

    @Override
    void deliver() {
      future.complete(Objects.requireNonNull(objects));
    }

    @Override
    void deliverFailure() {
      Asdu asdu = Objects.requireNonNull(negativeConfirmation);
      future.completeExceptionally(new NegativeConfirmationException(asdu.cause(), asdu));
    }

    @Override
    void fail(Throwable cause) {
      future.completeExceptionally(cause);
    }
  }

  // --- Session callbacks ----------------------------------------------------------------------

  /**
   * Bridges {@link Session} events to the client. Every inbound ASDU, data-transfer transition, and
   * close — including a transport connection loss that the session assembly routes here — arrives
   * through this single sink.
   */
  private final class SessionEvents implements Session.Events {

    @Override
    public void onAsdu(Asdu asdu) {
      onAsduReceived(asdu);
    }

    @Override
    public void onDataTransferStateChanged(boolean started) {
      publish(
          started ? new ClientEvent.DataTransferStarted() : new ClientEvent.DataTransferStopped());
    }

    @Override
    public void onClosed(@Nullable Throwable cause) {
      failAllPending(
          cause != null
              ? new ConnectionClosedException("session closed", cause)
              : new ConnectionClosedException("session closed"));
      publishConnectionClosed(cause);
      // The session self-closed on a protocol error or timeout: tear the transport down so a
      // persistent transport stops reconnecting, matching the protocol layer giving up.
      try {
        transport.disconnect();
      } catch (RuntimeException e) {
        LOGGER.debug("transport disconnect failed after session close", e);
      }
    }

    @Override
    public void onConnectionLost(@Nullable Throwable cause) {
      // Transport-level loss (peer drop, send failure, I/O error): fail pending work and publish
      // the closed event, but do NOT call transport.disconnect(). Disconnecting would fire
      // Event.Disconnect on the persistent ChannelFsm and stop its automatic reconnection; the old
      // client deliberately left the transport free to reconnect after an unsolicited drop.
      failAllPending(
          cause != null
              ? new ConnectionClosedException("connection lost", cause)
              : new ConnectionClosedException("connection lost"));
      publishConnectionClosed(cause);
    }
  }

  // --- Command service implementation ---------------------------------------------------------

  /** The command service backing {@link #commands()}. */
  private final class DefaultCommandService implements CommandService {

    @Override
    public CommandResult send(Command command, CommandMode mode) {
      return await(sendAsync(command, mode));
    }

    @Override
    public CompletionStage<CommandResult> sendAsync(Command command, CommandMode mode) {
      Objects.requireNonNull(command, "command");
      Objects.requireNonNull(mode, "mode");

      if (mode == CommandMode.SELECT_BEFORE_OPERATE) {
        return sendActivation(command, true)
            .thenCompose(
                selectAck -> {
                  if (selectAck.negative()) {
                    return CompletableFuture.completedFuture(toResult(command, selectAck));
                  }
                  return sendActivation(command, false).thenApply(ack -> toResult(command, ack));
                });
      }
      return sendActivation(command, false).thenApply(ack -> toResult(command, ack));
    }

    /**
     * Builds and sends one command activation, awaiting its confirmation.
     *
     * @param command the command to send.
     * @param select whether this is the select phase (S/E = 1) or the execute phase (S/E = 0).
     * @return a stage that completes with the confirming ASDU.
     */
    private CompletionStage<Asdu> sendActivation(Command command, boolean select) {
      InformationObject object;
      AsduType type;
      try {
        object = CommandAsdus.toObject(command, select);
        type = CommandAsdus.typeOf(command);
      } catch (RuntimeException e) {
        // A malformed request (e.g. select-before-operate on a bit-string command, which C_BO
        // cannot express) is surfaced through the returned stage rather than thrown on the caller's
        // thread, so every command failure reaches a thenCompose/exceptionally handler uniformly.
        return CompletableFuture.failedFuture(e);
      }
      PointAddress target = command.target();

      Asdu asdu =
          new Asdu(
              type,
              false,
              Cause.ACTIVATION,
              false,
              false,
              config.originatorAddress(),
              target.commonAddress(),
              List.of(object));

      CompletableFuture<Asdu> confirmation = new CompletableFuture<>();
      PendingConfirmation request =
          new PendingConfirmation(
              type, target.commonAddress(), target.objectAddress(), true, confirmation);
      if (!register(request)) {
        confirmation.completeExceptionally(alreadyInFlight("command for " + target));
        return confirmation;
      }
      armAndSend(request, asdu, config.commandTimeout());
      return confirmation;
    }

    /**
     * Builds the command result from a confirming ASDU.
     *
     * @param command the originating command.
     * @param ack the confirming ASDU.
     * @return the command result.
     */
    private CommandResult toResult(Command command, Asdu ack) {
      return new CommandResult(command.target(), !ack.negative(), ack.cause(), Optional.of(ack));
    }
  }

  /** Names the internal scheduler thread for diagnostics. */
  private static final class SchedulerThreadFactory implements ThreadFactory {

    private static final AtomicInteger POOL = new AtomicInteger();

    private final int poolId = POOL.getAndIncrement();
    private final AtomicInteger threadId = new AtomicInteger();

    @Override
    public Thread newThread(Runnable runnable) {
      Thread thread =
          new Thread(
              runnable, "iec60870-client-scheduler-" + poolId + "-" + threadId.getAndIncrement());
      thread.setDaemon(true);
      return thread;
    }
  }
}
