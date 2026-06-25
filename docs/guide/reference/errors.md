# Error model

The library draws one deliberate line. A *protocol-level negative answer the peer deliberately
returned* — a rejected command, an unknown address — is a **result object** when it is the expected
outcome of a normal operation. A *failure that prevents a meaningful answer* — a timeout, a lost
connection, malformed wire data, a sequence-number violation — is a **typed exception**. The point
of the split is that ordinary control flow (a controlled station rejecting a command) stays off the
exception path, while genuine faults stay impossible to ignore. Every exception this library throws
is **unchecked**, so nothing forces you to wrap calls in `try`/`catch`; you choose what to catch. For
the design rationale behind this line, see the architecture's
[errors and extensibility](../../architecture/errors-and-extensibility.md) document.

## The two paths at a glance

This is the fast-lookup table; the rest of the page is detail.

| Situation | How it's reported | Where to look |
|---|---|---|
| A command is rejected | `CommandResult.positive() == false` (a value, not a throw) | [`CommandResult`](#commandresult) |
| An interrogation / read / clock-sync is rejected | `NegativeConfirmationException` (thrown) | [Interpreting a negative confirmation](#interpreting-a-negative-confirmation) |
| A request times out | `ProtocolTimeoutException` (thrown) | [When each is raised](#when-each-is-raised) |
| The connection is lost or closed | `ConnectionClosedException` (thrown) | [Fatal vs. recoverable](#fatal-vs-recoverable) |
| A duplicate request is already in flight | `RequestInProgressException` (thrown) | [When each is raised](#when-each-is-raised) |
| Bad wire data arrives | `AsduDecodeException` (thrown) | [When each is raised](#when-each-is-raised) |
| An unknown TypeID is decoded | `UnsupportedAsduTypeException` (thrown) | [When each is raised](#when-each-is-raised) |
| The APCI window is violated | `SequenceNumberException` (thrown, fatal) | [Fatal vs. recoverable](#fatal-vs-recoverable) |
| An *uncorrelated* negative confirmation arrives | `ClientEvent.NegativeConfirmation` event | [Uncorrelated negative confirmations](#uncorrelated-negative-confirmations-events) |

## Typed exceptions

### The hierarchy

All library exceptions are unchecked and extend `Iec60870Exception`, so an application can catch that
one type to handle any library-specific failure uniformly. Every exception below lives in the root
package `com.digitalpetri.iec60870`.

```text
RuntimeException
└── Iec60870Exception                   base — catch to handle any library failure
    ├── AsduDecodeException           malformed / truncated / out-of-range wire data on decode
    ├── ProtocolTimeoutException      an expected response did not arrive in time
    ├── ConnectionClosedException     operation on a closed/lost connection; pending requests fail on loss
    ├── NegativeConfirmationException peer answered a request with the P/N bit set (carries Cause + ASDU)
    ├── RequestInProgressException    a conflicting request to the same target is still in flight
    ├── UnsupportedAsduTypeException  type identification undefined, or defined with no typed object mapping
    └── SequenceNumberException       APCI N(S)/N(R) violated the k/w flow-control rules
```

### When each is raised

| Exception | Raised when | Carries |
|---|---|---|
| `AsduDecodeException` | An ASDU or one of its fields cannot be decoded: bad START octet, truncated header, undefined cause, out-of-range count. | message / cause only |
| `ProtocolTimeoutException` | An APCI timer or a request/response deadline elapses before the peer responds. | message / cause only |
| `ConnectionClosedException` | A new operation is attempted on a closed connection, or a pending request is failed because the transport reported a loss. | message / cause only |
| `NegativeConfirmationException` | A *blocking* request ([`interrogate`](#what-each-operation-throws-or-returns), `read`, `synchronizeClock`) receives a confirming ASDU with the [P/N bit](glossary.md) set. | `Cause cause()`, `Optional<Asdu> asdu()` |
| `RequestInProgressException` | A request is issued while a conflicting request to the same target is still in flight. | message only |
| `UnsupportedAsduTypeException` | A type identification is undefined, or is defined but has no typed object mapping in this library. | message / cause only |
| `SequenceNumberException` | An inbound APCI N(S)/N(R) violates the `k`/`w` flow-control rules. | message / cause only |

Only `NegativeConfirmationException` exposes typed accessors. The other six carry just the standard
`getMessage()` / `getCause()` of a `RuntimeException` — there is no `timer()` on
`ProtocolTimeoutException`, no error-code enum on `ConnectionClosedException`, and no `target()` on
`RequestInProgressException`. Branch on type, and read the message for diagnostics.

### Fatal vs. recoverable

Not every exception leaves the connection usable.

- **Fatal to the connection.** A `SequenceNumberException`, and a `t1` `ProtocolTimeoutException`
  raised by the APCI session, close the session: the session reports the cause through
  [`ClientEvent.ConnectionClosed`](../how-to/handle-events.md) /
  `ServerEvent.ConnectionClosed`, which carries that cause. After one of these you must reconnect.
- **Recoverable.** A `NegativeConfirmationException`, or a `ProtocolTimeoutException` on a single
  request, leaves the connection open — the peer simply did not answer that one request in time, or
  answered it negatively. You can retry on the same client.

Which timer expiry is fatal versus per-request is covered in
[Timers & window](timers-and-window.md) and the architecture's
[APCI and timers](../../architecture/apci-and-timers.md) document.

## Result objects

The non-exception path. These are values you branch on, not exceptions you catch.

### `CommandResult`

A command does **not** throw when the controlled station rejects it. A rejection is a normal,
expected outcome, so it is reported as a value: `positive() == false`.

```java
CommandResult result = client.commands().single(point, true);   // direct execute
if (result.positive()) {
  // the controlled station confirmed the command
} else {
  // the station returned a negative confirmation — inspect why
  handleRejection(result.cause());        // Cause of the confirming ASDU
  result.confirmation().ifPresent(asdu -> log(asdu));
}
```

`CommandResult` is a record `(PointAddress target, boolean positive, Cause cause, Optional<Asdu>
confirmation)`:

- `positive()` — `true` if the confirming ASDU's [P/N bit](glossary.md) is clear (accepted),
  `false` on a negative confirmation.
- `cause()` — the [cause of transmission (COT)](glossary.md), a `Cause`, of the confirming ASDU.
- `confirmation()` — an `Optional<Asdu>` carrying the confirming ASDU if one was received.

Only transport/session failures — a timeout, a disconnect — throw from a command. A protocol-level
rejection is the non-positive result above. This mirrors the canonical
[`ClientExample`](../../../iec60870-examples/README.md) line `client.commands().single(SWITCH, true)`.
For [select-before-operate](glossary.md), the result reflects the *execute*
confirmation, not the select; see [Send commands](../how-to/send-commands.md) for the full
procedure.

### `InterrogationResult`

`terminated()` reports whether the station sent an [activation termination (`ACT_TERM`)](glossary.md);
`false` means the collection ended another way — for example a timeout mid-stream — *without*
raising, so the snapshot you hold may be partial.

```java
InterrogationResult snapshot = client.interrogate(CommonAddress.of(1));   // throws on a negative ACT_CON
if (!snapshot.terminated()) {
  // collection ended without an ACT_TERM (e.g. a timeout mid-stream) — the snapshot may be partial
}
for (InterrogationResult.PointEntry entry : snapshot.pointValues()) {
  // entry.address() : PointAddress, entry.value() : PointValue<?>
}
```

Note the asymmetry: a negative [activation confirmation (`ACT_CON`)](glossary.md) to the
interrogation itself *does* raise `NegativeConfirmationException`. So an `InterrogationResult` you
hold is always one whose activation was confirmed — `terminated()` only tells you how the *data
collection* ended. See [Connect & interrogate](../how-to/connect-and-interrogate.md) for the full
recipe.

### Server-side decisions

The server is the mirror image. A [`ServerHandler`](../how-to/host-a-server.md) rejects a request by
*returning* a decision, not by throwing; the server turns that decision into a negative confirmation
on the wire.

```java
ServerHandler handler = new ServerHandler() {
  @Override
  public CommandDecision onCommand(ServerContext ctx, CommandRequest request) {
    if (!authorized(request.target())) {
      return CommandDecision.reject(Cause.UNKNOWN_INFORMATION_OBJECT_ADDRESS);
    }
    return CommandDecision.accept();
  }
};
```

The reject factories are `CommandDecision.reject(Cause)`, `InterrogationResponse.reject(Cause)`, and
`ReadResponse.reject(Cause)`. The default `onCommand` already returns
`reject(UNKNOWN_INFORMATION_OBJECT_ADDRESS)`, so a server that never overrides command handling
declines every command. See [Host a server](../how-to/host-a-server.md) for building the handler.

## What each operation throws or returns

The authoritative per-operation lookup. Each row is grounded in the operation's `@throws` contract.

| Operation | Success | Peer rejection | Timeout | Connection loss |
|---|---|---|---|---|
| `client.connect()` | (void) | — | `ProtocolTimeoutException` (STARTDT) | `ConnectionClosedException` |
| `client.startDataTransfer()` / `stopDataTransfer()` | (void) | — | `ProtocolTimeoutException` | `ConnectionClosedException` |
| `client.interrogate(...)` | `InterrogationResult` | `NegativeConfirmationException` | `ProtocolTimeoutException` | `ConnectionClosedException` |
| `client.read(point)` | `List<InformationObject>` | `NegativeConfirmationException` | `ProtocolTimeoutException` | `ConnectionClosedException` |
| `client.synchronizeClock(...)` | (void) | `NegativeConfirmationException` | `ProtocolTimeoutException` | `ConnectionClosedException` |
| `client.commands().send(...)` / helpers | `CommandResult` (`positive` may be `false`) | **non-positive `CommandResult`** (not thrown) | `ProtocolTimeoutException` | `ConnectionClosedException` |
| `client.send(asdu)` (raw) | (void) | — (responses arrive via `events()`) | — | `ConnectionClosedException` |
| `server.start()` | (void) | — | — | `Iec60870Exception` on bind failure |
| `server.publish(...)` | (void) | — | — | skips un-started connections (see note) |

Notes:

1. **Duplicate in-flight request.** Any of the request operations may raise
   `RequestInProgressException` if a conflicting request to the same target is still in flight. The
   client serializes requests whose responses are indistinguishable on the wire; wait for the first
   to complete (or address a different target) before retrying. It is not declared on any single
   method's `@throws` — it comes from the client's request correlation — so treat it as a
   cross-cutting condition rather than something specific to one call.
2. **`publish` argument errors.** `server.publish(...)` throws `IllegalArgumentException` if no
   station hosts the point or the value's runtime type does not match the point's type. That is a
   *programming error*, **not** an `Iec60870Exception` — it is outside the `Iec60870Exception` family, so
   "catch `Iec60870Exception` to handle any library failure" still holds. `publish` also skips
   connections that have not started data transfer rather than failing.

## Interpreting a negative confirmation

A negative confirmation is a confirming ASDU whose [positive/negative (P/N) bit](glossary.md) is set:
the peer understood the request and declined it. You reach the same information two ways depending on
which path reported it.

- From a [`CommandResult`](#commandresult): `result.cause()` is the
  [cause of transmission (COT)](glossary.md), a `Cause`, of the confirming ASDU.
- From a thrown `NegativeConfirmationException`: `e.cause()` returns the same `Cause`, and `e.asdu()`
  returns an `Optional<Asdu>` carrying the confirming ASDU when it was available.

```java
// From a command result:
CommandResult result = client.commands().single(point, true);
if (!result.positive()) {
  Cause why = result.cause();              // e.g. UNKNOWN_INFORMATION_OBJECT_ADDRESS
}

// From a thrown negative confirmation (interrogate / read / synchronizeClock):
try {
  client.synchronizeClock(CommonAddress.of(1), Instant.now());
} catch (NegativeConfirmationException e) {
  Cause why = e.cause();                     // Cause of the confirming ASDU
  e.asdu().ifPresent(asdu -> inspect(asdu)); // Optional<Asdu> confirming ASDU
}
```

A controlled station commonly returns one of these diagnostic `Cause` constants to say *why* it
declined. They can arrive either as the `cause()` of a `CommandResult` or as the `cause()` of a
`NegativeConfirmationException`.

| `Cause` constant | Value | What it means for the integrator |
|---|---|---|
| `UNKNOWN_TYPE_ID` | 44 | The station does not support that ASDU type identification. |
| `UNKNOWN_CAUSE` | 45 | The station did not accept the cause of transmission you sent. |
| `UNKNOWN_COMMON_ADDRESS` | 46 | Wrong [Common Address (CA)](glossary.md) — no such station (`CommonAddress`). |
| `UNKNOWN_INFORMATION_OBJECT_ADDRESS` | 47 | Wrong [IOA](glossary.md) — the station has no such object (`InformationObjectAddress`). |

`Cause` lives in `com.digitalpetri.iec60870.asdu`; the full set of COT constants is listed in the
[glossary](glossary.md).

## Failures on the async API

Every blocking method has an `*Async` variant returning a `CompletionStage`. The same conditions that
*throw* from the blocking call *complete the stage exceptionally* with the same exception type — no
new exception types appear on the async surface.

```java
client.interrogateAsync(CommonAddress.of(1))
    .whenComplete((snapshot, failure) -> {
      if (failure != null) {
        // failure is the same exception type the blocking call would throw,
        // wrapped in a CompletionException when observed via join()
      }
    });
```

The stage completes exceptionally *with* the library exception. The `CompletionException` wrapping you
may have seen is added by the JDK only at the blocking `join()` / `get()` boundary; the cause inside
it is the library exception. Callbacks such as `whenComplete` and `exceptionally` receive the
library exception directly, so prefer those for inspecting the failure type.

## Uncorrelated negative confirmations (events)

A negative confirmation that is *not* tied to a pending blocking request is delivered as a
`ClientEvent.NegativeConfirmation(asdu, cause)` event rather than thrown. This covers, for example, a
negative confirmation arriving after the request that prompted it already timed out. Subscribe to it
through the event publisher; see [Handle events](../how-to/handle-events.md) for the subscription
mechanics and the threading rules.

## See also

- [Errors and extensibility](../../architecture/errors-and-extensibility.md) — the as-built design
  rationale for the result-vs-exception split.
- [Two-layer API](../../architecture/two-layer-api.md) — the high-level facade versus the raw layer.
- [APCI and timers](../../architecture/apci-and-timers.md) — which timer expiry is fatal to the
  connection.
- [Send commands](../how-to/send-commands.md) — reading a `CommandResult`, direct-execute versus
  select-before-operate.
- [Connect & interrogate](../how-to/connect-and-interrogate.md) — where
  `NegativeConfirmationException` and `InterrogationResult.terminated()` come up.
- [Handle events](../how-to/handle-events.md) — uncorrelated `NegativeConfirmation` and the
  `ConnectionClosed` event that carries a fatal cause.
- [Host a server](../how-to/host-a-server.md) — the server-side `reject(...)` decisions.
- [Tune the APCI session](../how-to/tune-apci.md) and [Timers & window](timers-and-window.md) —
  which timeout maps to which timer.
- [Glossary](glossary.md) — COT, the P/N bit, `ACT_CON` / `ACT_TERM`, STARTDT, and more.
- [Getting Started](../getting-started.md) — the happy path these failures branch off of.
