package com.digitalpetri.iec60870.client;

import com.digitalpetri.iec60870.ConnectionClosedException;
import com.digitalpetri.iec60870.EventBacklogExceededException;
import com.digitalpetri.iec60870.Iec60870Exception;
import com.digitalpetri.iec60870.NegativeConfirmationException;
import com.digitalpetri.iec60870.ProtocolTimeoutException;
import com.digitalpetri.iec60870.RequestInProgressException;
import com.digitalpetri.iec60870.address.CommonAddress;
import com.digitalpetri.iec60870.address.InformationObjectAddress;
import com.digitalpetri.iec60870.address.OriginatorAddress;
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
import com.digitalpetri.iec60870.diagnostics.ConnectionDiagnostics;
import com.digitalpetri.iec60870.diagnostics.Direction;
import com.digitalpetri.iec60870.diagnostics.LoggingContext;
import com.digitalpetri.iec60870.point.MonitorMapping;
import com.digitalpetri.iec60870.point.PointValueExtraction;
import com.digitalpetri.iec60870.session.Session;
import com.digitalpetri.iec60870.transport.ClientTransport;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
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
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
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
 * <p>The client also owns a lossless publisher for {@link ClientEvent} delivery on the configured
 * callback executor and a request-correlation layer that matches activation confirmations and
 * interrogation responses to the blocking and asynchronous calls that issued them. When the slowest
 * subscriber falls {@link ClientConfig#eventBacklogLimit()} events behind, the client pauses
 * inbound data with {@link Session#setInboundPaused(boolean)} until the subscriber catches up.
 *
 * <p>The client owns the connection lifecycle. The transport dials once per request; after a lost
 * connection, a protocol self-close, or a failed attempt, the client schedules the next attempt on
 * its scheduler according to the configured {@link ReconnectPolicy}, and every attempt resets the
 * session and repeats the initialization of {@link #connect()}.
 *
 * <p>The client reports closed connections, scheduled reconnects, inbound pauses, and discarded
 * events or inbound ASDUs to {@link ClientConfig#diagnostics()}, and sets {@link
 * ClientConfig#loggingContext()} around its log lines. Ignored U-frames reported by the session
 * through {@link Session.Events#onProtocolWarning(String)} are published as {@link
 * ClientEvent.ProtocolWarning} while the event backlog has room for data.
 *
 * <p>The instance is thread-safe. Construct it with a transport, a {@link ClientConfig}, and a
 * session factory that builds the {@link Session} wired to the facade's {@link Session.Events}.
 */
public final class DefaultIec60870Client implements Iec60870Client {

  private static final Logger LOGGER = LoggerFactory.getLogger(DefaultIec60870Client.class);

  /** Conventional information object address for station-level commands (interrogation, clock). */
  private static final InformationObjectAddress ZERO_ADDRESS = InformationObjectAddress.of(0);

  private final ClientTransport transport;
  private final ClientConfig config;
  private final ConnectionDiagnostics diagnostics;
  private final LoggingContext loggingContext;

  /**
   * Cached from {@link ClientConfig#maxInterrogationResponseObjects()}; {@code 0} means no bound.
   */
  private final int maxInterrogationResponseObjects;

  private final ScheduledExecutorService scheduler;
  private final boolean ownsScheduler;

  private final Executor callbackExecutor;
  private final EventStream<ClientEvent> publisher;
  private final SessionEvents sessionEvents = new SessionEvents();
  private final Session session;
  private final CommandService commandService;

  // Serialize command submission with session reset. Enqueueing never waits for a running
  // operation: an inline callback may hold the session lock that the current sender needs.
  private final Deque<Runnable> sessionOperations = new ArrayDeque<>();
  private boolean sessionOperationRunning;
  private final ReentrantLock lock = new ReentrantLock();
  private final List<PendingRequest> pending = new ArrayList<>();

  private final ReconnectPolicy reconnectPolicy;

  /** The state of the managed connection; guarded by lock. */
  private ConnectionState state = ConnectionState.DISCONNECTED;

  /** The connection being established or established, if any; guarded by lock. */
  private @Nullable Connection connection;

  /**
   * The most recently started connection. The next one dials only after its teardown finished;
   * guarded by lock.
   */
  private Connection lastConnection = Connection.finished();

  /** The scheduled reconnect, while waiting in RECONNECT_WAIT; guarded by lock. */
  private @Nullable ReconnectWait reconnectWait;

  /** Reconnects scheduled since the last established connection; guarded by lock. */
  private int reconnectAttempts;

  /** Whether DataTransferStarted was published without a matching DataTransferStopped yet. */
  private final AtomicBoolean dataTransferStarted = new AtomicBoolean();

  /**
   * Set when a subscriber's backlog reached the hard capacity, until the next connection; while
   * set, inbound data is discarded and the connection is being closed.
   */
  private final AtomicBoolean backlogExceeded = new AtomicBoolean();

  /**
   * Creates a client over the given transport and configuration, building its {@link Session}
   * through the supplied factory.
   *
   * <p>The factory receives the facade's {@link Session.Events} sink and returns a session wired to
   * deliver inbound ASDUs, data-transfer transitions, and self-initiated closes to it, and is given
   * the facade's owned scheduler so the protocol session and the facade share one timer thread. The
   * protocol assembly (the protocol-specific session and its wire framing) lives in the factory,
   * not in this class. The client creates an internal single-thread scheduler for request timeouts
   * and reconnect delays. Call {@link #connect()} to establish the connection.
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
   * @param scheduler the scheduler for request timeouts and reconnect delays, or {@code null} to
   *     create an internal one.
   * @throws NullPointerException if {@code transport}, {@code config}, or {@code sessionFactory} is
   *     null.
   */
  public DefaultIec60870Client(
      ClientTransport transport,
      ClientConfig config,
      BiFunction<Session.Events, ScheduledExecutorService, Session> sessionFactory,
      @Nullable ScheduledExecutorService scheduler) {

    this.transport = Objects.requireNonNull(transport, "transport");
    this.config = Objects.requireNonNull(config, "config");
    Objects.requireNonNull(sessionFactory, "sessionFactory");
    this.diagnostics = ConnectionDiagnostics.of(config.diagnostics());
    this.loggingContext = LoggingContext.of(config.loggingContext());
    this.maxInterrogationResponseObjects = config.maxInterrogationResponseObjects();
    this.reconnectPolicy = config.reconnectPolicy();

    if (scheduler != null) {
      this.scheduler = scheduler;
      this.ownsScheduler = false;
    } else {
      this.scheduler = Executors.newSingleThreadScheduledExecutor(new SchedulerThreadFactory());
      this.ownsScheduler = true;
    }
    this.callbackExecutor = config.callbackExecutor();
    this.publisher =
        new EventStream<>(
            callbackExecutor,
            config.eventBacklogLimit(),
            this::setInboundPaused,
            event -> diagnostics.onEventDropped(event.getClass()),
            loggingContext);

    // The facade owns the Session.Events sink; the factory wires the session to it. The factory
    // routes inbound frames to the session and transport-level connection loss to
    // Session.Events.onClosed, so the facade learns of every close through that single sink.
    this.session =
        Objects.requireNonNull(sessionFactory.apply(sessionEvents, this.scheduler), "session");

    this.commandService = new DefaultCommandService();
  }

  // --- Iec60870Client: lifecycle ----------------------------------------------------------------

  @Override
  public void connect() {
    await(connectAsync());
  }

  @Override
  public CompletionStage<Void> connectAsync() {
    Connection started;
    lock.lock();
    try {
      if (state == ConnectionState.CLOSED) {
        return CompletableFuture.failedFuture(new ConnectionClosedException("client closed"));
      }
      Connection current = connection;
      if (current != null) {
        return current.established.copy();
      }
      if (!publisher.hasRoomForData()) {
        return CompletableFuture.failedFuture(backlogExceeded("connect"));
      }
      cancelReconnectWait();
      started = startConnection();
    } finally {
      lock.unlock();
    }

    dial(started);
    // Each caller can cancel its own wait without cancelling initialization for the other callers.
    return started.established.copy();
  }

  /**
   * Makes {@code connection} the current connection in state CONNECTING. Called with lock held.
   *
   * @return the new connection.
   */
  private Connection startConnection() {
    var started = new Connection(lastConnection.closed);
    connection = started;
    lastConnection = started;
    state = ConnectionState.CONNECTING;
    return started;
  }

  /**
   * Dials the transport for {@code started} once its predecessor has been torn down, then
   * initializes the session on the new connection.
   *
   * <p>Waiting keeps late transport callbacks of an older connection, and a teardown queued by an
   * earlier {@link #disconnectAsync()}, away from the new connection.
   *
   * @param started the connection to establish.
   */
  private void dial(Connection started) {
    // Admission and the transport call run on the operation queue, like every teardown, so a
    // disconnect either runs first and the dial is skipped, or runs after and aborts the attempt.
    started.predecessorClosed.whenComplete(
        (ignored, unused) -> executeSessionOperation(() -> dialNow(started)));
  }

  private void dialNow(Connection started) {
    if (!isCurrent(started)) {
      return;
    }
    CompletionStage<Void> transportConnected;
    try {
      transportConnected = transport.connect();
    } catch (RuntimeException e) {
      transportConnected = CompletableFuture.failedFuture(e);
    }
    transportConnected
        .thenCompose(
            connected -> {
              // The transport reported the connection before completing; a teardown that ran in
              // between found no transport connection, so the close is reported here instead.
              if (started.markTransportConnected()) {
                diagnostics.onConnectionClosed(started.teardownCause());
              }
              return CompletableFuture.supplyAsync(
                      () -> initializeConnection(started), this::executeSessionOperation)
                  .thenCompose(stage -> stage);
            })
        .whenComplete(
            (connected, error) -> {
              if (error == null) {
                onEstablished(started);
              } else {
                endConnection(started, connectFailure(error));
              }
            });
  }

  private CompletionStage<Void> initializeConnection(Connection started) {
    if (!isCurrent(started)) {
      return CompletableFuture.failedFuture(new ConnectionClosedException("connection lost"));
    }
    backlogExceeded.set(false);
    publisher.resetPause();
    session.onConnected();
    if (!isCurrent(started)) {
      // Loss/close may invalidate the attempt while onConnected waits for the session lock. The
      // serial operation queue prevents a newer attempt from initializing before this cleanup.
      session.close();
      return CompletableFuture.failedFuture(new ConnectionClosedException("connection lost"));
    }
    if (!transport.isConnected()) {
      // The connection was lost before the loss could be attributed to it; the attempt fails and
      // follows the reconnect policy.
      session.close();
      return CompletableFuture.failedFuture(
          new ConnectionClosedException("connection lost before the session was armed"));
    }
    // The connection's teardown runs on the same operation queue, after this, so it sees the flag
    // and publishes the matching ConnectionClosed.
    started.opened = true;
    publish(new ClientEvent.ConnectionOpened());
    if (!isCurrent(started)) {
      return CompletableFuture.failedFuture(new ConnectionClosedException("connection lost"));
    }
    return config.startDataTransferOnConnect()
        ? session.startDataTransfer()
        : CompletableFuture.completedFuture(null);
  }

  private boolean isCurrent(Connection candidate) {
    lock.lock();
    try {
      return connection == candidate;
    } finally {
      lock.unlock();
    }
  }

  private void onEstablished(Connection established) {
    lock.lock();
    try {
      if (connection != established) {
        // Whoever ended this connection also failed its stage.
        return;
      }
      state = ConnectionState.CONNECTED;
      reconnectAttempts = 0;
    } finally {
      lock.unlock();
    }
    // CompletableFuture<Void> can only be completed with null; the @NotNull on complete() does not
    // apply to a Void result.
    //noinspection DataFlowIssue
    established.established.complete(null);
  }

  /**
   * Ends the current connection after a loss reported by the session or the transport.
   *
   * <p>A loss reported before the current attempt's transport connected belongs to an older
   * connection and is ignored.
   *
   * @param cause the reason for the loss, or {@code null} if the peer closed the connection.
   */
  private void onConnectionLost(@Nullable Throwable cause) {
    endConnection(
        null,
        cause != null ? cause : new ConnectionClosedException("connection closed by the peer"));
  }

  /**
   * Ends a connection that was lost or never established, then reconnects per the policy.
   *
   * <p>Does nothing if the connection already ended. Otherwise detaches its requests and moves to
   * RECONNECT_WAIT or DISCONNECTED in one critical section, so a replacement connection can never
   * see the old requests, and queues the teardown.
   *
   * @param expected the connection that ended, or {@code null} for the current connection once its
   *     transport has connected.
   * @param cause why it ended.
   */
  private void endConnection(@Nullable Connection expected, Throwable cause) {
    Connection ended;
    List<PendingRequest> requests;
    ReconnectWait wait = null;
    lock.lock();
    try {
      ended = connection;
      if (ended == null || (expected == null ? !ended.transportConnected : ended != expected)) {
        return;
      }
      connection = null;
      requests = detachRequests();
      if (reconnectPolicy.enabled()) {
        reconnectAttempts++;
        wait = new ReconnectWait(reconnectAttempts, reconnectPolicy.delay(reconnectAttempts));
        reconnectWait = wait;
        state = ConnectionState.RECONNECT_WAIT;
      } else {
        state = ConnectionState.DISCONNECTED;
      }
    } finally {
      lock.unlock();
    }

    // A caller may retry from these completions; the replacement dials only after the teardown.
    failPending(requests, new ConnectionClosedException("connection lost", cause));
    ended.established.completeExceptionally(
        cause instanceof Iec60870Exception iec
            ? iec
            : new ConnectionClosedException("connection attempt failed: " + cause, cause));

    ReconnectWait scheduled = wait;
    executeSessionOperation(
        () -> {
          publishClosed(ended, cause);
          if (scheduled != null) {
            scheduleReconnect(scheduled, cause);
          }
          closeTransportConnection(ended);
        });
  }

  /**
   * Closes the session and, if {@code ended} was opened, publishes its closing lifecycle events.
   *
   * <p>Runs on the session operation queue, so it is ordered after the initialization that
   * published {@link ClientEvent.ConnectionOpened} and before any later connection's.
   *
   * @param ended the connection being torn down, or {@code null} if none was open.
   * @param cause the cause for {@link ClientEvent.ConnectionClosed}, or {@code null} when the
   *     application closed the connection.
   */
  private void publishClosed(@Nullable Connection ended, @Nullable Throwable cause) {
    session.close();
    if (ended != null && ended.markTornDown(cause)) {
      diagnostics.onConnectionClosed(cause);
    }
    // The session is closed, so no data-transfer callback can race these events.
    if (ended != null && ended.opened) {
      if (dataTransferStarted.getAndSet(false)) {
        publish(new ClientEvent.DataTransferStopped());
      }
      publish(new ClientEvent.ConnectionClosed(cause));
    }
  }

  /** Closes the transport connection and marks the teardown of {@code ended} finished. */
  private void closeTransportConnection(Connection ended) {
    CompletionStage<Void> disconnected;
    try {
      disconnected = transport.disconnect();
    } catch (RuntimeException e) {
      loggingContext.debug(LOGGER, "{}: transport disconnect failed", remote(), e);
      disconnected = CompletableFuture.completedFuture(null);
    }
    disconnected.whenComplete((ignored, error) -> ended.markClosed());
  }

  /**
   * Publishes {@link ClientEvent.ReconnectScheduled} and arms the reconnect timer, unless the wait
   * was cancelled meanwhile.
   */
  private void scheduleReconnect(ReconnectWait wait, Throwable cause) {
    lock.lock();
    try {
      if (reconnectWait != wait) {
        return;
      }
    } finally {
      lock.unlock();
    }
    loggingContext.debug(
        LOGGER,
        "{}: reconnect attempt {} in {}: {}",
        remote(),
        wait.attempt,
        wait.delay,
        cause.toString());
    diagnostics.onReconnectScheduled(wait.attempt, wait.delay, cause);
    publish(new ClientEvent.ReconnectScheduled(wait.attempt, wait.delay, cause));
    wait.arm(wait.delay);
  }

  /** Starts the scheduled attempt when its timer fires, if it is still wanted. */
  private void reconnect(ReconnectWait wait) {
    Connection started;
    lock.lock();
    try {
      if (reconnectWait != wait) {
        return;
      }
      if (!publisher.hasRoomForData()) {
        // A subscriber is too far behind to take a new connection's events. Wait again for the
        // maximum delay, without publishing, so the lifecycle reserve is not used up while it
        // stays stalled.
        loggingContext.debug(
            LOGGER,
            "{}: reconnect deferred: an event subscriber is {} events behind",
            remote(),
            publisher.maxBacklog());
        wait.arm(reconnectPolicy.maxDelay());
        return;
      }
      reconnectWait = null;
      started = startConnection();
    } finally {
      lock.unlock();
    }
    dial(started);
  }

  /** Cancels a scheduled reconnect. Called with lock held. */
  private void cancelReconnectWait() {
    ReconnectWait wait = reconnectWait;
    if (wait != null) {
      reconnectWait = null;
      wait.cancel();
    }
  }

  /**
   * Removes every pending request, so they cannot be completed by a later connection. Called with
   * lock held.
   */
  private List<PendingRequest> detachRequests() {
    List<PendingRequest> requests = new ArrayList<>(pending);
    pending.clear();
    return requests;
  }

  /**
   * Translates the failure of a connection attempt to the exception its callers see.
   *
   * @param error the failure, possibly wrapped by a dependent stage.
   * @return the failure if it is an {@link Iec60870Exception}, otherwise a {@link
   *     ConnectionClosedException} carrying it.
   */
  private static Iec60870Exception connectFailure(Throwable error) {
    Throwable cause =
        error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
    if (cause instanceof Iec60870Exception iec) {
      return iec;
    }
    return new ConnectionClosedException("connection attempt failed: " + cause, cause);
  }

  @Override
  public void disconnect() {
    await(disconnectAsync());
  }

  @Override
  public CompletionStage<Void> disconnectAsync() {
    Connection ended;
    Connection barrier;
    List<PendingRequest> requests;
    lock.lock();
    try {
      if (state == ConnectionState.CLOSED) {
        return CompletableFuture.completedFuture(null);
      }
      // A connection started after this call dials only once the teardown below, and every
      // teardown before it, has finished.
      barrier = new Connection(lastConnection.closed);
      lastConnection = barrier;
      state = ConnectionState.DISCONNECTED;
      cancelReconnectWait();
      reconnectAttempts = 0;
      ended = connection;
      connection = null;
      requests = detachRequests();
    } finally {
      lock.unlock();
    }

    var failure = new ConnectionClosedException("client disconnected");
    failPending(requests, failure);
    if (ended != null) {
      ended.established.completeExceptionally(failure);
    }

    var done = new CompletableFuture<Void>();
    executeSessionOperation(
        () -> {
          publishClosed(ended, null);
          CompletionStage<Void> disconnected;
          try {
            disconnected = transport.disconnect();
          } catch (RuntimeException e) {
            loggingContext.debug(LOGGER, "{}: transport disconnect failed", remote(), e);
            disconnected = CompletableFuture.completedFuture(null);
          }
          disconnected.whenComplete(
              (ignored, error) -> {
                if (ended != null) {
                  ended.markClosed();
                }
                barrier.markClosed();
                barrier.closed.whenComplete((closed, unused) -> done.complete(null));
              });
        });
    return done;
  }

  @Override
  public ConnectionState connectionState() {
    lock.lock();
    try {
      return state;
    } finally {
      lock.unlock();
    }
  }

  @Override
  public void startDataTransfer() {
    await(startDataTransferAsync());
  }

  @Override
  public CompletionStage<Void> startDataTransferAsync() {
    if (!publisher.hasRoomForData()) {
      return CompletableFuture.failedFuture(backlogExceeded("start data transfer"));
    }
    return session.startDataTransfer();
  }

  @Override
  public void stopDataTransfer() {
    await(stopDataTransferAsync());
  }

  @Override
  public CompletionStage<Void> stopDataTransferAsync() {
    if (!publisher.hasRoomForData()) {
      return CompletableFuture.failedFuture(backlogExceeded("stop data transfer"));
    }
    return session.stopDataTransfer();
  }

  /**
   * Builds the failure for a lifecycle operation refused while a subscriber's backlog is at the
   * hard capacity.
   *
   * <p>Each admitted operation publishes a few lifecycle events into the reserve above the data
   * capacity. Refusing further operations until the subscriber catches up keeps that reserve from
   * filling, so lifecycle events are never dropped.
   *
   * @param operation the refused operation, for the detail message.
   * @return the exception to fail the operation with.
   */
  private EventBacklogExceededException backlogExceeded(String operation) {
    return new EventBacklogExceededException(
        "cannot "
            + operation
            + ": an event subscriber is "
            + publisher.maxBacklog()
            + " events behind");
  }

  @Override
  public Flow.Publisher<ClientEvent> events() {
    return publisher;
  }

  @Override
  public boolean isConnected() {
    return connectionState() == ConnectionState.CONNECTED && transport.isConnected();
  }

  @Override
  public void close() {
    Connection ended;
    Connection barrier;
    List<PendingRequest> requests;
    lock.lock();
    try {
      if (state == ConnectionState.CLOSED) {
        return;
      }
      state = ConnectionState.CLOSED;
      cancelReconnectWait();
      // The event stream completes only after this teardown and every one before it, including a
      // disconnect whose teardown is not yet queued, has published its closing events.
      barrier = new Connection(lastConnection.closed);
      lastConnection = barrier;
      ended = connection;
      connection = null;
      requests = detachRequests();
    } finally {
      lock.unlock();
    }

    var failure = new ConnectionClosedException("client closed");
    failPending(requests, failure);
    if (ended != null) {
      ended.established.completeExceptionally(failure);
    }

    executeSessionOperation(
        () -> {
          publishClosed(ended, null);
          try {
            transport.close();
          } catch (RuntimeException e) {
            loggingContext.debug(LOGGER, "{}: transport close failed", remote(), e);
          }
          if (ended != null) {
            ended.markClosed();
          }
          barrier.markClosed();
        });
    barrier.closed.whenComplete(
        (closed, unused) -> {
          publisher.close();
          if (ownsScheduler) {
            scheduler.shutdownNow();
          }
        });
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
        new PendingInterrogation(
            asdu,
            qoi,
            config.protocolProfile().cotLength() == 2,
            future,
            maxInterrogationResponseObjects);
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
        new PendingConfirmation(AsduType.C_CS_NA_1, station, ZERO_ADDRESS, confirmation);
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
    var result = new CompletableFuture<Void>();
    submitToSession(asdu)
        .whenCompleteAsync(
            (ignored, error) -> {
              if (error == null) {
                result.complete(null);
              } else {
                result.completeExceptionally(error);
              }
            },
            callbackExecutor);
    return result;
  }

  // --- Session output / input -----------------------------------------------------------------

  /**
   * Hands an outbound application ASDU to the session and returns a stage for the write.
   *
   * @param asdu the ASDU to send.
   * @return a stage that completes after the transport write, or fails if the ASDU cannot be sent.
   */
  private CompletionStage<Void> submitToSession(Asdu asdu) {
    try {
      return session.sendAsduAsync(asdu);
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
    if (!admitInbound()) {
      return;
    }
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
    publisher.afterInbound();
  }

  /**
   * Decides whether the events of one more inbound ASDU fit in every subscriber's backlog.
   *
   * <p>When the slowest subscriber has reached the hard capacity, the session's pause could not
   * hold the peer back. The client then stops taking in data and closes the connection with an
   * {@link EventBacklogExceededException} rather than continue with a gap in the event stream.
   *
   * @return {@code true} if the ASDU should be processed and its events published.
   */
  private boolean admitInbound() {
    if (backlogExceeded.get()) {
      diagnostics.onAsduDropped(Direction.IN, "event backlog exceeded");
      return false;
    }
    if (publisher.hasRoomForData()) {
      return true;
    }
    diagnostics.onAsduDropped(Direction.IN, "event backlog exceeded");
    if (backlogExceeded.compareAndSet(false, true)) {
      Connection current;
      lock.lock();
      try {
        current = connection;
      } finally {
        lock.unlock();
      }
      setInboundPaused(true);
      var cause =
          new EventBacklogExceededException(
              "an event subscriber is " + publisher.maxBacklog() + " events behind");
      if (current != null) {
        loggingContext.warn(LOGGER, "{}: closing the connection: {}", remote(), cause.getMessage());
        // Invoked under the session lock; close the session from the scheduler instead.
        try {
          scheduler.execute(() -> endConnection(current, cause));
        } catch (RejectedExecutionException e) {
          loggingContext.debug(
              LOGGER,
              "{}: scheduler rejected the backlog close; the client is closing",
              remote(),
              e);
        }
      }
    }
    return false;
  }

  private void setInboundPaused(boolean paused) {
    loggingContext.debug(
        LOGGER,
        "{}: {} inbound data; event backlog {}",
        remote(),
        paused ? "pausing" : "resuming",
        publisher.maxBacklog());
    diagnostics.onInboundPaused(paused);
    session.setInboundPaused(paused);
  }

  /** Returns the remote address from the logging context, for messages read without the MDC. */
  private String remote() {
    return loggingContext.remote();
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
    PendingCommand continuation = null;
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
        if (o == PendingRequest.Outcome.CONTINUE) {
          continuation = (PendingCommand) request;
          request.cancelTimeout();
        }
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

    if (continuation != null) {
      // Run EXECUTE off the session callback so its receive sequence has advanced before sending.
      PendingCommand request = continuation;
      callbackExecutor.execute(() -> sendCommandActivation(request));
    }
    if (finished != null) {
      PendingRequest request = finished;
      PendingRequest.Outcome finalOutcome = outcome;
      request.cancelTimeout();
      // User continuations run off the I/O thread and the session lock.
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
                if (removePending(request)) {
                  callbackExecutor.execute(() -> request.fail(error));
                }
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
   * Fails requests detached from a closed connection, outside the client lock.
   *
   * @param requests the requests removed with their connection.
   * @param cause the failure to deliver.
   */
  private void failPending(List<PendingRequest> requests, Throwable cause) {
    for (PendingRequest request : requests) {
      request.cancelTimeout();
      callbackExecutor.execute(() -> request.fail(cause));
    }
  }

  @Override
  public int pendingSendCount() {
    return session.pendingSendCount();
  }

  @Override
  public int pendingRequestCount() {
    lock.lock();
    try {
      return pending.size();
    } finally {
      lock.unlock();
    }
  }

  // --- Helpers --------------------------------------------------------------------------------

  /**
   * Publishes a client event to subscribers without dropping it.
   *
   * @param event the event to publish.
   */
  private void publish(ClientEvent event) {
    publisher.publish(event);
  }

  /**
   * Blocks until a stage completes, translating its failure to a typed exception.
   *
   * @param stage the stage to wait on.
   * @param <T> the result type.
   * @return the stage's result.
   * @throws Iec60870Exception if the stage completed exceptionally with a protocol failure.
   */
  private <T> T await(CompletionStage<T> stage) {
    // A subscriber that blocks in onNext on a call whose response is inbound data must not hold
    // the pause that keeps the response from being read.
    try (EventStream.Exclusion ignored = publisher.excludeCurrentSubscriber()) {
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
      /** A command SELECT was confirmed; queue its EXECUTE while retaining the registration. */
      CONTINUE,
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

  /** One command reservation, including both phases of select-before-operate. */
  private static final class PendingCommand extends PendingRequest {

    private final PointAddress target;
    private final Asdu execute;
    private final CompletableFuture<CommandResult> future = new CompletableFuture<>();
    private Asdu activation;
    private boolean executeQueued;
    private @Nullable Asdu confirmation;

    PendingCommand(PointAddress target, Asdu activation, Asdu execute) {
      this.target = target;
      this.activation = activation;
      this.execute = execute;
    }

    @Override
    boolean conflictsWith(PendingRequest other) {
      return other instanceof PendingCommand that
          && target.equals(that.target)
          && commandFamily(execute.type()) == commandFamily(that.execute.type());
    }

    @Override
    Outcome accept(Asdu asdu) {
      // OA zero means no specific originator: one-octet COT omits OA, and peers may leave it
      // unused even with two octets. A different nonzero OA still belongs to another source.
      if (executeQueued
          || asdu.type() != activation.type()
          || asdu.sequence() != activation.sequence()
          || asdu.test() != activation.test()
          || (!asdu.originatorAddress().equals(OriginatorAddress.none())
              && !asdu.originatorAddress().equals(activation.originatorAddress()))
          || !asdu.commonAddress().equals(activation.commonAddress())
          || !asdu.objects().equals(activation.objects())) {
        return Outcome.IGNORED;
      }
      boolean confirmationCause =
          asdu.cause() == Cause.ACTIVATION_CONFIRMATION
              || (asdu.negative()
                  && switch (asdu.cause()) {
                    case UNKNOWN_TYPE_ID,
                        UNKNOWN_CAUSE,
                        UNKNOWN_COMMON_ADDRESS,
                        UNKNOWN_INFORMATION_OBJECT_ADDRESS ->
                        true;
                    default -> false;
                  });
      if (!confirmationCause) {
        return Outcome.IGNORED;
      }
      if (activation != execute && !asdu.negative()) {
        executeQueued = true;
        return Outcome.CONTINUE;
      }
      confirmation = asdu;
      return Outcome.COMPLETED;
    }

    @Override
    void deliver() {
      Asdu ack = Objects.requireNonNull(confirmation);
      future.complete(new CommandResult(target, !ack.negative(), ack.cause(), Optional.of(ack)));
    }

    @Override
    void deliverFailure() {
      future.completeExceptionally(new ConnectionClosedException("command confirmation lost"));
    }

    @Override
    void fail(Throwable cause) {
      future.completeExceptionally(cause);
    }
  }

  /** A request awaiting a single activation confirmation, matched by command family / CA / IOA. */
  private static final class PendingConfirmation extends PendingRequest {

    private final AsduType family;
    private final CommonAddress station;
    private final InformationObjectAddress objectAddress;
    private final CompletableFuture<Asdu> future;
    private @Nullable Asdu confirmation;

    PendingConfirmation(
        AsduType family,
        CommonAddress station,
        InformationObjectAddress objectAddress,
        CompletableFuture<Asdu> future) {
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
      // caller observes the rejection rather than a timeout.
      boolean confirmationCause =
          asdu.cause() == Cause.ACTIVATION_CONFIRMATION
              || asdu.cause() == Cause.DEACTIVATION_CONFIRMATION;
      if (!confirmationCause && !asdu.negative()) {
        return Outcome.IGNORED;
      }
      // Clock synchronization is station-wide and permits a header-only confirmation; when an
      // echoed object is present its address must still match.
      if (!asdu.objects().isEmpty() && !asdu.objects().get(0).address().equals(objectAddress)) {
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

    private final Asdu activation;
    private final int responseCause;
    private final boolean hasOriginatorAddress;
    private final CompletableFuture<InterrogationResult> future;
    private final int maxObjects;
    private final List<InformationObject> collected = new ArrayList<>();
    private boolean confirmed;
    private boolean overflowed;
    private @Nullable Asdu negativeConfirmation;

    PendingInterrogation(
        Asdu activation,
        QualifierOfInterrogation qoi,
        boolean hasOriginatorAddress,
        CompletableFuture<InterrogationResult> future,
        int maxObjects) {
      this.activation = activation;
      this.responseCause = qoi.value().intValue();
      this.hasOriginatorAddress = hasOriginatorAddress;
      this.future = future;
      this.maxObjects = maxObjects;
    }

    @Override
    boolean conflictsWith(PendingRequest other) {
      // Preserve one outstanding interrogation per station, including across different groups.
      return other instanceof PendingInterrogation that
          && activation.commonAddress().equals(that.activation.commonAddress());
    }

    @Override
    Outcome accept(Asdu asdu) {
      if (!asdu.commonAddress().equals(activation.commonAddress())
          || asdu.test() != activation.test()) {
        return Outcome.IGNORED;
      }
      // A one-octet COT carries no originator. Peers using a two-octet COT may still leave OA
      // unused (zero); reject only replies directed to a different nonzero originator.
      if (hasOriginatorAddress
          && !asdu.originatorAddress().equals(OriginatorAddress.none())
          && !asdu.originatorAddress().equals(activation.originatorAddress())) {
        return Outcome.IGNORED;
      }
      if (asdu.type() == AsduType.C_IC_NA_1) {
        // Confirmations and terminations echo the single IOA 0 command and its requested QOI.
        if (!asdu.objects().equals(activation.objects())) {
          return Outcome.IGNORED;
        }
        // A negative confirmation may carry an error cause (for example UNKNOWN_COMMON_ADDRESS)
        // rather than ACTIVATION_CONFIRMATION when the controlled station declines the request;
        // a matching negative C_IC_NA_1 confirmation still rejects this request.
        if (asdu.negative()) {
          negativeConfirmation = asdu;
          return Outcome.FAILED;
        }
        return switch (asdu.cause()) {
          case ACTIVATION_CONFIRMATION -> {
            confirmed = true;
            yield Outcome.ACCEPTED;
          }
          case ACTIVATION_TERMINATION -> confirmed ? Outcome.COMPLETED : Outcome.IGNORED;
          default -> Outcome.IGNORED;
        };
      }
      if (confirmed && isInterrogationResponse(asdu.cause())) {
        // Bound the accumulated response (maxObjects == 0 disables the bound); see
        // ClientConfig#maxInterrogationResponseObjects.
        if (maxObjects > 0 && collected.size() + asdu.objects().size() > maxObjects) {
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
      future.complete(new InterrogationResult(activation.commonAddress(), collected, true));
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

    private boolean isInterrogationResponse(Cause cause) {
      // Standard station/group QOI and response COT share values 20..36 (IEC 60870-5-101, 7.4.5).
      return responseCause >= 20 && responseCause <= 36 && cause.value() == responseCause;
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
      // A rejected read mirrors the requested IOA (IEC 60870-5-101, 7.4.14). Reads of
      // different points may coexist, so only the echoed point can identify the failed request.
      if (asdu.type() == AsduType.C_RD_NA_1) {
        if (asdu.negative()
            && asdu.objects().size() == 1
            && asdu.objects().get(0) instanceof ReadCommand command
            && command.address().equals(point.objectAddress())) {
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
    public void onUndecodableAsdu(byte[] asdu, Iec60870Exception cause) {
      loggingContext.debug(
          LOGGER,
          "{}: received an undecodable ASDU ({} octets): {}",
          remote(),
          asdu.length,
          cause.toString());
      if (!admitInbound()) {
        return;
      }
      publish(new ClientEvent.UndecodableAsdu(asdu, cause));
      publisher.afterInbound();
    }

    @Override
    public void onProtocolWarning(String message) {
      // Best effort: a peer that floods ignored U-frames must not use up the lifecycle reserve.
      if (backlogExceeded.get() || !publisher.hasRoomForData()) {
        diagnostics.onEventDropped(ClientEvent.ProtocolWarning.class);
        return;
      }
      publish(new ClientEvent.ProtocolWarning(message));
      publisher.afterInbound();
    }

    @Override
    public void onDataTransferStateChanged(boolean started) {
      dataTransferStarted.set(started);
      publish(
          started ? new ClientEvent.DataTransferStarted() : new ClientEvent.DataTransferStopped());
    }

    @Override
    public void onClosed(@Nullable Throwable cause) {
      // The session closed itself on a protocol error or timeout. Like a lost connection, this
      // ends the current connection and follows the reconnect policy.
      onConnectionLost(cause);
    }

    @Override
    public void onConnectionLost(@Nullable Throwable cause) {
      DefaultIec60870Client.this.onConnectionLost(cause);
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

      PendingCommand request;
      try {
        Asdu execute = commandActivation(command, false);
        Asdu activation =
            mode == CommandMode.SELECT_BEFORE_OPERATE ? commandActivation(command, true) : execute;
        request = new PendingCommand(command.target(), activation, execute);
      } catch (RuntimeException e) {
        return CompletableFuture.failedFuture(e);
      }
      if (!register(request)) {
        request.fail(alreadyInFlight("command for " + command.target()));
      } else {
        sendCommandActivation(request);
      }
      return request.future;
    }

    private Asdu commandActivation(Command command, boolean select) {
      return new Asdu(
          CommandAsdus.typeOf(command),
          false,
          Cause.ACTIVATION,
          false,
          false,
          config.originatorAddress(),
          command.target().commonAddress(),
          List.of(CommandAsdus.toObject(command, select)));
    }
  }

  /** Submits a command phase only while its original transaction is still registered. */
  private void sendCommandActivation(PendingCommand request) {
    CompletableFuture.supplyAsync(
            () -> prepareAndSendCommand(request), this::executeSessionOperation)
        .thenCompose(write -> write)
        .whenComplete(
            (ignored, error) -> {
              if (error != null && removePending(request)) {
                callbackExecutor.execute(() -> request.fail(error));
              }
            });
  }

  private CompletionStage<Void> prepareAndSendCommand(PendingCommand request) {
    Asdu activation;
    lock.lock();
    try {
      if (!pending.contains(request)) {
        return CompletableFuture.completedFuture(null);
      }
      if (request.future.isCancelled()) {
        pending.remove(request);
        request.cancelTimeout();
        return CompletableFuture.completedFuture(null);
      }
      if (request.executeQueued) {
        request.activation = request.execute;
        request.executeQueued = false;
      }
      activation = request.activation;
      request.setTimeoutHandle(
          scheduler.schedule(
              () -> timeoutCommand(request, activation),
              config.commandTimeout().toMillis(),
              TimeUnit.MILLISECONDS));
    } finally {
      lock.unlock();
    }
    // Loss can invalidate the request and close the old session concurrently. Session reset is
    // queued behind this submission, so the ASDU cannot cross into the replacement session.
    return submitToSession(activation);
  }

  private void executeSessionOperation(Runnable operation) {
    synchronized (sessionOperations) {
      sessionOperations.addLast(operation);
      if (sessionOperationRunning) {
        return;
      }
      sessionOperationRunning = true;
    }
    while (true) {
      Runnable next;
      synchronized (sessionOperations) {
        next = sessionOperations.pollFirst();
        if (next == null) {
          sessionOperationRunning = false;
          return;
        }
      }
      // Only CompletableFuture tasks enter this queue. They capture operation failures and allow
      // this drain to continue; their completion handlers can enqueue more work without blocking.
      next.run();
    }
  }

  /** A cancelled SELECT timer must not time out the subsequent EXECUTE phase. */
  private void timeoutCommand(PendingCommand request, Asdu activation) {
    boolean removed;
    lock.lock();
    try {
      removed =
          request.activation == activation && !request.executeQueued && pending.remove(request);
      if (removed) {
        request.cancelTimeout();
      }
    } finally {
      lock.unlock();
    }
    if (removed) {
      callbackExecutor.execute(
          () ->
              request.fail(new ProtocolTimeoutException("command timed out awaiting a response")));
    }
  }

  /**
   * One connection, from its first dial until its teardown finishes, or the barrier that a {@link
   * #disconnectAsync()} places between connections.
   */
  private static final class Connection {

    /** Completes when the connection is established, or fails with why it never was. */
    private final CompletableFuture<Void> established = new CompletableFuture<>();

    /**
     * Completes when the teardown of this connection and of every connection or barrier before it
     * has finished, including the transport close.
     */
    private final CompletableFuture<Void> closed = new CompletableFuture<>();

    /** The {@link #closed} stage of the connection or barrier before this one. */
    private final CompletableFuture<Void> predecessorClosed;

    /**
     * Set once the transport connected; a loss reported before this belongs to another one. Written
     * under this connection's monitor.
     */
    private volatile boolean transportConnected;

    /** Whether the teardown has run; guarded by this connection's monitor. */
    private boolean tornDown;

    /** The cause the teardown was given; guarded by this connection's monitor. */
    private @Nullable Throwable teardownCause;

    /** Whether onConnectionClosed was reported; guarded by this connection's monitor. */
    private boolean closeReported;

    /** Set once ConnectionOpened was published; read and written on the operation queue. */
    private volatile boolean opened;

    private Connection(CompletableFuture<Void> predecessorClosed) {
      this.predecessorClosed = predecessorClosed;
    }

    /**
     * Records that the transport connected.
     *
     * @return {@code true} if the teardown already ran and the caller must report the close, which
     *     happens when a disconnect lands between the transport reporting the connection and
     *     completing its connect stage.
     */
    private synchronized boolean markTransportConnected() {
      transportConnected = true;
      return tornDown && claimCloseReport();
    }

    /**
     * Records that the teardown ran.
     *
     * @param cause the teardown's cause, or {@code null} for an application close.
     * @return {@code true} if the transport connected and the caller must report the close.
     */
    private synchronized boolean markTornDown(@Nullable Throwable cause) {
      tornDown = true;
      teardownCause = cause;
      return transportConnected && claimCloseReport();
    }

    private synchronized @Nullable Throwable teardownCause() {
      return teardownCause;
    }

    // Monitor held. Every connection the transport reported gets exactly one close report.
    private boolean claimCloseReport() {
      if (closeReported) {
        return false;
      }
      closeReported = true;
      return true;
    }

    /**
     * Records that this connection's own teardown has finished. {@link #closed} completes once the
     * predecessor's has too, so a cancelled connection cannot release a later one early.
     */
    private void markClosed() {
      // CompletableFuture<Void> can only be completed with null; the @NotNull on complete() does
      // not apply to a Void result.
      //noinspection DataFlowIssue
      predecessorClosed.whenComplete((ignored, error) -> closed.complete(null));
    }

    /**
     * Returns a connection whose teardown has already finished, the predecessor of the first.
     *
     * @return a finished connection.
     */
    private static Connection finished() {
      var connection = new Connection(CompletableFuture.completedFuture(null));
      // CompletableFuture<Void> can only be completed with null; the @NotNull on complete() does
      // not apply to a Void result.
      //noinspection DataFlowIssue
      connection.closed.complete(null);
      return connection;
    }
  }

  /** A scheduled reconnect attempt. */
  private final class ReconnectWait {

    private final int attempt;
    private final Duration delay;
    private volatile @Nullable ScheduledFuture<?> timer;
    private volatile boolean cancelled;

    private ReconnectWait(int attempt, Duration delay) {
      this.attempt = attempt;
      this.delay = delay;
    }

    /**
     * Starts (or restarts) the timer that triggers the attempt.
     *
     * @param after the delay; rounded up to at least one millisecond so a deferred attempt that
     *     re-arms itself cannot spin.
     */
    private void arm(Duration after) {
      long millis = Math.max(1, after.toMillis());
      try {
        timer = scheduler.schedule(() -> reconnect(this), millis, TimeUnit.MILLISECONDS);
      } catch (RejectedExecutionException e) {
        loggingContext.debug(
            LOGGER, "{}: scheduler rejected the reconnect; the client is closing", remote(), e);
        return;
      }
      if (cancelled) {
        cancel();
      }
    }

    private void cancel() {
      cancelled = true;
      ScheduledFuture<?> scheduled = timer;
      if (scheduled != null) {
        scheduled.cancel(false);
      }
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
