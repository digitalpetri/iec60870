# How-To: Secure with TLS

IEC 104 runs over plain TCP on port 2404 by default. TLS adds encryption and authentication at the
transport layer, beneath the protocol. You configure it with a single core type,
[`TlsOptions`](../../architecture/tls-and-configuration.md), and attach it to the transport builders'
`.tls(...)` method — it never touches the protocol config (`ClientConfig` / `ServerConfig`) or any
Netty type.

This page is mirror-imaged: the **client** is the
[controlling station](../reference/glossary.md) (master, an `Iec104Client`) and the **server** is the
[controlled station](../reference/glossary.md) (outstation, an `Iec104Server`). It is anchored on the
runnable
[`TlsExample`](../../../iec104-examples/src/main/java/com/digitalpetri/iec104/examples/TlsExample.java),
which starts a TLS server and client in-process and interrogates over the secured link; see the
[examples README](../../../iec104-examples/README.md) for run instructions.

## At a glance

- TLS lives on the transport builders' `.tls(TlsOptions)` method, not on the protocol config. For the
  protocol-layer knobs (`k`/`w` window, `t0`–`t3` timers, addressing field sizes) see
  [Tune the APCI session](./tune-apci.md).
- `TlsOptions` carries a standard `javax.net.ssl.SSLContext` plus two flags — `clientAuthRequired`
  and `verifyHostname`. There are no Netty or transport types on its surface.
- **Hostname verification is on by default** on the client.
- With TLS configured, `connect()` returns only after the TLS handshake succeeds (handshake gating).
- Mutual (client-certificate) authentication is a **server-side** switch.

This page secures the plaintext baseline from [Getting Started](../getting-started.md); read that
first if you have not yet run a working client and server.

## Step 1: Generate test certificates

For a quick self-signed setup, generate a PKCS#12 keystore for the server identity and export its
certificate into a truststore the client will trust. This is the exact recipe the example uses:

```bash
keytool -genkeypair -alias server -keyalg RSA -keysize 2048 \
    -dname "CN=localhost" -ext "san=dns:localhost,ip:127.0.0.1" \
    -keystore server.p12 -storetype PKCS12 -storepass changeit -validity 365
keytool -exportcert -alias server -keystore server.p12 -storepass changeit -file server.crt
keytool -importcert -noprompt -alias server -file server.crt \
    -keystore truststore.p12 -storetype PKCS12 -storepass changeit
```

After this you have two files: `server.p12` holds the **server identity** (its certificate and
private key), and `truststore.p12` holds the **certificate the client trusts** when validating the
server.

> The `-ext "san=dns:localhost,ip:127.0.0.1"` Subject Alternative Name is what lets hostname
> verification pass when the client dials `localhost` or `127.0.0.1`. Without a matching SAN, the
> default-on hostname check (Step 4) rejects the handshake.

This is a self-signed setup for local testing. In production you use a CA-issued certificate chain
instead of a self-signed certificate.

## Step 2: Build an SSLContext

Loading the key and trust material into an `SSLContext` is plain Java security (JSSE), not library
API — the library's surface starts at `TlsOptions`. The steps below are included so the recipe is
copy-paste complete; the library performs no keystore loading itself.

The **server** loads a **key**store (its identity) through a `KeyManagerFactory`:

```java
KeyStore store = KeyStore.getInstance("PKCS12");
try (InputStream in = Files.newInputStream(Path.of("server.p12"))) {
  store.load(in, "changeit".toCharArray());
}
KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
kmf.init(store, "changeit".toCharArray());

SSLContext serverContext = SSLContext.getInstance("TLS");
serverContext.init(kmf.getKeyManagers(), null, new SecureRandom());
```

The **client** loads a **trust**store (the certificates it trusts) through a `TrustManagerFactory`:

```java
KeyStore store = KeyStore.getInstance("PKCS12");
try (InputStream in = Files.newInputStream(Path.of("truststore.p12"))) {
  store.load(in, "changeit".toCharArray());
}
TrustManagerFactory tmf =
    TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
tmf.init(store);

SSLContext clientContext = SSLContext.getInstance("TLS");
clientContext.init(null, tmf.getTrustManagers(), new SecureRandom());
```

Both helpers produce an `SSLContext` via `SSLContext.getInstance("TLS")`. The example chooses the
store type by file extension — `.jks` loads as `JKS`, everything else as `PKCS12` — so JKS keystores
work too; the snippets above inline `PKCS12` for brevity. For mutual authentication (Step 5) the
client's context also needs key material and the server's context also needs a truststore.

## Step 3: Secure the server

Attach the server's `SSLContext` by wrapping it in `TlsOptions` and passing it to the builder's
`.tls(...)` method. This is the **only** line that differs from a plaintext server — see
[Host a server](./host-a-server.md) for building the `Station` and `ServerHandler`. Imports omitted.

```java
try (Iec104Server server =
    TcpIec104Server.builder()
        .bindAddress("127.0.0.1")
        .port(19998)
        .addStation(station)
        .tls(TlsOptions.builder(serverContext).build())
        .build()) {

  server.start();
  // ... serve until shutdown ...
}
```

`Iec104Server` is `AutoCloseable`, so the try-with-resources block stops the server and releases
transport resources on exit. The port is `19998` — a non-default port so it does not clash with a
plaintext server on 2404, matching the example you can run below.

## Step 4: Secure the client

Mirror the server: wrap the client's `SSLContext` in `TlsOptions` and pass it to `.tls(...)`.

```java
try (Iec104Client client =
    TcpIec104Client.builder()
        .host("127.0.0.1")
        .port(19998)
        .tls(TlsOptions.builder(clientContext).build())
        .startDataTransferOnConnect(true)
        .build()) {

  client.connect(); // returns only after the TLS handshake (and STARTDT) succeed
  InterrogationResult snapshot = client.interrogate(CommonAddress.of(1));
}
```

With TLS configured, `connect()` performs **handshake gating**: it returns only after the TLS
handshake completes, and — because `startDataTransferOnConnect(true)` is set — after the
[STARTDT](../reference/glossary.md) handshake too. A trust, hostname, or other handshake failure
surfaces as a failed `connect()` (the blocking call throws; the `*Async` variant completes
exceptionally). See the [error model reference](../reference/errors.md) for what a failed `connect()`
raises.

### Hostname verification

`verifyHostname` defaults to `true`: the client rejects a server certificate whose SAN (or CN) does
not match the host it dialed. This is the JDK `HTTPS` endpoint-identification check, and it runs
during the handshake. The `-ext "san=..."` in Step 1 is what makes the check pass for `localhost` /
`127.0.0.1`.

You can disable it for local testing where the certificate SAN cannot match the host:

```java
// Local testing only: removes protection against a substituted server certificate.
TlsOptions options =
    TlsOptions.builder(clientContext)
        .verifyHostname(false)
        .build();
```

Disabling hostname verification removes protection against a man-in-the-middle that presents a
different (but trusted) certificate. Leave it on outside local testing.

## Step 5: Require client certificates (mutual TLS)

By default only the server authenticates itself. To require the client to present a certificate too —
mutual TLS — set `clientAuthRequired(true)` on the **server's** `TlsOptions`:

```java
TlsOptions serverTls =
    TlsOptions.builder(serverContext)
        .clientAuthRequired(true) // server demands a client certificate
        .build();
```

`clientAuthRequired(true)` forces the client to present a trusted certificate or the handshake fails.
It is a server-side switch: setting it on a client's `TlsOptions` has no effect.

Mutual TLS also changes the key/trust material. The client's `SSLContext` now needs **key** managers
(its own identity) and the server's `SSLContext` now needs **trust** managers (a truststore for the
client certificates) — the inverse of Step 2. Build them with the same `KeyManagerFactory` /
`TrustManagerFactory` pattern, with the roles swapped. (`TlsExample` does not demonstrate mutual TLS;
its server context has no truststore and its client context has no keystore, so wiring the extra
material is left to you.)

## Reading the peer certificate

When you require client certificates, you may want to inspect the certificate the controlling station
presented. The peer certificate is exposed in **one** place: the transport-layer interface
`ServerTransportConnection.peerCertificate()` (package `com.digitalpetri.iec104.transport`), which
returns an `Optional<java.security.cert.Certificate>` — present only when the connection uses TLS and
the peer presented a certificate (that is, when `clientAuthRequired(true)` is set).

```java
// Transport-layer interface (com.digitalpetri.iec104.transport):
Optional<Certificate> cert = serverTransportConnection.peerCertificate();
```

The high-level `Iec104Server` facade does **not** surface the peer certificate. Both `ServerContext`
(handed to your `ServerHandler`) and the `ServerEvent` records expose only `remoteAddress()`, a
`java.net.SocketAddress` — there is no `peerCertificate()` accessor on either. So when authorizing a
controlling station through the facade, you have the remote address; the certificate is reachable
only if you work at the transport interface directly. See
[tls-and-configuration.md](../../architecture/tls-and-configuration.md) for why core keeps this TLS
surface narrow and does not leak an `SSLSession` into the facade.

## TlsOptions reference

`TlsOptions` is built through `TlsOptions.builder(sslContext)` — there is no public constructor.
The three configuration knobs:

| Knob | Meaning | Default | When to change | Consequence |
|---|---|---|---|---|
| `sslContext` (the `TlsOptions.builder(...)` argument) | Supplies all key + trust material through a standard `javax.net.ssl.SSLContext`. | required | Always — you must supply one. | The library loads no key/trust material itself; every choice (algorithms, key/trust stores, enabled protocols) is decided by how you build the `SSLContext`. |
| `clientAuthRequired(boolean)` | Server demands the peer (client) present a certificate — mutual TLS. | `false` | Set `true` on the **server** to authenticate the controlling station. | On a server, the client must present a trusted certificate or the handshake fails; enables `peerCertificate()` to be non-empty on the server's transport connection. On a client, no effect. |
| `verifyHostname(boolean)` | Client checks the server certificate's SAN/CN matches the dialed host (JDK `HTTPS` endpoint identification). | `true` | Set `false` only for local testing where the certificate SAN cannot match the host. | When `true`, a certificate valid for a different host is rejected during the handshake. When `false`, the client accepts any trusted certificate regardless of host, removing man-in-the-middle protection. On a server, no effect. |

## Run the TLS example

`TlsExample` starts a TLS server and client in-process and interrogates the station over the secured
link. Generate the certificates from Step 1 first, then run it with the four system properties
(it prints instructions and exits if they are unset):

```bash
mise exec -- mvn -q -pl iec104-examples exec:java \
    -Dexec.mainClass=com.digitalpetri.iec104.examples.TlsExample \
    -Dexec.args="" \
    -Diec104.tls.keystore=server.p12 -Diec104.tls.keystorePassword=changeit \
    -Diec104.tls.truststore=truststore.p12 -Diec104.tls.truststorePassword=changeit
```

See the [examples README](../../../iec104-examples/README.md) for the full set of runnable examples.

## See also

- [User Guide index](../README.md)
- [Getting Started](../getting-started.md) — the plaintext baseline this page secures
- [Host a server](./host-a-server.md) — building the `Station` and `ServerHandler`
- [Connect & interrogate](./connect-and-interrogate.md) — what the client does after the secured connect
- [Tune the APCI session](./tune-apci.md) — `ApciSettings` / `ProtocolProfile`: protocol config, not transport config
- [Handle events](./handle-events.md) — the `ServerEvent` / `ClientEvent` surface, including the `remoteAddress()` you get per connection
- [Reference: Error model](../reference/errors.md) — what a failed handshake throws on `connect()`
- [Reference: Timers & window](../reference/timers-and-window.md) — STARTDT and the `t0`–`t3` timers
- [Reference: Glossary](../reference/glossary.md) — controlling/controlled station, STARTDT, Common Address
- [`TlsExample`](../../../iec104-examples/src/main/java/com/digitalpetri/iec104/examples/TlsExample.java) — the runnable example this page mirrors
- Architecture deep-dive: [tls-and-configuration.md](../../architecture/tls-and-configuration.md) and [modules-and-dependencies.md](../../architecture/modules-and-dependencies.md)
