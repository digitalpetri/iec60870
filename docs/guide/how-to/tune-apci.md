# Tune the APCI session

Every protocol-layer knob lives in one of two immutable configuration records, and you set both
through the same two builder methods on either facade. `ProtocolProfile` fixes the **wire field
widths** — how many octets carry the [Cause of Transmission (COT)](../reference/glossary.md), the
[Common Address (CA)](../reference/glossary.md), and the
[Information Object Address (IOA)](../reference/glossary.md), plus the maximum
[ASDU](../reference/glossary.md) length. `ApciSettings` carries the
[APCI](../reference/glossary.md) flow-control parameters: the `k`/`w` window and the `t0`–`t3`
timers. You pass them with `.profile(ProtocolProfile)` and `.apci(ApciSettings)` on
`TcpIec104Client.builder()` or `TcpIec104Server.builder()`.

The headline rule comes first: **the field-width knobs in `ProtocolProfile` are a wire contract.**
Two stations interoperate only if these widths agree, so the most common reason to touch this page is
"the peer uses a 1-octet common address and a 2-octet IOA — make mine match." The window and timers
are performance and liveness tuning that rarely needs changing but must be understood before it is.
This page is the *how*; for the *why* behind the sequence-number method, the sliding window, and the
`TESTFR` keep-alive, see the [Timers & window reference](../reference/timers-and-window.md).

The shipped examples (`ClientExample`, `ServerExample`, `TlsExample`) all use the standard profile and
settings; this page shows how to override them.

## Where the knobs live

Transport knobs — host, port, TLS — live directly on the builder. The two protocol records are
separate: you pass them via `.profile(ProtocolProfile)` and `.apci(ApciSettings)`. Both default to
the standard 104 configuration (`ProtocolProfile.iec104Default()` and `ApciSettings.defaults()`), so
you only construct a record when you need to change something.

| Knob category | Where it goes | Carried by |
|---|---|---|
| Host / port / TLS | builder method (`.host`, `.port`, `.tls`, …) | the transport builder |
| Wire field widths | `.profile(...)` | `ProtocolProfile` |
| Window + timers | `.apci(...)` | `ApciSettings` |

For the design behind these records — why TLS and the field widths are configured against core but
applied by the transport — see the architecture's
[TLS and configuration](../../architecture/tls-and-configuration.md) document.

## Recipe: override the defaults

Build whichever records you need and pass them to the builder. The client (the controlling station)
and the server (the controlled station) must share the same `ProtocolProfile`, because the field
widths are a shared wire contract.

Client — override both records:

```java
import com.digitalpetri.iec60870.ApciSettings;
import com.digitalpetri.iec60870.ProtocolProfile;
import com.digitalpetri.iec60870.client.Iec60870Client;
import com.digitalpetri.iec60870.transport.tcp.TcpIec104Client;
import java.time.Duration;
import org.joou.UShort;

// Field widths the peer expects — these MUST match the controlled station exactly.
ProtocolProfile profile =
    new ProtocolProfile(
        /* cotLength */ 2,
        /* commonAddressLength */ 1,
        /* ioaLength */ 2,
        /* maxAsduLength */ 249);

// Window and timers — performance/liveness tuning; both ends should agree in practice.
ApciSettings apci =
    new ApciSettings(
        /* k  */ UShort.valueOf(12),
        /* w  */ UShort.valueOf(8),
        /* t0 */ Duration.ofSeconds(30),
        /* t1 */ Duration.ofSeconds(15),
        /* t2 */ Duration.ofSeconds(10),
        /* t3 */ Duration.ofSeconds(20));

try (Iec60870Client client =
    TcpIec104Client.builder()
        .host("127.0.0.1")
        .port(2404)
        .profile(profile)
        .apci(apci)
        .build()) {
  client.connect();
  // ... interrogate, send commands, etc.
}
```

Server — the controlled station applies the *same* records so the two ends agree:

```java
import com.digitalpetri.iec60870.server.Iec60870Server;
import com.digitalpetri.iec60870.transport.tcp.TcpIec104Server;

// Reuse the `profile` and `apci` locals built exactly as in the client recipe above —
// the field widths are a shared wire contract, so both ends must construct the same profile.
try (Iec60870Server server =
    TcpIec104Server.builder()
        .bindAddress("0.0.0.0")
        .port(2404)
        .profile(profile)
        .apci(apci)
        .addStation(station) // see "Host a server" for building stations + points
        .build()) {
  server.start();
  // ... publish monitor data, answer commands
}
```

The `station` local is built separately — see [Host a server](host-a-server.md) for the full
station-and-point setup.

## Field widths (ProtocolProfile)

Every row below is a **shared wire contract** — set the same value on both stations, or frames fail
to decode at runtime. The constructor validates each field's range and throws
`IllegalArgumentException` for an out-of-range value. `ProtocolProfile.iec104Default()` returns
`(2, 2, 3, 249)`, the standard 104 profile.

| Knob | Meaning | Default | When to change | Consequence |
|---|---|---|---|---|
| `cotLength` | Cause-of-transmission octets; `2` includes the [originator address](../reference/glossary.md) octet, `1` omits it | `2` | Peer uses a 1-octet COT (no originator address) | Must match the peer. With `1`, the originator address is not carried on the wire. Range `1..2` |
| `commonAddressLength` | Common address (station address) octets, little-endian | `2` | Peer uses a 1-octet common address | Must match the peer; with `1`, common addresses are limited to `0..255`. Range `1..2` |
| `ioaLength` | Information object address octets, little-endian | `3` | Peer uses a 1- or 2-octet IOA | Must match the peer; fewer octets caps the addressable IOA range. Range `1..3` |
| `maxAsduLength` | Maximum ASDU length in octets | `249` | Peer enforces a smaller max, or you want to bound frame size | Frames that would exceed this are not produced. Range `1..249` |

The common real case is "104 default but with one different field." `ProtocolProfile` is a record with
no `withX` convenience methods, so start from the default and re-construct using its component
accessors (`cotLength()`, `commonAddressLength()`, `ioaLength()`, `maxAsduLength()`):

```java
ProtocolProfile base = ProtocolProfile.iec104Default(); // (2, 2, 3, 249)

// Same as the default, but with a 1-octet common address to match the peer.
ProtocolProfile profile =
    new ProtocolProfile(
        base.cotLength(),
        /* commonAddressLength */ 1,
        base.ioaLength(),
        base.maxAsduLength());
```

## Window: k and w (ApciSettings)

The window governs how many I-frames may stream before an acknowledgement is required. `w ≤ k` is
enforced in the constructor; the common rule of thumb is `w ≈ 2/3 · k`. The
[Timers & window reference](../reference/timers-and-window.md) explains how the sliding window
actually advances — defer the mechanism there.

| Knob | Meaning | Default | When to change | Consequence |
|---|---|---|---|---|
| `k` | Max I-format frames a station may have outstanding (sent, not yet acknowledged) | `12` | Raise for higher throughput on high-latency links; lower to bound memory / in-flight data | Larger `k` lets more frames stream before an ack is required; sending stalls once `k` are outstanding until an ack reopens the window. Range `1..32767` |
| `w` | Number of received I-frames after which an acknowledgement is sent | `8` | Tune ack frequency; keep below `k` | Smaller `w` acks more eagerly (more S-frames, lower ack latency); `w` **must not exceed `k`**. Range `1..32767` |

**Footgun:** `k` and `w` are `org.joou.UShort`, not `int`. Pass `UShort.valueOf(12)`, not a bare
`12`, and import `org.joou.UShort`. This is the single most likely copy-paste failure.

## Timers: t0–t3 (ApciSettings)

All four durations must be positive (validated in the constructor). `t0` is applied as the TCP
connection-establishment timeout — `TcpIec104Client` sets it as the client bootstrap's
`CONNECT_TIMEOUT_MILLIS` — while `t1`–`t3` run inside the session. The mechanics — what arms and cancels each timer,
and the wraparound math — live in the
[Timers & window reference](../reference/timers-and-window.md); this table gives only the decision.

| Knob | Meaning | Default | When to change | Consequence |
|---|---|---|---|---|
| `t0` | Connection-establishment timeout | `30 s` | Slow / long-haul links where TCP+TLS setup needs longer | Applied as the client's TCP connect timeout (`CONNECT_TIMEOUT_MILLIS`): a larger value waits longer before a connect attempt fails, a smaller one fails faster. A `bootstrapCustomizer` runs afterward and may override the channel option |
| `t1` | Timeout awaiting acknowledgement of a sent I- or U-frame (`STARTDT`/`STOPDT`/`TESTFR` act) | `15 s` | Match the peer's expectations on lossy / slow links | If it expires the link is considered dead: the session closes with a `ProtocolTimeoutException` |
| `t2` | Maximum delay before acknowledging received I-frames | `10 s` | Tune ack latency; keep below `t1` | **Should be `< t1`**; bounds ack latency even when traffic never reaches `w` |
| `t3` | Idle timeout after which a `TESTFR act` is sent | `20 s` | Tune dead-peer detection on idle links | Lower detects a dead peer faster at the cost of more keep-alive traffic |

## Validation and the must-match-peer rule

There are two distinct failure modes, and they surface at different times.

- **Construction-time, fail-fast.** An out-of-range field width, `w > k`, or a non-positive duration
  throws `IllegalArgumentException` *when you build the record* — before you ever connect. These are
  range checks the library can make on its own.
- **Runtime decode failures.** The library validates ranges, but it cannot know the field widths the
  *peer* chose. A mismatch (your 3-octet IOA against the peer's 2-octet IOA, say) is not caught at
  build time; it surfaces as a decode failure (`AsduDecodeException`) once frames start flowing.
  Matching your `ProtocolProfile` to the peer's is the integrator's responsibility, not something the
  build can verify.

For the full picture of what each operation throws or returns, see the
[Error model reference](../reference/errors.md).

## See also

- [Timers & window reference](../reference/timers-and-window.md) — the deep semantics of the `k`/`w`
  window and the `t0`–`t3` timers.
- [Getting Started](../getting-started.md) — the end-to-end happy path, if you do not yet have a
  client or server running.
- [Host a server](host-a-server.md) — building the stations and points the server recipe elides.
- [Secure with TLS](secure-with-tls.md) — the other transport-builder knob, `.tls(...)`.
- [Connect & interrogate](connect-and-interrogate.md) — exercising a tuned client.
- [Error model reference](../reference/errors.md) — what construction and decoding throw.
- [Glossary](../reference/glossary.md) — IEC 104 vocabulary mapped to our Java types.
- [User Guide index](../README.md) — the rest of the guide.

For internals beyond the reference — how `ApciSession` arms each timer and advances the window — see
the architecture's [APCI and timers](../../architecture/apci-and-timers.md) document.
