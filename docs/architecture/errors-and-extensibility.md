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
    ├── SequenceNumberException         APCI N(S)/N(R) violated the k/w flow-control rules
    └── RequestInProgressException      a conflicting request to the same target is still in flight
```

When each is used:

| Exception | Raised when | Typical source |
|---|---|---|
| `AsduDecodeException` | A frame or an ASDU cannot be decoded: bad START octet or length (framing), or a truncated ASDU, undefined cause, or element value its record rejects (content) | `Apdu.Serde` / `Asdu.Serde` / element `Serde`s during decode |
| `ProtocolTimeoutException` | An APCI timer or a request/response deadline elapses before the peer responds | `ApciSession` t1 expiry; client `commandTimeout` / `requestTimeout` |
| `ConnectionClosedException` | A new operation is attempted on a closed connection, or a pending request is failed because the transport reported a loss | `ApciSession.close()`; client/server on disconnect |
| `NegativeConfirmationException` | A correlated request (e.g. `interrogate`, `read`, `synchronizeClock`, `counterInterrogate`, `test`, `resetProcess`, parameter activation) receives a confirming ASDU with the P/N bit set, or, for the counter, test, reset, and parameter services, a cause of 44 to 47 | client request correlation |
| `UnsupportedAsduTypeException` | `AsduType.fromId(int)` sees an undefined type identification, or the codec registry has no codec for the type (the `F_*` types) | decode dispatch; `AsduType.fromId` |
| `SequenceNumberException` | An inbound I-frame's N(S) does not match V(R), or an N(R) acknowledges frames that were never sent | `ApciSession.onApdu(...)` |
| `RequestInProgressException` | A blocking request is issued while a conflicting request to the same target is still in flight (their responses could not be told apart) | client request serialization in `DefaultIec60870Client` |

A `SequenceNumberException` (and a t1 `ProtocolTimeoutException`) is fatal to the connection: the
`ApciSession` closes itself and reports the cause through `Events.onClosed`, which the facade surfaces
as a `ClientEvent.ConnectionClosed` / `ServerEvent.ConnectionClosed` carrying the cause.

A decode exception is fatal only when it is a framing error. A content error in an otherwise intact
frame is reported as an event and the connection stays open; see [Undecodable
ASDUs](#undecodable-asdus).

## Undecodable ASDUs

A correctly framed frame can carry an ASDU that `Asdu.Serde.decode` cannot map onto the typed model:
an undefined or private type identification, an `F_*` type with no codec, an undefined or private
cause of transmission, sequence addressing that overflows the IOA range, a count that does not match
the octets present, or an element its record rejects. `Asdu.Serde.decode` reports each of these as an
`AsduDecodeException` or an `UnsupportedAsduTypeException`, including an element cut short (the
underlying `IndexOutOfBoundsException` becomes the cause).

The frame boundary and the link control information are still trustworthy in each case, so the link
layer separates framing from content:

- **CS 104.** `Cs104Binding` decodes the APCI first with `ApduFramer.decodeHeader`. A framing error (bad
  START octet, length outside `4..253`, undefined U-format function, S- or U-frame with a body, I-frame
  with an empty body) closes the connection as before. For an I-frame, the binding then decodes the
  ASDU. If that fails, it calls `ApciSession.onUndecodableIFrame`, which runs the same checks as any
  I-frame (data transfer started, N(S) equal to V(R), N(R) within the outstanding window), advances
  V(R), and acknowledges the frame within `t2` or at `w`.
- **CS 101.** `Ft12Framer.decode` verifies the checksum and returns a frame whose ASDU fails to decode
  as an `Ft12Frame.UndecodableVariable`. The engines run their normal frame-count-bit and
  acknowledgement procedure for it, so the peer does not retransmit it. A frame with a bad checksum is
  still dropped as line noise.

The session then reports the octets through `Session.Events.onUndecodableAsdu(byte[], cause)` instead
of `onAsdu`:

- The client publishes `ClientEvent.UndecodableAsdu(asdu, cause)` in place of `AsduReceived`.
- The server publishes `ServerEvent.UndecodableAsdu(remoteAddress, asdu, cause)` and answers as IEC
  60870-5-101 7.2.3 describes: the octets mirrored back with P/N=1 and cause `UNKNOWN_TYPE_ID` (44) when
  the type identification is undefined or has no codec, or `UNKNOWN_CAUSE` (45) when only the cause is
  undefined. Other content errors have no defined reply, and an ASDU that already carries P/N=1 is not
  mirrored. The mirror is sent with `Session.sendRawAsduAsync`, because the typed model cannot represent
  the undefined field. The CS 104 session supports raw sends; the CS 101 link layers do not yet, so a
  CS 101 server reports the ASDU without mirroring it.

Time tags do not cause content errors. `Cp56Time2a`, `Cp24Time2a`, and `Cp16Time2a` accept any value
their wire bit fields can hold and expose `isWellFormed()`, so a tag from a device with an unset clock
(often all zero with IV=1) decodes, and the information object's value is delivered. `MonitorMapping`
leaves the timestamp empty for a malformed CP56Time2a tag.

`UndecodableAsduPolicy.STRICT`, set on the binding or the `Tcp*` / `Serial*` builders, restores the
previous behavior for conformance testing. On CS 104 the connection closes without acknowledging the
I-frame and the decode failure is the cause of the `ConnectionClosed` event. On CS 101 the frame is
discarded without an acknowledgement. The default is `REPORT_AND_CONTINUE`.

A profile mismatch, such as a wrong IOA width, shows up as a stream of undecodable-ASDU events rather
than a closed connection.

### Result objects (the non-exception path)

Some operations report a negative answer as a value rather than an exception, because a rejection is
a normal, expected outcome:

- **`CommandResult(target, positive, cause, confirmation)`** — returned by `CommandService`. A
  command does **not** throw when the station rejects it: `positive() == false` means a negative
  confirmation arrived, and `cause()` / `confirmation()` describe it. Only transport/session failures
  (timeout, disconnect) throw.
- **`ParameterResult(target, positive, cause, valueInOperation, confirmation)`** — returned by
  `ParameterService.load`. A station confirms a parameter load with the value now in operation, the
  old value when it rejects the load, so a rejection is `positive() == false` with that value, not an
  exception.
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
the station/point model, commands, and events) supports the standard, modeled TypeIDs. `Asdu.Serde`
has overloads that take an `InformationObjectCodecRegistry`, so code that decodes ASDUs itself can
plug in codecs. The session path does not: it always decodes with the standard registry, and
`AsduType.fromId` rejects an undefined type identification before any registry is consulted. A type
the standard registry cannot decode reaches the application as an undecodable ASDU with its raw
octets (see [Undecodable ASDUs](#undecodable-asdus)), not as a typed `Asdu`.

### Raw send/receive hooks

An application can drop to the raw layer at any time:

- **Client.** `send(Asdu)` transmits any ASDU the typed model can encode; every inbound ASDU is
  published as `ClientEvent.AsduReceived` (in addition to any `PointUpdated` events), so an
  application can observe and respond to types the facade does not act on. An ASDU that cannot be
  decoded is published as `ClientEvent.UndecodableAsdu` with its raw octets.
- **Server.** `ServerHandler.onRawAsdu(ServerContext, Asdu)` is offered every decoded ASDU before the
  standard dispatch. Returning `true` claims the ASDU — the standard dispatch is skipped and the
  handler is responsible for any reply via `ServerContext.send(Asdu)`. Returning `false` (the default)
  defers to standard handling. This is the server-side hook for a bespoke procedure built on a
  standard, supported TypeID the facade does not itself act on. An undecodable ASDU never reaches the
  handler; the server publishes `ServerEvent.UndecodableAsdu` and sends the mirror described above.

Together these cover the modeled procedures and the bespoke ones built on standard TypeIDs: a
received ASDU of a supported type that the facade does not act on is still surfaced as a raw `Asdu`
(via `ClientEvent.AsduReceived` or `ServerHandler.onRawAsdu`), so it is never silently dropped. The
client event stream itself never drops events; a lagging subscriber pauses inbound data instead (see
[Buffers and threading](buffers-and-threading.md#client-event-delivery-and-inbound-flow-control)). A
TypeID the library does not model, or an `F_*` type, is surfaced as raw octets. The facades cannot
send such a type, so private TypeIDs and file transfer are not supported end to end.
