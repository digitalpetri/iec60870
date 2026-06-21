# Buffers and Threading

This document covers two cross-cutting concerns a caller must get right: who owns the `ByteBuf` that
the codecs read and write, and which thread runs application callbacks.

## Buffer ownership

The only place a `ByteBuf` appears in `iec104-core` is inside the `Serde` classes of the raw model
(`Asdu.Serde`, `Apdu.Serde`, `ControlField.Serde`, `CommonAddress.Serde`, the per-type
`InformationObjectCodec`s, and the element/time `Serde`s). They follow one uniform rule:

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
  transport allocates ByteBuf                 transport receives bytes, frames on 0x68 + length
        │                                            │
  Apdu.Serde.encode(apdu, profile, buf)        Apdu.Serde.decode(profile, buf)
        │  (writes; never releases)                  │  (reads; never releases)
  transport writes buf to channel,             transport delivers Apdu, then
  Netty releases it after the write            releases the framed ByteBuf
```

In `iec104-transport-tcp`, `Iec104FrameEncoder` allocates (or is handed) the outbound buffer, calls
`encode`, and lets Netty release it after the channel write; `Iec104FrameDecoder` performs the
length-field framing on the `0x68` start octet and length octet, calls `decode` to produce an `Apdu`,
and releases the framed buffer. Because the codecs do not release, the transport's reference counting
stays correct and there is no double-free or use-after-free across the boundary. A caller using the
high-level facade never sees a `ByteBuf` at all — it is gone by the time an `Apdu` or `Asdu` exists.

## Threading and callback serialization

Above the codec boundary the library deals only in immutable objects, and it imposes a clear
threading contract so application code never has to reason about transport I/O threads.

### The layers and their threads

- **Transport I/O threads.** A `TransportListener` (`onApdu`, `onConnectionLost`) and the transport
  lifecycle stages may run on a Netty I/O thread. These callbacks must return promptly and must not
  block — the transport interfaces document this explicitly.
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

Event delivery is **serial**. A subscriber to `Iec104Client.events()` or `Iec104Server.events()`
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
| Outbound/inbound `ByteBuf` | Transport (`iec104-transport-tcp`) | Allocates and releases; codecs only read/write |
| One connection's protocol state | `ApciSession` | Single internal lock; callbacks run under it, must not block |
| Application callback thread | `callbackExecutor` (client/server config) | Serial event delivery; safe to block here |
| Blocking-call completion | `callbackExecutor` | Blocking facade methods complete on this executor |
