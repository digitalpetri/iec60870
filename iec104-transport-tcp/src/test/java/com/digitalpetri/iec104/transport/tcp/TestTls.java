package com.digitalpetri.iec104.transport.tcp;

import java.io.IOException;
import java.io.InputStream;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.SecureRandom;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

/**
 * Test-only helpers that build {@link SSLContext}s from the self-signed PKCS#12 keystores under
 * {@code src/test/resources/tls}.
 *
 * <p>The keystores were generated once with {@code keytool} (a self-signed {@code CN=localhost} RSA
 * certificate with a {@code SAN} of {@code localhost}/{@code 127.0.0.1}). {@code
 * server-keystore.p12} carries the server's private key and certificate; {@code
 * server-truststore.p12} carries only that certificate so a trusting client can be built without
 * trusting the platform CAs. This keeps the TLS tests self-contained: no certificates are generated
 * at runtime and no BouncyCastle dependency (which is not on this module's classpath) is required.
 */
final class TestTls {

  /** Classpath location of the server identity keystore (private key + certificate). */
  static final String SERVER_KEYSTORE = "/tls/server-keystore.p12";

  /** Classpath location of the truststore holding only the server certificate. */
  static final String SERVER_TRUSTSTORE = "/tls/server-truststore.p12";

  /**
   * Classpath location of a truststore holding an unrelated self-signed certificate; a client using
   * it trusts a real CA set that nonetheless does not include the server's certificate.
   */
  static final String OTHER_TRUSTSTORE = "/tls/other-truststore.p12";

  /**
   * Classpath location of a keystore whose certificate ({@code CN=mismatch.example.com}, SAN {@code
   * dns:mismatch.example.com}) matches no loopback address, so a client trusting it but dialing
   * {@code 127.0.0.1} fails hostname identification.
   */
  static final String MISMATCH_KEYSTORE = "/tls/mismatch-keystore.p12";

  /** Classpath location of the truststore holding only the mismatch certificate. */
  static final String MISMATCH_TRUSTSTORE = "/tls/mismatch-truststore.p12";

  /** The PKCS#12 store password shared by both test keystores. */
  static final char[] PASSWORD = "changeit".toCharArray();

  private TestTls() {}

  /**
   * Builds the server-side {@link SSLContext} keyed with the self-signed server identity.
   *
   * @return a server SSL context.
   * @throws GeneralSecurityException if the key material cannot be loaded.
   * @throws IOException if the keystore resource cannot be read.
   */
  static SSLContext serverContext() throws GeneralSecurityException, IOException {
    KeyStore keyStore = load(SERVER_KEYSTORE);

    KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
    kmf.init(keyStore, PASSWORD);

    SSLContext context = SSLContext.getInstance("TLS");
    context.init(kmf.getKeyManagers(), null, new SecureRandom());
    return context;
  }

  /**
   * Builds a client {@link SSLContext} that trusts the self-signed server certificate, so the
   * handshake against {@link #serverContext()} succeeds.
   *
   * @return a trusting client SSL context.
   * @throws GeneralSecurityException if the trust material cannot be loaded.
   * @throws IOException if the truststore resource cannot be read.
   */
  static SSLContext trustingClientContext() throws GeneralSecurityException, IOException {
    KeyStore trustStore = load(SERVER_TRUSTSTORE);

    TrustManagerFactory tmf =
        TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
    tmf.init(trustStore);

    SSLContext context = SSLContext.getInstance("TLS");
    context.init(null, tmf.getTrustManagers(), new SecureRandom());
    return context;
  }

  /**
   * Builds a client {@link SSLContext} that does <em>not</em> trust the server certificate, so the
   * handshake against {@link #serverContext()} fails certificate validation.
   *
   * <p>The trust manager is initialised from a non-empty truststore that contains an unrelated
   * self-signed certificate. This makes the failure an unambiguous "untrusted server certificate"
   * rejection at the trust anchor rather than an empty-trust-anchors configuration error.
   *
   * @return a non-trusting client SSL context.
   * @throws GeneralSecurityException if the trust material cannot be loaded.
   * @throws IOException if the truststore resource cannot be read.
   */
  static SSLContext untrustingClientContext() throws GeneralSecurityException, IOException {
    KeyStore otherTrustStore = load(OTHER_TRUSTSTORE);

    TrustManagerFactory tmf =
        TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
    tmf.init(otherTrustStore);

    SSLContext context = SSLContext.getInstance("TLS");
    context.init(null, tmf.getTrustManagers(), new SecureRandom());
    return context;
  }

  /**
   * Builds a server-side {@link SSLContext} whose identity certificate is valid for {@code
   * mismatch.example.com} only, so a client dialing a loopback address fails hostname
   * identification.
   *
   * @return a server SSL context keyed with the mismatch identity.
   * @throws GeneralSecurityException if the key material cannot be loaded.
   * @throws IOException if the keystore resource cannot be read.
   */
  static SSLContext mismatchedServerContext() throws GeneralSecurityException, IOException {
    KeyStore keyStore = load(MISMATCH_KEYSTORE);

    KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
    kmf.init(keyStore, PASSWORD);

    SSLContext context = SSLContext.getInstance("TLS");
    context.init(kmf.getKeyManagers(), null, new SecureRandom());
    return context;
  }

  /**
   * Builds a client {@link SSLContext} that trusts the mismatch certificate, so the handshake
   * against {@link #mismatchedServerContext()} fails only on hostname identification rather than on
   * trust.
   *
   * @return a client SSL context trusting the mismatch certificate.
   * @throws GeneralSecurityException if the trust material cannot be loaded.
   * @throws IOException if the truststore resource cannot be read.
   */
  static SSLContext clientTrustingMismatch() throws GeneralSecurityException, IOException {
    KeyStore trustStore = load(MISMATCH_TRUSTSTORE);

    TrustManagerFactory tmf =
        TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
    tmf.init(trustStore);

    SSLContext context = SSLContext.getInstance("TLS");
    context.init(null, tmf.getTrustManagers(), new SecureRandom());
    return context;
  }

  private static KeyStore load(String resource) throws GeneralSecurityException, IOException {
    KeyStore keyStore = KeyStore.getInstance("PKCS12");
    try (InputStream in = TestTls.class.getResourceAsStream(resource)) {
      if (in == null) {
        throw new IOException("missing test keystore resource: " + resource);
      }
      keyStore.load(in, PASSWORD);
    }
    return keyStore;
  }
}
