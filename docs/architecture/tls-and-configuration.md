# TLS and Configuration

## TLS

TLS is configured in core but applied by the transport. The core type is `TlsOptions`
(`com.digitalpetri.iec60870.TlsOptions`), a small immutable holder that carries:

- an `javax.net.ssl.SSLContext` supplying the key and trust material, and
- a `clientAuthRequired` flag selecting mutual authentication.

It is built with a fluent builder seeded from the context:

```java
TlsOptions options = TlsOptions.builder(sslContext)
    .clientAuthRequired(true)   // require the peer to present a certificate (server side)
    .build();
```

`TlsOptions` contains **no transport-specific types** — just the standard `SSLContext`. This is what
lets TLS be configured against the core API while the actual engine (Netty's `SslHandler`) lives only
in `iec60870-transport-tcp`. The transport translates `TlsOptions` into its concrete TLS engine; the
`clientAuthRequired` flag requires client authentication on a server and has no effect on a client.
`TlsOptions` is passed to the transport-module builders (`TcpIec104Client.builder().tls(...)` /
`TcpIec104Server.builder().tls(...)`), not to `ClientConfig`/`ServerConfig`, because transport
concerns are kept out of core configuration.

### Handshake gating

The connection is not considered established until the TLS handshake completes. The transport
contract states it plainly: `ClientTransport.connect()`'s returned stage completes **only after the
handshake succeeds** when TLS is configured. In the Netty implementation this means waiting on the
`SslHandler`'s handshake future before completing the connect stage. Consequently
`Iec60870Client.connect()` (which blocks on `connectAsync()`) returns only once the secure channel is
fully up — and, when `startDataTransferOnConnect` is set, after the `STARTDT` handshake as well. A
handshake or trust failure surfaces as a failed connect, mapped to the typed exceptions described in
[errors-and-extensibility.md](errors-and-extensibility.md).

### Peer certificate exposure

On the server side, the only TLS context exposed to the application is deliberately narrow. A
`ServerTransportConnection` (the transport's per-connection handle) exposes:

- `SocketAddress remoteAddress()`, and
- `Optional<java.security.cert.Certificate> peerCertificate()` — the peer's certificate when the
  connection is secured with TLS and the peer presented one; always empty for a non-TLS or serial
  transport, which carries no peer certificate.

There is no leak of `SSLSession`, `SslHandler`, or any other engine type into core; the connection
surfaces just the remote address and the peer certificate, which is all a server needs to authorize a
controlling station. Through the high-level server, the connection's `remoteAddress()` also appears on
the `ServerEvent` records and on the `ServerContext` handed to a `ServerHandler`.

## Configuration and profiles

Two records carry the protocol-layer configuration shared by client and server. Both are immutable,
validate in their compact constructors, and provide standard-default factories. Transport knobs
(host, port, TLS) are **not** here — they live on the transport-module builders.

### ProtocolProfile — field widths

`ProtocolProfile(int cotLength, int commonAddressLength, int ioaLength, int maxAsduLength)` fixes the
station-wide wire field widths that both peers must agree on:

| Field | Meaning | Valid range | IEC 104 default |
|---|---|---|---|
| `cotLength` | Cause-of-transmission octets; `1` omits the originator address octet, `2` includes it | 1..2 | 2 |
| `commonAddressLength` | Common address octets (little-endian) | 1..2 | 2 |
| `ioaLength` | Information object address octets (little-endian) | 1..3 | 3 |
| `maxAsduLength` | Maximum ASDU length in octets | 1..255 | 249 |

The `maxAsduLength` bound is the single-octet ceiling (`255`); it is validated only at construction
and never enforced at encode/decode. The previous `249` ceiling was the 104-specific maximum; the
relaxed `1..255` range admits 104's `249` and any single-octet frame a future protocol profile needs.

`ProtocolProfile.iec104Default()` returns `(2, 2, 3, 249)` — the standard 104 profile, with an
originator address present. The profile is what `Asdu.Serde` and `Apdu.Serde` consult to know how many
octets each address field occupies and whether to read/write the originator octet.

### ApciSettings — window and timers

`ApciSettings(UShort k, UShort w, Duration t0, Duration t1, Duration t2, Duration t3)` carries the
APCI flow-control parameters consumed by `ApciSession`:

| Field | Meaning | Valid range | Default |
|---|---|---|---|
| `k` | Max outstanding unacknowledged I-frames | 1..32767 | 12 |
| `w` | Received I-frames before an acknowledgement is sent | 1..32767, and `w ≤ k` | 8 |
| `t0` | Connection-establishment timeout | positive | 30 s |
| `t1` | Timeout on a sent frame awaiting acknowledgement | positive | 15 s |
| `t2` | Timeout for acknowledging received frames (should be `< t1`) | positive | 10 s |
| `t3` | Idle timeout after which a `TESTFR` is sent | positive | 20 s |

`ApciSettings.defaults()` returns `k=12, w=8, t0=30s, t1=15s, t2=10s, t3=20s`. The compact constructor
rejects out-of-range window sizes, `w > k`, and any non-positive duration. See
[apci-and-timers.md](apci-and-timers.md) for how these drive the session.

### Where the profiles are used

The `ProtocolProfile` is set on `ClientConfig` and `ServerConfig` (defaulting to
`ProtocolProfile.iec104Default()`). The APCI parameters are *not* held directly; both configs carry a
neutral `SessionSettings` handle instead (see below), defaulting to `ApciSettings.defaults()`. The
configs also hold the non-transport behavioral knobs:

- **`ClientConfig`** — `originatorAddress`, an optional `pointCatalog`, `startDataTransferOnConnect`
  (default `true`), `commandTimeout` (10 s), `requestTimeout` (30 s, for interrogation/read/clock-sync),
  `callbackExecutor`, and a `typeCodecRegistry`.
- **`ServerConfig`** — the hosted `stations`, the `ServerHandler`, an `OutboundQueuePolicy` for a full
  outbound queue, the `TimeTagStyle` used when reporting monitor data (default `CP56`),
  `maxConnections` (16), `callbackExecutor`, and a `typeCodecRegistry`.

### SessionSettings — the neutral session-settings handle

`ClientConfig` and `ServerConfig` do not name `ApciSettings` directly; they carry a
`SessionSettings sessionSettings` component. `SessionSettings` is a non-sealed marker interface in the
core root package, and `ApciSettings implements SessionSettings`. The protocol-specific settings that
parameterize a session are inherently protocol-specific (104's `k`/`w` window and `t0`-`t3` timers
live on `ApciSettings`), so the neutral configs hold the marker and the binding that assembles the
concrete session downcasts the handle to the type it understands. The marker is deliberately
*not* sealed: a sealed hierarchy would force its permitted subtypes into the core module, but the
protocol-specific settings live in their own protocol modules (which depend on core, not the
reverse), so an open marker keeps that dependency direction correct.

### OutboundQueuePolicy — a full outbound session queue

A single neutral `OutboundQueuePolicy` enum in the core root package governs what happens when a
session's bounded outbound queue is full and another ASDU is offered: `DROP_OLDEST` (the default,
exposed as `OutboundQueuePolicy.DEFAULT`), `DROP_NEWEST`, or `BLOCK` (backpressure). It reads
correctly for a 104 `k`-window draining the head of the queue *and* a future 101 stop-and-wait
window of one. `ServerConfig.eventQueuePolicy` is this type.

Both configs are built with a defaults-seeded builder, so a usable configuration needs only the
fields an application actually wants to change.
