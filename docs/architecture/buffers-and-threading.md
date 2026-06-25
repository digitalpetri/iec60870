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

Because the codecs do not release and the SPI transfers ownership cleanly in one direction per call,
reference counting stays correct and there is no double-free or use-after-free across the boundary. A
caller using the high-level facade never sees a `ByteBuf` at all — the facade does the framing and
deframing, so by the time an `Apdu` or `Asdu` exists the buffer is gone. (Netty PARANOID leak
detection is enabled in the transport-tcp and cross-module test scopes so any missed release surfaces
loudly as a `LEAK:` log during the build.)

Client transport lifecycle has one extra distinction: `disconnect()` is an intentional shutdown that
may stop reconnection, while `closeConnection()` closes only the current connection. The cs104
binding uses that narrower close when a malformed inbound frame cannot be decoded, so a persistent
TCP client can drop the bad socket and reconnect without the core SPI exposing Netty channel types.

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
| Application callback thread | `callbackExecutor` (client/server config) | Serial event delivery; safe to block here |
| Blocking-call completion | `callbackExecutor` | Blocking facade methods complete on this executor |
