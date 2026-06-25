package com.digitalpetri.iec60870.server;

import com.digitalpetri.iec60870.ConnectionClosedException;
import com.digitalpetri.iec60870.Iec60870Exception;
import com.digitalpetri.iec60870.OutboundQueuePolicy;
import com.digitalpetri.iec60870.address.CommonAddress;
import com.digitalpetri.iec60870.address.InformationObjectAddress;
import com.digitalpetri.iec60870.address.OriginatorAddress;
import com.digitalpetri.iec60870.address.PointAddress;
import com.digitalpetri.iec60870.asdu.Asdu;
import com.digitalpetri.iec60870.asdu.AsduType;
import com.digitalpetri.iec60870.asdu.Cause;
import com.digitalpetri.iec60870.asdu.InformationObject;
import com.digitalpetri.iec60870.asdu.element.FixedTestBitPattern;
import com.digitalpetri.iec60870.asdu.element.QualifierOfInterrogation;
import com.digitalpetri.iec60870.asdu.element.QualifierOfResetProcess;
import com.digitalpetri.iec60870.asdu.object.ClockSynchronizationCommand;
import com.digitalpetri.iec60870.asdu.object.CounterInterrogationCommand;
import com.digitalpetri.iec60870.asdu.object.InterrogationCommand;
import com.digitalpetri.iec60870.asdu.object.ResetProcessCommand;
import com.digitalpetri.iec60870.asdu.object.TestCommand;
import com.digitalpetri.iec60870.asdu.object.TestCommandWithCp56Time;
import com.digitalpetri.iec60870.asdu.time.Cp56Time2a;
import com.digitalpetri.iec60870.point.MonitorMapping;
import com.digitalpetri.iec60870.point.PointCapability;
import com.digitalpetri.iec60870.point.PointType;
import com.digitalpetri.iec60870.point.PointValue;
import com.digitalpetri.iec60870.point.TimeTagStyle;
import com.digitalpetri.iec60870.session.Session;
import com.digitalpetri.iec60870.transport.ServerTransport;
import com.digitalpetri.iec60870.transport.ServerTransportConnection;
import java.net.SocketAddress;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.SubmissionPublisher;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.joou.UShort;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The default {@link Iec60870Server} implementation, driving any {@link ServerTransport}.
 *
 * <p>For each accepted connection the server builds a per-connection {@link Session} through the
 * supplied session factory and dispatches received control-direction ASDUs to the configured {@link
 * ServerHandler}, answering interrogation, read, command, clock-synchronization, test, and reset
 * requests and mirroring unknown ones with the appropriate negative cause. The facade speaks to
 * each {@link Session} purely in terms of {@link Asdu}s and receives inbound ASDUs and lifecycle
 * transitions through a per-connection {@link Session.Events} sink it owns; the protocol-specific
 * session and its wire framing are assembled in the factory. {@link #publish(PointAddress,
 * PointValue, Cause)} updates the station image and pushes monitor ASDUs to every started
 * connection. Server events are delivered through a {@link SubmissionPublisher} on the configured
 * callback executor.
 *
 * <p>The instance is thread-safe. Construct it with a transport, a {@link ServerConfig}, and a
 * per-connection session factory; the server registers itself as the transport's connection handler
 * on construction. Per-connection handler callbacks are serialized.
 */
public final class DefaultIec60870Server implements Iec60870Server {

  private static final Logger LOGGER = LoggerFactory.getLogger(DefaultIec60870Server.class);

  /**
   * Conventional information object address for station-level confirmations (interrogation etc.).
   */
  private static final InformationObjectAddress ZERO_ADDRESS = InformationObjectAddress.of(0);

  /** A fixed instant within the CP56Time2a 2000..2099 century, for synthesized timed echoes. */
  private static final Instant EPOCH_2000 = Instant.parse("2000-01-01T00:00:00Z");

  private final ServerTransport transport;
  private final ServerConfig config;

  /**
   * Builds a {@link Session} for each accepted connection, wired to the connection's {@link
   * Session.Events} sink and using the facade's shared scheduler. The protocol-specific session and
   * its wire framing are assembled here, outside the facade.
   */
  private final SessionFactory sessionFactory;

  private final ScheduledExecutorService scheduler;
  private final boolean ownsScheduler;
  private final Executor callbackExecutor;

  private final SubmissionPublisher<ServerEvent> publisher;
  private final StationRegistry registry;

  private final Set<ServerConnection> connections = ConcurrentHashMap.newKeySet();

  /** Atomic admission counter so the {@code maxConnections} cap cannot be overshot under a race. */
  private final AtomicInteger connectionCount = new AtomicInteger();

  /**
   * Creates a server over the given transport and configuration, building each connection's {@link
   * Session} through the supplied factory.
   *
   * <p>The factory receives the accepted transport connection and the connection's {@link
   * Session.Events} sink and returns a session wired to it. The protocol assembly (the
   * protocol-specific session and its wire framing) lives in the factory, not in this class. The
   * server registers itself as the transport's connection handler and creates an internal
   * single-thread scheduler. Call {@link #start()} to begin accepting connections.
   *
   * @param transport the transport that accepts controlling-station connections.
   * @param config the server configuration.
   * @param sessionFactory builds each connection's session wired to its {@link Session.Events}.
   * @throws NullPointerException if any argument is null.
   */
  public DefaultIec60870Server(
      ServerTransport transport, ServerConfig config, SessionFactory sessionFactory) {
    this(transport, config, sessionFactory, null);
  }

  /**
   * Creates a server over the given transport and configuration, building each connection's {@link
   * Session} through the supplied factory and using an injected scheduler.
   *
   * <p>When {@code scheduler} is {@code null} an internal single-thread scheduler is created and
   * shut down on {@link #close()}; otherwise the supplied scheduler is used and is never shut down
   * by the server. A test may inject a deterministic scheduler here.
   *
   * @param transport the transport that accepts controlling-station connections.
   * @param config the server configuration.
   * @param sessionFactory builds each connection's session wired to its {@link Session.Events}.
   * @param scheduler the scheduler for per-connection timers, or {@code null} to create an internal
   *     one.
   * @throws NullPointerException if {@code transport}, {@code config}, or {@code sessionFactory} is
   *     null.
   */
  public DefaultIec60870Server(
      ServerTransport transport,
      ServerConfig config,
      SessionFactory sessionFactory,
      @Nullable ScheduledExecutorService scheduler) {

    this.transport = Objects.requireNonNull(transport, "transport");
    this.config = Objects.requireNonNull(config, "config");
    this.sessionFactory = Objects.requireNonNull(sessionFactory, "sessionFactory");

    if (scheduler != null) {
      this.scheduler = scheduler;
      this.ownsScheduler = false;
    } else {
      this.scheduler = Executors.newSingleThreadScheduledExecutor(new SchedulerThreadFactory());
      this.ownsScheduler = true;
    }
    this.callbackExecutor = config.callbackExecutor();
    this.publisher = new SubmissionPublisher<>(callbackExecutor, Flow.defaultBufferSize());
    this.registry = new DefaultStationRegistry(config.stations());

    transport.setConnectionHandler(this::onAccept);
  }

  /**
   * Builds a per-connection {@link Session} wired to the connection's {@link Session.Events} sink,
   * using the facade's shared scheduler.
   *
   * <p>The protocol-specific session and its wire framing are assembled by the factory, outside the
   * facade. The factory is responsible for routing inbound frames to the session and for routing a
   * transport-level connection loss to {@link Session.Events#onClosed(Throwable)}, so the facade
   * learns of every close through that single sink.
   */
  @FunctionalInterface
  public interface SessionFactory {

    /**
     * Builds the session for an accepted connection.
     *
     * @param connection the accepted transport connection.
     * @param events the connection's event sink, owned by the facade.
     * @param scheduler the facade's shared scheduler for session timers.
     * @return the session wired to {@code events}.
     */
    Session create(
        ServerTransportConnection connection,
        Session.Events events,
        ScheduledExecutorService scheduler);
  }

  // --- Iec60870Server: lifecycle ----------------------------------------------------------------

  @Override
  public void start() {
    await(startAsync());
  }

  @Override
  public CompletionStage<Void> startAsync() {
    return transport.bind();
  }

  @Override
  public void stop() {
    await(stopAsync());
  }

  @Override
  public CompletionStage<Void> stopAsync() {
    return transport.unbind();
  }

  @Override
  public StationRegistry stations() {
    return registry;
  }

  @Override
  public Flow.Publisher<ServerEvent> events() {
    return publisher;
  }

  @Override
  public void close() {
    try {
      transport.unbind();
    } catch (RuntimeException e) {
      LOGGER.debug("transport unbind failed during close", e);
    }
    for (ServerConnection connection : connections) {
      connection.close(null);
    }
    publisher.close();
    if (ownsScheduler) {
      scheduler.shutdownNow();
    }
  }

  // --- Iec60870Server: publish ------------------------------------------------------------------

  @Override
  public void publish(PointAddress point, PointValue<?> value, Cause cause) {
    await(publishAsync(point, value, cause));
  }

  @Override
  public CompletionStage<Void> publishAsync(PointAddress point, PointValue<?> value, Cause cause) {
    Objects.requireNonNull(point, "point");
    Objects.requireNonNull(value, "value");
    Objects.requireNonNull(cause, "cause");

    try {
      Station station =
          registry
              .station(point.commonAddress())
              .orElseThrow(
                  () ->
                      new IllegalArgumentException(
                          "no station for common address: " + point.commonAddress()));
      PointType type =
          station
              .definition(point.objectAddress())
              .orElseThrow(() -> new IllegalArgumentException("no point defined at: " + point))
              .type();

      // Update the station image, then build the monitor ASDU from the chosen time-tag style.
      station.updateValue(point.objectAddress(), value);
      InformationObject object =
          MonitorMapping.toMonitorObject(type, point.objectAddress(), value, config.timeTagStyle());
      Asdu asdu =
          new Asdu(
              MonitorTypes.of(type, config.timeTagStyle()),
              false,
              cause,
              false,
              false,
              OriginatorAddress.none(),
              point.commonAddress(),
              List.of(object));

      for (ServerConnection connection : connections) {
        connection.enqueueMonitor(asdu);
      }
      return CompletableFuture.completedFuture(null);
    } catch (RuntimeException e) {
      return CompletableFuture.failedFuture(e);
    }
  }

  // --- Connection acceptance ------------------------------------------------------------------

  /**
   * Handles a newly accepted transport connection by creating per-connection state and building its
   * session through the factory (which wires inbound frames and connection loss to the connection's
   * event sink).
   *
   * @param transportConnection the accepted connection.
   */
  private void onAccept(ServerTransportConnection transportConnection) {
    // Reserve a slot atomically: increment first, roll back on overshoot, so concurrent accepts
    // cannot both pass a size()>=max check and exceed the cap.
    if (connectionCount.incrementAndGet() > config.maxConnections()) {
      connectionCount.decrementAndGet();
      LOGGER.debug(
          "rejecting connection from {}: max connections ({}) reached",
          transportConnection.remoteAddress(),
          config.maxConnections());
      transportConnection.close();
      return;
    }

    ServerConnection connection = new ServerConnection(transportConnection);
    connections.add(connection);
    connection.onConnected();
    publish(new ServerEvent.ConnectionAccepted(connection.remoteAddress()));
  }

  // --- Event publishing -----------------------------------------------------------------------

  /**
   * Publishes a server event to subscribers, dropping it if no subscriber can accept it.
   *
   * @param event the event to publish.
   */
  private void publish(ServerEvent event) {
    if (publisher.isClosed()) {
      return;
    }
    publisher.offer(event, (subscriber, dropped) -> false);
  }

  // --- Helpers --------------------------------------------------------------------------------

  /**
   * Blocks until a stage completes, translating its failure to a typed exception.
   *
   * @param stage the stage to wait on.
   * @throws Iec60870Exception if the stage completed exceptionally with a protocol failure.
   */
  private static void await(CompletionStage<?> stage) {
    try {
      stage.toCompletableFuture().join();
    } catch (CompletionException e) {
      throw unwrap(e.getCause());
    } catch (CancellationException e) {
      throw new ConnectionClosedException("operation cancelled", e);
    }
  }

  /**
   * Translates a stage failure to a {@link RuntimeException} suitable for rethrowing.
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
        cause != null ? cause.getMessage() : "operation failed",
        cause != null ? cause : new IllegalStateException("unknown failure"));
  }

  // --- Per-connection state -------------------------------------------------------------------

  /**
   * The server's state for a single accepted connection: a per-connection {@link Session} built by
   * the factory and a serial dispatch chain that runs handler callbacks one at a time. The facade
   * speaks to the session only in terms of {@link Asdu}s; inbound frames and connection loss are
   * delivered through the connection's {@link Session.Events} sink, which the factory wires.
   */
  private final class ServerConnection {

    private final ServerTransportConnection transportConnection;
    private final SocketAddress remoteAddress;
    private final Session session;

    // Serializes handler dispatch; each ASDU chains off the previous dispatch.
    private volatile CompletableFuture<Void> dispatchTail = CompletableFuture.completedFuture(null);
    private final AtomicBoolean closed = new AtomicBoolean(false);

    ServerConnection(ServerTransportConnection transportConnection) {
      this.transportConnection = transportConnection;
      this.remoteAddress = transportConnection.remoteAddress();
      // The facade owns the Session.Events sink; the factory builds the protocol session wired to
      // it, using the facade's shared scheduler.
      this.session =
          Objects.requireNonNull(
              sessionFactory.create(transportConnection, new SessionEvents(), scheduler),
              "session");
    }

    SocketAddress remoteAddress() {
      return remoteAddress;
    }

    void onConnected() {
      session.onConnected();
    }

    /**
     * Enqueues a monitor ASDU produced by {@link #publish}; only started connections transmit it.
     */
    void enqueueMonitor(Asdu asdu) {
      if (closed.get()) {
        return;
      }
      if (!session.isDataTransferStarted()) {
        return;
      }
      // Apply backpressure for the BLOCK policy on the publishing thread, OUTSIDE the session lock,
      // before offering the ASDU. DROP_OLDEST / DROP_NEWEST are enforced inside the session's
      // bounded send queue together with the flow-control window.
      if (config.eventQueuePolicy() == OutboundQueuePolicy.BLOCK && config.maxOutboundQueue() > 0) {
        try {
          session.awaitSendCapacity(config.outboundBlockTimeout().toMillis());
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          return;
        }
        if (closed.get()) {
          return;
        }
      }
      session.sendAsdu(asdu);
    }

    /** Sends an ASDU on behalf of a handler (the ServerContext escape hatch). */
    private void send(Asdu asdu) {
      if (!closed.get()) {
        session.sendAsdu(asdu);
      }
    }

    /**
     * Dispatches a received ASDU on the serial per-connection chain so handler callbacks never run
     * concurrently for this connection.
     */
    // Read-modify-write of dispatchTail is serialized by the session lock (onAsdu, the only caller,
    // runs under it), so the non-atomic compound assignment is safe.
    @SuppressWarnings("NonAtomicOperationOnVolatileField")
    private void dispatch(Asdu asdu) {
      dispatchTail =
          dispatchTail
              .handle((ignored, ignoredError) -> null)
              .thenComposeAsync(ignored -> handle(asdu), callbackExecutor)
              .exceptionally(
                  error -> {
                    LOGGER.debug("dispatch failed for {}", remoteAddress, error);
                    // null is the only valid completion value for a CompletableFuture<Void>.
                    //noinspection DataFlowIssue
                    return null;
                  });
    }

    /**
     * Handles one received ASDU, returning a stage that completes when its reply has been queued.
     */
    private CompletionStage<Void> handle(Asdu asdu) {
      publish(new ServerEvent.AsduReceived(remoteAddress, asdu));

      ServerContext context = new Context(asdu.commonAddress());

      return config
          .handler()
          .onRawAsduAsync(context, asdu)
          .thenCompose(
              consumed -> {
                if (consumed) {
                  return done();
                }
                return dispatchByType(context, asdu);
              });
    }

    /** Routes an ASDU to the per-type handling once the raw hook has declined it. */
    private CompletionStage<Void> dispatchByType(ServerContext context, Asdu asdu) {
      AsduType type = asdu.type();
      return switch (type) {
        case C_IC_NA_1 -> handleInterrogation(context, asdu);
        case C_CI_NA_1 -> handleCounterInterrogation(asdu);
        case C_RD_NA_1 -> handleRead(context, asdu);
        case C_CS_NA_1 -> handleClockSync(context, asdu);
        case C_TS_NA_1, C_TS_TA_1 -> handleTest(asdu);
        case C_RP_NA_1 -> handleReset(context, asdu);
        case C_SC_NA_1,
            C_DC_NA_1,
            C_RC_NA_1,
            C_SE_NA_1,
            C_SE_NB_1,
            C_SE_NC_1,
            C_BO_NA_1,
            C_SC_TA_1,
            C_DC_TA_1,
            C_RC_TA_1,
            C_SE_TA_1,
            C_SE_TB_1,
            C_SE_TC_1,
            C_BO_TA_1 ->
            handleCommand(context, asdu);
        default -> handleUnknown(asdu);
      };
    }

    // --- Interrogation ------------------------------------------------------------------------

    private CompletionStage<Void> handleInterrogation(ServerContext context, Asdu asdu) {
      Optional<Station> station = registry.station(asdu.commonAddress());
      QualifierOfInterrogation qoi =
          asdu.objects().isEmpty() || !(asdu.objects().get(0) instanceof InterrogationCommand cmd)
              ? QualifierOfInterrogation.STATION
              : cmd.qualifier();

      if (station.isEmpty()) {
        send(activationConfirmation(asdu, true, Cause.UNKNOWN_COMMON_ADDRESS));
        return done();
      }

      InterrogationRequest request = new InterrogationRequest(asdu.commonAddress(), qoi);
      return config
          .handler()
          .onInterrogationAsync(context, request)
          .thenAccept(response -> emitInterrogation(asdu, qoi, response));
    }

    private void emitInterrogation(
        Asdu request, QualifierOfInterrogation qoi, InterrogationResponse response) {
      if (!response.accepted()) {
        Cause cause = response.rejectCause();
        send(activationConfirmation(request, true, cause != null ? cause : Cause.UNKNOWN_CAUSE));
        return;
      }

      send(activationConfirmation(request, false, Cause.ACTIVATION_CONFIRMATION));

      Cause monitorCause = interrogationCause(qoi);
      for (InformationObject object : response.objects()) {
        send(
            new Asdu(
                MonitorTypes.of(MonitorMapping.typeOf(object), styleOf(object)),
                false,
                monitorCause,
                false,
                false,
                OriginatorAddress.none(),
                request.commonAddress(),
                List.of(object)));
      }

      send(activationTermination(request, AsduType.C_IC_NA_1));
    }

    // --- Counter interrogation ----------------------------------------------------------------

    private CompletionStage<Void> handleCounterInterrogation(Asdu asdu) {
      Optional<Station> station = registry.station(asdu.commonAddress());
      if (station.isEmpty()) {
        send(activationConfirmation(asdu, true, Cause.UNKNOWN_COMMON_ADDRESS));
        return done();
      }
      if (asdu.objects().isEmpty()
          || !(asdu.objects().get(0) instanceof CounterInterrogationCommand command)) {
        send(activationConfirmation(asdu, true, Cause.UNKNOWN_INFORMATION_OBJECT_ADDRESS));
        return done();
      }

      send(activationConfirmation(asdu, false, Cause.ACTIVATION_CONFIRMATION));

      // RQT selects the requested counter group: 1..4 a specific group, 5 (or any other value) a
      // general counter request reporting every integrated-totals point.
      int rqt = command.qualifier().request();
      Cause monitorCause = counterCause(rqt);
      List<Station.InterrogatedPoint> points =
          rqt >= 1 && rqt <= 4
              ? station.get().selectCounterGroup(rqt)
              : station.get().selectCounterGroup(0);
      for (Station.InterrogatedPoint point : points) {
        InformationObject object =
            MonitorMapping.toMonitorObject(
                PointType.INTEGRATED_TOTALS, point.address(), point.value(), config.timeTagStyle());
        send(
            new Asdu(
                MonitorTypes.of(PointType.INTEGRATED_TOTALS, config.timeTagStyle()),
                false,
                monitorCause,
                false,
                false,
                OriginatorAddress.none(),
                asdu.commonAddress(),
                List.of(object)));
      }

      send(activationTermination(asdu, AsduType.C_CI_NA_1));
      return done();
    }

    // --- Read ---------------------------------------------------------------------------------

    private CompletionStage<Void> handleRead(ServerContext context, Asdu asdu) {
      if (asdu.objects().isEmpty()) {
        send(mirror(asdu, Cause.UNKNOWN_INFORMATION_OBJECT_ADDRESS));
        return done();
      }
      InformationObjectAddress ioa = asdu.objects().get(0).address();
      PointAddress point = new PointAddress(asdu.commonAddress(), ioa);
      ReadRequest request = new ReadRequest(point);

      return config
          .handler()
          .onReadAsync(context, request)
          .thenAccept(
              response -> {
                if (!response.accepted()) {
                  Cause cause = response.rejectCause();
                  send(
                      mirror(
                          asdu, cause != null ? cause : Cause.UNKNOWN_INFORMATION_OBJECT_ADDRESS));
                  return;
                }
                InformationObject object = Objects.requireNonNull(response.object());
                send(
                    new Asdu(
                        MonitorTypes.of(MonitorMapping.typeOf(object), styleOf(object)),
                        false,
                        Cause.REQUEST,
                        false,
                        false,
                        OriginatorAddress.none(),
                        asdu.commonAddress(),
                        List.of(object)));
              });
    }

    // --- Clock synchronization ----------------------------------------------------------------

    private CompletionStage<Void> handleClockSync(ServerContext context, Asdu asdu) {
      if (asdu.objects().isEmpty()
          || !(asdu.objects().get(0) instanceof ClockSynchronizationCommand command)) {
        send(activationConfirmation(asdu, true, Cause.UNKNOWN_INFORMATION_OBJECT_ADDRESS));
        return done();
      }
      ClockSyncRequest request = new ClockSyncRequest(asdu.commonAddress(), command.time());
      return config
          .handler()
          .onClockSyncAsync(context, request)
          .thenAccept(
              decision -> {
                if (decision.accepted()) {
                  // Echo the supplied time in the activation confirmation.
                  send(
                      new Asdu(
                          AsduType.C_CS_NA_1,
                          false,
                          Cause.ACTIVATION_CONFIRMATION,
                          false,
                          false,
                          OriginatorAddress.none(),
                          asdu.commonAddress(),
                          List.of(command)));
                } else {
                  Cause cause = decision.rejectCause();
                  send(
                      activationConfirmation(
                          asdu, true, cause != null ? cause : Cause.UNKNOWN_CAUSE));
                }
              });
    }

    // --- Test command -------------------------------------------------------------------------

    private CompletionStage<Void> handleTest(Asdu asdu) {
      // Echo the test object in the activation confirmation. When the request carried no object,
      // synthesize one whose concrete record matches the type identification so the codec does not
      // fail at encode: C_TS_TA_1 expects a TestCommandWithCp56Time, C_TS_NA_1 a plain TestCommand.
      InformationObject echo;
      if (!asdu.objects().isEmpty()) {
        echo = asdu.objects().get(0);
      } else if (asdu.type() == AsduType.C_TS_TA_1) {
        // CP56Time2a carries a two-digit year mapped to 2000..2099, so synthesize a time within
        // that century (the epoch year 1970 is out of range and would throw at construction).
        echo =
            new TestCommandWithCp56Time(
                ZERO_ADDRESS, UShort.valueOf(0), Cp56Time2a.from(EPOCH_2000, ZoneOffset.UTC));
      } else {
        echo = new TestCommand(ZERO_ADDRESS, FixedTestBitPattern.DEFAULT);
      }
      send(
          new Asdu(
              asdu.type(),
              false,
              Cause.ACTIVATION_CONFIRMATION,
              false,
              false,
              OriginatorAddress.none(),
              asdu.commonAddress(),
              List.of(echo)));
      return done();
    }

    // --- Reset process ------------------------------------------------------------------------

    private CompletionStage<Void> handleReset(ServerContext context, Asdu asdu) {
      Optional<Station> station = registry.station(asdu.commonAddress());
      if (station.isEmpty()) {
        send(activationConfirmation(asdu, true, Cause.UNKNOWN_COMMON_ADDRESS));
        return done();
      }
      QualifierOfResetProcess qrp =
          asdu.objects().isEmpty()
                  || !(asdu.objects().get(0) instanceof ResetProcessCommand command)
              ? QualifierOfResetProcess.GENERAL
              : command.qualifier();
      ResetRequest request = new ResetRequest(asdu.commonAddress(), qrp);
      return config
          .handler()
          .onResetAsync(context, request)
          .thenAccept(
              decision -> {
                if (decision.accepted()) {
                  send(activationConfirmation(asdu, false, Cause.ACTIVATION_CONFIRMATION));
                } else {
                  Cause cause = decision.rejectCause();
                  send(
                      activationConfirmation(
                          asdu, true, cause != null ? cause : Cause.UNKNOWN_CAUSE));
                }
              });
    }

    // --- Commands -----------------------------------------------------------------------------

    private CompletionStage<Void> handleCommand(ServerContext context, Asdu asdu) {
      publish(new ServerEvent.CommandReceived(remoteAddress, asdu));

      if (asdu.objects().isEmpty()) {
        send(mirror(asdu, Cause.UNKNOWN_INFORMATION_OBJECT_ADDRESS));
        return done();
      }

      Optional<Station> station = registry.station(asdu.commonAddress());
      if (station.isEmpty()) {
        send(mirror(asdu, Cause.UNKNOWN_COMMON_ADDRESS));
        return done();
      }

      InformationObject commandObject = asdu.objects().get(0);
      PointAddress target = new PointAddress(asdu.commonAddress(), commandObject.address());
      CommandMode mode = CommandModes.of(commandObject);
      CommandRequest request = new CommandRequest(target, asdu.type(), commandObject, mode);

      return config
          .handler()
          .onCommandAsync(context, request)
          .thenAccept(decision -> emitCommandDecision(asdu, target, mode, decision));
    }

    private void emitCommandDecision(
        Asdu request, PointAddress target, CommandMode mode, CommandDecision decision) {

      if (!decision.accepted()) {
        Cause cause = decision.rejectCause();
        send(mirror(request, cause != null ? cause : Cause.UNKNOWN_INFORMATION_OBJECT_ADDRESS));
        return;
      }

      // Positive activation confirmation echoing the command object.
      send(
          new Asdu(
              request.type(),
              false,
              Cause.ACTIVATION_CONFIRMATION,
              false,
              false,
              OriginatorAddress.none(),
              request.commonAddress(),
              request.objects()));

      // Image update + return information apply only to an executing command. ACT_TERM must always
      // be sent, even if building the return information throws (for example a type-mismatched
      // accepted value), so the controlling station is not left awaiting termination.
      if (mode == CommandMode.EXECUTE) {
        try {
          decision
              .updatedValueOptional()
              .ifPresent(value -> applyUpdateAndReturn(request, target, value));
        } finally {
          send(activationTermination(request, request.type()));
        }
      }
    }

    private void applyUpdateAndReturn(Asdu request, PointAddress target, PointValue<?> value) {
      Optional<Station> station = registry.station(target.commonAddress());
      if (station.isEmpty()) {
        return;
      }
      Optional<PointDefinition<?>> definition = station.get().definition(target.objectAddress());
      if (definition.isEmpty()) {
        return;
      }
      PointType type = definition.get().type();

      // Build the monitor object FIRST so a runtime type mismatch (an accepted value whose type
      // does not match the point definition) throws before the station image is mutated, leaving
      // the image uncorrupted.
      InformationObject monitor =
          MonitorMapping.toMonitorObject(
              type, target.objectAddress(), value, config.timeTagStyle());
      station.get().updateValue(target.objectAddress(), value);

      send(
          new Asdu(
              MonitorTypes.of(type, config.timeTagStyle()),
              false,
              Cause.RETURN_REMOTE,
              false,
              false,
              OriginatorAddress.none(),
              request.commonAddress(),
              List.of(monitor)));
    }

    // --- Unknown type -------------------------------------------------------------------------

    private CompletionStage<Void> handleUnknown(Asdu asdu) {
      send(mirror(asdu, Cause.UNKNOWN_TYPE_ID));
      return done();
    }

    // --- ASDU construction helpers ------------------------------------------------------------

    /**
     * Builds an activation confirmation for a control command: the same type and object(s) with the
     * cause set to activation confirmation (or the given negative cause) and the P/N bit set when
     * {@code negative}.
     */
    private Asdu activationConfirmation(Asdu request, boolean negative, Cause cause) {
      return new Asdu(
          request.type(),
          false,
          cause,
          negative,
          false,
          OriginatorAddress.none(),
          request.commonAddress(),
          request.objects());
    }

    /**
     * Builds an activation termination for the given control type, echoing the request's first
     * information object (if any).
     */
    private Asdu activationTermination(Asdu request, AsduType type) {
      List<InformationObject> objects =
          request.objects().isEmpty() ? List.of() : List.of(request.objects().get(0));
      return new Asdu(
          type,
          false,
          Cause.ACTIVATION_TERMINATION,
          false,
          false,
          OriginatorAddress.none(),
          request.commonAddress(),
          objects);
    }

    /** Mirrors a received ASDU back with the P/N bit set and the given negative cause. */
    private Asdu mirror(Asdu request, Cause cause) {
      return new Asdu(
          request.type(),
          request.sequence(),
          cause,
          true,
          request.test(),
          OriginatorAddress.none(),
          request.commonAddress(),
          request.objects());
    }

    // --- Lifecycle ----------------------------------------------------------------------------

    void close(@Nullable Throwable cause) {
      // Atomic check-then-set so two racing close paths (session.onClosed and the transport's
      // onConnectionLost) cannot both tear the connection down or publish ConnectionClosed twice.
      if (!closed.compareAndSet(false, true)) {
        return;
      }
      session.close();
      // Release the slot reserved in onAccept. The CAS above makes this run exactly once, so the
      // count is balanced even if a loss fires in the window after onAccept reserved the slot (and
      // wired this connection's transport listener) but before connections.add(this) ran.
      connectionCount.decrementAndGet();
      connections.remove(this);
      try {
        transportConnection.close();
      } catch (RuntimeException e) {
        LOGGER.debug("transport close failed for {}", remoteAddress, e);
      }
      publish(new ServerEvent.ConnectionClosed(remoteAddress, cause));
    }

    private CompletionStage<Void> done() {
      return CompletableFuture.completedFuture(null);
    }

    /**
     * Bridges {@link Session} events for this connection. Every inbound ASDU, data-transfer
     * transition, and close — including a transport connection loss routed here by the session
     * assembly — arrives through this single sink.
     */
    private final class SessionEvents implements Session.Events {

      @Override
      public void onAsdu(Asdu asdu) {
        // Invoked under the session lock; hand off to the serial dispatch chain.
        dispatch(asdu);
      }

      @Override
      public void onDataTransferStateChanged(boolean started) {
        publish(
            started
                ? new ServerEvent.DataTransferStarted(remoteAddress)
                : new ServerEvent.DataTransferStopped(remoteAddress));
      }

      @Override
      public void onClosed(@Nullable Throwable cause) {
        close(cause);
      }
    }

    /** The per-request {@link ServerContext} for this connection. */
    private final class Context implements ServerContext {

      private final CommonAddress commonAddress;

      Context(CommonAddress commonAddress) {
        this.commonAddress = commonAddress;
      }

      @Override
      public SocketAddress remoteAddress() {
        return remoteAddress;
      }

      @Override
      public Optional<Station> station() {
        return registry.station(commonAddress);
      }

      @Override
      public InterrogationResponse defaultInterrogation(InterrogationRequest request) {
        Objects.requireNonNull(request, "request");
        Optional<Station> station = registry.station(request.station());
        if (station.isEmpty()) {
          return InterrogationResponse.reject(Cause.UNKNOWN_COMMON_ADDRESS);
        }
        List<InformationObject> objects = new ArrayList<>();
        for (Station.InterrogatedPoint point : station.get().select(request.qualifier())) {
          objects.add(
              MonitorMapping.toMonitorObject(
                  point.type(), point.address(), point.value(), config.timeTagStyle()));
        }
        return InterrogationResponse.of(objects);
      }

      @Override
      public ReadResponse defaultRead(ReadRequest request) {
        Objects.requireNonNull(request, "request");
        Optional<Station> station = registry.station(request.point().commonAddress());
        if (station.isEmpty()) {
          return ReadResponse.reject(Cause.UNKNOWN_COMMON_ADDRESS);
        }
        InformationObjectAddress ioa = request.point().objectAddress();
        Optional<PointDefinition<?>> definition = station.get().definition(ioa);
        Optional<PointValue<?>> value = station.get().currentValue(ioa);
        if (definition.isEmpty() || value.isEmpty()) {
          return ReadResponse.reject(Cause.UNKNOWN_INFORMATION_OBJECT_ADDRESS);
        }
        if (!definition.get().hasCapability(PointCapability.READABLE)) {
          return ReadResponse.reject(Cause.UNKNOWN_INFORMATION_OBJECT_ADDRESS);
        }
        InformationObject object =
            MonitorMapping.toMonitorObject(
                definition.get().type(), ioa, value.get(), config.timeTagStyle());
        return ReadResponse.of(object);
      }

      @Override
      public void send(Asdu asdu) {
        Objects.requireNonNull(asdu, "asdu");
        ServerConnection.this.send(asdu);
      }
    }
  }

  // --- Static helpers -------------------------------------------------------------------------

  /**
   * Returns the time-tag style that matches a monitor object's concrete record, so a reported
   * object is carried by the matching monitor type identification.
   *
   * @param object the monitor object.
   * @return the matching time-tag style.
   */
  private static TimeTagStyle styleOf(InformationObject object) {
    return MonitorTypes.styleOf(object);
  }

  /**
   * Returns the interrogation-response cause of transmission for a qualifier of interrogation.
   *
   * <p>The station qualifier ({@code 20}) maps to {@link Cause#INTERROGATED_BY_STATION}; group
   * qualifiers ({@code 21}..{@code 36}) map to the corresponding {@code INTERROGATED_BY_GROUP_n}
   * cause. Any other qualifier value falls back to {@link Cause#INTERROGATED_BY_STATION}.
   *
   * @param qoi the qualifier of interrogation.
   * @return the interrogation-response cause to carry on the reported monitor ASDUs.
   */
  private static Cause interrogationCause(QualifierOfInterrogation qoi) {
    int value = qoi.value().intValue();
    int station = QualifierOfInterrogation.STATION.value().intValue();
    int firstGroup = QualifierOfInterrogation.GROUP_1.value().intValue();
    if (value == station) {
      return Cause.INTERROGATED_BY_STATION;
    }
    int group = value - firstGroup + 1;
    return switch (group) {
      case 1 -> Cause.INTERROGATED_BY_GROUP_1;
      case 2 -> Cause.INTERROGATED_BY_GROUP_2;
      case 3 -> Cause.INTERROGATED_BY_GROUP_3;
      case 4 -> Cause.INTERROGATED_BY_GROUP_4;
      case 5 -> Cause.INTERROGATED_BY_GROUP_5;
      case 6 -> Cause.INTERROGATED_BY_GROUP_6;
      case 7 -> Cause.INTERROGATED_BY_GROUP_7;
      case 8 -> Cause.INTERROGATED_BY_GROUP_8;
      case 9 -> Cause.INTERROGATED_BY_GROUP_9;
      case 10 -> Cause.INTERROGATED_BY_GROUP_10;
      case 11 -> Cause.INTERROGATED_BY_GROUP_11;
      case 12 -> Cause.INTERROGATED_BY_GROUP_12;
      case 13 -> Cause.INTERROGATED_BY_GROUP_13;
      case 14 -> Cause.INTERROGATED_BY_GROUP_14;
      case 15 -> Cause.INTERROGATED_BY_GROUP_15;
      case 16 -> Cause.INTERROGATED_BY_GROUP_16;
      default -> Cause.INTERROGATED_BY_STATION;
    };
  }

  /**
   * Returns the counter-interrogation-response cause of transmission for a QCC request field.
   *
   * <p>RQT values {@code 1..4} map to the corresponding {@code REQUESTED_BY_GROUP_n_COUNTER} cause;
   * RQT {@code 5} (general) and any other value map to {@link Cause#REQUESTED_BY_GENERAL_COUNTER}.
   *
   * @param rqt the QCC request field.
   * @return the cause to carry on the reported integrated-totals ASDUs.
   */
  private static Cause counterCause(int rqt) {
    return switch (rqt) {
      case 1 -> Cause.REQUESTED_BY_GROUP_1_COUNTER;
      case 2 -> Cause.REQUESTED_BY_GROUP_2_COUNTER;
      case 3 -> Cause.REQUESTED_BY_GROUP_3_COUNTER;
      case 4 -> Cause.REQUESTED_BY_GROUP_4_COUNTER;
      default -> Cause.REQUESTED_BY_GENERAL_COUNTER;
    };
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
              runnable, "iec60870-server-scheduler-" + poolId + "-" + threadId.getAndIncrement());
      thread.setDaemon(true);
      return thread;
    }
  }
}
