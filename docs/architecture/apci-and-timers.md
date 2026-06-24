# APCI and Timers

The APCI (Application Protocol Control Information) is the link-control layer that IEC 60870-5-104
adds on top of TCP. It does three jobs: it gates user-data transfer (`STARTDT`/`STOPDT`), it keeps an
otherwise-idle connection alive and detects death (`TESTFR` plus the `t3` idle timer), and it
provides reliable, ordered delivery with a sliding-window acknowledgement scheme on top of the
byte-stream TCP gives. The library implements all of this in one class, `ApciSession`
(`com.digitalpetri.iec60870.apci`), which is symmetric: the same engine runs in both the client and the
server, parameterized by a `Role`. (Spec references below cite IEC 60870-5-104 §5.1, §5.3, §5.5.)

## Frame formats

Every APDU begins with a `0x68` start octet, a length octet, and four APCI control octets; an ASDU
body follows only for I-format frames. The control field is modeled by the sealed `ControlField`
(§5.1):

- **I-format** (`ControlField.TypeI`) — numbered information transfer. Carries the send sequence
  number N(S) and the receive sequence number N(R), and always carries an ASDU. Selected when bit 1
  of the first control octet is `0`.
- **S-format** (`ControlField.TypeS`) — numbered supervisory acknowledge. Carries N(R) only, no ASDU.
  Used to acknowledge received I-frames when there is no reverse I-traffic to piggyback on. First
  control octet `0x01`.
- **U-format** (`ControlField.TypeU`) — unnumbered control function, no ASDU. Carries exactly one
  `UFunction`: `STARTDT_ACT`/`STARTDT_CON`, `STOPDT_ACT`/`STOPDT_CON`, `TESTFR_ACT`/`TESTFR_CON`.
  First control octet has bits 1 and 2 set.

`Apdu` enforces the invariant that an ASDU is present **if and only if** the control field is
I-format. `ControlField.Serde` encodes/decodes the four octets; `Apdu.Serde` wraps that with the
start octet and the back-patched length (which counts the four control octets plus the ASDU body and
excludes the start and length octets). The U-format octet values are fixed: `STARTDT act 0x07 /
con 0x0B`, `STOPDT act 0x13 / con 0x23`, `TESTFR act 0x43 / con 0x83`.

## The sequence-number method

I-frames are numbered with 15-bit send and receive sequence numbers that wrap modulo `32768` (§5.1).
On the wire each occupies the high 15 bits of a 16-bit field; the low bit is the format flag, so the
codec shifts the number left by one (`(ns << 1)`), which `ControlField.Serde` does explicitly.

Each peer keeps two state variables:

- **V(S)** — the send state: the number to assign to the next I-frame it sends.
- **V(R)** — the receive state: the number it expects on the next I-frame it receives.

After a connection is established both are reset to `0` (`ApciSession.onConnected()`). When an
I-frame is sent it carries `N(S) = V(S)` and `N(R) = V(R)` (so every sent I-frame also acknowledges
everything received so far), and V(S) is incremented. When an I-frame is received, the session checks
`N(S) == V(R)`; a mismatch is a fatal protocol error that closes the connection with a
`SequenceNumberException`. Otherwise it delivers the ASDU, increments V(R), and processes the frame's
N(R) to confirm its own previously-sent I-frames.

All sequence arithmetic goes through a static helper, `ApciSession.sequenceDistance(from, to)`, which
returns the forward distance modulo `32768`, so window and acknowledgement checks behave correctly
across the `32767 → 0` wraparound.

## Flow control: k and w

Two parameters from `ApciSettings` bound the window (§5.5):

- **k** — the maximum number of I-frames a station may have outstanding (sent but not yet
  acknowledged). Default `12`.
- **w** — the number of received I-frames after which the station must send an acknowledgement (an
  S-frame, if no reverse I-traffic carried it sooner). Default `8`. `w` must not exceed `k`.

Send side: `sendAsdu(asdu)` appends to an internal queue and flushes it. The flush loop sends I-frames
only while `sequenceDistance(ack, V(S)) < k`; once `k` frames are outstanding it stops, leaving the
rest queued, until an inbound N(R) acknowledges enough frames to reopen the window. (For a `SERVER`
role it also holds the queue until data transfer has started.)

Receive side: each received I-frame increments an unacked-received counter. When that counter reaches
`w`, the session immediately sends an S-frame carrying the current V(R) and resets the counter.
Sending any I-frame or S-frame also discharges the counter, because both carry N(R).

## STARTDT / STOPDT gating and roles

After connect, the default state is *stopped*: no monitor data flows until the controlling station
starts data transfer (§5.3). The small role differences are the only client/server divergence in
`ApciSession`:

- **`Role.CLIENT`** initiates. `startDataTransfer()` sends `STARTDT act`, arms `t1`, and returns a
  `CompletionStage` that completes when `STARTDT con` arrives. `stopDataTransfer()` is symmetric with
  `STOPDT act`/`con`.
- **`Role.SERVER`** responds. On `STARTDT act` it sets the started flag, replies `STARTDT con`, and
  flushes any queued I-frames; on `STOPDT act` it clears the flag and replies `STOPDT con`. A server
  withholds queued monitor I-frames entirely until data transfer is started.

The high-level facade wires this up: `Iec60870Client.startDataTransfer()` drives the client session, and
`ClientConfig.startDataTransferOnConnect` (default `true`) makes `connect()` perform the handshake
automatically. The server's per-connection session gates outbound data so a controlling station that
has not sent `STARTDT` receives nothing.

## Timers

Four timers come from `ApciSettings` (defaults in parentheses; §5.5). `t0` is applied by the client
transport as the TCP connection-establishment timeout: `TcpIec104Client` projects `apci.t0()` onto
the bootstrap's `CONNECT_TIMEOUT_MILLIS` channel option on each connect attempt (a
`bootstrapCustomizer` runs afterward and may override it). `t1`–`t3` are owned by `ApciSession` and
scheduled on an injected `ScheduledExecutorService`.

| Timer | Default | Meaning | Where applied |
|---|---|---|---|
| `t0` | 30 s | Connection-establishment timeout | Client transport connect (`CONNECT_TIMEOUT_MILLIS`) |
| `t1` | 15 s | Timeout awaiting acknowledgement of a sent I-frame or U-frame (STARTDT/STOPDT/TESTFR act) | `ApciSession` |
| `t2` | 10 s | Maximum delay before acknowledging received I-frames (must be `< t1`) | `ApciSession` |
| `t3` | 20 s | Idle timeout after which a `TESTFR act` is sent | `ApciSession` |

How `ApciSession` runs them:

- **`t1`** is armed whenever an I-frame is sent or a U-frame `act` is sent, and cancelled when the
  outstanding count returns to zero (and no test frame is awaiting confirmation). If it expires, the
  connection has stalled: the session closes itself with a `ProtocolTimeoutException` and reports it
  through `Events.onClosed`.
- **`t2`** is armed when an I-frame is received and is not yet armed; it is *not* restarted on every
  subsequent frame. On expiry, if any received frames are still unacknowledged, the session sends an
  S-frame. Sending any acknowledgement cancels it. This bounds acknowledgement latency below `t1`
  even when traffic does not reach the `w` threshold.
- **`t3`** is a sliding idle timer: any sent or received frame re-arms it. On expiry — meaning the
  connection has been silent — the session sends `TESTFR act` and arms `t1` to await `TESTFR con`. A
  received `TESTFR act` is answered immediately with `TESTFR con`; a received `TESTFR con` clears the
  outstanding test and cancels `t1`. Together `t3`+`TESTFR`+`t1` detect a dead peer on an otherwise
  idle link.

## Lifecycle and threading

```
connect ──► onConnected()         V(S)=V(R)=0, arm t3
            │
   CLIENT:  startDataTransfer() ─► STARTDT act, arm t1 ─► STARTDT con ─► (data transfer started)
            │
   data:    sendAsdu(...) ─► I-frame if window open, else queued
            onApdu(...)   ─► I: check N(S)=V(R), deliver, ++V(R), process N(R), ack via w/t2
                              S: process N(R)
                              U: STARTDT/STOPDT/TESTFR per role
            │
   idle:    t3 ─► TESTFR act ─► (t1) ─► TESTFR con
            │
   error:   bad N(S) / bad N(R) / t1 ─► closeWithError(...) ─► Events.onClosed(cause)
            │
   teardown: close()  (idempotent; fails any pending STARTDT/STOPDT future)
```

All mutable session state is guarded by one internal `ReentrantLock`, and the timer callbacks acquire
that same lock, so any method may be called from any thread. The `Output` (outbound APDU sink) and
`Events` callbacks are invoked while the lock is held and must not block or re-enter the session —
the higher layers therefore hop to a separate callback executor before doing application work. See
[buffers-and-threading.md](buffers-and-threading.md).
