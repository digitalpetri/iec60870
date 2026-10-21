package com.digitalpetri.iec60870.cs104;

import com.digitalpetri.iec60870.AsduDecodeException;
import com.digitalpetri.iec60870.Iec60870Exception;
import com.digitalpetri.iec60870.OutboundQueuePolicy;
import com.digitalpetri.iec60870.ProtocolProfile;
import com.digitalpetri.iec60870.SessionSettings;
import com.digitalpetri.iec60870.UndecodableAsduPolicy;
import com.digitalpetri.iec60870.asdu.Asdu;
import com.digitalpetri.iec60870.diagnostics.Direction;
import com.digitalpetri.iec60870.diagnostics.LoggingContext;
import com.digitalpetri.iec60870.session.Session;
import com.digitalpetri.iec60870.transport.ClientTransport;
import com.digitalpetri.iec60870.transport.ServerTransportConnection;
import com.digitalpetri.iec60870.transport.TransportListener;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.UnpooledByteBufAllocator;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Assembles a complete IEC 60870-5-104 {@link Session} over a core octet transport handle.
 *
 * <p>This is the assembly-side counterpart of the {@link ApduFramer} framing seam: given an octet
 * transport handle (a {@link ClientTransport} for the controlling station, or a {@link
 * ServerTransportConnection} for one accepted connection on a controlled station) plus the APCI
 * settings, the wire profile, an allocator, the facade's {@link Session.Events} sink, and the
 * shared scheduler, it wires an {@link ApciSession} to the transport:
 *
 * <ul>
 *   <li>the session's {@link ApciSession.Output} frames each outbound {@link Apdu} with {@link
 *       ApduFramer#encode(Apdu, ProtocolProfile, ByteBufAllocator)} and hands the resulting
 *       whole-frame {@link ByteBuf} to the transport's {@code send};
 *   <li>a registered {@link TransportListener} decodes the APCI of each inbound whole-frame {@link
 *       ByteBuf} with {@link ApduFramer#decodeHeader(ByteBuf)}, then decodes the ASDU of an
 *       I-format frame, and feeds the result to {@link ApciSession#onApdu(Apdu)};
 *   <li>an I-format frame whose ASDU cannot be decoded goes to {@link
 *       ApciSession#onUndecodableIFrame(ControlField.TypeI, byte[], Iec60870Exception)} under the
 *       default {@link UndecodableAsduPolicy#REPORT_AND_CONTINUE} policy, so the session
 *       acknowledges it and reports its octets while the connection stays open;
 *   <li>a failed outbound send or a transport connection loss routes the cause to {@link
 *       Session.Events#onConnectionLost(Throwable)} (distinct from the session's own protocol-error
 *       {@link Session.Events#onClosed(Throwable)} self-close); the receiver closes the session;
 *   <li>an inbound framing error, or (under {@link UndecodableAsduPolicy#STRICT}) an undecodable
 *       ASDU, closes the transport connection and then routes the cause to {@link
 *       Session.Events#onConnectionLost(Throwable)}; the receiver closes the session.
 * </ul>
 *
 * <p>This is the single, permanent home for the {@code Apdu}&lt;-&gt;octet wiring that the {@code
 * TcpIec104Client}/{@code TcpIec104Server} builders used to host inline. It lives in {@code cs104}
 * and depends only on {@code cs104} + core + {@code netty-buffer}; it imports nothing from the
 * transport-tcp or application modules, so any octet transport — including a future serial
 * transport — can be bound to a 104 session through it.
 *
 * <p><b>Allocator.</b> The binding holds the {@link ByteBufAllocator} used for outbound framing,
 * defaulting to {@link UnpooledByteBufAllocator#DEFAULT} when none is supplied. The frame buffers
 * it allocates are handed to the transport's {@code send}, which owns and releases them.
 *
 * <p><b>Diagnostics.</b> A binding constructed with an {@link ApciDiagnostics} listener reports
 * every APDU it frames ({@link ApciDiagnostics#onApduSent(ControlField, int)}, before the transport
 * takes ownership of the buffer) and every frame whose control field decodes ({@link
 * ApciDiagnostics#onApduReceived(ControlField, int)}), each decode failure with the offending frame
 * ({@link ApciDiagnostics#onDecodeError(ByteBuf, RuntimeException)}), and, while {@link
 * ApciDiagnostics#traceFrames()} returns {@code true}, a read-only view of each raw frame. The
 * trace and decode-error callbacks run with the connection's logging context in the MDC. It hands
 * the same listener and its logging context to the assembled {@link ApciSession}. A server binding
 * adds {@value LoggingContext#REMOTE_KEY} with each accepted connection's remote address.
 */
public final class Cs104Binding {

  private static final Logger LOGGER = LoggerFactory.getLogger(Cs104Binding.class);

  private final ApciSettings settings;
  private final ProtocolProfile profile;
  private final ByteBufAllocator allocator;
  private final UndecodableAsduPolicy undecodableAsduPolicy;
  private final ApciDiagnostics diagnostics;
  private final LoggingContext loggingContext;

  /**
   * Creates a binding with the {@link UnpooledByteBufAllocator#DEFAULT default unpooled allocator}.
   *
   * @param settings the APCI flow-control parameters for the assembled sessions; if a neutral
   *     {@link SessionSettings} is supplied it must be an {@link ApciSettings}.
   * @param profile the wire profile that governs ASDU field widths during framing.
   */
  public Cs104Binding(SessionSettings settings, ProtocolProfile profile) {
    this(settings, profile, UnpooledByteBufAllocator.DEFAULT);
  }

  /**
   * Creates a binding with an explicit outbound-framing allocator.
   *
   * @param settings the APCI flow-control parameters for the assembled sessions; if a neutral
   *     {@link SessionSettings} is supplied it must be an {@link ApciSettings}.
   * @param profile the wire profile that governs ASDU field widths during framing.
   * @param allocator the allocator used to obtain outbound frame buffers, or {@code null} for the
   *     default unpooled allocator.
   * @throws IllegalArgumentException if {@code settings} is not an {@link ApciSettings}.
   */
  public Cs104Binding(
      SessionSettings settings, ProtocolProfile profile, @Nullable ByteBufAllocator allocator) {
    this(settings, profile, allocator, UndecodableAsduPolicy.DEFAULT);
  }

  /**
   * Creates a binding with an explicit outbound-framing allocator and undecodable-ASDU policy.
   *
   * @param settings the APCI flow-control parameters for the assembled sessions; if a neutral
   *     {@link SessionSettings} is supplied it must be an {@link ApciSettings}.
   * @param profile the wire profile that governs ASDU field widths during framing.
   * @param allocator the allocator used to obtain outbound frame buffers, or {@code null} for the
   *     default unpooled allocator.
   * @param undecodableAsduPolicy what the assembled sessions do with a correctly framed I-frame
   *     whose ASDU cannot be decoded.
   * @throws IllegalArgumentException if {@code settings} is not an {@link ApciSettings}.
   */
  public Cs104Binding(
      SessionSettings settings,
      ProtocolProfile profile,
      @Nullable ByteBufAllocator allocator,
      UndecodableAsduPolicy undecodableAsduPolicy) {
    this(settings, profile, allocator, undecodableAsduPolicy, ApciDiagnostics.NONE, Map.of());
  }

  /**
   * Creates a binding with an explicit outbound-framing allocator, undecodable-ASDU policy,
   * diagnostics listener, and logging context.
   *
   * @param settings the APCI flow-control parameters for the assembled sessions; if a neutral
   *     {@link SessionSettings} is supplied it must be an {@link ApciSettings}.
   * @param profile the wire profile that governs ASDU field widths during framing.
   * @param allocator the allocator used to obtain outbound frame buffers, or {@code null} for the
   *     default unpooled allocator.
   * @param undecodableAsduPolicy what the assembled sessions do with a correctly framed I-frame
   *     whose ASDU cannot be decoded.
   * @param diagnostics the listener for the assembled sessions' frame and APCI events, shared by
   *     every session this binding assembles unless {@link #bindServer(ServerTransportConnection,
   *     Session.Events, ScheduledExecutorService, int, OutboundQueuePolicy, ApciDiagnostics)}
   *     supplies one per connection; exceptions it throws are caught and logged.
   * @param loggingContext the MDC entries set around the log lines of the assembled sessions.
   * @throws IllegalArgumentException if {@code settings} is not an {@link ApciSettings}.
   */
  public Cs104Binding(
      SessionSettings settings,
      ProtocolProfile profile,
      @Nullable ByteBufAllocator allocator,
      UndecodableAsduPolicy undecodableAsduPolicy,
      ApciDiagnostics diagnostics,
      Map<String, String> loggingContext) {
    Objects.requireNonNull(settings, "settings");
    this.settings = downcast(settings);
    this.profile = Objects.requireNonNull(profile, "profile");
    this.allocator = allocator != null ? allocator : UnpooledByteBufAllocator.DEFAULT;
    this.undecodableAsduPolicy =
        Objects.requireNonNull(undecodableAsduPolicy, "undecodableAsduPolicy");
    this.diagnostics = ApciDiagnostics.of(Objects.requireNonNull(diagnostics, "diagnostics"));
    this.loggingContext = LoggingContext.of(loggingContext);
  }

  /**
   * Assembles a CLIENT-role {@link Session} bound to {@code transport}.
   *
   * <p>The session's outbound APDUs are framed and written to {@code transport}; inbound frames
   * from the transport's {@link TransportListener} are deframed into the session. {@link
   * Session#setInboundPaused(boolean)} pauses and resumes the transport's reads. A failed send or a
   * transport connection loss is reported to {@code events}, which closes the session; a framing
   * error first closes the current transport connection.
   *
   * @param transport the octet client transport to bind.
   * @param events the facade's event sink; the assembled session reports delivered ASDUs, lifecycle
   *     transitions, and closure through it.
   * @param scheduler the shared scheduler for the session's {@code t1}/{@code t2}/{@code t3}
   *     timers.
   * @return the assembled session.
   */
  public Session bindClient(
      ClientTransport transport, Session.Events events, ScheduledExecutorService scheduler) {
    Objects.requireNonNull(transport, "transport");
    Objects.requireNonNull(events, "events");
    Objects.requireNonNull(scheduler, "scheduler");

    return assemble(
        ApciSession.Role.CLIENT,
        scheduler,
        events,
        transport::send,
        transport::setListener,
        transport::setReadPaused,
        null,
        transport::closeConnection,
        diagnostics,
        loggingContext);
  }

  /**
   * Assembles a SERVER-role {@link Session} bound to one accepted {@code connection}.
   *
   * <p>The session's outbound APDUs are framed and written to {@code connection}; inbound frames
   * from the connection's {@link TransportListener} are deframed into the session. A failed send or
   * a connection loss is reported to {@code events}, which closes the session. The supplied
   * outbound-queue bound and overflow policy are applied to the assembled session.
   *
   * @param connection the accepted octet transport connection to bind.
   * @param events the facade's event sink for this connection.
   * @param scheduler the shared scheduler for the session's {@code t1}/{@code t2}/{@code t3}
   *     timers.
   * @param maxOutboundQueue the maximum number of ASDUs held while the window is closed, or {@code
   *     0} for an unbounded queue.
   * @param queuePolicy the action taken when a bounded outbound queue overflows.
   * @return the assembled session.
   */
  public Session bindServer(
      ServerTransportConnection connection,
      Session.Events events,
      ScheduledExecutorService scheduler,
      int maxOutboundQueue,
      OutboundQueuePolicy queuePolicy) {
    return bindServer(connection, events, scheduler, maxOutboundQueue, queuePolicy, diagnostics);
  }

  /**
   * Assembles a SERVER-role {@link Session} bound to one accepted {@code connection}, reporting to
   * a diagnostics listener chosen for that connection.
   *
   * <p>Behaves like {@link #bindServer(ServerTransportConnection, Session.Events,
   * ScheduledExecutorService, int, OutboundQueuePolicy)}, except that {@code diagnostics} replaces
   * the binding's listener for this connection.
   *
   * @param connection the accepted octet transport connection to bind.
   * @param events the facade's event sink for this connection.
   * @param scheduler the shared scheduler for the session's {@code t1}/{@code t2}/{@code t3}
   *     timers.
   * @param maxOutboundQueue the maximum number of ASDUs held while the window is closed, or {@code
   *     0} for an unbounded queue.
   * @param queuePolicy the action taken when a bounded outbound queue overflows.
   * @param diagnostics the listener for this connection's frame and APCI events.
   * @return the assembled session.
   */
  public Session bindServer(
      ServerTransportConnection connection,
      Session.Events events,
      ScheduledExecutorService scheduler,
      int maxOutboundQueue,
      OutboundQueuePolicy queuePolicy,
      ApciDiagnostics diagnostics) {
    Objects.requireNonNull(connection, "connection");
    Objects.requireNonNull(events, "events");
    Objects.requireNonNull(scheduler, "scheduler");
    Objects.requireNonNull(queuePolicy, "queuePolicy");
    Objects.requireNonNull(diagnostics, "diagnostics");

    // The server connection SPI exposes close() (a per-connection teardown), exactly matching the
    // Netty server's decode-failure behavior: tear down just this one accepted connection. Use it
    // as the per-failure close handle so a malformed inbound frame closes both the session and the
    // underlying connection on every transport.
    return assemble(
        ApciSession.Role.SERVER,
        scheduler,
        events,
        connection::send,
        connection::setListener,
        paused -> {},
        new ServerQueueConfig(maxOutboundQueue, queuePolicy),
        connection::close,
        ApciDiagnostics.of(diagnostics),
        loggingContext.withRemote(connection.remoteAddress()));
  }

  private Session assemble(
      ApciSession.Role role,
      ScheduledExecutorService scheduler,
      Session.Events events,
      FrameSink frameSink,
      Consumer<TransportListener> listenerSink,
      Consumer<Boolean> readPause,
      @Nullable ServerQueueConfig queueConfig,
      Runnable closeConnection,
      ApciDiagnostics diagnostics,
      LoggingContext loggingContext) {

    // A failed write or a transport loss is reported and nothing else: the receiver closes the
    // session
    // and may open a replacement on it from the report, which a later close would break.

    ApciSession.Output output =
        new ApciSession.Output() {
          @Override
          public void send(Apdu apdu) {
            sendAsync(apdu);
          }

          @Override
          public CompletionStage<Void> sendAsync(Apdu apdu) {
            return write(apdu.control(), ApduFramer.encode(apdu, profile, allocator));
          }

          @Override
          public CompletionStage<Void> sendRawAsync(ControlField.TypeI control, byte[] asdu) {
            return write(control, ApduFramer.encode(control, asdu, allocator));
          }

          @Override
          public void setReadPaused(boolean paused) {
            readPause.accept(paused);
          }

          private CompletionStage<Void> write(ControlField control, ByteBuf buffer) {
            // Report before send(), which takes ownership of the buffer and may release it.
            diagnostics.onApduSent(control, buffer.readableBytes());
            if (diagnostics.traceFrames()) {
              try (LoggingContext.Scope ignored = loggingContext.open()) {
                diagnostics.onFrame(Direction.OUT, buffer.asReadOnly());
              }
            }
            return frameSink
                .send(buffer)
                .whenComplete(
                    (ignored, error) -> {
                      if (error != null) {
                        events.onConnectionLost(error);
                      }
                    });
          }
        };

    ApciSession session =
        queueConfig == null
            ? new ApciSession(
                role,
                settings,
                scheduler,
                output,
                events,
                0,
                OutboundQueuePolicy.DROP_OLDEST,
                diagnostics,
                loggingContext)
            : new ApciSession(
                role,
                settings,
                scheduler,
                output,
                events,
                queueConfig.maxOutboundQueue(),
                queueConfig.queuePolicy(),
                diagnostics,
                loggingContext);

    listenerSink.accept(
        new TransportListener() {
          @Override
          public void onFrame(ByteBuf frame) {
            // Decode in two steps so a content error in the ASDU does not cost the APCI state. A
            // framing error (bad START/length/control field, or a body that does not fit the frame
            // format) leaves the byte stream or the APCI untrustworthy: route it to the SAME sink a
            // real transport loss uses (Session.Events.onConnectionLost) and tear the underlying
            // connection down. On the client side closeConnection() drops only the current
            // connection, and the facade reconnects per its policy; on the server side close()
            // tears down only the accepted connection.
            //
            // Once the control field is known, an I-frame whose ASDU cannot be decoded still has a
            // valid N(S)/N(R). Under REPORT_AND_CONTINUE the session runs its sequence checks,
            // acknowledges the frame, and reports the octets; under STRICT it is treated like a
            // framing error. The buffer is owned and released by the transport after onFrame
            // returns, so it is neither retained nor released here.
            int frameStart = frame.readerIndex();
            int frameLength = frame.readableBytes();
            if (diagnostics.traceFrames()) {
              try (LoggingContext.Scope ignored = loggingContext.open()) {
                diagnostics.onFrame(Direction.IN, frame.asReadOnly());
              }
            }
            ApduFramer.Header header;
            try {
              header = ApduFramer.decodeHeader(frame);
            } catch (RuntimeException decodeFailure) {
              reportDecodeError(frame, frameStart, frameLength, decodeFailure);
              closeOnDecodeFailure(decodeFailure);
              return;
            }
            diagnostics.onApduReceived(header.control(), frameLength);
            if (!(header.control() instanceof ControlField.TypeI control)) {
              session.onApdu(new Apdu(header.control(), null));
              return;
            }
            ByteBuf asduOctets = frame.readSlice(header.asduLength());
            int asduStart = asduOctets.readerIndex();
            Asdu asdu;
            try {
              asdu = Asdu.Serde.decode(profile, asduOctets);
            } catch (RuntimeException decodeFailure) {
              reportDecodeError(frame, frameStart, frameLength, decodeFailure);
              if (undecodableAsduPolicy == UndecodableAsduPolicy.STRICT) {
                closeOnDecodeFailure(decodeFailure);
                return;
              }
              byte[] octets = ByteBufUtil.getBytes(asduOctets, asduStart, header.asduLength());
              session.onUndecodableIFrame(control, octets, asIec60870Exception(decodeFailure));
              return;
            }
            session.onApdu(new Apdu(control, asdu));
          }

          private void reportDecodeError(
              ByteBuf frame, int frameStart, int frameLength, RuntimeException decodeFailure) {
            loggingContext.debug(
                LOGGER,
                "{}: undecodable frame ({} octets): {}",
                loggingContext.remote(),
                frameLength,
                decodeFailure.toString());
            // Lend the whole frame, whatever the failed decode already consumed.
            try (LoggingContext.Scope ignored = loggingContext.open()) {
              diagnostics.onDecodeError(
                  frame.slice(frameStart, frameLength).asReadOnly(), decodeFailure);
            }
          }

          /** Set while this listener closes the connection itself after a decode failure. */
          private volatile boolean closingOnDecodeFailure;

          private void closeOnDecodeFailure(RuntimeException decodeFailure) {
            // Close the connection, then report, then touch nothing: the facade closes the session
            // and may open a replacement from the report, which a later close of the connection or
            // the session would break. Closing the session first would fail a pending STARTDT
            // ahead of the report, with a less useful cause. A transport that reports this close
            // as a loss does so without the decode failure, so that report is ignored.
            closingOnDecodeFailure = true;
            try {
              closeConnection.run();
            } finally {
              closingOnDecodeFailure = false;
            }
            events.onConnectionLost(decodeFailure);
          }

          @Override
          public void onConnectionLost(@Nullable Throwable cause) {
            if (closingOnDecodeFailure) {
              return;
            }
            events.onConnectionLost(cause);
          }
        });

    return session;
  }

  private static Iec60870Exception asIec60870Exception(RuntimeException decodeFailure) {
    // Asdu.Serde.decode reports bad input only as AsduDecodeException or
    // UnsupportedAsduTypeException; wrap anything else so the event type stays uniform.
    return decodeFailure instanceof Iec60870Exception e
        ? e
        : new AsduDecodeException("ASDU decode failed: " + decodeFailure, decodeFailure);
  }

  private static ApciSettings downcast(SessionSettings settings) {
    if (settings instanceof ApciSettings apciSettings) {
      return apciSettings;
    }
    throw new IllegalArgumentException(
        "cs104 requires ApciSettings, got: " + settings.getClass().getName());
  }

  /** Sends one whole frame on the bound octet transport handle. */
  @FunctionalInterface
  private interface FrameSink {
    CompletionStage<Void> send(ByteBuf frame);
  }

  /** The SERVER-role outbound-queue bound and overflow policy. */
  private record ServerQueueConfig(int maxOutboundQueue, OutboundQueuePolicy queuePolicy) {}
}
