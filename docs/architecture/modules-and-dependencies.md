# Modules and Dependencies

The library is a Maven multi-module build under the `iec60870-parent` POM. Eight modules matter to a
caller: `iec60870-core` (the protocol model and SPIs), `iec60870-cs104` (the 104 APCI link/session
engine), `iec60870-cs101` (the 101 FT1.2 link layer), `iec60870-application` (the high-level
`Iec60870Client`/`Iec60870Server` API, with no Netty, shared by both profiles), `iec60870-transport-tcp`
(the Netty TCP/TLS **octet transport only**, core-only), `iec60870-transport-serial` (the serial-port
**octet transport only**, core-only), `iec60870-tcp` (the **assembly module** holding the user-facing
builders that wire a 104 — or, optionally, a 101 — stack over TCP), and `iec60870-serial` (the
**assembly module** holding the user-facing builders that wire a 101 stack over a serial port). A
further module, `iec60870-test-integration`, holds cross-module integration tests, and an internal
`iec60870-test-common` module holds the shared, test-only fixtures those suites reuse.

## The core / cs104 / application / transport split

`iec60870-core` owns everything that is independent of how bytes reach the network, of the 104 link
layer, and of the high-level facade:

- the raw protocol model and codecs (`.asdu`, `.address`, `.asdu.element`, `.asdu.time`);
- the **`Session` SPI** (`.session`) — the protocol-neutral data-transfer lifecycle contract — but
  **no** session engine: the concrete 104 `ApciSession` lives in `iec60870-cs104`;
- the error model (package-root exceptions) and the configuration types (`ProtocolProfile`, the
  neutral `SessionSettings` marker, the neutral `OutboundQueuePolicy`, `TlsOptions`);
- the **octet transport SPI** (`.transport`) — `ClientTransport`, `ServerTransport`,
  `ServerTransportConnection`, `TransportListener` — which the implementations drive but do not
  implement. The SPI is shaped in **whole-frame `ByteBuf`s**: `send(ByteBuf)` and `onFrame(ByteBuf)`
  exchange one complete, length-delimited frame each. Turning a frame into an `Apdu` (via
  `ApduFramer`) happens above the SPI, so the 101-over-TCP variant reuses the same transport.
  `ClientTransport.disconnect()` means intentional shutdown; `ClientTransport.closeConnection()`
  closes only the current connection so a persistent transport can reconnect after a protocol
  binding drops malformed input.

`iec60870-cs104` owns the genuinely-104 link/session code (the package `.cs104`):

- the APCI session engine `com.digitalpetri.iec60870.cs104.ApciSession` — the symmetric sequence /
  window / timer state machine used by both roles — which `implements` the core `Session` SPI;
- the `Apdu` / `ControlField` / `UFunction` model and the `Apdu.Serde` codec, plus the `ApduFramer`
  `Apdu`↔`ByteBuf` bridge that the assembly layer uses to frame and deframe whole frames;
- `ApciSettings`, the 104 `k`/`w` window and `t0`–`t3` timer parameters, which `implements` the
  neutral `SessionSettings` marker from core;
- `Cs104Binding`, the **assembly point** that wires an `ApciSession` to a core octet transport
  handle (see below). It depends only on `cs104` + core + `netty-buffer` and imports nothing from
  `transport-tcp` or `application`, so any octet transport — the Netty TCP transport, or a serial
  transport — can be bound to a 104 session through it.

It depends on `iec60870-core` (and `netty-buffer` for the `ByteBuf` codec boundary). The dependency
direction is one-way: core never references `cs104`, which is why the 101 link layer drops in as a
sibling module (`iec60870-cs101`, below) with no surgery on core.

`iec60870-application` owns the high-level layer and carries **no Netty at all** (not even
`ByteBuf`):

- the high-level client and server *interfaces* and their default implementations
  (`Iec60870Client` / `DefaultIec60870Client`, `Iec60870Server` / `DefaultIec60870Server`);
- the point and catalog model (`.point`, `.catalog`), the command/station model, and the
  configuration records (`ClientConfig`, `ServerConfig`).

It depends on `iec60870-core` only and speaks purely in terms of `Asdu` + the `Session` SPI. The
facades never construct an `ApciSession` or touch `Apdu`/`ByteBuf` framing; instead they accept an
**injected `Session`** (client) or a **per-connection session factory** (server), so the
protocol-specific session and its wire framing are assembled elsewhere. A `NoNettyInApplicationTest`
guard fails the build if any `io.netty.*` token appears in the application's main sources.

`iec60870-transport-tcp` owns the Netty **octet transport only** — it is core-only and holds no
builder:

- `NettyClientTransport` / `NettyServerTransport`, the implementations of the core transport
  interfaces;
- the framing handler `Iec104FrameDecoder`, which slices one whole-frame `ByteBuf` per complete
  frame off the wire, and `InboundFrameHandler`, which forwards each frame to the `TransportListener`
  (outbound is a raw `ByteBuf` write — there is no encoder handler);
- TLS via Netty's `SslHandler`, and the client channel lifecycle via
  `com.digitalpetri.netty:netty-channel-fsm`.

These octet classes import nothing from `cs104`, `cs101`, or `application`; a
`TcpTransportDependencyGuardTest` enforces that core-only boundary.

`iec60870-tcp` owns the **user-facing entry points** `TcpIec104Client` and `TcpIec104Server` (package
`com.digitalpetri.iec60870.tcp`), whose builders carry the transport knobs (`host`, `port`,
`localBind`, `TlsOptions`, bootstrap/event-loop customization).

A builder in the assembly module is the **104 assembly point**: it constructs the Netty transport
(from `transport-tcp`), delegates the 104 session wiring to a `Cs104Binding` (in `cs104`), and hands
the assembled `Session` (client) / session factory (server) to `new DefaultIec60870Client(...)` /
`new DefaultIec60870Server(...)`. The builder itself contains no `new ApciSession(...)` and no direct
`ApduFramer` call — that wiring lives entirely in `Cs104Binding`. `build()` then returns the
**application interface** type:

```java
// Returns com.digitalpetri.iec60870.client.Iec60870Client — an application type.
try (Iec60870Client client = TcpIec104Client.builder()
        .host("127.0.0.1").port(2404)
        .startDataTransferOnConnect(true)
        .build()) {
  client.connect();
  InterrogationResult snapshot = client.interrogate(CommonAddress.of(1));
}
```

This is a deliberate placement. The protocol methods on the returned object — `connect`,
`startDataTransfer`, `interrogate`, `read`, `commands`, `events`, `synchronizeClock`, `send`,
`close`, and the `*Async` variants — are all defined on the application `Iec60870Client` interface.
Only the *transport wiring* (host, port, TLS) lives in the assembly-module builder; the 104 session
assembly itself is delegated to `Cs104Binding` in `cs104`.

### `Cs104Binding`: the 104 session assembly

`com.digitalpetri.iec60870.cs104.Cs104Binding` is the single, reusable place the `Apdu`↔octet wiring
lives. Given a core octet transport handle (a `ClientTransport`, or a `ServerTransportConnection` for
one accepted server connection) plus an `ApciSettings`, a `ProtocolProfile`, a `ByteBufAllocator`
(defaulting to an unpooled allocator), the facade's `Session.Events` sink, and the shared scheduler,
it:

- constructs an `ApciSession` whose `Output.send(Apdu)` runs `ApduFramer.encode(apdu, profile,
  allocator)` then `octetTransport.send(ByteBuf)`;
- registers a `TransportListener` whose `onFrame(ByteBuf)` runs `ApduFramer.decode(profile, frame)`
  then `apciSession.onApdu(apdu)`;
- routes a failed outbound send or a connection loss to `Session.Events.onConnectionLost`;
- closes the underlying current connection when a malformed inbound frame fails during deframing,
  using `ClientTransport.closeConnection()` for client sessions and
  `ServerTransportConnection.close()` for server sessions;
- performs the `(ApciSettings) sessionSettings` downcast — this is the one place that downcast lives.

The `TcpIec104Client`/`TcpIec104Server` builders are the *only* types that name both the protocol
(104) and the transport (TCP); they construct the `NettyClientTransport`/`NettyServerTransport`, then
call `Cs104Binding.bindClient(...)` / `bindServer(...)` to produce the `Session` / session factory.
Because `Cs104Binding` depends only on `cs104` + core + `netty-buffer`, it is independent of the
transport: the same `NettyClientTransport`/`NettyServerTransport` is reused unchanged by the serial
and 101-over-TCP builders, which bind their `Ft12LinkLayer` through the parallel `Cs101Binding`
(see below).

A caller who already has a transport and a `Session` can bypass the builders entirely and construct
`new DefaultIec60870Client(clientTransport, clientConfig, sessionFactory)` directly; the high-level
behavior is identical.

## The 101 link layer and serial transport

The IEC 60870-5-101 serial profile is built as two sibling modules that mirror the 104 pair, sharing
the same `iec60870-core` model and `iec60870-application` facade.

`iec60870-cs101` owns the genuinely-101 link code (the package `.cs101`):

- the FT1.2 link layer `com.digitalpetri.iec60870.cs101.Ft12LinkLayer` — which `implements` the core
  `Session` SPI as a **peer of `ApciSession`**, not a transport plugged under it. It is a thin
  dispatcher that selects an engine by `(role, mode)`: the symmetric point-to-point `BalancedEngine`,
  or the `UnbalancedMasterEngine` / `UnbalancedSlaveEngine` of the master/secondary polling machine;
- the `Ft12Frame` / `LinkControlField` model and the `Ft12Framer` `Ft12Frame`↔`ByteBuf` codec that
  the assembly layer uses to frame and deframe whole FT1.2 frames, the way `cs104` owns
  `Apdu`/`ApduFramer`;
- `LinkSettings`, the FT1.2 addressing, acknowledgement, and stop-and-wait timer parameters, which
  `implements` the neutral `SessionSettings` marker from core;
- `Cs101Binding`, the **assembly point** that is the FT1.2 peer of `Cs104Binding`: it wires an
  `Ft12LinkLayer` to a core octet transport handle, framing outbound `Ft12Frame`s and deframing
  inbound whole frames. It depends only on `cs101` + core + `netty-buffer` and imports nothing from
  `transport-tcp`, `transport-serial`, or `application`.

It depends on `iec60870-core` (and `netty-buffer` for the `ByteBuf` codec boundary). For 101 the
link reset is the data-transfer-start analog: `Session.startDataTransfer()` drives the FT1.2
link-reset bring-up and `isDataTransferStarted()` reports link availability, just as STARTDT does on
the 104 session.

`iec60870-transport-serial` owns the serial **octet transport only** (the package
`.transport.serial`) — it is core-only and holds no builder:

- `SerialClientTransport` / `SerialServerTransport`, the implementations of the core transport
  interfaces over a serial port via jSerialComm (`Ft12SerialChannel`, `SerialServerConnection`);
- the framing handler `Ft12Deframer`, which slices one whole-frame `ByteBuf` per complete FT1.2 frame
  off the byte stream, and the `SerialPortConfig` / `Rs485Options` knobs.

Its octet classes depend on **core only** — the same octet-classes-stay-core-only rule
`transport-tcp` follows — and a `SerialTransportDependencyGuardTest` enforces that boundary.

`iec60870-serial` owns the **user-facing entry points** `SerialIec101Client` and `SerialIec101Server`
(package `com.digitalpetri.iec60870.serial`), whose builders carry the serial knobs (port name,
baud/parity/stop bits, RS-485 options). Each builder constructs the serial transport (from
`transport-serial`) and calls `Cs101Binding.bindClient(...)` / `bindServer(...)` to assemble the
`Session` / session factory exactly as the `Tcp*` builders call `Cs104Binding`.

Because `Cs104Binding` and `Cs101Binding` each depend only on their link module + core +
`netty-buffer`, either binding can be wired to any octet transport. `iec60870-tcp` exploits
this to offer the optional 101-over-TCP entry points `TcpIec101Client` / `TcpIec101Server`, which
reuse the same `NettyClientTransport` / `NettyServerTransport` but install an `Ft12FrameDecoder` in
place of `Iec104FrameDecoder` and delegate to `Cs101Binding` — which is why `iec60870-tcp` depends on
both `cs104` and `cs101`. The `SerialIec101*` and `TcpIec101*` builders return the same application
`Iec60870Client` / `Iec60870Server` interfaces the 104 builders return; only the link layer and
transport beneath the `Session` SPI differ.

## Dependency rules (the boundary that keeps core transport-agnostic)

The split is enforced by a strict rule about which types may cross which package boundary.

**No Netty runtime type appears in core or in the high-level public API.** Concretely, the following
must never appear in any `iec60870-core` package:

- `io.netty.channel.*` — `Channel`, `EventLoopGroup`, `ChannelHandler`, `ServerBootstrap`;
- `io.netty.handler.*` — including `SslHandler` and the TLS engine wiring;
- a `ByteBuf` in any *high-level* public API signature (`Iec60870Client`/`Iec60870Server`, the
  event/command/point/catalog model).

The single, *deliberate* exception is `netty-buffer`. `ByteBuf` is the sanctioned codec-boundary
type: the raw codec layer encodes and decodes through co-located `Serde` classes that operate on
`io.netty.buffer.ByteBuf`, **and** the octet transport SPI exchanges whole-frame `ByteBuf`s. Core
declares a direct `netty-buffer` dependency for exactly that reason. The `iec60870-core` POM
documents this in a comment:

> Deliberate netty-buffer dependency: the raw codec layer encodes/decodes ASDUs through co-located
> Serde classes that operate on Netty ByteBuf. ByteBuf is confined to the codec layer; it must not
> appear in high-level client/server/model public API signatures, and no netty-channel/handler types
> are permitted in core.

So `ByteBuf` is allowed inside the nested `Serde` classes of core's `.asdu`, `.address`,
`.asdu.element`, and `.asdu.time` (for example `Asdu.Serde`, `CommonAddress.Serde`) and of cs104's
`.cs104` (`Apdu.Serde`, `ControlField.Serde`), and on the `.transport` octet SPI (`send(ByteBuf)`,
`onFrame(ByteBuf)`). It must not reach `Iec60870Client`, `Iec60870Server`, the
event/command/point/catalog types, `ProtocolProfile`, or `ApciSettings`. Above those boundaries
everything is an immutable Java object; the `ApciSession` itself never touches a `ByteBuf`.

The octet transport SPI in `.transport` is the formal seam. It is expressed entirely in core types —
whole-frame `ByteBuf`s, `SocketAddress`, and `java.security.cert.Certificate`, never a Netty channel
or handler type — so `iec60870-transport-tcp` can implement it with Netty without that detail leaking
back into core.

### Dependency table

| Module | Compile dependencies | Notes |
|---|---|---|
| `iec60870-core` | `org.jspecify:jspecify`, `org.jooq:joou`, `io.netty:netty-buffer`, `org.slf4j:slf4j-api` | `netty-buffer` confined to `Serde`/transport SPI; no channel/handler |
| `iec60870-cs104` | `iec60870-core`, `org.jspecify:jspecify`, `org.jooq:joou`, `io.netty:netty-buffer`, `org.slf4j:slf4j-api` | the 104 link/session engine; `netty-buffer` confined to the `Apdu`/`ControlField` `Serde` codecs; published API module |
| `iec60870-cs101` | `iec60870-core`, `org.jspecify:jspecify`, `org.jooq:joou`, `io.netty:netty-buffer`, `org.slf4j:slf4j-api` | the 101 FT1.2 link layer; `netty-buffer` confined to the `Ft12Frame`/`LinkControlField` `Serde` codecs and `Ft12Framer`; published API module |
| `iec60870-application` | `iec60870-core`, `org.jspecify:jspecify`, `org.jooq:joou`, `org.slf4j:slf4j-api` | **zero Netty**; depends on core only (never on cs104 or cs101). Guarded by `NoNettyInApplicationTest` |
| `iec60870-transport-tcp` | `iec60870-core`, `org.jspecify:jspecify`, `io.netty:netty-buffer`, `io.netty:netty-codec`, `io.netty:netty-handler`, `com.digitalpetri.netty:netty-channel-fsm`, `org.slf4j:slf4j-api` | **core-only** Netty octet transport, including TLS via `netty-handler`; no `cs104`/`cs101`/`application` dependency; the octet classes (`NettyClientTransport`/`NettyServerTransport`, pipeline, decoders) stay link- and application-free, enforced by `TcpTransportDependencyGuardTest` |
| `iec60870-transport-serial` | `iec60870-core`, `org.jspecify:jspecify`, `io.netty:netty-buffer`, `com.fazecast:jSerialComm`, `org.slf4j:slf4j-api` | **core-only** serial octet transport; jSerialComm and `netty-buffer` confined to the octet classes; no `cs101`/`application` dependency; the octet classes (`SerialClientTransport`/`SerialServerTransport`, `Ft12Deframer`) stay cs101- and application-free, enforced by `SerialTransportDependencyGuardTest` |
| `iec60870-tcp` | `iec60870-transport-tcp`, `iec60870-cs104`, `iec60870-cs101`, `iec60870-application`, `iec60870-core`, `org.jspecify:jspecify`, `org.slf4j:slf4j-api` | the **TCP assembly module** (the sole TCP point of convergence); hosts the `TcpIec104*`/`TcpIec101*` builders, which call `Cs104Binding`/`Cs101Binding` over the Netty octet transport |
| `iec60870-serial` | `iec60870-transport-serial`, `iec60870-cs101`, `iec60870-application`, `iec60870-core`, `org.jspecify:jspecify`, `org.slf4j:slf4j-api` | the **serial assembly module** (the sole serial point of convergence); hosts the `SerialIec101*` builders, which call `Cs101Binding` over the serial octet transport |

The sink modules (`iec60870-examples`, `iec60870-test-integration`, `iec60870-test-interop`) name `client`/`server`/
`point` types directly, so each declares a **direct** `iec60870-application` dependency rather than
relying on transitive reach through `iec60870-tcp`. The internal dependency graph is acyclic with a
single source (`core`): `cs104 → core`, `cs101 → core`, `application → core` (`application`, `cs104`,
and `cs101` are mutually incomparable siblings — no edge among them); the octet transports are
core-only (`transport-tcp → core`, `transport-serial → core`); the assembly modules converge the
triple (`tcp → {transport-tcp, cs104, cs101, application, core}`,
`serial → {transport-serial, cs101, application, core}`); and the sinks → `{application, tcp}`
(`iec60870-test-integration` also → `cs101`; `iec60870-examples` and `iec60870-test-interop` also → `serial` for the
`SerialIec101*` examples/tests). Nothing depends back into the application, transport, or assembly
modules. The parent enforcer's `banCircularDependencies` rule asserts this acyclicity on every build.

`iec60870-test-common` is an internal, test-only module that holds the shared, core-level test
fixtures (the deterministic `ManualScheduler`, the `RecordingEvents` `Session.Events` recorder, the
frame-capturing `RecordingClientTransport` / `RecordingServerConnection`, the in-JVM
`LoopbackOctetTransport` and fault-injecting `FaultInjectingOctetTransport` octet transports, and the
`ParanoidLeakDetection` JUnit extension). It depends on **core only** — plus `netty-buffer` for the
`ByteBuf` boundary and `junit-jupiter-api` for the extension — and is consumed at `test` scope by
`iec60870-cs104`, `iec60870-cs101`, `iec60870-application`, `iec60870-transport-tcp`,
`iec60870-transport-serial`, `iec60870-tcp`, `iec60870-serial`, and `iec60870-test-integration`. Keeping it
core-only mirrors the octet-classes-stay-core-only rule and means it can never become a path for a
`cs104`/`cs101`/`application`/transport type to leak across module boundaries. It is never published
(its deploy, sign, and install steps are skipped).

Versions are centralized in the parent POM (Netty `4.1.x`, jOOU `0.9.x`, JSpecify `1.0.0`,
`netty-channel-fsm` `1.0.x`, SLF4J `2.0.x`). The `netty-channel-fsm` dependency excludes
`netty-handler` and `slf4j-api` so the module's own pinned versions win.

## jOOU and JSpecify conventions

Two cross-cutting conventions shape the API surface:

**jOOU unsigned wrappers.** Values that are unsigned on the wire cross public boundaries as jOOU
types behind domain records: a wire `UI8` becomes `org.joou.UByte`, a `UI16` becomes `UShort`, and a
`UI24`/`UI32` becomes `UInteger`. So `CommonAddress` wraps a `UShort`, `InformationObjectAddress`
wraps a `UInteger`, `OriginatorAddress` wraps a `UByte`, and `ApciSettings.k`/`w` are `UShort`. The
signed carrier used by the buffer (`writeShortLE`, `readUnsignedByte`, …) is created only inside a
`Serde` and never escapes a public type. Signed or floating process values (scaled, normalized,
short-float, counters, step position) stay as plain Java primitives (`short`, `float`, `int`).

**JSpecify `@NullMarked`.** Every package carries a `package-info.java` annotated
`@org.jspecify.annotations.NullMarked`. The default is non-null; only genuinely nullable positions
are annotated `@Nullable` (for example `Apdu.asdu()` is non-null only for I-format frames, and
`ClientEvent.ConnectionClosed.cause()` is nullable for an orderly close). This lets a JSpecify-aware
toolchain check nullness across the whole library.
