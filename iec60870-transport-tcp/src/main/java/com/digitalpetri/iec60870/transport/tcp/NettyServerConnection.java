package com.digitalpetri.iec60870.transport.tcp;

import com.digitalpetri.iec60870.apci.Apdu;
import com.digitalpetri.iec60870.transport.ServerTransportConnection;
import com.digitalpetri.iec60870.transport.TransportListener;
import io.netty.channel.Channel;
import io.netty.handler.ssl.SslHandler;
import java.net.SocketAddress;
import java.security.cert.Certificate;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSession;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A {@link ServerTransportConnection} backed by one accepted Netty child {@link Channel}.
 *
 * <p>One instance wraps one accepted controlling-station channel. The owning {@link
 * NettyServerTransport} constructs it during {@code childChannel} initialization and hands it to
 * the registered connection handler, which installs a {@link TransportListener}. Outbound frames
 * are written and flushed on the channel; the listener is resolved lazily so it may be registered
 * after the connection is delivered.
 *
 * <p>{@link #peerCertificate()} narrows the TLS context to exactly what a server needs: when the
 * channel carries an {@link SslHandler} and the peer presented a certificate, the leaf certificate
 * from the negotiated {@link SSLSession} is returned; otherwise the result is empty.
 */
class NettyServerConnection implements ServerTransportConnection {

  private static final Logger LOGGER = LoggerFactory.getLogger(NettyServerConnection.class);

  private final AtomicReference<@Nullable TransportListener> listener = new AtomicReference<>();

  private final Channel channel;

  /**
   * Creates a connection wrapping an accepted child channel.
   *
   * @param channel the accepted Netty child channel.
   */
  NettyServerConnection(Channel channel) {
    this.channel = channel;
  }

  /**
   * Supplies the registered listener for the terminal inbound handler.
   *
   * @return the current listener, or {@code null} if none has been registered.
   */
  @Nullable TransportListener listener() {
    return listener.get();
  }

  @Override
  public CompletionStage<Void> send(Apdu apdu) {
    CompletableFuture<Void> result = new CompletableFuture<>();

    channel
        .writeAndFlush(apdu)
        .addListener(
            future -> {
              if (future.isSuccess()) {
                // CompletableFuture<Void> is completed with null by design; Netty's
                // GenericFutureListener has no @Nullable metadata, so the IDE flags it.
                //noinspection DataFlowIssue
                result.complete(null);
              } else {
                result.completeExceptionally(future.cause());
              }
            });

    return result;
  }

  @Override
  public void setListener(TransportListener listener) {
    this.listener.set(listener);
  }

  @Override
  public void close() {
    channel.close();
  }

  @Override
  public SocketAddress remoteAddress() {
    return channel.remoteAddress();
  }

  @Override
  public Optional<Certificate> peerCertificate() {
    SslHandler sslHandler = channel.pipeline().get(SslHandler.class);
    // ChannelPipeline.get(Class) returns null when no such handler is installed (plaintext
    // channels), but Netty lacks @Nullable metadata, so the IDE thinks the check is dead.
    //noinspection ConstantValue
    if (sslHandler == null) {
      return Optional.empty();
    }

    SSLSession session = sslHandler.engine().getSession();
    try {
      Certificate[] chain = session.getPeerCertificates();
      if (chain.length == 0) {
        return Optional.empty();
      }
      return Optional.of(chain[0]);
    } catch (SSLPeerUnverifiedException e) {
      LOGGER.debug("peer certificate unavailable on {}: {}", channel, e.getMessage());
      return Optional.empty();
    }
  }
}
