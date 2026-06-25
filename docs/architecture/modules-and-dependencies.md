# Modules and Dependencies

The library is a Maven multi-module build under the `iec60870-parent` POM. Four modules matter to a
caller: `iec60870-core` (the protocol model and SPIs), `iec60870-cs104` (the 104 link/session
engine), `iec60870-application` (the high-level `Iec60870Client`/`Iec60870Server` API, with no
Netty), and `iec60870-transport-tcp` (the Netty TCP/TLS transport plus the builders that assemble a
104 stack). A further module, `iec60870-tests`, holds cross-module integration tests.

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
  `ApduFramer`) happens above the SPI, so a future 101-over-TCP can reuse the same transport.

`iec60870-cs104` owns the genuinely-104 link/session code (the package `.cs104`):

- the APCI session engine `com.digitalpetri.iec60870.cs104.ApciSession` — the symmetric sequence /
  window / timer state machine used by both roles — which `implements` the core `Session` SPI;
- the `Apdu` / `ControlField` / `UFunction` model and the `Apdu.Serde` codec, plus the `ApduFramer`
  `Apdu`↔`ByteBuf` bridge that the assembly layer uses to frame and deframe whole frames;
- `ApciSettings`, the 104 `k`/`w` window and `t0`–`t3` timer parameters, which `implements` the
  neutral `SessionSettings` marker from core.

It depends on `iec60870-core` (and `netty-buffer` for the `ByteBuf` codec boundary). The dependency
direction is one-way: core never references `cs104`, so the future 101 link layer can be added as a
sibling module with no surgery on core.

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

`iec60870-transport-tcp` owns the Netty side:

- `NettyClientTransport` / `NettyServerTransport`, the implementations of the core transport
  interfaces;
- the framing handler `Iec104FrameDecoder`, which slices one whole-frame `ByteBuf` per complete
  frame off the wire, and `InboundFrameHandler`, which forwards each frame to the `TransportListener`
  (outbound is a raw `ByteBuf` write — there is no encoder handler);
- TLS via Netty's `SslHandler`, and the client channel lifecycle via
  `com.digitalpetri.netty:netty-channel-fsm`;
- the **user-facing entry points** `TcpIec104Client` and `TcpIec104Server`, whose builders carry the
  transport knobs (`host`, `port`, `localBind`, `TlsOptions`, bootstrap/event-loop customization).

A builder in the transport module is the transitional **104 assembly point**: it constructs the
Netty transport, builds the `ApciSession` whose `Output` runs `ApduFramer.encode → transport.send`
and whose inbound listener runs `ApduFramer.decode → ApciSession.onApdu`, and hands the assembled
`Session` (client) / session factory (server) to `new DefaultIec60870Client(...)` /
`new DefaultIec60870Server(...)`. `build()` then returns the **application interface** type:

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
Only the *transport wiring* (host, port, TLS) and the 104 session assembly live in the
transport-module builder. (This assembly will move into a `Cs104Binding` in a later phase.)

A caller who already has a transport and a `Session` can bypass the builders entirely and construct
`new DefaultIec60870Client(clientTransport, clientConfig, sessionFactory)` directly; the high-level
behavior is identical.

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
| `iec60870-cs104` | `iec60870-core`, `org.jspecify:jspecify`, `io.netty:netty-buffer`, `org.slf4j:slf4j-api` | the 104 link/session engine; `netty-buffer` confined to the `Apdu`/`ControlField` `Serde` codecs; published API module |
| `iec60870-application` | `iec60870-core`, `org.jspecify:jspecify`, `org.jooq:joou`, `org.slf4j:slf4j-api` | **zero Netty**; depends on core only (never on cs104). Guarded by `NoNettyInApplicationTest` |
| `iec60870-transport-tcp` | `iec60870-application`, `iec60870-cs104`, `iec60870-core`, `io.netty:netty-buffer`, `io.netty:netty-codec`, `io.netty:netty-handler`, `com.digitalpetri.netty:netty-channel-fsm`, `org.slf4j:slf4j-api` | full Netty stack, including TLS via `netty-handler`; hosts the transitional 104 session assembly (only the `Tcp*` builders reference `cs104`; the octet classes stay cs104-free) |

The sink modules (`iec60870-examples`, `iec60870-tests`, `iec60870-interop`) name `client`/`server`/
`point` types directly, so each declares a **direct** `iec60870-application` dependency rather than
relying on transitive reach through `iec60870-transport-tcp`. The internal dependency graph is
acyclic with a single source (`core`): `cs104 → core`, `application → core` (`application` and
`cs104` are incomparable siblings — no edge between them), `transport-tcp → {application, cs104,
core}`, and the sinks → `{application, transport-tcp}` (examples also → `core`). Nothing depends back
into the application or transport modules.

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
