# How-To: Handle events

Subscribe to events, register a request handler, and follow the threading rules.

There are **two distinct surfaces** for reacting to what arrives on a connection. One is an
*event stream* you **observe** — `events()`, on both the client and the server — to learn what
happened: a point updated, a connection opened or closed, a command arrived. The other is the
server-only *request handler* (`ServerHandler`) you implement to **decide** how the outstation
answers an incoming request. This page covers both, then the threading contract that governs all of
it.

If you have not connected a client or hosted a server yet, start with
[Getting Started](../getting-started.md), then [Host a server](./host-a-server.md). This page assumes
you already have an `Iec60870Client` or `Iec60870Server` in hand.

## The two surfaces at a glance

| Surface | Role | Use it to |
|---|---|---|
| `events()` → `Flow.Publisher<ClientEvent>` / `Flow.Publisher<ServerEvent>` | client and server | *observe* what happened: point updates, lifecycle changes, received ASDUs, audit |
| `ServerHandler` (builder `handler(...)`) | server only | *decide* how to answer an inbound interrogation, read, command, clock-sync, or reset |

The rest of the page is "events" (the observe surface, both roles) then "handler" (the decide
surface, server only).

## Subscribe to client events

The client exposes its event stream as a [`java.util.concurrent.Flow.Publisher`](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/concurrent/Flow.html)
of [`ClientEvent`](../reference/glossary.md). Register a `Flow.Subscriber` with
`client.events().subscribe(...)`. The `Flow` contract requires you to request demand in
`onSubscribe`; for an unbounded stream of events, request `Long.MAX_VALUE`:

```java
import com.digitalpetri.iec60870.client.ClientEvent;
import com.digitalpetri.iec60870.point.PointValue;
import java.util.concurrent.Flow;

client.events().subscribe(new Flow.Subscriber<ClientEvent>() {
  @Override
  public void onSubscribe(Flow.Subscription subscription) {
    subscription.request(Long.MAX_VALUE);          // request unbounded demand
  }

  @Override
  public void onNext(ClientEvent event) {
    if (event instanceof ClientEvent.PointUpdated u) {
      PointValue<?> v = u.value();
      System.out.println(
          u.address() + " = " + v.value()
              + " [" + v.type() + " via " + u.asduType() + "] (" + u.cause() + ")");
    } else if (event instanceof ClientEvent.DataTransferStarted s) {
      System.out.println("STARTDT done");
    } else if (event instanceof ClientEvent.ConnectionClosed c) {
      System.out.println("closed: " + c.causeOptional());
    }
    // Other lifecycle / AsduReceived / warning events are ignored here.
  }

  @Override
  public void onError(Throwable t) {
    t.printStackTrace();
  }

  @Override
  public void onComplete() {}
});

// Subscribe BEFORE connect so no early event is missed.
client.connect();
```

> **Subscribe before you connect.** `events()` returns the same publisher every time, with no
> replay buffer. A late subscriber misses every event published before it subscribed — including the
> `ConnectionOpened` / `DataTransferStarted` lifecycle events that fire during `connect()`. Always
> subscribe first.

`ClientEvent` is a `sealed` interface, so an `instanceof` chain (shown above) covers every case the
compiler knows about. The Javadoc shows the equivalent Java 21 `switch` pattern form; this project
targets Java 17, so the body snippets here use `instanceof` chains, matching `ClientExample`.

## What each client event means (and when it fires)

| Event | Fires when | Typical use |
|---|---|---|
| `ConnectionOpened` | the TCP/TLS transport connection is established | log "link up" |
| `DataTransferStarted` | the [STARTDT](../reference/glossary.md) handshake completed; user data may now flow | enable polling/UI |
| `PointUpdated` | once per information object of a received [monitor ASDU](../reference/glossary.md) | update your value cache |
| `AsduReceived` | once for *every* received [ASDU](../reference/glossary.md) | inspect raw or unmodeled frames |
| `NegativeConfirmation` | the peer sent a negative confirmation not tied to a pending blocking call | log/audit a stray rejection |
| `ProtocolWarning` | the client hit a recoverable protocol anomaly (no disconnect) | diagnostics/logging |
| `DataTransferStopped` | the [STOPDT](../reference/glossary.md) handshake completed; user data paused | pause polling/UI |
| `ConnectionClosed` | the connection closed or was lost | reconnect or shut down (see below) |

> **`ConnectionOpened` vs `DataTransferStarted`** — `ConnectionOpened` means the transport is up;
> `DataTransferStarted` means STARTDT finished and user data may flow. With
> `startDataTransferOnConnect(true)` (the default) both fire around `connect()`, but they remain two
> distinct events.

> **`PointUpdated` vs `AsduReceived`** — `PointUpdated` fires once *per information object* of a
> received monitor ASDU (point level); `AsduReceived` fires once for *every* received ASDU (ASDU
> level), including that same monitor ASDU and non-monitor frames. Use `PointUpdated` for values; use
> `AsduReceived` to see raw or [unmodeled frames](./work-with-raw-asdus.md).

> **`NegativeConfirmation` vs a thrown exception** — `NegativeConfirmation` is published only for a
> negative confirmation that is *not* correlated to a pending blocking request. A negative
> confirmation that answers a blocking `commands().send(...)` surfaces as
> `CommandResult.positive() == false` instead, and an interrogation rejection throws. See the
> [error model](../reference/errors.md).

## Connection-state changes

The lifecycle of a client connection is a quartet of events:

```
ConnectionOpened → DataTransferStarted → … → DataTransferStopped → ConnectionClosed
```

`ConnectionClosed` carries `causeOptional()` (an `Optional<Throwable>`) that distinguishes an
orderly close from a fault: an empty optional is an orderly local close (typically after
`client.close()`); a present throwable is a fault. The client does **not** auto-reconnect — branch on
the cause and rebuild or reconnect yourself:

```java
} else if (event instanceof ClientEvent.ConnectionClosed closed) {
  if (closed.causeOptional().isPresent()) {
    // Connection dropped on a fault — schedule a reconnect.
    // Do the actual reconnect off this thread if it blocks (see Threading rules).
  } else {
    // Orderly local close — typically after client.close(); nothing to do.
  }
}
```

> **No built-in auto-reconnect.** The client facade exposes only `connect()` and `close()` — there
> is no retry policy. To reconnect, call `client.connect()` again, or rebuild the client with
> `TcpIec104Client.builder()…build()`. The STARTDT/STOPDT internals behind these events are described
> in [APCI and timers](../../architecture/apci-and-timers.md).

## Spontaneous and monitor data on the client

For most clients, `PointUpdated` is the event you care about: one fires for each information object a
received monitor ASDU carries, already decoded into a [`PointValue`](../reference/choosing-a-point-type.md).
A [spontaneous](../reference/glossary.md) update arrives with [cause of transmission](../reference/glossary.md)
`SPONTANEOUS`; you can read it from `cause()`.

```java
if (event instanceof ClientEvent.PointUpdated u) {
  PointAddress at = u.address();                  // com.digitalpetri.iec60870.address.PointAddress
  PointValue<?> v = u.value();                    // v.value(), v.type(), v.quality()
  // u.timestamp() is the object's time tag, if any (Optional<Instant>).
  cache.put(at, v);
}
```

> **`value().type()` vs `asduType()`** — `value().type()` is the logical
> [`PointType`](../reference/choosing-a-point-type.md) (for example `SINGLE_POINT`); `asduType()` is
> the exact wire [`AsduType`](../reference/glossary.md) that carried it (for example untimed
> `M_SP_NA_1` vs CP56-tagged `M_SP_TB_1`). Use `asduType()` when you need to distinguish the time-tag
> variant that the point value otherwise collapses.

This is the *live* stream. For a one-shot snapshot of a station's points, run an interrogation
instead — see [Connect & interrogate](./connect-and-interrogate.md), which returns an
`InterrogationResult` rather than streaming `PointUpdated`. The server side that publishes these
updates is [Host a server](./host-a-server.md).

## Observe server events

The server exposes its own event stream, `server.events()` →
`Flow.Publisher<ServerEvent>`. The subscription mechanics mirror the client exactly. Every
`ServerEvent` carries `remoteAddress()` so you can tell connections apart.

```java
import com.digitalpetri.iec60870.server.ServerEvent;
import java.util.concurrent.Flow;

server.events().subscribe(new Flow.Subscriber<ServerEvent>() {
  @Override
  public void onSubscribe(Flow.Subscription s) {
    s.request(Long.MAX_VALUE);
  }

  @Override
  public void onNext(ServerEvent event) {
    if (event instanceof ServerEvent.ConnectionAccepted a) {
      log.info("client {} connected", a.remoteAddress());
    } else if (event instanceof ServerEvent.CommandReceived c) {
      audit.record(c.remoteAddress(), c.asdu());   // observe only — does NOT answer the command
    }
    // Other events ignored here.
  }

  @Override
  public void onError(Throwable t) { /* ... */ }

  @Override
  public void onComplete() {}
});
```

| Event | Fires when |
|---|---|
| `ConnectionAccepted` | a controlling-station connection is accepted |
| `DataTransferStarted` | a connection's STARTDT handshake completed |
| `CommandReceived` | a control command arrived on a connection |
| `AsduReceived` | *every* ASDU arrived on a connection |
| `DataTransferStopped` | a connection's STOPDT handshake completed |
| `ConnectionClosed` | a connection closed or was lost (`causeOptional()` distinguishes fault from orderly) |

> **Server events are observe-only.** They are an audit/notification feed; they do **not** decide the
> response. The response to a command is decided by `ServerHandler.onCommand` (next section), not from
> the event subscriber.

> **`CommandReceived` vs `AsduReceived`** — every command ASDU *also* surfaces as `AsduReceived`
> (which fires for every received ASDU, "in addition to any more specific event"). Use
> `CommandReceived` for command-specific auditing.

## Handle server requests with `ServerHandler`

`ServerHandler` is the *decide* surface. Register it on the builder with `handler(...)`. Every method
has a `default` implementing standard outstation behavior, so you override only what you customize:

```java
import com.digitalpetri.iec60870.asdu.Cause;
import com.digitalpetri.iec60870.asdu.object.SingleCommand;
import com.digitalpetri.iec60870.point.PointValue;
import com.digitalpetri.iec60870.server.CommandDecision;
import com.digitalpetri.iec60870.server.CommandRequest;
import com.digitalpetri.iec60870.server.Iec60870Server;
import com.digitalpetri.iec60870.server.ServerContext;
import com.digitalpetri.iec60870.server.ServerHandler;
import com.digitalpetri.iec60870.transport.tcp.TcpIec104Server;

ServerHandler handler = new ServerHandler() {
  @Override
  public CommandDecision onCommand(ServerContext ctx, CommandRequest req) {
    if (req.target().equals(SWITCH) && req.commandObject() instanceof SingleCommand sc) {
      return CommandDecision.acceptAndUpdate(PointValue.single(sc.on()));
    }
    return CommandDecision.reject(Cause.UNKNOWN_INFORMATION_OBJECT_ADDRESS);
  }
};

Iec60870Server server =
    TcpIec104Server.builder().port(2404).addStation(station).handler(handler).build();
```

The hooks, their defaults, and what they return:

| Hook | Default behavior | Returns |
|---|---|---|
| `onInterrogation` | answer from the station's value image (`ctx.defaultInterrogation(request)`) | `InterrogationResponse` |
| `onRead` | answer the addressed point's current value (`ctx.defaultRead(request)`) | `ReadResponse` |
| `onCommand` | reject with `UNKNOWN_INFORMATION_OBJECT_ADDRESS` | `CommandDecision` |
| `onClockSync` | accept | `ClockSyncDecision` |
| `onReset` | accept | `ResetDecision` |
| `onRawAsdu` | return `false` (defer to standard dispatch) | `boolean` |

Each hook has a blocking form and an `*Async` form (for example
`onCommandAsync(ServerContext, CommandRequest)` returning `CompletionStage<CommandDecision>`). The
server invokes the async form; its default completes with the result of the blocking form. Override
the **blocking** form for synchronous logic — that is fine, because the server runs handlers off the
I/O thread (see [Threading rules](#threading-rules-read-this)). Override the **`*Async`** form when
the answer itself depends on a non-blocking or I/O-bound computation.

`ServerContext` is the per-callback context: it exposes `remoteAddress()`, `station()`, the
default-answer helpers `defaultInterrogation(...)` / `defaultRead(...)`, and a fire-and-forget
`send(Asdu)` escape hatch. **Do not retain it** — it is valid only for the duration of the callback.

> **`ServerEvent.CommandReceived` (observe) vs `ServerHandler.onCommand` (decide)** — the event tells
> you a command arrived on connection X; `onCommand` is where you accept it (`accept()` /
> `acceptAndUpdate(...)`) or reject it (`reject(Cause)`). Do not try to answer a command from the
> event subscriber.

This page shows only `onCommand`, the most common override. The full handler walkthrough — stations,
the point model, interrogation and read handling, and the
[select-before-operate](../reference/glossary.md) phase (`CommandMode.SELECT`) — lives in
[Host a server](./host-a-server.md). The `onRawAsdu` hook is
the server-side escape hatch for [unmodeled TypeIDs](./work-with-raw-asdus.md); see also the
[two-layer API](../../architecture/two-layer-api.md).

## Threading rules (read this)

Everything above runs under one contract, distilled from
[Buffers and threading](../../architecture/buffers-and-threading.md):

- **Callbacks run on the configured `callbackExecutor`** — `callbackExecutor(...)` on either builder,
  default the common `ForkJoinPool`. Event subscribers (`onNext`) and `ServerHandler` callbacks both
  run there, off any transport I/O thread.
- **Delivery is serial per connection.** A subscriber never observes two events concurrently, and a
  handler never observes two callbacks for one connection concurrently; events arrive in order.
- **Blocking is allowed on the callback executor** — both in event subscribers and in blocking
  handler forms — but **never block a transport I/O thread**. The library already hops application
  work onto the callback executor for you.
- **Use the `*Async` handler form** when the answer is itself non-blocking and I/O-bound (for example
  a database lookup that returns a `CompletionStage`), so you do not tie up the callback thread.
- **Keep `onNext` fast.** The event publisher is a `SubmissionPublisher`; a subscriber that falls far
  enough behind that its buffer overflows can be **dropped/lagged** rather than blocking the
  publisher. Do the minimum in `onNext` and offload slow work.

| Choice | Consequence |
|---|---|
| Default `ForkJoinPool` callback executor | parallel across connections; per-connection delivery still serial |
| Single-threaded `callbackExecutor` | strict submission order across *everything*, at the cost of cross-connection parallelism |
| Blocking inside `onNext` / a blocking handler | safe (off the I/O thread), but a slow subscriber risks being dropped — offload slow work |
| `*Async` handler form | the server does not wait on the callback thread for an I/O-bound answer |

For the full ownership and serialization model, read
[Buffers and threading](../../architecture/buffers-and-threading.md).

## Gotchas / checklist

- **Subscribe before connect/start.** `events()` has no replay buffer; late subscribers miss earlier
  events.
- **Request demand in `onSubscribe`** (`subscription.request(Long.MAX_VALUE)` for an unbounded
  stream), or you receive nothing.
- **Do not retain `ServerContext`.** It is valid only inside the callback it was passed to.
- **Never block a transport I/O thread.** Blocking on the callback executor is fine; the library puts
  your callbacks there.
- **Events are fire-and-forget observation.** There is no acknowledgment; you cannot "answer" an
  event.
- **The client does not auto-reconnect.** Rebuild or reconnect yourself from `ConnectionClosed`.
- **Command rejection is not an exception.** On the client it surfaces as
  `CommandResult.positive() == false`; on the server you return `CommandDecision.reject(...)`. See
  [Send commands](./send-commands.md) and the [error model](../reference/errors.md).

## Where this runs in the examples

- **`ClientExample`** —
  [`iec60870-examples/src/main/java/com/digitalpetri/iec60870/examples/ClientExample.java`](../../../iec60870-examples/src/main/java/com/digitalpetri/iec60870/examples/ClientExample.java).
  Its inner `PrintingSubscriber` has exactly the shape of the client-event subscriber above: it
  subscribes *before* `connect()`, requests `Long.MAX_VALUE` in `onSubscribe`, and prints
  `PointUpdated`, `AsduReceived`, and `ConnectionClosed` events using `instanceof` chains. Run it
  with
  `mise exec -- mvn -q -pl iec60870-examples exec:java -Dexec.mainClass=com.digitalpetri.iec60870.examples.ClientExample`.
- **`ServerExample`** —
  [`iec60870-examples/src/main/java/com/digitalpetri/iec60870/examples/ServerExample.java`](../../../iec60870-examples/src/main/java/com/digitalpetri/iec60870/examples/ServerExample.java).
  Its inner `ExampleHandler` overrides only `onCommand`: it accepts a `SingleCommand` on the
  commandable point with `CommandDecision.acceptAndUpdate(...)` and otherwise rejects, exactly like
  the `ServerHandler` snippet above. Run it with
  `mise exec -- mvn -q -pl iec60870-examples exec:java -Dexec.mainClass=com.digitalpetri.iec60870.examples.ServerExample`.

Both are documented in the [examples README](../../../iec60870-examples/README.md).

## See also

- [Getting Started](../getting-started.md) — the end-to-end happy path and the mental model.
- [Connect & interrogate](./connect-and-interrogate.md) — the snapshot path vs. the live
  `PointUpdated` stream.
- [Send commands](./send-commands.md) — the command lifecycle and why rejection is not an exception.
- [Host a server](./host-a-server.md) — the full `ServerHandler` and station model (the publish side).
- [Work with raw ASDUs](./work-with-raw-asdus.md) — `AsduReceived` / `onRawAsdu` for unmodeled types.
- [Reference: Error model](../reference/errors.md) — exceptions vs. result objects.
- [Reference: Glossary](../reference/glossary.md) — IEC 104 terms mapped to Java types.
- [Reference: Choosing a point type](../reference/choosing-a-point-type.md) — `PointType` and
  `PointValue`.
- [User Guide index](../README.md).
