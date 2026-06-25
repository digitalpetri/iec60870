package com.digitalpetri.iec60870.transport.tcp;

import com.digitalpetri.iec60870.TlsOptions;
import com.digitalpetri.iec60870.transport.TransportListener;
import io.netty.channel.Channel;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.ssl.SslHandler;
import java.util.function.Supplier;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLParameters;
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
 *   <li>{@link Iec104FrameDecoder} — length-delimited framing emitting one whole-frame {@code
 *       ByteBuf}.
 *   <li>{@link InboundFrameHandler} — terminal handler forwarding each frame to the {@link
 *       TransportListener}.
 * </ol>
 *
 * <p>Outbound is a raw {@code ByteBuf} write: the protocol layer above frames each APDU into a
 * complete length-delimited buffer and the transport writes-and-flushes it, so no outbound encoder
 * handler is installed. The pipeline is profile-agnostic; APDU encode/decode happens above this SPI
 * (via {@code ApduFramer}).
 *
 * <p>This class is a stateless builder of pipeline handlers and is never instantiated.
 */
final class Iec104Pipeline {

  /** The pipeline name of the {@link SslHandler}, present only when TLS is enabled. */
  static final String SSL_HANDLER = "ssl";

  /** The pipeline name of the {@link Iec104FrameDecoder}. */
  static final String FRAME_DECODER = "iec60870-frame-decoder";

  /** The pipeline name of the terminal {@link InboundFrameHandler}. */
  static final String INBOUND_HANDLER = "iec60870-inbound";

  private Iec104Pipeline() {}

  /**
   * Installs the IEC 104 handlers on {@code channel} in the fixed safe order.
   *
   * <p>When {@code tlsOptions} is non-null, an {@link SslHandler} created from the supplied {@link
   * javax.net.ssl.SSLContext} is added first. The caller decides the engine's client-or-server mode
   * and (for servers) whether client authentication is required.
   *
   * @param channel the channel whose pipeline is configured.
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
      @Nullable TlsOptions tlsOptions,
      boolean clientMode,
      Supplier<@Nullable TransportListener> listenerSupplier) {

    return configure(channel, tlsOptions, clientMode, listenerSupplier, null, -1);
  }

  /**
   * Installs the IEC 104 handlers on {@code channel} in the fixed safe order, supplying the peer
   * host and port so a client engine can perform certificate identification.
   *
   * <p>Behaves exactly like {@link #configure(Channel, TlsOptions, boolean, Supplier)} except that,
   * in client mode, {@code peerHost} and {@code peerPort} seed the {@link SSLEngine} with the
   * advisory peer information used for endpoint identification and SNI. They are ignored in server
   * mode.
   *
   * @param channel the channel whose pipeline is configured.
   * @param tlsOptions the TLS options, or {@code null} for a plaintext connection.
   * @param clientMode {@code true} to configure the TLS engine in client mode, {@code false} for
   *     server mode.
   * @param listenerSupplier supplies the {@link TransportListener} the inbound handler forwards to.
   * @param peerHost the host being dialed, used for client-side identification, or {@code null}
   *     when unknown.
   * @param peerPort the port being dialed, or {@code -1} when unknown.
   * @return the {@link SslHandler} that was installed, or {@code null} when TLS is not configured.
   */
  @SuppressWarnings("UnusedReturnValue")
  static @Nullable SslHandler configure(
      Channel channel,
      @Nullable TlsOptions tlsOptions,
      boolean clientMode,
      Supplier<@Nullable TransportListener> listenerSupplier,
      @Nullable String peerHost,
      int peerPort) {

    ChannelPipeline pipeline = channel.pipeline();

    SslHandler sslHandler = null;
    if (tlsOptions != null) {
      sslHandler = newSslHandler(tlsOptions, clientMode, peerHost, peerPort);
      pipeline.addLast(SSL_HANDLER, sslHandler);
    }

    pipeline.addLast(FRAME_DECODER, new Iec104FrameDecoder());
    pipeline.addLast(INBOUND_HANDLER, new InboundFrameHandler(listenerSupplier));

    return sslHandler;
  }

  /**
   * Creates an {@link SslHandler} from the JDK {@link javax.net.ssl.SSLContext} carried by {@code
   * tlsOptions}.
   *
   * <p>In client mode, when {@code peerHost} is non-null the engine is created with the advisory
   * peer host and port so the JDK can perform SNI and certificate identification, and when {@link
   * TlsOptions#verifyHostname()} is enabled the {@code HTTPS} endpoint identification algorithm is
   * applied so a certificate that does not match the host fails the handshake. In server mode the
   * engine performs no endpoint identification and applies the client-auth requirement.
   *
   * @param tlsOptions the TLS options supplying the SSL context and client-auth requirement.
   * @param clientMode {@code true} for a client engine, {@code false} for a server engine.
   * @param peerHost the host being dialed, used for client-side identification, or {@code null}
   *     when unknown.
   * @param peerPort the port being dialed, or {@code -1} when unknown.
   * @return the configured {@link SslHandler}.
   */
  private static SslHandler newSslHandler(
      TlsOptions tlsOptions, boolean clientMode, @Nullable String peerHost, int peerPort) {
    SSLEngine engine;
    if (clientMode && peerHost != null) {
      engine = tlsOptions.sslContext().createSSLEngine(peerHost, peerPort);
    } else {
      engine = tlsOptions.sslContext().createSSLEngine();
    }
    engine.setUseClientMode(clientMode);

    if (clientMode) {
      if (tlsOptions.verifyHostname()) {
        SSLParameters parameters = engine.getSSLParameters();
        parameters.setEndpointIdentificationAlgorithm("HTTPS");
        engine.setSSLParameters(parameters);
      }
    } else {
      engine.setNeedClientAuth(tlsOptions.clientAuthRequired());
    }

    return new SslHandler(engine);
  }
}
