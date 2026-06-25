# Timers & window

The APCI — the link-control layer IEC 60870-5-104 adds over TCP, implemented here by
[`ApciSession`](../../architecture/apci-and-timers.md) and configured by the
[`ApciSettings`](glossary.md) record — carries six tunable parameters: the window size `k`, the
acknowledge-after count `w`, and the four timeouts `t0`–`t3`. **These values are part of the
interoperability contract: both stations should agree on them**, because the library applies exactly
the configured set and performs no APCI parameter negotiation on the wire. This page is the deep
reference an integrator consults when reading a packet capture or aligning two stacks; it states what
each value means and what surfaces when one expires. To *set* these values, see
[Tune the APCI session](../how-to/tune-apci.md); for the full state machine, see
[Architecture: APCI and timers](../../architecture/apci-and-timers.md).

Throughout, an [I-format frame](glossary.md) carries an [ASDU](glossary.md) and both sequence
numbers, an S-format frame carries only `N(R)`, and a U-format frame carries one of
STARTDT/STOPDT/TESTFR.

## The defaults at a glance

| Parameter | Default | Meaning (one line) |
|---|---|---|
| `k` | `12` | Max I-format frames a station may have outstanding (sent, not yet acknowledged). |
| `w` | `8` | Number of received I-format frames after which the station sends an acknowledgement. |
| `t0` | `30 s` | Connection-establishment timeout. |
| `t1` | `15 s` | Timeout awaiting acknowledgement of a sent I-frame or U-frame `act`. |
| `t2` | `10 s` | Maximum delay before acknowledging received I-frames (should be `< t1`). |
| `t3` | `20 s` | Idle timeout after which a `TESTFR act` is sent. |

These are the standard IEC 60870-5-104 values and exactly what `ApciSettings.defaults()` returns; the
constructor enforces `k` and `w` in `1..32767`, `w ≤ k`, and positive durations, throwing
`IllegalArgumentException` otherwise. The shipped examples (`ClientExample`, `ServerExample`,
`TlsExample`) run on `ApciSettings.defaults()`, which is why their transcripts show the standard
handshake and idle-test cadence.

The accessors below are what a reader meets in [Tune the APCI session](../how-to/tune-apci.md); `k`
and `w` are `org.joou.UShort`, and the timeouts are `java.time.Duration`. This snippet only reads the
default set — it is not a configuration recipe (that belongs to the how-to).

```java
import com.digitalpetri.iec60870.cs104.ApciSettings;
import java.time.Duration;
import org.joou.UShort;

ApciSettings defaults = ApciSettings.defaults();
// k=12, w=8, t0=30s, t1=15s, t2=10s, t3=20s
UShort k = defaults.k();      // 12
UShort w = defaults.w();      // 8
Duration t1 = defaults.t1();  // PT15S
```

## The k / w sliding window

`k` and `w` bound a sliding acknowledgement window. `k` caps how far the sender may run ahead of the
peer's acknowledgements; `w` sets how promptly the receiver acknowledges. The two together trade
throughput against acknowledgement latency, and both ends must be mutually tolerable: if one station's
`k` exceeds what the other will tolerate, the link stalls.

### k — outstanding I-frames

`k` is the maximum number of I-format frames a station may have *outstanding* — sent but not yet
acknowledged by the peer's `N(R)`. The sender keeps issuing I-frames until `k` are outstanding, then
**stalls**: further frames stay queued and are not transmitted until an inbound acknowledgement
reopens the window. The queue drains as acknowledgements arrive. Range `1..32767`, default `12`. A
larger `k` allows more frames in flight on a high-latency link; too large a value risks exceeding the
peer's tolerance.

### w — acknowledge after w frames

`w` is the number of received I-format frames after which a station sends an acknowledgement. The
receiver counts unacknowledged received I-frames; on reaching `w` it immediately sends an S-frame
carrying the current `N(R)` and resets the counter. Any I- or S-frame the receiver sends also
discharges the counter, because both frame types carry `N(R)` — so reverse traffic can satisfy the
obligation before `w` is reached. Range `1..32767`, default `8`. The constructor enforces `w ≤ k`; a
common rule of thumb is `w ≈ ⅔·k`.

### Relationship to the timers

The window is bounded **in volume** by `k`/`w` and **in time** by the timers: the send side is
protected by [`t1`](#t1--acknowledgement-timeout) (a stalled, unacknowledged frame is eventually
fatal) and the receive side by [`t2`](#t2--acknowledge-when-idle-timeout) (a received frame is
acknowledged within `t2` even if traffic never reaches `w`). The next sections cover those timers.

## Sequence numbers (N(S), N(R), V(S), V(R))

Reliable, ordered delivery rides on 15-bit sequence numbers that wrap at `32768`. Each peer keeps two
state variables, both reset to `0` when a connection is established:

- **V(S)** — the send state: the number it will assign to the next I-frame it sends.
- **V(R)** — the receive state: the number it expects on the next I-frame it receives.

Every I-frame carries both **N(S)** (its own send number) and **N(R)** (the next number it expects
from the peer), so each I-frame also acknowledges everything received so far. When a station receives
an I-frame it checks that `N(S)` matches its `V(R)`. A mismatch — an out-of-sequence `N(S)`, or an
`N(R)` that acknowledges frames never sent — is a fatal protocol error: the session closes with a
`SequenceNumberException` (see [Error model](errors.md)). This is why a single dropped or reordered
frame tears the link down rather than recovering silently. For the wraparound arithmetic, see the
[architecture doc](../../architecture/apci-and-timers.md).

## The timers

Three of the four timers — `t1`, `t2`, `t3` — are owned by the running session and driven by frame
traffic. `t0` governs connection setup. Each subsection states what the timer measures, its default,
when it is armed, when it is cleared, and what happens on expiry.

### t0 — connection establishment

`t0` is the connection-establishment timeout: how long a station waits for the TCP connection (and,
with TLS, the handshake) to complete before abandoning the connect attempt. It is validated to be
positive and carried in `ApciSettings`; default `30 s`. Unlike `t1`–`t3` it is not armed by frame
traffic and is not consumed by the running session — it describes the *connect* phase, not the
steady-state link. `TcpIec104Client` applies the configured `t0` to each connect attempt by setting
it as the client bootstrap's `CONNECT_TIMEOUT_MILLIS` channel option (a `bootstrapCustomizer` runs
afterward and may override it). So a larger `t0` waits longer before a connect attempt fails and a
smaller one fails faster (see the [aligning section](#aligning-two-stacks) and the
[architecture doc](../../architecture/apci-and-timers.md)).

### t1 — acknowledgement timeout

`t1` is the timeout awaiting acknowledgement of something sent. It is armed when an I-frame or a
U-frame `act` (STARTDT/STOPDT/TESTFR `act`) is sent, and cleared once nothing remains outstanding (no
outstanding I-frames and no test frame awaiting confirmation). On expiry the link is considered dead:
the session closes with a `ProtocolTimeoutException`, surfaced as a connection-closed event rather
than thrown to a steady-state caller (see [Handle events](../how-to/handle-events.md) and
[Error model](errors.md)). Default `15 s`.

### t2 — acknowledge-when-idle timeout

`t2` bounds acknowledgement latency below `t1` even when traffic never reaches the `w` threshold. It
is armed when an I-frame is received and `t2` is not already running; it is **not** restarted on every
subsequent frame. On expiry, if received frames are still unacknowledged, the session sends an
S-frame. Sending any acknowledgement (an I- or S-frame) cancels it. `t2` should be `< t1`. Default
`10 s`.

### t3 — idle test timeout

`t3` is a sliding idle timer re-armed by any sent or received frame. On expiry — meaning the link has
been silent — the session sends `TESTFR act` and arms `t1` to await `TESTFR con`. This is the
keep-alive and dead-peer detector on an otherwise quiet link: `t3` + `TESTFR` + `t1` together prove
the peer is still there. Default `20 s`.

| Timer | Default | Armed when | Cleared / re-armed when | On expiry |
|---|---|---|---|---|
| `t0` | `30 s` | Not a session timer; applied to each connect attempt | n/a — set as the bootstrap's `CONNECT_TIMEOUT_MILLIS` | Connect attempt fails if the connection is not established within `t0` — see the note below |
| `t1` | `15 s` | An I-frame or U-frame `act` (STARTDT/STOPDT/TESTFR) is sent | No frames remain outstanding (and no test frame awaits confirmation) | Link considered dead: session closes with `ProtocolTimeoutException`, surfaced as a connection-closed event |
| `t2` | `10 s` | An I-frame is received and `t2` is not already running | Any acknowledgement (I- or S-frame) is sent | If received frames are still unacknowledged, an S-frame is sent |
| `t3` | `20 s` | Re-armed by any sent or received frame (sliding idle timer) | Re-armed by traffic | `TESTFR act` is sent and `t1` is armed to await `TESTFR con` |

**Note on `t0`:** `t0` is the connection-establishment timeout and is validated to be positive. It is
not a session-armed timer like `t1`–`t3`; instead `TcpIec104Client` applies it as the client
bootstrap's `CONNECT_TIMEOUT_MILLIS`, so a connect attempt that does not establish the TCP connection
(and, with TLS, the handshake) within `t0` fails. A `bootstrapCustomizer` runs after the option is
set and may override it.

The constructor checks that each duration is positive but does **not** enforce ordering between them.
The relationships are guidance, not validation: keep `t2 < t1` so a routine idle acknowledgement never
trips the acknowledgement timeout, and in practice keep `t1 < t3` so a stalled acknowledgement is
caught before the idle test fires.

## STARTDT / STOPDT / TESTFR

The three U-format control functions gate and probe the link. They are framed by role: the client is
the [controlling station](glossary.md) (master) and initiates STARTDT/STOPDT; the server is the
controlled station (slave) and responds automatically.

After `connect()` the link is *stopped* — no monitor data flows until the controlling station sends
STARTDT. With `startDataTransferOnConnect` defaulting to `true`, `connect()` performs the STARTDT
handshake for you; set it to `false` to drive it manually with `startDataTransfer()`. The server
answers all three functions automatically per role; a controlling station that never sends STARTDT
receives nothing.

| Function | Direction | Client (controlling station) | Server (controlled station) | Effect |
|---|---|---|---|---|
| STARTDT | act → con | `startDataTransfer()` sends `STARTDT act`, arms `t1`, completes when `STARTDT con` arrives | Replies `STARTDT con` and flushes queued monitor I-frames | Enables monitor-direction data flow |
| STOPDT | act → con | `stopDataTransfer()` sends `STOPDT act` | Replies `STOPDT con`, withholds further monitor I-frames | Halts monitor flow; TCP stays connected |
| TESTFR | act → con | Sent automatically on `t3` expiry; arms `t1` for the `con` | Answers `TESTFR act` with `TESTFR con` automatically | Liveness probe on an idle link |

The client methods come in blocking and async pairs: `startDataTransfer()` /
`startDataTransferAsync()` and `stopDataTransfer()` / `stopDataTransferAsync()`. There are no
server-side STARTDT/STOPDT methods — the controlled station answers automatically. The TESTFR probe is
automatic on `t3` on either side. For the frame-octet values and the per-role state machine, see the
[architecture doc](../../architecture/apci-and-timers.md).

## Aligning two stacks

The APCI parameters are part of the negotiated subset two stations exchange before they interoperate,
but **the library applies exactly what you configure — there is no APCI parameter negotiation on the
wire, so you must set both ends yourself.** When wiring this library to a second stack (another
vendor's master or slave, a simulator, lib60870-C), reconcile:

1. **The field-width contract** — the `ProtocolProfile` field sizes (COT length, Common Address width,
   IOA width, max ASDU length). This is the addressing half of the contract; the decision table for it
   is owned by [Tune the APCI session](../how-to/tune-apci.md), and the terms are defined in the
   [Glossary](glossary.md). Do not re-derive it here.
2. **The window** — `k` and `w`. Each end's `k` must be tolerable to the other; keep `w ≤ k` on both.
3. **The timeouts** — `t0`–`t3`. Mismatched timeouts cause spurious test frames, premature
   acknowledgement-timeout drops, or stalled links. Keep `t2 < t1` and `t1 < t3`.

For the TypeID side of the contract — which ASDU types each end can actually decode — see the
[Coverage matrix](coverage-matrix.md). For the builder calls that set every value above, see
[Tune the APCI session](../how-to/tune-apci.md).

## See also

- [Tune the APCI session](../how-to/tune-apci.md) — how to set `k`/`w` and `t0`–`t3` via the builders.
- [Glossary](glossary.md) — IEC 104 vocabulary mapped to our Java types.
- [Error model](errors.md) — what `t1` expiry and sequence violations surface as.
- [Coverage matrix](coverage-matrix.md) — the TypeID side of the interoperability contract.
- [Getting Started](../getting-started.md) — the end-to-end happy path.
- [Host a server](../how-to/host-a-server.md) — building a controlled station.
- [Handle events](../how-to/handle-events.md) — where connection-closed events surface.
- [User Guide index](../README.md) — the full guide.
- [Architecture: APCI and timers](../../architecture/apci-and-timers.md) — the full state machine,
  frame-octet values, and sequence-number arithmetic.
