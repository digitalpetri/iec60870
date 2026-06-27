# FT1.2 Link Layer

The FT1.2 link layer is the serial link-control layer that IEC 60870-5-101 uses in place of the 104
APCI. It does three jobs: it gates user-data transfer behind a link-reset handshake (the analog of
`STARTDT`), it keeps an otherwise-idle link alive and detects death (an idle keep-alive probe plus
the per-transaction confirm timer), and it provides reliable, ordered delivery with a stop-and-wait
acknowledgement scheme over the raw octet stream a serial line gives. The library implements this in
the `iec60870-cs101` module (`com.digitalpetri.iec60870.cs101`), where `Ft12LinkLayer` `implements`
the core `Session` SPI as the serial peer of `ApciSession`. Unlike the symmetric 104 engine,
`Ft12LinkLayer` is a thin dispatcher: it selects one of three concrete state machines by
`(role, mode)` — `BalancedEngine`, `UnbalancedMasterEngine`, or `UnbalancedSlaveEngine` — and
forwards every call to it. The FT1.2 frame model (`Ft12Frame`, `LinkControlField`, the `Ft12Framer`
bridge) and the `LinkSettings` link parameters live alongside it in `iec60870-cs101`; core holds only
the neutral `Session` SPI, not the engines. The ASDU application layer above the link is shared,
unchanged, with the 104 path. (Spec references below cite IEC 60870-5-1 for the FT1.2 frame format
and IEC 60870-5-2 for the balanced and unbalanced link transmission procedures.)

## Frame formats

FT1.2 (IEC 60870-5-1) defines three frame shapes, distinguished by their leading start octet and
modeled by the sealed `Ft12Frame`. There is no explicit control-information header like the 104
`0x68`-plus-length-plus-four-control-octet APCI; the link control octet and link address sit inside
the user data, and a variable frame's ASDU length is implied rather than carried.

- **Variable-length** (`Ft12Frame.Variable`) — carries exactly one ASDU. Layout
  `0x68 | L | L | 0x68 | userData | CS | 0x16`, where `userData = control(1) +
  linkAddress(linkAddressLength, little-endian) + asduBytes`. The user-data length `L` is sent
  **twice** (the decoder rejects a frame whose two length octets disagree), the `0x68` start octet is
  repeated after the length pair, and the ASDU length is recovered as `L - 1 - linkAddressLength`.
- **Fixed-length** (`Ft12Frame.FixedLength`) — a link control field and link address with no ASDU,
  used for link control and acknowledgements. Layout `0x10 | userData | CS | 0x16`, where
  `userData = control(1) + linkAddress(linkAddressLength, little-endian)`.
- **Single-character** (`Ft12Frame.SingleChar`) — the lone octet `0xE5`, a compact positive
  acknowledgement that carries neither a control field nor a link address. The reserved single
  character `0xA2` is recognized on decode (and rejected with an `AsduDecodeException`) but is never
  emitted.

`Ft12Framer` is the `Ft12Frame`↔`ByteBuf` seam, the FT1.2 peer of the 104 `ApduFramer`. It owns the
start-octet, doubled-length, checksum, and end-octet handling around `Asdu.Serde`. The checksum `CS`
is the 8-bit arithmetic sum (modulo 256) of the user-data octets only — the control octet, the link
address, and (for a variable frame) the ASDU bytes — and the frame is terminated by the `0x16` end
octet. Like `ApduFramer`, the framer performs no stream reassembly: `decode` requires a buffer
positioned at the start octet that already holds exactly one whole frame, so length-based framing of
a partial byte stream stays the transport's responsibility.

## The link control field

Every fixed- and variable-length frame begins its user data with the one-octet `LinkControlField`.
Its bit layout is fixed (IEC 60870-5-1):

- **bit 7 (`0x80`) — DIR** — the direction bit. In balanced mode the controlling station (`CLIENT`)
  sends `DIR=1` and the controlled station (`SERVER`) sends `DIR=0`. In unbalanced mode this bit is
  the reserved RES bit and is always `0`.
- **bit 6 (`0x40`) — PRM** — the primary/secondary bit. `PRM=1` marks a primary (request) frame the
  station initiated; `PRM=0` marks a secondary (response) frame.
- **bit 5 (`0x20`) — FCB (primary) / ACD (secondary)** — the frame count bit on a primary frame, the
  access-demand bit on a secondary frame. The two views alias the same stored bit; use `fcb()` on
  primary frames and `acd()` on secondary frames.
- **bit 4 (`0x10`) — FCV (primary) / DFC (secondary)** — the frame count valid bit on a primary
  frame, the data-flow-control bit on a secondary frame. Use `fcv()` and `dfc()` respectively.
- **bits 3..0 (`0x0F`) — function code** — the four-bit link-layer function code, `0..15`.

`LinkControlField.primary(dir, fcb, fcv, functionCode)` and
`LinkControlField.secondary(dir, acd, dfc, functionCode)` build the two flavors;
`toOctet()`/`fromOctet(int)` encode and decode the wire value. The function codes the library uses
are:

- **Primary (PRM=1):** `FC0` reset-of-remote-link, `FC2` test-function-for-link (balanced),
  `FC3` user-data send/confirm, `FC4` user-data send/no-reply (unbalanced broadcast),
  `FC9` request-status-of-link, `FC10` request-class-1-data (unbalanced),
  `FC11` request-class-2-data (unbalanced).
- **Secondary (PRM=0):** `FC0` positive acknowledgement (ACK), `FC1` negative acknowledgement
  (NACK), `FC8` respond-user-data (unbalanced), `FC9` respond/data-not-available (unbalanced),
  `FC11` status-of-link, `FC14` link-service-not-functioning and `FC15` link-service-not-implemented
  (balanced; the unbalanced outstation also returns `FC14` to reject user data sent before a link
  reset).

**FCV gates the FCB.** Only a primary frame with `FCV=1` carries a meaningful frame count bit and is
FCB-checked by the secondary: user data (`FC3`) and the class polls (`FC10`/`FC11`). The bring-up and
control frames carry `FCV=0` — reset-of-remote-link (`FC0`), request-status-of-link (`FC9`), and the
send/no-reply broadcast (`FC4`) — so their FCB is not significant and they are never treated as
retransmissions.

## The FCB stop-and-wait method

FT1.2 flow control is **stop-and-wait with a window of one outstanding frame**, sequenced by a single
alternating bit rather than by sequence numbers (IEC 60870-5-2). A station's primary process holds at
most one unconfirmed primary frame at a time; nothing else is sent until that frame is acknowledged,
times out, or is exhausted by retransmission.

- **The frame count bit (FCB)** alternates `0`/`1` once per confirmed `FCV=1` transaction. After a
  link reset the primary's next FCB is `1` (`nextFcb = true`) and the secondary expects its first
  `FCV=1` frame to differ from `expectedFcb = false`, so the first post-reset user-data or poll frame
  always reads as new. Each successful confirmation toggles the bit.
- **An unchanged FCB is a retransmission.** When a secondary receives an `FCV=1` frame whose FCB
  equals the one it last accepted, it treats the frame as a replay of a response the primary never
  heard: it **replays its cached last response** (`lastResponse` / `lastSecondaryResponse`) without
  re-delivering the ASDU. A changed FCB is a fresh frame: the secondary delivers the ASDU,
  acknowledges, caches the new response, and advances its expected FCB. This makes a lost
  acknowledgement recoverable without duplicate delivery.
- **The window is one frame, end to end.** A primary stamps `FCV=1`, sends the frame, arms the
  confirm timer, and waits. The confirmation is the secondary's `FC0` acknowledgement or the
  single-character `0xE5`; on it the primary toggles its FCB, clears the pending slot, and releases
  the next queued frame.

This is the deliberate opposite of the 104 sequence-number method. Where `ApciSession` keeps 15-bit
`V(S)`/`V(R)` send and receive state, lets up to `k` (default `12`) I-frames be outstanding on a
**sliding window**, and acknowledges cumulatively with an N(R) carried on every I- or S-frame after
at most `w` (default `8`) received frames, FT1.2 carries no sequence numbers at all: a single
toggling bit, exactly one frame in flight, and an explicit per-frame confirmation. Lost-frame
recovery is by timeout-and-retransmit of the single outstanding frame rather than by gap detection
across a window, and there is no cumulative N(R) acknowledgement — each frame is individually
confirmed.

## Balanced transmission

In `LinkMode.BALANCED` the link is a symmetric, full-duplex, point-to-point connection between two
**combined stations**, and `BalancedEngine` runs regardless of `Role`. Each station runs a primary
process (the frames it initiates, with its own FCB) and a secondary process (acknowledging the
peer's frames, tracking the peer's FCB) at the same time. The `Role` selects only the small
asymmetries: the `DIR` bit on every outgoing frame (`CLIENT` → `DIR=1`, `SERVER` → `DIR=0`), which
station initiates the link-reset bring-up, and which station may drive
`startDataTransfer()`/`stopDataTransfer()`.

- **Link-reset bring-up is the data-transfer-start analog.** A `Role.CLIENT` station's
  `startDataTransfer()` drives the handshake **FC9 → FC11 → FC0 → ack**: it sends a
  request-status-of-link (`FC9`), and on the peer's status-of-link (`FC11`) reply it sends a
  reset-of-remote-link (`FC0`); the positive acknowledgement of that reset completes the bring-up.
  Bring-up makes the link available, fires `Events.onDataTransferStateChanged(true)`, resets the FCB
  state (primary `nextFcb = true`, secondary `expectedFcb = false` on both ends), completes the stage
  returned by `startDataTransfer()`, and flushes any queued user data.
- **A `Role.SERVER` station follows the peer.** It reaches the available state by *receiving* the
  peer's reset-of-remote-link, which sets the link available, fires the same data-transfer-state
  event, acknowledges, and flushes its own queued data. `isDataTransferStarted()` returns whether the
  link is available.
- **There is no stop-data service.** `stopDataTransfer()` (CLIENT only) completes immediately and
  leaves the link available; it exists only to satisfy the `Session` contract symmetrically with
  `startDataTransfer()`. Both methods throw `IllegalStateException` on a `SERVER` station.
- **User data is one frame at a time.** `sendAsdu(asdu)` queues the ASDU and flushes the queue; the
  flush sends a send/confirm user-data frame (`FC3`, `FCV=1`) only while the link is available and no
  primary frame is outstanding. Queued ASDUs go out in submission order.
- **Idle keep-alive and liveness.** The link-state timer probes an idle but available link with a
  request-status-of-link (`FC9`) keep-alive; a received test-function (`FC2`) or status request is
  answered from the secondary process. If a primary frame goes unconfirmed after the configured
  retries, `BalancedEngine` self-closes the session with a `ProtocolTimeoutException` reported through
  `Events.onClosed`.

## Unbalanced transmission

In `LinkMode.UNBALANCED` the link is an asymmetric, half-duplex, multi-drop bus: one master (always
the primary) polls one or more secondary stations (slaves), and a slave **never initiates** a
transfer. The dispatcher selects `UnbalancedMasterEngine` for `Role.CLIENT` and
`UnbalancedSlaveEngine` for `Role.SERVER`.

**Master (`UnbalancedMasterEngine`).** Because a single medium is shared, the master enforces a
*global* stop-and-wait discipline — at most one transaction (`Pending`) is outstanding on the whole
bus — while tracking the FCB *per secondary* in a `SlaveState` (its `nextFcb`, its `LinkState`, and
its DFC flag).

- **Per-slave bring-up.** Each configured slave is brought up independently with `FC9 → FC11 → FC0 →
  ack`; the reset's acknowledgement transitions that slave to `AVAILABLE` and restarts its FCB at
  `1`.
- **Poll scheduler.** Once available, slaves are polled for class-2 data with request-class-2
  (`FC11`, `FCV=1`) frames, round-robin across the available slaves, on the configured
  `PollConfig.pollInterval` cadence. The `pump()` bus loop runs only while data transfer is started
  and the bus is free, in priority order: (1) bring up any not-yet-reset slave; (2) deliver the head
  command if its target slave can accept it; (3) on a due poll tick, request class-2 data from the
  next available slave.
- **Class-1/class-2 and ACD escalation.** A poll response carrying the **access-demand bit (ACD)**
  escalates immediately to a request-class-1 (`FC10`) drain of the slave's high-priority data,
  bounded by `MAX_ACD_DRAIN` (16) consecutive drains so a slave that keeps asserting ACD cannot
  monopolize the bus; any remaining class-1 data is picked up on the slave's next class-2 poll.
- **DFC back-pressure.** A response carrying the **data-flow-control bit (DFC)** marks the slave
  back-pressured. That suspends sending it *user data* but not *polling* it — a back-pressured slave
  is still polled so its data drains and its DFC bit is re-read — until a later response clears DFC.
- **Commands and broadcast.** `sendAsdu(asdu)` appends to a global command queue; the target slave is
  the one whose link address equals the ASDU's common address (the frozen facade mapping), sent as
  send/confirm user data (`FC3`, `FCV=1`). An ASDU whose common address equals the configured
  `broadcastAddress` is sent as send/no-reply (`FC4`) to all stations and is not acknowledged.
- **Per-slave degradation.** When a transaction goes unconfirmed after the configured retries the
  offending *slave* is degraded to `LinkState.ERROR` and the transaction is dropped; the bus and the
  `Session` stay open, so one dead slave cannot kill the link. Unlike `BalancedEngine`, the master
  therefore **never self-closes the session on a protocol timeout** — only a transport loss (the
  binding calling `close()`) ends the session.

**Slave (`UnbalancedSlaveEngine`).** A secondary is purely reactive: it has no primary process, no
FCB of its own, and no confirm/repeat timers. Every frame it emits answers a primary frame from the
master, and it simply replays its last response when it sees the same FCB again.

- **Class queues.** Application ASDUs published through `sendAsdu(asdu)` are **buffered, not sent**,
  sorted into two bounded FIFO queues: a class-1 (event) queue and a class-2 (cyclic) queue. The
  frozen class-assignment heuristic routes a `Cause.SPONTANEOUS` ASDU to class 1 and every other
  cause to class 2 — the facade has a single publish path, where the lib60870 model leaves the class
  choice to the application.
- **Poll responses.** A request-class-2 (`FC11`) dequeues one class-2 ASDU as a respond-user-data
  (`FC8`) frame, falling back to a class-1 ASDU when class-2 is empty and to a data-not-available
  (`FC9`/`0xE5`) frame when both are empty; a request-class-1 (`FC10`) dequeues one class-1 ASDU or
  answers data-not-available.
- **ACD and DFC.** Each response advertises the **ACD** bit when class-1 data is still pending (so the
  master can escalate to a class-1 poll) and the **DFC** bit while either bounded class queue is
  saturated (back-pressure).
- **Availability.** The link becomes available when the master sends its reset-of-remote-link, which
  fires `Events.onDataTransferStateChanged(true)`. Send/confirm user data (`FC3`) that arrives before
  that reset is rejected with a link-service-not-functioning (`FC14`) frame and never delivered, so a
  peer cannot bypass the link-reset gate to inject application data. As a `SERVER`-role station the
  slave never drives `startDataTransfer()`/`stopDataTransfer()`; both throw `IllegalStateException`.

## Link addressing

The link address identifies a station on the link and is encoded as `linkAddressLength` little-endian
octets (low octet first), with `linkAddressLength` in the range `0..2`.

- **Balanced: optional.** The address may be absent (`linkAddressLength = 0`, valid only in balanced
  mode), which omits the field entirely on a single point-to-point link; one or two octets are also
  permitted.
- **Unbalanced: mandatory.** `LinkSettings` requires `linkAddressLength >= 1` in unbalanced mode,
  because the address selects which secondary a frame is directed at. The all-secondaries
  **broadcast address** is `255` for a one-octet address and `65535` for a two-octet address;
  `LinkSettings` validates that `broadcastAddress` matches the address width, and the master sends
  send/no-reply (`FC4`) broadcasts to it. In unbalanced mode the facade maps each ASDU's common
  address onto the target slave's link address.

A `linkAddressLength` of `0` with a non-zero address is rejected, as is an out-of-range address for
the configured width.

## Timers

Three durations from `LinkSettings` drive the FT1.2 stop-and-wait machine, plus the unbalanced
master's poll cadence from `LinkSettings.PollConfig` (defaults in parentheses). All are scheduled on
an injected `ScheduledExecutorService` and run under the engine lock.

| Timer | Default | Meaning | Where applied |
|---|---|---|---|
| `confirmTimeout` | 200 ms | Time to wait for the acknowledgement of a sent primary frame before the first retransmission | `BalancedEngine`, `UnbalancedMasterEngine` |
| `repeatTimeout` | 1000 ms | Spacing between repeated transmissions of an unacknowledged primary frame | `BalancedEngine`, `UnbalancedMasterEngine` |
| `linkStateTimeout` | 5000 ms | Idle interval after which an available link is probed with a request-status-of-link keep-alive | `BalancedEngine` |
| `pollInterval` | 1000 ms | Cadence between class-2 poll cycles across the available slaves | `UnbalancedMasterEngine` |

How the engines run them:

- **`confirmTimeout` / `repeatTimeout`** arm a single confirm timer whenever a primary frame is sent
  awaiting a confirmation: first at `confirmTimeout`, then re-armed at `repeatTimeout` for each
  retransmission. Receiving the confirmation cancels it and clears the pending slot. On the
  `maxRetries`th (default `3`) unanswered retransmission the engines diverge: `BalancedEngine` closes
  the session with a `ProtocolTimeoutException` via `Events.onClosed`, while `UnbalancedMasterEngine`
  degrades only the offending slave to `LinkState.ERROR`, drops the transaction, and keeps the bus
  open. The unbalanced slave arms neither timer — the master owns all retransmission, and the slave
  merely replays its cached response on an unchanged FCB.
- **`linkStateTimeout`** is `BalancedEngine`'s idle keep-alive clock; any inbound frame restarts it
  (and sending user data re-arms it). On expiry, if the link is available and the primary process is
  idle with an empty send queue, the engine sends a request-status-of-link (`FC9`) keep-alive and
  arms the confirm timer to await the status-of-link (`FC11`) reply; otherwise it simply keeps the
  idle clock ticking. The unbalanced master uses the poll cadence for liveness instead, and the
  purely-reactive slave has no idle timer.
- **`pollInterval`** is `UnbalancedMasterEngine`'s steady-state class-2 poll clock, armed when data
  transfer starts and re-armed on every tick. Each tick marks a poll owed and pumps the bus loop,
  which issues a request-class-2 frame to the next available slave when the bus is free.

## Lifecycle and threading

```
connect ──► onConnected()              reset link state, (balanced) arm link-state timer
            │
  CLIENT/   startDataTransfer():
  BALANCED:   FC9 ─► FC11 ─► FC0 ─► ack ─► linkAvailable, onDataTransferStateChanged(true)
  CLIENT/   startDataTransfer():
  MASTER:     arm poll timer, pump() ─► per-slave FC9/FC11/FC0 bring-up
  SERVER:   (await peer reset) ──────────► linkAvailable, onDataTransferStateChanged(true)
            │
  data:     sendAsdu(...) ─► balanced/master: one outstanding FC3 frame, else queued
                             slave: buffered into class-1 / class-2 queues, sent on poll
            onFrame(...)  ─► primary frame: secondary process (FCB check, deliver, ack/replay)
                             secondary frame / 0xE5: confirm the outstanding primary, toggle FCB
            │
  idle:     (balanced) linkStateTimer ─► FC9 keep-alive ─► (confirm) ─► FC11
            (master)   pollTimer ──────► request-class-2 next available slave
            │
  error:    confirm timeout after maxRetries ─►
              balanced: closeWithError(ProtocolTimeoutException) ─► Events.onClosed(cause)
              master:   markSlaveError(slave) ─► bus stays open (no self-close)
            │
  teardown: close()  (idempotent; binding routes transport loss to Events.onConnectionLost)
```

Each engine guards all mutable state with one internal `ReentrantLock`, and its timer callbacks
acquire that same lock, so a caller may invoke any method from any thread. Stale timer dispatches are
neutralized by **generation counters**: arming a timer bumps the generation, and a fired task that
finds the generation changed (or the session closed) returns without acting — so a task already
dispatched while a fresh timer was armed becomes a no-op. The `Output` (outbound frame sink) and
`Events` callbacks are invoked **while the lock is held** and must not block or re-enter the engine;
the higher layers therefore hop to a separate callback executor before doing application work. See
[buffers-and-threading.md](buffers-and-threading.md).

`Ft12LinkLayer` is the public façade over this: it picks the engine by `(role, mode)` —
`BALANCED` → `BalancedEngine` for either role, `UNBALANCED` + `CLIENT` → `UnbalancedMasterEngine`,
`UNBALANCED` + `SERVER` → `UnbalancedSlaveEngine` — and forwards every `Session` call plus
`onFrame(Ft12Frame)`. `Cs101Binding` is the assembly seam (the FT1.2 peer of `Cs104Binding`): it
frames each outbound `Ft12Frame` to a whole-frame `ByteBuf`, deframes inbound frames, and routes a
failed send or a transport connection loss to `Session.Events.onConnectionLost`, distinct from the
engine's own protocol-error self-close on `onClosed`.

File transfer (the `F_*` TypeIDs) is **out of scope** for the high-level layer on the serial path; it
is reachable only through the raw-ASDU send/receive hooks, and a modeled file-transfer service is to
be inherited later from the shared CS104 file path.
