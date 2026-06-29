package com.digitalpetri.iec60870.test.interop;

import java.io.IOException;
import java.io.Writer;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.ExtendedKeyUsage;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.asn1.x509.KeyPurposeId;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.openssl.jcajce.JcaPEMWriter;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.OperatorCreationException;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

/** Per-test CA, Java SSL contexts, and PEM files for CS104 TLS interop. */
final class InteropTlsMaterial {

  private static final char[] PASSWORD = "changeit".toCharArray();

  private final Path directory;
  private final X509Certificate caCertificate;
  private final X509Certificate serverCertificate;
  private final KeyPair serverKeyPair;
  private final X509Certificate clientCertificate;
  private final KeyPair clientKeyPair;

  private InteropTlsMaterial(
      Path directory,
      X509Certificate caCertificate,
      X509Certificate serverCertificate,
      KeyPair serverKeyPair,
      X509Certificate clientCertificate,
      KeyPair clientKeyPair) {
    this.directory = directory;
    this.caCertificate = caCertificate;
    this.serverCertificate = serverCertificate;
    this.serverKeyPair = serverKeyPair;
    this.clientCertificate = clientCertificate;
    this.clientKeyPair = clientKeyPair;
  }

  static InteropTlsMaterial create(Path directory) throws GeneralSecurityException, IOException {
    Files.createDirectories(directory);

    KeyPair caKeyPair = generateKeyPair();
    X509Certificate caCertificate = createCaCertificate(caKeyPair);

    KeyPair serverKeyPair = generateKeyPair();
    X509Certificate serverCertificate =
        createLeafCertificate(
            "CN=interop-server",
            serverKeyPair,
            caCertificate,
            caKeyPair,
            new GeneralNames(
                new GeneralName[] {
                  new GeneralName(GeneralName.dNSName, "localhost"),
                  new GeneralName(GeneralName.iPAddress, "127.0.0.1"),
                  new GeneralName(GeneralName.dNSName, "host.testcontainers.internal")
                }),
            KeyPurposeId.id_kp_serverAuth);

    KeyPair clientKeyPair = generateKeyPair();
    X509Certificate clientCertificate =
        createLeafCertificate(
            "CN=interop-client",
            clientKeyPair,
            caCertificate,
            caKeyPair,
            new GeneralNames(new GeneralName(GeneralName.dNSName, "interop-client")),
            KeyPurposeId.id_kp_clientAuth);

    writePem(directory.resolve("ca.pem"), caCertificate);
    writePem(directory.resolve("server.pem"), serverCertificate);
    writePem(directory.resolve("server-key.pem"), serverKeyPair.getPrivate());
    writePem(directory.resolve("client.pem"), clientCertificate);
    writePem(directory.resolve("client-key.pem"), clientKeyPair.getPrivate());

    return new InteropTlsMaterial(
        directory,
        caCertificate,
        serverCertificate,
        serverKeyPair,
        clientCertificate,
        clientKeyPair);
  }

  Path directory() {
    return directory;
  }

  X509Certificate clientCertificate() {
    return clientCertificate;
  }

  SSLContext clientContext() throws GeneralSecurityException {
    return context(clientKeyPair, clientCertificate);
  }

  SSLContext serverContext() throws GeneralSecurityException {
    return context(serverKeyPair, serverCertificate);
  }

  private SSLContext context(KeyPair keyPair, X509Certificate certificate)
      throws GeneralSecurityException {
    KeyStore keyStore = emptyKeyStore();
    keyStore.setKeyEntry(
        "identity",
        keyPair.getPrivate(),
        PASSWORD,
        new X509Certificate[] {certificate, caCertificate});

    KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
    kmf.init(keyStore, PASSWORD);

    KeyStore trustStore = emptyKeyStore();
    trustStore.setCertificateEntry("ca", caCertificate);

    TrustManagerFactory tmf =
        TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
    tmf.init(trustStore);

    SSLContext context = SSLContext.getInstance("TLSv1.2");
    context.init(kmf.getKeyManagers(), tmf.getTrustManagers(), new SecureRandom());
    return context;
  }

  private static KeyPair generateKeyPair() throws GeneralSecurityException {
    KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
    generator.initialize(2048, new SecureRandom());
    return generator.generateKeyPair();
  }

  private static X509Certificate createCaCertificate(KeyPair keyPair)
      throws GeneralSecurityException {
    X500Name subject = new X500Name("CN=interop-ca");
    Instant now = Instant.now();
    Date notBefore = Date.from(now.minus(Duration.ofMinutes(5)));
    Date notAfter = Date.from(now.plus(Duration.ofDays(2)));

    try {
      JcaX509v3CertificateBuilder builder =
          new JcaX509v3CertificateBuilder(
              subject, serial(), notBefore, notAfter, subject, keyPair.getPublic());
      builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(true));
      builder.addExtension(
          Extension.keyUsage, true, new KeyUsage(KeyUsage.keyCertSign | KeyUsage.cRLSign));

      ContentSigner signer =
          new JcaContentSignerBuilder("SHA256withRSA").build(keyPair.getPrivate());
      X509CertificateHolder holder = builder.build(signer);
      return new JcaX509CertificateConverter().getCertificate(holder);
    } catch (OperatorCreationException | IOException e) {
      throw new GeneralSecurityException("failed to create CA certificate", e);
    }
  }

  private static X509Certificate createLeafCertificate(
      String subjectDn,
      KeyPair keyPair,
      X509Certificate caCertificate,
      KeyPair caKeyPair,
      GeneralNames subjectAltNames,
      KeyPurposeId purpose)
      throws GeneralSecurityException {

    Instant now = Instant.now();
    Date notBefore = Date.from(now.minus(Duration.ofMinutes(5)));
    Date notAfter = Date.from(now.plus(Duration.ofDays(2)));
    X500Name issuer = new X500Name(caCertificate.getSubjectX500Principal().getName());
    X500Name subject = new X500Name(subjectDn);

    try {
      JcaX509v3CertificateBuilder builder =
          new JcaX509v3CertificateBuilder(
              issuer, serial(), notBefore, notAfter, subject, keyPair.getPublic());
      builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(false));
      builder.addExtension(
          Extension.keyUsage,
          true,
          new KeyUsage(KeyUsage.digitalSignature | KeyUsage.keyEncipherment));
      builder.addExtension(Extension.extendedKeyUsage, false, new ExtendedKeyUsage(purpose));
      builder.addExtension(Extension.subjectAlternativeName, false, subjectAltNames);

      ContentSigner signer =
          new JcaContentSignerBuilder("SHA256withRSA").build(caKeyPair.getPrivate());
      X509CertificateHolder holder = builder.build(signer);
      return new JcaX509CertificateConverter().getCertificate(holder);
    } catch (OperatorCreationException | IOException e) {
      throw new GeneralSecurityException("failed to create leaf certificate", e);
    }
  }

  private static BigInteger serial() {
    byte[] bytes = new byte[16];
    new SecureRandom().nextBytes(bytes);
    bytes[0] &= 0x7f;
    return new BigInteger(1, bytes);
  }

  private static void writePem(Path path, Object object) throws IOException {
    try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.US_ASCII);
        JcaPEMWriter pemWriter = new JcaPEMWriter(writer)) {
      pemWriter.writeObject(object);
    }
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
}
