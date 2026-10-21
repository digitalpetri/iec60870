# Buffers and Threading

This document covers two cross-cutting concerns a caller must get right: who owns the `ByteBuf` that
the codecs read and write, and which thread runs application callbacks.

## Buffer ownership

`ByteBuf` appears in `iec60870-core` in two places. The first is inside the `Serde` classes of the
raw model (`Asdu.Serde`, `Apdu.Serde`, `ControlField.Serde`, `CommonAddress.Serde`, the per-type
`InformationObjectCodec`s, and the element/time `Serde`s). The second is on the **octet transport
SPI** (`com.digitalpetri.iec60870.transport`), where `ByteBuf` is the sanctioned codec-boundary type
that carries one complete, length-delimited frame across the seam. The transport SPI exchanges
whole-frame `ByteBuf`s; turning a frame into an `Apdu` (via `ApduFramer`) happens *above* the SPI,
not inside the transport.

### Codec `Serde` rule

The raw-model `Serde` classes follow one uniform rule:

> **The codec never allocates and never releases the buffer. Allocation and release are the
> transport's job.**

Concretely:

- **`encode` writes into a caller-owned buffer and never releases it.** It appends starting at the
  buffer's current writer index and leaves the buffer otherwise untouched. For example
  `Apdu.Serde.encode(apdu, profile, buffer)` writes the start octet, back-patches the length octet
  once the body size is known, and writes the control octets and (for I-frames) the ASDU — all into
  the buffer the caller passed.
- **`decode` reads from a caller-owned buffer and never releases it.** It advances the reader index
  over the bytes it consumes and leaves the rest. `decode` assumes a complete unit is present;
  length-based framing is the transport's responsibility (see below). If the buffer ends before a
  fixed envelope field is fully readable, decode throws `AsduDecodeException` rather than reading past
  the end.

All multi-octet fields use Mode 1 — least significant octet first (little-endian) — and the codecs
say so explicitly with width-and-endianness calls (`writeShortLE`/`readUnsignedShortLE`,
`writeIntLE`, `writeByte`/`readUnsignedByte`, `writeFloatLE`/`readFloatLE`). They never rely on the
buffer's default byte order.

### Where allocation and release actually happen

```
  encode path (outbound)                      decode path (inbound)
  ─────────────────────                       ─────────────────────
  facade frames Apdu -> ByteBuf via            decoder frames on 0x68 + length, emits one
  ApduFramer.encode(apdu, profile, alloc)      whole-frame ByteBuf via in.readRetainedSlice(...)
        │                                            │
  transport.send(byteBuf)                      TransportListener.onFrame(byteBuf)
        │  (transport writes-and-flushes,            │  (transport owns; facade deframes
        │   then releases)                           │   synchronously via ApduFramer.decode)
  raw ByteBuf write to channel                 InboundFrameHandler auto-releases the slice
                                               after onFrame returns
```

In `iec60870-transport-tcp`, framing is pure octet handling — no `Apdu` is parsed there. On the
inbound path, `Iec104FrameDecoder` performs the length-field framing on the `0x68` start octet and
length octet, slices exactly one whole frame with `in.readRetainedSlice(frameLength)` (a slice with
its own `+1` reference count), and emits that `ByteBuf` downstream; the terminal
`InboundFrameHandler` is a `SimpleChannelInboundHandler<ByteBuf>` with auto-release on, so it
releases the slice once the listener's `onFrame` returns — balancing the retain. On the outbound
path there is no encoder handler: the protocol layer above the SPI frames each `Apdu` into a complete
length-delimited `ByteBuf` (via `ApduFramer.encode`) and the transport writes-and-flushes that buffer
raw, with Netty releasing it after the write.

### Octet transport SPI ownership

The `ByteBuf` that crosses the transport SPI is reference-counted, and ownership transfers in a
single uniform direction at each call:

- **Inbound** — `TransportListener.onFrame(ByteBuf)`: the transport **owns** the frame. The listener
  must decode it synchronously within the call and must **not** retain it past the call or release
  it. This mirrors Netty's `SimpleChannelInboundHandler`, which auto-releases the message after the
  callback returns.
- **Outbound** — `ClientTransport.send(ByteBuf)` / `ServerTransportConnection.send(ByteBuf)`: the
  caller allocates the frame; the transport writes-and-flushes it and releases it. The caller must
  **not** release it after calling `send`.
- **Outbound order** — frames reach the peer in the order `send` is called, whichever thread calls
  it. `ApciSession` emits frames under its lock from the Netty event loop (acknowledgements, window
  flushes after an S-frame) and from application and timer threads (new I-frames, `t2` S-frames).
  Netty writes immediately on the event loop but queues a task from any other thread, so a direct
  `writeAndFlush` would let an event-loop frame overtake an earlier queued one and break the N(S) or
  N(R) sequence at the peer. `NettyClientTransport` and `NettyServerConnection` therefore submit every
  write as an event-loop task through `OrderedFrameWriter`.

Because the codecs do not release and the SPI transfers ownership cleanly in one direction per call,
reference counting stays correct and there is no double-free or use-after-free across the boundary. A
caller using the high-level facade never sees a `ByteBuf` at all — the facade does the framing and
deframing, so by the time an `Apdu` or `Asdu` exists the buffer is gone. (Netty PARANOID leak
detection is enabled in the transport-tcp and cross-module test scopes so any missed release surfaces
loudly as a `LEAK:` log during the build.)

Client transport lifecycle has one extra distinction: `disconnect()` is an intentional shutdown that
may stop reconnection, while `closeConnection()` closes only the current connection. The cs104
binding uses that narrower close when an inbound frame has a framing error (or, under
`UndecodableAsduPolicy.STRICT`, an undecodable ASDU), so a persistent TCP client can drop the bad
socket and reconnect without the core SPI exposing Netty channel types. An undecodable ASDU in an
intact frame does not close anything by default; the binding copies its octets out of the frame
before `onFrame` returns, so the reported `byte[]` never aliases the transport's buffer.

## Threading and callback serialization

Above the codec boundary the library deals only in immutable objects, and it imposes a clear
threading contract so application code never has to reason about transport I/O threads.

### The layers and their threads

- **Transport I/O threads.** A `TransportListener` (`onFrame`, `onConnectionLost`) and the transport
  lifecycle stages may run on a Netty I/O thread. These callbacks must return promptly and must not
  block — the transport interfaces document this explicitly. `onFrame` additionally must decode its
  frame `ByteBuf` synchronously without retaining it (the transport owns and releases the buffer).
- **The `ApciSession`.** All its state is guarded by a single internal `ReentrantLock`; its timer
  callbacks (scheduled on an injected `ScheduledExecutorService`) take the same lock. A caller may
  invoke any session method from any thread. Critically, the session invokes its `Output` and
  `Events` callbacks **while holding the lock**, so those callbacks must not block and must not
  re-enter the session. The session is therefore the synchronization point for one connection's
  protocol state.
- **The callback executor.** Because the session callbacks run under a lock on whatever thread drove
  the session, the high-level client and server do not run *application* code there. They hop to a
  configured `Executor` — `ClientConfig.callbackExecutor` / `ServerConfig.callbackExecutor`, default
  the common `ForkJoinPool` — to deliver events, complete blocking calls, and run `ServerHandler`
  callbacks.

### Serialization guarantees

Event delivery is **serial**. A subscriber to `Iec60870Client.events()` or `Iec60870Server.events()`
never observes two events concurrently; events arrive in order on the callback executor. The
configuration docs note that the executor should preserve submission order — a single-threaded
executor is the simplest choice that does. On the server, `ServerHandler` callbacks for a single
connection are likewise serialized: a handler never receives two callbacks for the same connection at
once, even though different connections may be handled concurrently.

The practical rule for application code:

> **Do not block a transport I/O thread or an `ApciSession` callback.** Application work — including
> anything slow or blocking — belongs on the callback executor, which is where the facade already
> delivers events and runs handler callbacks. A blocking `ServerHandler` implementation is fine
> because the server invokes it on that executor, off the I/O thread; an `*Async` handler variant is
> available when the answer is itself non-blocking and I/O-bound.

### Ownership summary

| Resource | Owner | Rule |
|---|---|---|
| Inbound frame `ByteBuf` (`onFrame`) | Transport (`iec60870-transport-tcp`) | Transport owns; listener decodes synchronously, never retains or releases |
| Outbound frame `ByteBuf` (`send`) | Caller allocates, transport releases | Caller frames via `ApduFramer.encode`; transport writes-and-flushes then releases; caller never releases after `send` |
| Codec `Serde` `ByteBuf` | Caller of the `Serde` | Codec only reads/writes; never allocates or releases |
| One connection's protocol state | `ApciSession` | Single internal lock; callbacks run under it, must not block |
| Application callback thread | `callbackExecutor` (client/server config) | Serial event delivery; safe to block here (a blocked client subscriber pauses inbound data, see below) |
| Blocking-call completion | `callbackExecutor` | Blocking facade methods complete on this executor |

## Client event delivery and inbound flow control

`DefaultIec60870Client` publishes `ClientEvent`s through a package-private `EventStream`, a
`SubmissionPublisher` wrapper that never drops an event. Each subscriber is wrapped so the stream
knows exactly how many published events that subscriber's `onNext` has not yet returned from. The
buffer behind each subscriber grows on demand, so an idle client pays nothing for its size.

The bound is `ClientConfig.eventBacklogLimit()` (default 4096), enforced with protocol flow control
rather than by dropping:

1. The client publishes the events for one inbound ASDU (`AsduReceived`, any `PointUpdated`, any
   `NegativeConfirmation`, or one `UndecodableAsdu`), then checks the slowest subscriber's backlog.
2. At the limit, it calls `Session.setInboundPaused(true)`. `ApciSession` forwards that to
   `ClientTransport.setReadPaused(true)`, and `NettyClientTransport` clears Netty's `autoRead` on the
   current channel. Frames already read are still delivered and acknowledged within `w` and `t2`;
   frames not read are not received, so no acknowledgement obligation starts for them. The
   outstation stops after `k` unacknowledged I-frames, so the backlog exceeds the limit by at most
   the events of `k` APDUs.
3. When a subscriber's `onNext` returns, or a subscriber cancels or fails, and the slowest backlog is
   at or below half the limit, the stream calls `setInboundPaused(false)` and reads resume.

The pause and resume calls happen on the I/O thread (inside `Session.Events.onAsdu`) and on the
callback executor, so `setInboundPaused` must not wait for the session lock. `ApciSession` guards its
pause state with a separate innermost lock. A pause applies to the current connection; a new
connection starts reading, and the next ASDU pauses it again if the backlog is still high.

Reads also carry the peer's S- and U-frames. While paused, the client cannot see the outstation's
acknowledgements or a close, so `ApciSession` closes the session with a `ProtocolTimeoutException`
once a pause lasts `t1`, at about the time the outstation's own `t1` expires on the oldest unread
I-frame. A stalled subscriber therefore ends in a visible `ConnectionClosed` with a cause, never in
silent loss.

Lifecycle events (`ConnectionOpened`, `DataTransferStarted`, `DataTransferStopped`,
`ConnectionClosed`) are never subject to the limit. The hard per-subscriber capacity is the smallest
power of two at or above `2 * limit + 8192`; data events stop 1024 slots short of it, so lifecycle
events always fit. A session that cannot pause (the CS 101 link layers keep the `Session` default
no-op for now) relies on that capacity. If the slowest backlog reaches it, the client discards
further inbound data, closes the connection with an `EventBacklogExceededException`, and delivers
`ConnectionClosed` with that cause after every event published before it. Until that subscriber
catches up, `connect`, `startDataTransfer`, and `stopDataTransfer` fail with the same exception.
Each operation admitted below the data capacity publishes at most a few lifecycle events, so
repeated connect or start/stop cycles cannot exhaust the reserve.

`DefaultIec60870Server` keeps a plain `SubmissionPublisher` with `Flow.defaultBufferSize()` slots per
subscriber. Its events follow control-direction traffic, so the risk is lower; an event that finds
a full buffer is dropped for that subscriber and logged at WARN.

## Outbound completion and bounded replies

`Session.sendAsdu` submits discardable traffic such as spontaneous events. Its bounded queue follows
`OutboundQueuePolicy`. `Session.sendAsduAsync` submits a protected ASDU and returns its first
transport-write completion. It remains pending while flow control or polling holds the ASDU in the
queue. It fails if that queue is full of protected entries, encoding or writing fails, or the session
closes or resets before completion. Success does not mean that the peer acknowledged or processed the ASDU. An APCI
encoding rejection fails only the affected submission; queued writes after it can continue. The
legacy void APCI send still throws when its ASDU is rejected synchronously.

Protected entries cannot be evicted by later event publishing. `DROP_OLDEST` evicts the oldest
remaining discardable entry; if all queued entries are protected, the incoming event is dropped.
A new protected submission evicts the oldest discardable entry from a full queue, regardless of the
event overflow policy. It is rejected only when every queued entry is protected. Both submission
modes share the configured queue bound. Session implementations use `OutboundAsduQueue` to track entries through transport completion,
including writes already removed from the pending queue when the connection closes.

The server streams each station or counter interrogation response one ASDU at a time, waiting for
each transport write before offering the next ASDU. ACT_TERM is offered only after every data write
succeeds. A rejected or failed solicited write closes that connection. Large responses therefore do
not overflow the event queue, and the per-connection handler chain stays pending until the response
finishes.

Before taking an interrogation snapshot or invoking a custom interrogation handler, the server
pauses event publishing for that connection. Publications already being submitted, and any older
deferred events, finish before the snapshot is taken. New publications wait until the response
finishes, so a snapshot cannot overwrite a newer event at the peer. This applies to station/group
and counter interrogations and preserves custom handler response objects. Rejection or handler
failure releases the pause too.

Each connection has a separate deferred-event buffer bounded by `maxOutboundQueue`, in addition to
the session's queue; zero leaves both unbounded. The deferred buffer uses the configured event drop
policy or BLOCK timeout. Only publishing threads may wait for capacity. After the response, deferred
events drain one tracked write at a time, without blocking the callback executor. Solicited replies
sent while that backlog drains or while a publication is being submitted, such as a later command's
confirmation and return information, queue behind them. A reply therefore cannot overtake an older
event still waiting in the backlog. Queued replies are capped at `maxOutboundQueue`. Below that cap,
a reply that finds the buffer full evicts the oldest deferred event, as a protected session entry
does. Once replies alone reach the cap, the next reply is rejected and the connection closes. Events
never evict replies, so the buffer holds at most `maxOutboundQueue` events and as many replies.
Replies that a custom interrogation handler sends during its own response still go out directly. A
subsequent interrogation waits only for the finite prefix ahead of its snapshot marker; later
publications cannot indefinitely postpone it. Close discards the deferred backlog and wakes blocked
publishers.

Direct callers of the `Session` SPI must dispatch dependent work before blocking or re-entering a
session: its completion callbacks can run under the session lock. Facade `sendAsync` relays success
and failure onto the configured client callback executor, as other facade operations do. The built-in
server uses an iterative drain to support direct executors and synchronously completed writes without
recursion per response item. Custom `Session` implementations must implement `sendAsduAsync` to
support facade sends; its compatibility default fails without submitting. Custom asynchronous
`ApciSession.Output` and `Ft12LinkLayer.Output` sinks must override `sendAsync` and return the actual
write stage. Their default assumes the existing `send` method writes synchronously.
