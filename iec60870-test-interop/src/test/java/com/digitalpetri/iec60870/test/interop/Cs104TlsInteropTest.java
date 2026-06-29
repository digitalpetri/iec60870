package com.digitalpetri.iec60870.test.interop;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.digitalpetri.iec60870.ProtocolProfile;
import com.digitalpetri.iec60870.TlsOptions;
import com.digitalpetri.iec60870.address.OriginatorAddress;
import com.digitalpetri.iec60870.client.Iec60870Client;
import com.digitalpetri.iec60870.cs104.ApciSettings;
import com.digitalpetri.iec60870.cs104.Cs104Binding;
import com.digitalpetri.iec60870.server.DefaultIec60870Server;
import com.digitalpetri.iec60870.server.Iec60870Server;
import com.digitalpetri.iec60870.server.ServerConfig;
import com.digitalpetri.iec60870.tcp.TcpIec104Client;
import com.digitalpetri.iec60870.transport.ServerTransport;
import com.digitalpetri.iec60870.transport.ServerTransportConnection;
import com.digitalpetri.iec60870.transport.TransportListener;
import com.digitalpetri.iec60870.transport.tcp.NettyServerTransport;
import com.digitalpetri.iec60870.transport.tcp.NettyServerTransportConfig;
import io.netty.buffer.ByteBuf;
import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.security.cert.Certificate;
import java.time.Duration;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import org.testcontainers.Testcontainers;
import org.testcontainers.containers.ContainerLaunchException;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.output.Slf4jLogConsumer;
import org.testcontainers.containers.output.ToStringConsumer;
import org.testcontainers.containers.startupcheck.OneShotStartupCheckStrategy;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.utility.MountableFile;

/** CS104 mutual-TLS interop against lib60870-C peers. */
@Tag("interop")
@DisplayName("IEC 60870-5-104 TLS interop vs lib60870-C")
class Cs104TlsInteropTest {

  private static final Duration IMAGE_BUILD_TIMEOUT = Duration.ofMinutes(10);
  private static final Duration WAIT_TIMEOUT = Duration.ofSeconds(20);

  @Test
  @DisplayName("Java TLS client drives lib60870-C TLS server")
  void javaTlsClientDrivesCTlsServer(@TempDir Path tempDir) throws Exception {
    InteropTlsMaterial tls = InteropTlsMaterial.create(tempDir);

    try (GenericContainer<?> server =
        new GenericContainer<>(image())
            .withCopyFileToContainer(MountableFile.forHostPath(tls.directory()), "/interop-tls")
            .withExposedPorts(2404)
            .withEnv("INTEROP_TLS", "1")
            .withEnv("INTEROP_TLS_CERT_DIR", "/interop-tls")
            .withCommand("stdbuf", "-oL", "-eL", "interop_server")
            .withStartupTimeout(IMAGE_BUILD_TIMEOUT)
            .waitingFor(Wait.forLogMessage(".*INTEROP-SERVER READY.*tls=1.*", 1))) {

      server.withLogConsumer(new Slf4jLogConsumer(LoggerFactory.getLogger("interop_tls_server")));
      server.start();

      Iec60870Client client =
          TcpIec104Client.builder()
              .host(server.getHost())
              .port(server.getMappedPort(2404))
              .profile(ProtocolProfile.iec104Default())
              .originatorAddress(OriginatorAddress.of(3))
              .tls(TlsOptions.builder(tls.clientContext()).verifyHostname(false).build())
              .startDataTransferOnConnect(true)
              .build();
      InteropEventRecorder events = new InteropEventRecorder();
      client.events().subscribe(events);

      try {
        client.connect();
        assertTrue(client.isConnected(), "client should be connected after mTLS handshake");
        InteropClientContract.assertFocusedClientContract(client, events, WAIT_TIMEOUT);
      } finally {
        client.close();
      }
    }
  }

  @Test
  @DisplayName("lib60870-C TLS client drives Java TLS server")
  void cTlsClientDrivesJavaTlsServer(@TempDir Path tempDir) throws Exception {
    InteropTlsMaterial tls = InteropTlsMaterial.create(tempDir);
    AtomicReference<@Nullable Certificate> peerCertificate = new AtomicReference<>();
    int serverPort = freeEphemeralPort();

    Iec60870Server server = startRecordingServer(serverPort, tls, peerCertificate);
    Testcontainers.exposeHostPorts(serverPort);

    try {
      ToStringConsumer logs = new ToStringConsumer();

      try (GenericContainer<?> client =
          new GenericContainer<>(image())
              .withCopyFileToContainer(MountableFile.forHostPath(tls.directory()), "/interop-tls")
              .withStartupTimeout(IMAGE_BUILD_TIMEOUT)
              .withStartupCheckStrategy(new OneShotStartupCheckStrategy())
              .withEnv("INTEROP_TLS", "1")
              .withEnv("INTEROP_TLS_CERT_DIR", "/interop-tls")
              .withEnv("INTEROP_HOST", "host.testcontainers.internal")
              .withEnv("INTEROP_PORT", Integer.toString(serverPort))
              .withEnv("INTEROP_CA", "1")
              .withEnv("INTEROP_ACCEPT_IOA", Integer.toString(InteropClientContract.IOA_ACCEPT))
              .withEnv("INTEROP_REJECT_IOA", Integer.toString(InteropClientContract.IOA_REJECT))
              .withCommand(
                  "stdbuf",
                  "-oL",
                  "-eL",
                  "interop_client",
                  "host.testcontainers.internal",
                  Integer.toString(serverPort))) {

        client.withLogConsumer(logs);
        client.withLogConsumer(new Slf4jLogConsumer(LoggerFactory.getLogger("interop_tls_client")));

        ContainerLaunchException launchFailure = null;
        try {
          client.start();
        } catch (ContainerLaunchException e) {
          launchFailure = e;
        }

        String output = logs.toUtf8String();
        assertContains(output, "INTEROP-CLIENT RESULT pass=", output);
        ContainerLaunchException launchFailureForMessage = launchFailure;
        assertTrue(
            containsResultWithFailZero(output),
            () ->
                "interop_client reported failures"
                    + (launchFailureForMessage != null ? " and exited non-zero" : "")
                    + ". Full client log:\n"
                    + output);
        assertContains(output, "PASS: connect", output);
        assertContains(output, "PASS: STARTDT_CON received", output);
        assertContains(output, "PASS: station interrogation (ACT_CON + data)", output);
        assertContains(output, "PASS: accept command confirmed (P/N=0)", output);
        assertContains(output, "PASS: reject command negatively confirmed (P/N=1)", output);
        assertContains(output, "PASS: STOPDT_CON received", output);
      }

      Certificate actual = peerCertificate.get();
      assertNotNull(actual, "Java server should expose the C client's peer certificate");
      assertArrayEquals(
          tls.clientCertificate().getEncoded(),
          actual.getEncoded(),
          "server-side peer certificate should be the generated client cert");
    } finally {
      server.close();
    }
  }

  private static Iec60870Server startRecordingServer(
      int port, InteropTlsMaterial tls, AtomicReference<@Nullable Certificate> peerCertificate)
      throws Exception {
    TlsOptions tlsOptions =
        TlsOptions.builder(tls.serverContext()).clientAuthRequired(true).build();

    NettyServerTransport delegate =
        new NettyServerTransport(
            NettyServerTransportConfig.builder("127.0.0.1", port).tlsOptions(tlsOptions).build());
    ServerTransport recordingTransport = new RecordingServerTransport(delegate, peerCertificate);

    ApciSettings apci = ApciSettings.defaults();
    ServerConfig serverConfig =
        ServerConfig.builder()
            .protocolProfile(ProtocolProfile.iec104Default())
            .sessionSettings(apci)
            .station(InteropServerFixture.buildStation())
            .handler(InteropServerFixture.acceptRejectHandler())
            .build();

    Cs104Binding binding = new Cs104Binding(apci, ProtocolProfile.iec104Default());
    Iec60870Server server =
        new DefaultIec60870Server(
            recordingTransport,
            serverConfig,
            (connection, events, scheduler) ->
                binding.bindServer(
                    connection,
                    events,
                    scheduler,
                    serverConfig.maxOutboundQueue(),
                    serverConfig.eventQueuePolicy()));
    server.start();
    return server;
  }

  private static ImageFromDockerfile image() {
    return new ImageFromDockerfile("iec60870-test-interop/lib60870c-interop", false)
        .withFileFromPath(".", Path.of("docker/lib60870c"));
  }

  private static int freeEphemeralPort() throws IOException {
    try (ServerSocket socket = new ServerSocket(0)) {
      socket.setReuseAddress(true);
      return socket.getLocalPort();
    }
  }

  private static void assertContains(String haystack, String needle, String fullLog) {
    assertTrue(
        haystack.contains(needle),
        () -> "expected client log to contain '" + needle + "'. Full client log:\n" + fullLog);
  }

  private static boolean containsResultWithFailZero(String output) {
    for (String line : output.split("\\R")) {
      if (line.contains("INTEROP-CLIENT RESULT") && line.contains("fail=0")) {
        return true;
      }
    }
    return false;
  }

  private record RecordingServerTransport(
      ServerTransport delegate, AtomicReference<@Nullable Certificate> peerCertificateRef)
      implements ServerTransport {

    @Override
    public CompletionStage<Void> bind() {
      return delegate.bind();
    }

    @Override
    public CompletionStage<Void> unbind() {
      return delegate.unbind();
    }

    @Override
    public void setConnectionHandler(Consumer<ServerTransportConnection> onAccept) {
      delegate.setConnectionHandler(
          connection -> onAccept.accept(new RecordingConnection(connection, peerCertificateRef)));
    }
  }

  private record RecordingConnection(
      ServerTransportConnection delegate, AtomicReference<@Nullable Certificate> peerCertificateRef)
      implements ServerTransportConnection {

    @Override
    public CompletionStage<Void> send(ByteBuf frame) {
      return delegate.send(frame);
    }

    @Override
    public void setListener(TransportListener listener) {
      delegate.setListener(
          new TransportListener() {
            @Override
            public void onFrame(ByteBuf frame) {
              delegate.peerCertificate().ifPresent(peerCertificateRef::set);
              listener.onFrame(frame);
            }

            @Override
            public void onConnectionLost(@Nullable Throwable cause) {
              listener.onConnectionLost(cause);
            }
          });
    }

    @Override
    public void close() {
      delegate.close();
    }

    @Override
    public java.net.SocketAddress remoteAddress() {
      return delegate.remoteAddress();
    }

    @Override
    public java.util.Optional<Certificate> peerCertificate() {
      return delegate.peerCertificate();
    }
  }
}
