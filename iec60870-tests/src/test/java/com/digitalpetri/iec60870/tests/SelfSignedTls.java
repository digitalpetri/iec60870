package com.digitalpetri.iec60870.tests;

import java.io.IOException;
import java.math.BigInteger;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.OperatorCreationException;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

/**
 * Generates a self-signed RSA certificate at test time with BouncyCastle and builds the {@link
 * SSLContext}s the TLS integration test needs.
 *
 * <p>Each {@link #generate()} call mints a fresh key pair and a {@code CN=localhost} certificate
 * whose subject-alternative names cover {@code localhost} and {@code 127.0.0.1}, so the loopback
 * server's identity validates against a client that trusts it. From the generated material a test
 * can build:
 *
 * <ul>
 *   <li>a {@linkplain #serverContext() server context} keyed with the certificate and its private
 *       key;
 *   <li>a {@linkplain #trustingClientContext() trusting client context} whose trust anchors are
 *       exactly that certificate, so the handshake succeeds; and
 *   <li>an {@linkplain #untrustingClientContext() untrusting client context} whose trust anchors
 *       are a different self-signed certificate, so the handshake fails certificate validation.
 * </ul>
 *
 * <p>Nothing is read from disk and no keystore files are checked in: every run is self-contained.
 */
final class SelfSignedTls {

  private static final char[] PASSWORD = "changeit".toCharArray();

  private final X509Certificate certificate;
  private final KeyPair keyPair;

  private SelfSignedTls(X509Certificate certificate, KeyPair keyPair) {
    this.certificate = certificate;
    this.keyPair = keyPair;
  }

  /**
   * Generates a fresh self-signed {@code CN=localhost} identity.
   *
   * @return the generated identity.
   * @throws GeneralSecurityException if key or certificate generation fails.
   */
  static SelfSignedTls generate() throws GeneralSecurityException {
    KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
    generator.initialize(2048, new SecureRandom());
    KeyPair keyPair = generator.generateKeyPair();

    X509Certificate certificate = createCertificate(keyPair);
    return new SelfSignedTls(certificate, keyPair);
  }

  /**
   * Builds a server-side {@link SSLContext} keyed with this identity's certificate and private key.
   *
   * @return a server SSL context.
   * @throws GeneralSecurityException if the key material cannot be assembled.
   */
  SSLContext serverContext() throws GeneralSecurityException {
    KeyStore keyStore = emptyKeyStore();
    keyStore.setKeyEntry(
        "server", keyPair.getPrivate(), PASSWORD, new X509Certificate[] {certificate});

    KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
    kmf.init(keyStore, PASSWORD);

    SSLContext context = SSLContext.getInstance("TLS");
    context.init(kmf.getKeyManagers(), null, new SecureRandom());
    return context;
  }

  /**
   * Builds a client {@link SSLContext} that trusts this identity's certificate, so the handshake
   * against {@link #serverContext()} succeeds.
   *
   * @return a trusting client SSL context.
   * @throws GeneralSecurityException if the trust material cannot be assembled.
   */
  SSLContext trustingClientContext() throws GeneralSecurityException {
    return clientContextTrusting(certificate);
  }

  /**
   * Builds a client {@link SSLContext} that does <em>not</em> trust this identity's certificate, so
   * the handshake against {@link #serverContext()} fails certificate validation.
   *
   * <p>The trust anchors are a separate, freshly generated self-signed certificate. This makes the
   * failure an unambiguous "untrusted server certificate" rejection rather than an empty-trust
   * configuration error.
   *
   * @return a non-trusting client SSL context.
   * @throws GeneralSecurityException if the trust material cannot be assembled.
   */
  SSLContext untrustingClientContext() throws GeneralSecurityException {
    SelfSignedTls unrelated = generate();
    return clientContextTrusting(unrelated.certificate);
  }

  private static SSLContext clientContextTrusting(X509Certificate trusted)
      throws GeneralSecurityException {
    KeyStore trustStore = emptyKeyStore();
    trustStore.setCertificateEntry("trusted", trusted);

    TrustManagerFactory tmf =
        TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
    tmf.init(trustStore);

    SSLContext context = SSLContext.getInstance("TLS");
    context.init(null, tmf.getTrustManagers(), new SecureRandom());
    return context;
  }

  private static KeyStore emptyKeyStore() throws GeneralSecurityException {
    try {
      KeyStore keyStore = KeyStore.getInstance("PKCS12");
      keyStore.load(null, null);
      return keyStore;
    } catch (IOException e) {
      throw new GeneralSecurityException("failed to initialise in-memory keystore", e);
    }
  }

  private static X509Certificate createCertificate(KeyPair keyPair)
      throws GeneralSecurityException {
    X500Name subject = new X500Name("CN=localhost");
    Instant now = Instant.now();
    Date notBefore = Date.from(now.minus(Duration.ofMinutes(5)));
    Date notAfter = Date.from(now.plus(Duration.ofDays(1)));
    BigInteger serial = BigInteger.valueOf(System.currentTimeMillis());

    GeneralNames subjectAltNames =
        new GeneralNames(
            new GeneralName[] {
              new GeneralName(GeneralName.dNSName, "localhost"),
              new GeneralName(GeneralName.iPAddress, "127.0.0.1")
            });

    try {
      JcaX509v3CertificateBuilder builder =
          new JcaX509v3CertificateBuilder(
              subject, serial, notBefore, notAfter, subject, keyPair.getPublic());
      builder.addExtension(Extension.subjectAlternativeName, false, subjectAltNames);

      ContentSigner signer =
          new JcaContentSignerBuilder("SHA256withRSA").build(keyPair.getPrivate());
      X509CertificateHolder holder = builder.build(signer);

      return new JcaX509CertificateConverter().getCertificate(holder);
    } catch (OperatorCreationException | IOException e) {
      throw new GeneralSecurityException("failed to create self-signed certificate", e);
    }
  }
}
