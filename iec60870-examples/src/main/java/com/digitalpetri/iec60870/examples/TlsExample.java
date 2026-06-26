package com.digitalpetri.iec60870.examples;

import com.digitalpetri.iec60870.TlsOptions;
import com.digitalpetri.iec60870.address.CommonAddress;
import com.digitalpetri.iec60870.address.PointAddress;
import com.digitalpetri.iec60870.client.Iec60870Client;
import com.digitalpetri.iec60870.client.InterrogationResult;
import com.digitalpetri.iec60870.point.PointCapability;
import com.digitalpetri.iec60870.point.PointType;
import com.digitalpetri.iec60870.point.PointValue;
import com.digitalpetri.iec60870.server.Iec60870Server;
import com.digitalpetri.iec60870.server.PointDefinition;
import com.digitalpetri.iec60870.server.Station;
import com.digitalpetri.iec60870.tcp.TcpIec104Client;
import com.digitalpetri.iec60870.tcp.TcpIec104Server;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.SecureRandom;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

/**
 * The {@link ClientExample}/{@link ServerExample} pair secured with TLS, configured from a keystore
 * and truststore supplied through system properties.
 *
 * <p>Both the controlled station (server) and the controlling station (client) wrap their transport
 * with {@link TlsOptions}, built from an {@link SSLContext} this example assembles from PKCS#12 (or
 * JKS) files on disk. When TLS is configured, {@link Iec60870Client#connect()} completes only after
 * the TLS handshake succeeds.
 *
 * <h2>Supplying certificates</h2>
 *
 * <p>This example reads its key and trust material from these system properties (a value's absence
 * disables that side):
 *
 * <ul>
 *   <li>{@code iec104.tls.keystore} / {@code iec104.tls.keystorePassword} — the server identity
 *       (its certificate and private key);
 *   <li>{@code iec104.tls.truststore} / {@code iec104.tls.truststorePassword} — the certificates
 *       the client trusts when validating the server.
 * </ul>
 *
 * <p>For a quick self-signed setup, generate a PKCS#12 keystore and export its certificate into a
 * truststore with {@code keytool}:
 *
 * <pre>{@code
 * keytool -genkeypair -alias server -keyalg RSA -keysize 2048 \
 *     -dname "CN=localhost" -ext "san=dns:localhost,ip:127.0.0.1" \
 *     -keystore server.p12 -storetype PKCS12 -storepass changeit -validity 365
 * keytool -exportcert -alias server -keystore server.p12 -storepass changeit -file server.crt
 * keytool -importcert -noprompt -alias server -file server.crt \
 *     -keystore truststore.p12 -storetype PKCS12 -storepass changeit
 * }</pre>
 *
 * <p>Then run with, for example:
 *
 * <pre>{@code
 * java -Diec104.tls.keystore=server.p12 -Diec104.tls.keystorePassword=changeit \
 *      -Diec104.tls.truststore=truststore.p12 -Diec104.tls.truststorePassword=changeit \
 *      -cp ... com.digitalpetri.iec60870.examples.TlsExample
 * }</pre>
 */
public final class TlsExample {

  /** Common address of the hosted station. */
  static final CommonAddress STATION = CommonAddress.of(1);

  /** Single-point status indication hosted by the station. */
  static final PointAddress STATUS = PointAddress.of(1, 100);

  private static final int PORT = 19998;

  private TlsExample() {}

  /**
   * Starts a TLS server, connects a TLS client, interrogates the station, then shuts down.
   *
   * <p>If the keystore/truststore system properties are not set this prints the configuration
   * instructions and exits without attempting a connection.
   *
   * @param args ignored.
   * @throws Exception if assembling the SSL material or the protocol exchange fails.
   */
  public static void main(String[] args) throws Exception {
    String keystorePath = System.getProperty("iec104.tls.keystore");
    String truststorePath = System.getProperty("iec104.tls.truststore");
    if (keystorePath == null || truststorePath == null) {
      System.out.println("TLS example requires keystore/truststore system properties; see the");
      System.out.println("class Javadoc for how to generate them and which properties to set.");
      return;
    }

    SSLContext serverContext =
        serverSslContext(
            Path.of(keystorePath), System.getProperty("iec104.tls.keystorePassword", ""));
    SSLContext clientContext =
        clientSslContext(
            Path.of(truststorePath), System.getProperty("iec104.tls.truststorePassword", ""));

    Station station =
        Station.builder(STATION)
            .point(
                PointDefinition.of(
                    STATUS,
                    PointType.SINGLE_POINT,
                    PointValue.single(true),
                    PointCapability.REPORTED))
            .group(1, STATUS)
            .build();

    try (Iec60870Server server =
        TcpIec104Server.builder()
            .bindAddress("127.0.0.1")
            .port(PORT)
            .addStation(station)
            .tls(TlsOptions.builder(serverContext).build())
            .build()) {

      server.start();
      System.out.println("[tls] server listening on " + PORT);

      try (Iec60870Client client =
          TcpIec104Client.builder()
              .host("127.0.0.1")
              .port(PORT)
              .tls(TlsOptions.builder(clientContext).build())
              .startDataTransferOnConnect(true)
              .build()) {

        client.connect();
        System.out.println("[tls] handshake complete; connected");

        InterrogationResult snapshot = client.interrogate(STATION);
        System.out.println(
            "[tls] interrogation reported " + snapshot.pointValues().size() + " points");
      }
    }
    System.out.println("[tls] done");
  }

  /**
   * Builds a server {@link SSLContext} keyed with the identity in the given keystore.
   *
   * @param keystore the path to a PKCS#12 or JKS keystore holding the server's certificate and key.
   * @param password the keystore (and key) password.
   * @return the server SSL context.
   * @throws Exception if the keystore cannot be loaded or the key managers cannot be initialized.
   */
  static SSLContext serverSslContext(Path keystore, String password) throws Exception {
    KeyStore store = loadKeyStore(keystore, password);
    KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
    kmf.init(store, password.toCharArray());

    SSLContext context = SSLContext.getInstance("TLS");
    context.init(kmf.getKeyManagers(), null, new SecureRandom());
    return context;
  }

  /**
   * Builds a client {@link SSLContext} that trusts the certificates in the given truststore.
   *
   * @param truststore the path to a PKCS#12 or JKS truststore holding the trusted certificates.
   * @param password the truststore password.
   * @return the client SSL context.
   * @throws Exception if the truststore cannot be loaded or the trust managers cannot be
   *     initialized.
   */
  static SSLContext clientSslContext(Path truststore, String password) throws Exception {
    KeyStore store = loadKeyStore(truststore, password);
    TrustManagerFactory tmf =
        TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
    tmf.init(store);

    SSLContext context = SSLContext.getInstance("TLS");
    context.init(null, tmf.getTrustManagers(), new SecureRandom());
    return context;
  }

  private static KeyStore loadKeyStore(Path path, String password) throws Exception {
    String type = path.toString().endsWith(".jks") ? "JKS" : "PKCS12";
    KeyStore store = KeyStore.getInstance(type);
    try (InputStream in = Files.newInputStream(path)) {
      store.load(in, password.toCharArray());
    }
    return store;
  }
}
