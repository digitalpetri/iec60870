# Link layer (FT1.2)

The FT1.2 link layer is what IEC 60870-5-101 puts under the shared ASDU application layer in place of
the 104 [APCI](glossary.md): a stop-and-wait link with byte-level framing, implemented here by
`Ft12LinkLayer` (the serial peer of [`ApciSession`](../../architecture/apci-and-timers.md)) and
configured by the [`LinkSettings`](glossary.md) record. It carries the link mode, the link address
and its width, the acknowledgement style, and three stop-and-wait timers. **These values are part of
the interoperability contract: both stations must agree on them**, because the library applies
exactly the configured set and performs no link-parameter negotiation on the wire. This page is the
deep reference an integrator consults when reading a serial trace or aligning two stacks; it states
what each value means and how the link behaves. To *set* these values and build a serial client or
server, see [Connect over serial](../how-to/connect-over-serial.md).

Throughout, a *variable-length* frame carries one [ASDU](glossary.md), a *fixed-length* frame carries
link control and the link address but no ASDU, and the *single character* `0xE5` is a compact
positive acknowledgement.

## The defaults at a glance

| Parameter | Default | Meaning (one line) |
|---|---|---|
| `mode` | `BALANCED` | The FT1.2 procedure: balanced point-to-point or unbalanced master/secondary. |
| `linkAddress` | `1` | The station's own (balanced) or target (unbalanced) link address. |
| `linkAddressLength` | `1` | Link-address field width in octets (`0..2`); `0` is balanced-only. |
| `broadcastAddress` | `255` | Unbalanced all-secondaries address (`255` for 1 octet, `65535` for 2); ignored when balanced. |
| `useSingleCharAck` | `true` | Emit a positive acknowledgement as the single-character `0xE5` frame. |
| `confirmTimeout` | `200 ms` | Time the primary waits for an acknowledgement before repeating a frame. |
| `repeatTimeout` | `1000 ms` | Spacing between repeated transmissions of an unacknowledged frame. |
| `maxRetries` | `3` | Maximum repeat transmissions of an unacknowledged frame. |
| `linkStateTimeout` | `5000 ms` | Idle interval after which the link status is polled. |
| `pollConfig` | absent (balanced) / empty slave list at `1000 ms` (unbalanced) | The unbalanced master's poll list and cadence. |

These are what `LinkSettings.balanced()` and `LinkSettings.unbalanced()` seed; the constructor
enforces `linkAddressLength` in `0..2` (unbalanced requires `>= 1`), `linkAddress` in range for its
length, a matching `broadcastAddress` under unbalanced mode, `maxRetries >= 0`, and positive
durations, throwing `IllegalArgumentException` otherwise.

The accessors below are what a reader meets in [Connect over serial](../how-to/connect-over-serial.md);
the link timers are `java.time.Duration`. This snippet only reads the default set — it is not a
configuration recipe (that belongs to the how-to).

```java
import com.digitalpetri.iec60870.cs101.LinkSettings;
import java.time.Duration;

LinkSettings link = LinkSettings.balanced().build();
// mode=BALANCED, linkAddress=1, linkAddressLength=1, confirmTimeout=200ms,
// repeatTimeout=1000ms, maxRetries=3, linkStateTimeout=5000ms
int address = link.linkAddress();          // 1
Duration confirm = link.confirmTimeout();  // PT0.2S
```

## FCB stop-and-wait vs. the k/w window

This is the one structural difference a 104 integrator must internalize. The 104 APCI runs a
[sliding `k`/`w` window](timers-and-window.md): up to `k` I-frames may be *outstanding*
(unacknowledged) at once, tracked by 15-bit `V(S)`/`V(R)` sequence numbers, and acknowledgements
arrive after `w` received frames. FT1.2 has **no window and no sequence numbers** — it is strict
stop-and-wait with **exactly one outstanding frame**.

The primary stamps a one-bit **frame count bit (FCB)** on each frame whose **frame count valid bit
(FCV)** is set, sends it, and then **waits**: it sends nothing further until the secondary
acknowledges — with a fixed-length acknowledgement frame, or the single-character `0xE5` when
`useSingleCharAck` is enabled. Only then does the primary flip the FCB and send the next confirmed
frame. The alternating FCB is the entire anti-duplication scheme:

- The FCB alternates `0`/`1` on every confirmed (`FCV = 1`) transaction. After a link reset the
  primary's FCB starts at `1` and the secondary expects `0`.
- An **unchanged** FCB tells the secondary the frame is a **retransmission** of the previous one: it
  replays its cached response and does **not** re-deliver the ASDU upward. This is how a lost
  acknowledgement is recovered without duplicating data.

So where 104 trades throughput against acknowledgement latency by sizing a window, FT1.2 trades it
against round-trip time directly: one frame, one acknowledgement, repeat. The retransmission behavior
is governed by [`confirmTimeout`, `repeatTimeout`, and `maxRetries`](#the-link-timers) rather than by
`t1`.

## Balanced vs. unbalanced

The `mode` (`com.digitalpetri.iec60870.cs101.LinkMode`) selects which FT1.2 transmission procedure
the link runs, and the two are not interoperable with each other.

### Balanced

A **balanced** link is a symmetric point-to-point connection between two *combined* stations on a
full-duplex line; **either** station may act as the primary and initiate a transfer. The link address
is optional (`linkAddressLength` may be `0`, `1`, or `2`) and there is no broadcast address. Bring-up
is a link-reset handshake: the initiating station sends *request-status-of-link* (FC9), the peer
answers *status-of-link* (FC11), the initiator then sends *reset-of-remote-link* (FC0) and the peer
acknowledges. After the reset both ends start their FCB state fresh and the link is available. On the
high-level facade `startDataTransfer()` (which `connect()` calls by default) drives this bring-up;
its completion is the data-transfer-start signal, the FT1.2 analog of 104's STARTDT.

### Unbalanced

An **unbalanced** link is an asymmetric master/secondary bus: a single primary station owns the line
and **polls** one or more secondaries, which **never initiate**. A secondary buffers its spontaneous
(class-1) and cyclic (class-2) data and waits to be asked. The master polls each secondary for
class-2 data with *request-class-2* (FC11) on the configured cadence, round-robin; when a secondary's
response carries the **access-demand bit (ACD)**, the master escalates to *request-class-1* (FC10) to
drain that secondary's high-priority data. A secondary signals it is busy with the **data-flow-control
bit (DFC)**. The all-secondaries `broadcastAddress` addresses send/no-reply (FC4) messages to every
station at once. The poll list and cadence come from the `LinkSettings.PollConfig`
(`slaveAddresses` + `pollInterval`), configured through `LinkSettings.unbalanced().slaveAddresses(...)`
and `.pollInterval(...)`.

## Link addressing

The link address identifies a station on the link and is distinct from the ASDU
[Common Address](glossary.md) (which addresses a station/sector inside the application layer).

- **`linkAddressLength`** — the field width in octets. `0` carries no address on the wire and is
  **balanced-only** (a single point-to-point pair needs no addressing); `1` covers addresses `0..255`
  and `2` covers `0..65535`. Unbalanced mode requires at least `1`. This width also sizes the
  fixed-length frames the transport deframes, so the serial port and the 101-over-TCP frame decoder
  are configured from it automatically.
- **`linkAddress`** — in balanced mode the station's **own** address (the two ends carry distinct
  addresses); in unbalanced mode the **target** secondary address. Must be in range for the width.
- **`broadcastAddress`** — the unbalanced all-secondaries address: `255` for a one-octet address,
  `65535` for two. Ignored in balanced mode; under unbalanced mode it must equal the width's maximum.

## The link timers

Three timers govern the stop-and-wait retransmission and idle liveness. Unlike 104's `t1`/`t2`/`t3`
they are not a window-acknowledgement pair; they bound a single outstanding frame and the idle
interval.

### confirmTimeout

How long the primary waits for an acknowledgement of a sent frame before treating it as lost and
repeating it. Must be positive; default `200 ms`. Size it above the worst-case round-trip at the
configured baud — too low repeats frames the secondary is still answering.

### repeatTimeout

The spacing between successive repeated transmissions of a still-unacknowledged frame. Must be
positive; default `1000 ms`. Together with `maxRetries` it bounds how long the link spends retrying
before giving up.

### maxRetries

The maximum number of repeat transmissions of an unacknowledged frame before the transaction fails.
Must be `>= 0`; default `3`. After the retries are exhausted the frame is abandoned and the link is
treated as down.

### linkStateTimeout

The idle interval after which the link status is polled (a *request-status-of-link* probe) to confirm
the peer is still alive on an otherwise quiet line — the FT1.2 keep-alive, analogous in purpose to
104's `t3` + `TESTFR`. Must be positive; default `5000 ms`.

| Timer | Default | Bounds | On expiry |
|---|---|---|---|
| `confirmTimeout` | `200 ms` | The wait for an acknowledgement of one sent frame | The frame is repeated (subject to `maxRetries`) |
| `repeatTimeout` | `1000 ms` | The spacing between repeated transmissions | The next repeat is sent |
| `linkStateTimeout` | `5000 ms` | The idle interval before a link-status probe | A *request-status-of-link* probe is sent |

The unbalanced master additionally polls on `pollConfig.pollInterval()` (default `1000 ms`),
independent of these timers.

## Aligning two stacks

When wiring this library to a second 101 stack (another vendor's master or secondary, a simulator,
lib60870-C), reconcile — because none of it is negotiated on the wire:

1. **The transmission procedure** — `mode` must match: balanced on both ends, or one unbalanced
   master against unbalanced secondaries. The two procedures do not interoperate.
2. **Link addressing** — `linkAddressLength` must match, and the addresses must be consistent: in
   balanced mode the two ends carry distinct `linkAddress` values; in unbalanced mode every
   secondary's `linkAddress` must appear in the master's `slaveAddresses`, and `broadcastAddress`
   must match the width.
3. **The serial line** — baud rate and the character frame (the standard 8E1: 8 data bits, even
   parity, 1 stop bit). FT1.2 requires even parity. (Not applicable to 101-over-TCP.)
4. **The acknowledgement style** — `useSingleCharAck`. A peer that does not accept the `0xE5`
   single-character acknowledgement needs it disabled so a fixed-length acknowledgement is sent
   instead.
5. **The timers** — `confirmTimeout`, `repeatTimeout`, `maxRetries`, `linkStateTimeout`. Mismatched
   values cause premature repeats, stalled links, or slow dead-peer detection; size `confirmTimeout`
   above the link round-trip.

The field-width half of the contract — the `ProtocolProfile` COT length, common-address width, IOA
width, and max ASDU length — is shared with 104 and unchanged; its decision table is owned by
[Tune the APCI session](../how-to/tune-apci.md), and the terms are defined in the
[Glossary](glossary.md). For the TypeID side of the contract — which ASDU types each end can decode —
see the [Coverage matrix](coverage-matrix.md); the application layer is identical for 101 and 104.

## See also

- [Connect over serial](../how-to/connect-over-serial.md) — how to set these values and build a
  serial or 101-over-TCP client and server.
- [Timers & window](timers-and-window.md) — the 104 counterpart: the `k`/`w` sliding window and the
  `t0`–`t3` timers, contrasted above.
- [Tune the APCI session](../how-to/tune-apci.md) — the shared `ProtocolProfile` field-width decision
  table.
- [Glossary](glossary.md) — FT1.2, link address, FCB/FCV, ACD/DFC, balanced/unbalanced, stop-and-wait.
- [Coverage matrix](coverage-matrix.md) — the shared TypeID coverage; the ASDU layer is the same as
  104.
- [Getting Started](../getting-started.md) — the end-to-end happy path; the facade above the link is
  identical for 101 and 104.
- [User Guide index](../README.md) — the full guide.
