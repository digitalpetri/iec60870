package com.digitalpetri.iec104.transport.tcp;

import com.digitalpetri.iec104.ProtocolProfile;
import com.digitalpetri.iec104.TlsOptions;
import com.digitalpetri.iec104.transport.TransportListener;
import io.netty.channel.Channel;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.ssl.SslHandler;
import java.util.function.Supplier;
import javax.net.ssl.SSLEngine;
import org.jspecify.annotations.Nullable;

/**
 * Builds the fixed IEC 60870-5-104 channel pipeline shared by the client and server transports.
 *
 * <p>The pipeline is assembled in a single safe order so security and framing cannot be bypassed by
 * downstream handlers:
 *
 * <ol>
 *   <li>{@link SslHandler} — present only when TLS is configured; TLS terminates first so all
 *       subsequent handlers see plaintext.
 *   <li>{@link Iec104FrameDecoder} — length-delimited APDU framing and decode to {@code Apdu}.
 *   <li>{@link Iec104FrameEncoder} — encode outbound {@code Apdu} back onto the wire.
 *   <li>{@link InboundApduHandler} — terminal handler forwarding to the {@link TransportListener}.
 * </ol>
 *
 * <p>This class is a stateless builder of pipeline handlers and is never instantiated.
 */
final class Iec104Pipeline {

  /** The pipeline name of the {@link SslHandler}, present only when TLS is enabled. */
  static final String SSL_HANDLER = "ssl";

  /** The pipeline name of the {@link Iec104FrameDecoder}. */
  static final String FRAME_DECODER = "iec104-frame-decoder";

  /** The pipeline name of the {@link Iec104FrameEncoder}. */
  static final String FRAME_ENCODER = "iec104-frame-encoder";

  /** The pipeline name of the terminal {@link InboundApduHandler}. */
  static final String INBOUND_HANDLER = "iec104-inbound";

  private Iec104Pipeline() {}

  /**
   * Installs the IEC 104 handlers on {@code channel} in the fixed safe order.
   *
   * <p>When {@code tlsOptions} is non-null, an {@link SslHandler} created from the supplied {@link
   * javax.net.ssl.SSLContext} is added first. The caller decides the engine's client-or-server mode
   * and (for servers) whether client authentication is required.
   *
   * @param channel the channel whose pipeline is configured.
   * @param profile the protocol profile that governs ASDU field widths.
   * @param tlsOptions the TLS options, or {@code null} for a plaintext connection.
   * @param clientMode {@code true} to configure the TLS engine in client mode, {@code false} for
   *     server mode.
   * @param listenerSupplier supplies the {@link TransportListener} the inbound handler forwards to.
   * @return the {@link SslHandler} that was installed, or {@code null} when TLS is not configured;
   *     callers awaiting the handshake use its {@link SslHandler#handshakeFuture() handshake
   *     future}.
   */
  // The returned SslHandler is an intentional API affordance so callers can await the handshake;
  // current callers re-fetch it from the pipeline in a later callback, so it is not used here.
  @SuppressWarnings("UnusedReturnValue")
  static @Nullable SslHandler configure(
      Channel channel,
      ProtocolProfile profile,
      @Nullable TlsOptions tlsOptions,
      boolean clientMode,
      Supplier<@Nullable TransportListener> listenerSupplier) {

    ChannelPipeline pipeline = channel.pipeline();

    SslHandler sslHandler = null;
    if (tlsOptions != null) {
      sslHandler = newSslHandler(tlsOptions, clientMode);
      pipeline.addLast(SSL_HANDLER, sslHandler);
    }

    pipeline.addLast(FRAME_DECODER, new Iec104FrameDecoder(profile));
    pipeline.addLast(FRAME_ENCODER, new Iec104FrameEncoder(profile));
    pipeline.addLast(INBOUND_HANDLER, new InboundApduHandler(listenerSupplier));

    return sslHandler;
  }

  /**
   * Creates an {@link SslHandler} from the JDK {@link javax.net.ssl.SSLContext} carried by {@code
   * tlsOptions}.
   *
   * @param tlsOptions the TLS options supplying the SSL context and client-auth requirement.
   * @param clientMode {@code true} for a client engine, {@code false} for a server engine.
   * @return the configured {@link SslHandler}.
   */
  private static SslHandler newSslHandler(TlsOptions tlsOptions, boolean clientMode) {
    SSLEngine engine = tlsOptions.sslContext().createSSLEngine();
    engine.setUseClientMode(clientMode);
    if (!clientMode) {
      engine.setNeedClientAuth(tlsOptions.clientAuthRequired());
    }

    return new SslHandler(engine);
  }
}
