package com.digitalpetri.iec60870.cs104;

import com.digitalpetri.iec60870.AsduDecodeException;
import com.digitalpetri.iec60870.Iec60870Exception;
import com.digitalpetri.iec60870.OutboundQueuePolicy;
import com.digitalpetri.iec60870.ProtocolProfile;
import com.digitalpetri.iec60870.SessionSettings;
import com.digitalpetri.iec60870.UndecodableAsduPolicy;
import com.digitalpetri.iec60870.asdu.Asdu;
import com.digitalpetri.iec60870.session.Session;
import com.digitalpetri.iec60870.transport.ClientTransport;
import com.digitalpetri.iec60870.transport.ServerTransportConnection;
import com.digitalpetri.iec60870.transport.TransportListener;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.UnpooledByteBufAllocator;
import java.util.Objects;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;

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
 *   <li>a failed outbound send, a transport connection loss, an inbound framing error, or (under
 *       {@link UndecodableAsduPolicy#STRICT}) an undecodable ASDU closes the session and routes the
 *       cause to {@link Session.Events#onConnectionLost(Throwable)} (distinct from the session's
 *       own protocol-error {@link Session.Events#onClosed(Throwable)} self-close).
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
 */
public final class Cs104Binding {

  private final ApciSettings settings;
  private final ProtocolProfile profile;
  private final ByteBufAllocator allocator;
  private final UndecodableAsduPolicy undecodableAsduPolicy;

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
    Objects.requireNonNull(settings, "settings");
    this.settings = downcast(settings);
    this.profile = Objects.requireNonNull(profile, "profile");
    this.allocator = allocator != null ? allocator : UnpooledByteBufAllocator.DEFAULT;
    this.undecodableAsduPolicy =
        Objects.requireNonNull(undecodableAsduPolicy, "undecodableAsduPolicy");
  }

  /**
   * Assembles a CLIENT-role {@link Session} bound to {@code transport}.
   *
   * <p>The session's outbound APDUs are framed and written to {@code transport}; inbound frames
   * from the transport's {@link TransportListener} are deframed into the session. {@link
   * Session#setInboundPaused(boolean)} pauses and resumes the transport's reads. A failed send or a
   * transport connection loss closes the session and notifies {@code events}; a framing error also
   * closes the current transport connection without performing an intentional disconnect.
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
        transport::closeConnection);
  }

  /**
   * Assembles a SERVER-role {@link Session} bound to one accepted {@code connection}.
   *
   * <p>The session's outbound APDUs are framed and written to {@code connection}; inbound frames
   * from the connection's {@link TransportListener} are deframed into the session. A failed send or
   * a connection loss closes the session and notifies {@code events}. The supplied outbound-queue
   * bound and overflow policy are applied to the assembled session.
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
    Objects.requireNonNull(connection, "connection");
    Objects.requireNonNull(events, "events");
    Objects.requireNonNull(scheduler, "scheduler");
    Objects.requireNonNull(queuePolicy, "queuePolicy");

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
        connection::close);
  }

  private Session assemble(
      ApciSession.Role role,
      ScheduledExecutorService scheduler,
      Session.Events events,
      FrameSink frameSink,
      Consumer<TransportListener> listenerSink,
      Consumer<Boolean> readPause,
      @Nullable ServerQueueConfig queueConfig,
      Runnable closeConnection) {

    // The Output and listener below both reference the session after construction; hold it in a
    // one-element array so they can close it on a failed write or transport loss.
    ApciSession[] holder = new ApciSession[1];

    ApciSession.Output output =
        new ApciSession.Output() {
          @Override
          public void send(Apdu apdu) {
            sendAsync(apdu);
          }

          @Override
          public CompletionStage<Void> sendAsync(Apdu apdu) {
            return write(ApduFramer.encode(apdu, profile, allocator));
          }

          @Override
          public CompletionStage<Void> sendRawAsync(ControlField.TypeI control, byte[] asdu) {
            return write(ApduFramer.encode(control, asdu, allocator));
          }

          @Override
          public void setReadPaused(boolean paused) {
            readPause.accept(paused);
          }

          private CompletionStage<Void> write(ByteBuf buffer) {
            return frameSink
                .send(buffer)
                .whenComplete(
                    (ignored, error) -> {
                      if (error != null) {
                        holder[0].close();
                        events.onConnectionLost(error);
                      }
                    });
          }
        };

    ApciSession session =
        queueConfig == null
            ? new ApciSession(role, settings, scheduler, output, events)
            : new ApciSession(
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
            ApduFramer.Header header;
            try {
              header = ApduFramer.decodeHeader(frame);
            } catch (RuntimeException decodeFailure) {
              closeOnDecodeFailure(decodeFailure);
              return;
            }
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

          private void closeOnDecodeFailure(RuntimeException decodeFailure) {
            session.close();
            events.onConnectionLost(decodeFailure);
            closeConnection.run();
          }

          @Override
          public void onConnectionLost(@Nullable Throwable cause) {
            session.close();
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
