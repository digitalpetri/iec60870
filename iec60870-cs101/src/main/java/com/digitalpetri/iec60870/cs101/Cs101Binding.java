package com.digitalpetri.iec60870.cs101;

import com.digitalpetri.iec60870.OutboundQueuePolicy;
import com.digitalpetri.iec60870.ProtocolProfile;
import com.digitalpetri.iec60870.SessionSettings;
import com.digitalpetri.iec60870.session.Session;
import com.digitalpetri.iec60870.transport.ClientTransport;
import com.digitalpetri.iec60870.transport.ServerTransportConnection;
import com.digitalpetri.iec60870.transport.TransportListener;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.UnpooledByteBufAllocator;
import java.util.Objects;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;

/**
 * Assembles a complete IEC 60870-5-101 (FT1.2) {@link Session} over a core octet transport handle.
 *
 * <p>This is the assembly-side counterpart of the {@link Ft12Framer} framing seam, and the FT1.2
 * peer of the 104 {@code Cs104Binding}: given an octet transport handle (a {@link ClientTransport}
 * for the controlling station, or a {@link ServerTransportConnection} for one accepted connection
 * on a controlled station) plus the link settings, the wire profile, an allocator, the facade's
 * {@link Session.Events} sink, and the shared scheduler, it wires an {@link Ft12LinkLayer} to the
 * transport:
 *
 * <ul>
 *   <li>the session's {@link Ft12LinkLayer.Output} frames each outbound {@link Ft12Frame} with
 *       {@link Ft12Framer#encode(Ft12Frame, ProtocolProfile, int, ByteBufAllocator)} and hands the
 *       resulting whole-frame {@link ByteBuf} to the transport's {@code send};
 *   <li>a registered {@link TransportListener} deframes each inbound whole-frame {@link ByteBuf}
 *       with {@link Ft12Framer#decode(ProtocolProfile, int, ByteBuf)} and feeds the {@link
 *       Ft12Frame} to {@link Ft12LinkLayer#onFrame(Ft12Frame)};
 *   <li>a failed outbound send, a transport connection loss, or an inbound decode failure closes
 *       the session and routes the cause to {@link Session.Events#onConnectionLost(Throwable)}
 *       (distinct from the session's own protocol-error {@link Session.Events#onClosed(Throwable)}
 *       self-close).
 * </ul>
 *
 * <p>This is the single, permanent home for the {@code Ft12Frame}&lt;-&gt;octet wiring that the
 * {@code SerialIec101Client}/{@code SerialIec101Server} builders host. It lives in {@code cs101}
 * and depends only on {@code cs101} + core + {@code netty-buffer}; it imports nothing from the
 * transport-serial or application modules, so any octet transport — including a future TCP
 * transport carrying 101 — can be bound to an FT1.2 session through it.
 *
 * <p><b>Allocator.</b> The binding holds the {@link ByteBufAllocator} used for outbound framing,
 * defaulting to {@link UnpooledByteBufAllocator#DEFAULT} when none is supplied. The frame buffers
 * it allocates are handed to the transport's {@code send}, which owns and releases them.
 */
public final class Cs101Binding {

  private final LinkSettings settings;
  private final ProtocolProfile profile;
  private final ByteBufAllocator allocator;
  private final int linkAddressLength;

  /**
   * Creates a binding with the {@link UnpooledByteBufAllocator#DEFAULT default unpooled allocator}.
   *
   * @param settings the FT1.2 link parameters for the assembled sessions; if a neutral {@link
   *     SessionSettings} is supplied it must be a {@link LinkSettings}.
   * @param profile the wire profile that governs ASDU field widths during framing.
   */
  public Cs101Binding(SessionSettings settings, ProtocolProfile profile) {
    this(settings, profile, UnpooledByteBufAllocator.DEFAULT);
  }

  /**
   * Creates a binding with an explicit outbound-framing allocator.
   *
   * @param settings the FT1.2 link parameters for the assembled sessions; if a neutral {@link
   *     SessionSettings} is supplied it must be a {@link LinkSettings}.
   * @param profile the wire profile that governs ASDU field widths during framing.
   * @param allocator the allocator used to obtain outbound frame buffers, or {@code null} for the
   *     default unpooled allocator.
   * @throws IllegalArgumentException if {@code settings} is not a {@link LinkSettings}.
   */
  public Cs101Binding(
      SessionSettings settings, ProtocolProfile profile, @Nullable ByteBufAllocator allocator) {
    Objects.requireNonNull(settings, "settings");
    this.settings = downcast(settings);
    this.profile = Objects.requireNonNull(profile, "profile");
    this.allocator = allocator != null ? allocator : UnpooledByteBufAllocator.DEFAULT;
    this.linkAddressLength = this.settings.linkAddressLength();
  }

  /**
   * Assembles a CLIENT-role {@link Session} bound to {@code transport}.
   *
   * <p>The session's outbound FT1.2 frames are framed and written to {@code transport}; inbound
   * frames from the transport's {@link TransportListener} are deframed into the session. A failed
   * send or a transport connection loss closes the session and notifies {@code events}; a malformed
   * inbound frame also closes the current transport connection without performing an intentional
   * disconnect.
   *
   * @param transport the octet client transport to bind.
   * @param events the facade's event sink; the assembled session reports delivered ASDUs, lifecycle
   *     transitions, and closure through it.
   * @param scheduler the shared scheduler for the session's confirm and link-state timers.
   * @return the assembled session.
   */
  public Session bindClient(
      ClientTransport transport, Session.Events events, ScheduledExecutorService scheduler) {
    Objects.requireNonNull(transport, "transport");
    Objects.requireNonNull(events, "events");
    Objects.requireNonNull(scheduler, "scheduler");

    return assemble(
        Ft12LinkLayer.Role.CLIENT,
        scheduler,
        events,
        transport::send,
        transport::setListener,
        null,
        transport::closeConnection);
  }

  /**
   * Assembles a SERVER-role {@link Session} bound to one accepted {@code connection}.
   *
   * <p>The session's outbound FT1.2 frames are framed and written to {@code connection}; inbound
   * frames from the connection's {@link TransportListener} are deframed into the session. A failed
   * send or a connection loss closes the session and notifies {@code events}. The supplied
   * outbound-queue bound and overflow policy are applied to the assembled session.
   *
   * @param connection the accepted octet transport connection to bind.
   * @param events the facade's event sink for this connection.
   * @param scheduler the shared scheduler for the session's confirm and link-state timers.
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
    Objects.requireNonNull(connection, "connection");
    Objects.requireNonNull(events, "events");
    Objects.requireNonNull(scheduler, "scheduler");
    Objects.requireNonNull(queuePolicy, "queuePolicy");

    // The server connection SPI exposes close() (a per-connection teardown), exactly matching the
    // Netty server's decode-failure behavior: tear down just this one accepted connection. Use it
    // as the per-failure close handle so a malformed inbound frame closes both the session and the
    // underlying connection on every transport.
    return assemble(
        Ft12LinkLayer.Role.SERVER,
        scheduler,
        events,
        connection::send,
        connection::setListener,
        new ServerQueueConfig(maxOutboundQueue, queuePolicy),
        connection::close);
  }

  private Session assemble(
      Ft12LinkLayer.Role role,
      ScheduledExecutorService scheduler,
      Session.Events events,
      FrameSink frameSink,
      Consumer<TransportListener> listenerSink,
      @Nullable ServerQueueConfig queueConfig,
      Runnable closeConnection) {

    // The Output and listener below both reference the session after construction; hold it in a
    // one-element array so they can close it on a failed write or transport loss.
    Ft12LinkLayer[] holder = new Ft12LinkLayer[1];

    Ft12LinkLayer.Output output =
        frame -> {
          // Frame the FT1.2 frame into a whole-frame ByteBuf and hand it to the octet transport,
          // which owns and releases the buffer. A failed write closes the session and routes the
          // loss to the facade through Session.Events.onConnectionLost.
          ByteBuf buffer = Ft12Framer.encode(frame, profile, linkAddressLength, allocator);
          frameSink
              .send(buffer)
              .whenComplete(
                  (ignored, error) -> {
                    if (error != null) {
                      holder[0].close();
                      events.onConnectionLost(error);
                    }
                  });
        };

    Ft12LinkLayer session =
        queueConfig == null
            ? new Ft12LinkLayer(role, settings, scheduler, output, events)
            : new Ft12LinkLayer(
                role,
                settings,
                scheduler,
                output,
                events,
                queueConfig.maxOutboundQueue(),
                queueConfig.queuePolicy());
    holder[0] = session;

    listenerSink.accept(
        new TransportListener() {
          @Override
          public void onFrame(ByteBuf frame) {
            // Deframe and dispatch inbound bytes. A malformed or undecodable frame makes
            // Ft12Framer.decode throw a decode-time RuntimeException, such as AsduDecodeException
            // for a bad start/length/checksum/end octet or a malformed embedded ASDU,
            // UnsupportedAsduTypeException for an undefined TypeID, or IndexOutOfBoundsException
            // for
            // a short/truncated read. Guard the deframe-and-dispatch path so the failure does not
            // escape back out the transport's delivery stack: route it to the SAME sink a real
            // transport loss uses (Session.Events.onConnectionLost) and tear the underlying
            // connection down, converging every transport on the existing close handling.
            //
            // This reproduces, on every transport, the behavior on a decode failure: close the
            // session, publish a single ConnectionClosed carrying the decode cause, and close the
            // underlying connection. On the client side closeConnection() leaves persistent
            // transports free to reconnect; on the server side close() tears down only the accepted
            // connection. The buffer is owned and released by the transport after onFrame returns
            // (the SPI's inbound ownership contract), so it is neither retained nor released here.
            Ft12Frame ft12Frame;
            try {
              ft12Frame = Ft12Framer.decode(profile, linkAddressLength, frame);
            } catch (RuntimeException decodeFailure) {
              session.close();
              events.onConnectionLost(decodeFailure);
              closeConnection.run();
              return;
            }
            session.onFrame(ft12Frame);
          }

          @Override
          public void onConnectionLost(@Nullable Throwable cause) {
            session.close();
            events.onConnectionLost(cause);
          }
        });

    return session;
  }

  private static LinkSettings downcast(SessionSettings settings) {
    if (settings instanceof LinkSettings linkSettings) {
      return linkSettings;
    }
    throw new IllegalArgumentException(
        "cs101 requires LinkSettings, got: " + settings.getClass().getName());
  }

  /** Sends one whole frame on the bound octet transport handle. */
  @FunctionalInterface
  private interface FrameSink {
    CompletionStage<Void> send(ByteBuf frame);
  }

  /** The SERVER-role outbound-queue bound and overflow policy. */
  private record ServerQueueConfig(int maxOutboundQueue, OutboundQueuePolicy queuePolicy) {}
}
