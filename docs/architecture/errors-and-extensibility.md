# Errors and Extensibility

## The error model

The library draws a deliberate line between two kinds of "the request did not succeed":

- A **protocol-level negative answer** — the peer understood the request and declined it (a command
  rejected, an unknown address) — is reported as a **result object** when it is the expected outcome
  of a normal operation.
- A **failure** — a malformed message, a timeout, a lost connection, a sequence-number violation — is
  thrown as a **typed exception**.

This keeps ordinary control flow (a station rejecting a command) off the exception path while still
making genuine faults impossible to ignore.

### Exception hierarchy

All library exceptions are unchecked and extend `Iec60870Exception`, so an application can catch that
one type to handle any library-specific failure uniformly.

```
RuntimeException
└── Iec60870Exception                     base; catch to handle any library failure
    ├── AsduDecodeException             malformed / truncated / out-of-range wire data on decode
    ├── ProtocolTimeoutException        an expected response did not arrive in time (t1..t3, request deadline)
    ├── ConnectionClosedException       operation on a closed/lost connection; pending requests failed on loss
    ├── NegativeConfirmationException   peer answered a request with P/N=1 (carries Cause and the ASDU)
    ├── UnsupportedAsduTypeException    type identification undefined, or defined but with no typed object mapping
    └── SequenceNumberException         APCI N(S)/N(R) violated the k/w flow-control rules
```

When each is used:

| Exception | Raised when | Typical source |
|---|---|---|
| `AsduDecodeException` | An ASDU or a field cannot be decoded: bad START octet, truncated header, undefined cause, out-of-range count | `Apdu.Serde` / `Asdu.Serde` / element `Serde`s during decode |
| `ProtocolTimeoutException` | An APCI timer or a request/response deadline elapses before the peer responds | `ApciSession` t1 expiry; client `commandTimeout` / `requestTimeout` |
| `ConnectionClosedException` | A new operation is attempted on a closed connection, or a pending request is failed because the transport reported a loss | `ApciSession.close()`; client/server on disconnect |
| `NegativeConfirmationException` | A *blocking* request (e.g. `interrogate`, `read`, `synchronizeClock`) receives a confirming ASDU with the P/N bit set | client request correlation |
| `UnsupportedAsduTypeException` | `AsduType.fromId(int)` sees an undefined type identification, or a typed object is required for a type that is only reachable via the raw codec | decode dispatch; `AsduType.fromId` |
| `SequenceNumberException` | An inbound I-frame's N(S) does not match V(R), or an N(R) acknowledges frames that were never sent | `ApciSession.onApdu(...)` |

A `SequenceNumberException` (and a t1 `ProtocolTimeoutException`) is fatal to the connection: the
`ApciSession` closes itself and reports the cause through `Events.onClosed`, which the facade surfaces
as a `ClientEvent.ConnectionClosed` / `ServerEvent.ConnectionClosed` carrying the cause.

### Result objects (the non-exception path)

Some operations report a negative answer as a value rather than an exception, because a rejection is
a normal, expected outcome:

- **`CommandResult(target, positive, cause, confirmation)`** — returned by `CommandService`. A
  command does **not** throw when the station rejects it: `positive() == false` means a negative
  confirmation arrived, and `cause()` / `confirmation()` describe it. Only transport/session failures
  (timeout, disconnect) throw.
- **`InterrogationResult(station, objects, terminated)`** — `terminated()` reports whether the station
  sent `ACT_TERM`; `false` means the collection ended another way (such as a timeout) without raising.
  (A negative `ACT_CON` to the interrogation itself *does* raise `NegativeConfirmationException`.)
- **`ClientEvent.NegativeConfirmation(asdu, cause)`** — an *uncorrelated* negative confirmation, one
  not tied to a pending blocking request, is delivered as an event rather than thrown.
- **`CommandDecision`** (server side) — a `ServerHandler` rejects a command by returning
  `CommandDecision.reject(cause)`, which the server turns into a negative activation confirmation, not
  by throwing.

The guideline: choose the exception when a fault prevents the operation from producing a meaningful
answer; choose the result object when "rejected" is itself a meaningful answer the caller will branch
on.

## Extensibility

The raw layer is the extension surface. The high-level facade (`Iec60870Client` / `Iec60870Server`,
the station/point model, commands, and events) supports the standard, modeled TypeIDs. There is no
pluggable codec registry: the encode/decode dispatch is driven by `AsduType`, and `AsduType.fromId`
rejects any undefined type identification with `UnsupportedAsduTypeException` before any codec could
be consulted. An unsupported or unmodeled TypeID is therefore surfaced as a decode failure
(`AsduDecodeException` / `UnsupportedAsduTypeException`), not delivered as a raw event.

### Raw send/receive hooks

Even without a custom codec, an application can drop to the raw layer at any time:

- **Client.** `send(Asdu)` transmits any ASDU as-is; every inbound ASDU is published as
  `ClientEvent.AsduReceived` (in addition to any `PointUpdated` events), so an application can observe
  and respond to types the facade does not model.
- **Server.** `ServerHandler.onRawAsdu(ServerContext, Asdu)` is offered every received ASDU before the
  standard dispatch. Returning `true` claims the ASDU — the standard dispatch is skipped and the
  handler is responsible for any reply via `ServerContext.send(Asdu)`. Returning `false` (the default)
  defers to standard handling. This is the server-side hook for private TypeIDs and any bespoke
  procedure (including a file-transfer state machine built on the `F_*` types).

Together these cover the modeled procedures and the bespoke ones built on standard TypeIDs: a
received ASDU of a supported type that the facade does not act on is still surfaced as a raw `Asdu`
(via `ClientEvent.AsduReceived` or `ServerHandler.onRawAsdu`), so it is never silently dropped. A
TypeID the library does not model at all cannot be decoded and is reported as a decode failure
rather than delivered.
