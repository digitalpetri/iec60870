# Modules and Dependencies

The library is a Maven multi-module build under the `iec60870-parent` POM. Two modules matter to a
caller: `iec60870-core` (the protocol and the high-level API) and `iec60870-transport-tcp` (the Netty
TCP/TLS transport). A third module, `iec60870-tests`, holds cross-module integration tests.

## The core-vs-transport split

`iec60870-core` owns everything that is independent of how bytes reach the network:

- the raw protocol model and codecs (`.asdu`, `.apci`, `.address`, `.asdu.element`, `.asdu.time`);
- the APCI session engine (`com.digitalpetri.iec60870.apci.ApciSession`) — the symmetric sequence /
  window / timer state machine used by both client and server;
- the high-level client and server *interfaces* and their default implementations
  (`Iec60870Client` / `DefaultIec60870Client`, `Iec60870Server` / `DefaultIec60870Server`);
- the point and catalog model (`.point`, `.catalog`), the error model (package-root exceptions),
  and the configuration types (`ProtocolProfile`, `ApciSettings`, `TlsOptions`);
- the **octet transport SPI** (`.transport`) — `ClientTransport`, `ServerTransport`,
  `ServerTransportConnection`, `TransportListener` — which the core implementations drive but do not
  implement. The SPI is shaped in **whole-frame `ByteBuf`s**: `send(ByteBuf)` and `onFrame(ByteBuf)`
  exchange one complete, length-delimited frame each. Turning a frame into an `Apdu` (via
  `ApduFramer`) happens above the SPI, so a future 101-over-TCP can reuse the same transport.

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

A builder in the transport module constructs the Netty transport plus a `DefaultIec60870Client` /
`DefaultIec60870Server`, and `build()` returns the **core interface** type:

```java
// Returns com.digitalpetri.iec60870.client.Iec60870Client — a core type.
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
`close`, and the `*Async` variants — are all defined on the core `Iec60870Client` interface. Only the
*transport wiring* (host, port, TLS) lives on the transport-module builder, because the dependency
rule below forbids transport wiring from appearing in core.

A caller who already has a transport implementation can bypass the builders entirely and construct
`new DefaultIec60870Client(clientTransport, clientConfig)` directly; the high-level behavior is
identical.

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

So `ByteBuf` is allowed inside the nested `Serde` classes of `.asdu`, `.apci`, `.address`,
`.asdu.element`, and `.asdu.time` (for example `Asdu.Serde`, `Apdu.Serde`, `ControlField.Serde`,
`CommonAddress.Serde`), and on the `.transport` octet SPI (`send(ByteBuf)`, `onFrame(ByteBuf)`). It
must not reach `Iec60870Client`, `Iec60870Server`, the event/command/point/catalog types,
`ProtocolProfile`, or `ApciSettings`. Above those boundaries everything is an immutable Java object;
the `ApciSession` itself never touches a `ByteBuf`.

The octet transport SPI in `.transport` is the formal seam. It is expressed entirely in core types —
whole-frame `ByteBuf`s, `SocketAddress`, and `java.security.cert.Certificate`, never a Netty channel
or handler type — so `iec60870-transport-tcp` can implement it with Netty without that detail leaking
back into core.

### Dependency table

| Module | Compile dependencies | Notes |
|---|---|---|
| `iec60870-core` | `org.jspecify:jspecify`, `org.jooq:joou`, `io.netty:netty-buffer`, `org.slf4j:slf4j-api` | `netty-buffer` confined to `Serde`; no channel/handler |
| `iec60870-transport-tcp` | `iec60870-core`, `io.netty:netty-buffer`, `io.netty:netty-codec`, `io.netty:netty-handler`, `com.digitalpetri.netty:netty-channel-fsm`, `org.slf4j:slf4j-api` | full Netty stack, including TLS via `netty-handler` |

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
