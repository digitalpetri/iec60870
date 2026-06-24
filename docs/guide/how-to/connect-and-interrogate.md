# How-To: Connect & Interrogate

Connect a client to a controlled station, start data transfer, ask for a full snapshot of its data
with a general interrogation, pull frozen counter values, and read the results from either the
returned result or the event stream. See the runnable
[`ClientExample`](../../../iec60870-examples/src/main/java/com/digitalpetri/iec60870/examples/ClientExample.java)
for the same flow end to end.

## What you'll build

A connected [`Iec60870Client`](../../architecture/two-layer-api.md), a general interrogation snapshot
read two ways (from the returned result and from the event listener), and a counter interrogation
issued through the raw escape hatch. The client is the **controlling station** (master); the station
it talks to is the **controlled station** (server), addressed by its
[Common Address (CA)](../reference/glossary.md) — a `CommonAddress` you pass to `interrogate`.

## Before you start

- A running controlled station to talk to. Start the
  [Getting Started](../getting-started.md) hello-server or the
  [`ServerExample`](../../../iec60870-examples/src/main/java/com/digitalpetri/iec60870/examples/ServerExample.java)
  on `127.0.0.1:2404`.
- The Maven coordinate `com.digitalpetri.iec60870:iec60870-transport-tcp` (version `0.1.0-SNAPSHOT`),
  which transitively brings in `iec60870-core`.
- Java 17.

If you have not yet run the end-to-end happy path, read [Getting Started](../getting-started.md)
first; this page assumes you know how the client and server fit together.

## Step 1 — Build and connect the client

Build a client with [`TcpIec104Client.builder()`](../../architecture/modules-and-dependencies.md) and
open the connection inside a try-with-resources block. `build()` returns the core `Iec60870Client`
interface (it has no Netty types on its surface), and the client is `AutoCloseable`, so it closes the
connection and releases transport resources when the block exits.

Imports omitted.

```java
try (Iec60870Client client =
    TcpIec104Client.builder()
        .host("127.0.0.1")
        .port(2404)
        .startDataTransferOnConnect(true) // default; folds STARTDT into connect()
        .build()) {

  client.connect(); // connects and performs the STARTDT handshake
  // ... interrogate, read, command ...
}
```

This page uses the blocking surface for readability. Every operation also has an `*Async` mirror
(`connectAsync`, `interrogateAsync`, `sendAsync`, …) that returns a `CompletionStage`; blocking calls
throw on failure, while the async variants complete exceptionally. See the
[error model reference](../reference/errors.md) for which call surfaces which failure.

## Step 2 — Start data transfer (STARTDT)

No monitor data flows from the controlled station until **STARTDT** completes — IEC 104 gates
user-data transfer behind a STARTDT handshake. The builder flag `startDataTransferOnConnect(true)`
(the default) folds that handshake into `connect()`, so most callers never call `startDataTransfer()`
explicitly. The snippet above is the common case.

If you set the flag to `false` — for example to inspect the connection before allowing data — start
data transfer yourself after connecting:

```java
// With startDataTransferOnConnect(false): connect, then start data transfer explicitly.
client.connect();
client.startDataTransfer(); // STARTDT act -> STARTDT con
```

STARTDT and STOPDT are summarized in the [timers & window reference](../reference/timers-and-window.md);
the handshake and timer internals (`t1`, the `k`/`w` window) live in the
[APCI and timers architecture doc](../../architecture/apci-and-timers.md).

## Step 3 — Run a general interrogation

A **general interrogation** asks the controlled station for a complete snapshot of its reported data.
The single call below sends an interrogation command (`C_IC_NA_1`) with
[Qualifier of Interrogation (QOI)](../reference/glossary.md) `STATION` (20) and blocks until the
station has streamed the snapshot:

```java
CommonAddress station = CommonAddress.of(1);
InterrogationResult snapshot = client.interrogate(station);
```

### What the single call does

Under the hood `interrogate` performs the whole activation cycle for you; you never handle the
individual confirmations for a general interrogation. At the protocol level the cycle is:

1. The client sends the interrogation command `C_IC_NA_1` with
   [Cause of Transmission (COT)](../reference/glossary.md) **ACTIVATION** (6) and QOI = STATION (20).
2. The station replies **ACT_CON** (activation confirmation, COT 7). A *negative* ACT_CON means the
   station rejected the request, and `interrogate(...)` throws `NegativeConfirmationException`.
3. The station streams its reported points, each ASDU carrying COT **INTERROGATED_BY_STATION** (20).
4. The station sends **ACT_TERM** (activation termination, COT 10) to mark the snapshot complete.
   `InterrogationResult.terminated()` is `true` when this arrives.

### Failure modes

- A negative ACT_CON (the station rejected the interrogation) throws `NegativeConfirmationException`.
- An interrogation that does not complete in time throws `ProtocolTimeoutException`.
- A closed or lost link throws `ConnectionClosedException`.

All three live in the root package `com.digitalpetri.iec60870`; see the
[error model reference](../reference/errors.md).

## Step 4 — Read the interrogation result

`interrogate` returns an `InterrogationResult` record with the snapshot:

- `station()` — the `CommonAddress` that was interrogated.
- `objects()` — the raw `InformationObject`s in receive order.
- `terminated()` — `true` if ACT_TERM arrived; `false` means collection ended early (for example a
  timeout) and the snapshot may be partial.
- `pointValues()` — projects the monitor objects onto domain values, returning a
  `List<InterrogationResult.PointEntry>` of `(PointAddress address, PointValue<?> value)`. Objects
  that are not monitor types are skipped.

```java
System.out.println("reported " + snapshot.pointValues().size()
    + " points; terminated=" + snapshot.terminated());

for (InterrogationResult.PointEntry entry : snapshot.pointValues()) {
  PointAddress address = entry.address();
  PointValue<?> value = entry.value();
  System.out.println(address + " [" + value.type() + "] = " + value.value()
      + " quality=" + value.quality());
}
```

When `terminated()` is `false`, the record still returns whatever objects were collected before the
collection ended — treat the snapshot as potentially incomplete.

## Interrogate a single group

To interrogate one group rather than the whole station, use the overload that takes a
`QualifierOfInterrogation`. The constants `GROUP_1` … `GROUP_16` select interrogation groups 1..16,
and `QualifierOfInterrogation.of(int)` accepts a raw value:

```java
InterrogationResult group1 =
    client.interrogate(station, QualifierOfInterrogation.GROUP_1);
```

A group interrogation returns data only for points the server has assigned to that group with
`Station.Builder.group(group, point)` (1..16). If no points are assigned to the group, the snapshot
is empty. See [host a server](./host-a-server.md) for group assignment.

## Step 5 — Counter (integrated-totals) interrogation

The high-level client has **no `counterInterrogate(...)` method**. Counter interrogation
(`C_CI_NA_1`) is issued through the raw `send(Asdu)` escape hatch, and the frozen counter values
arrive asynchronously on `events()` as integrated-totals (`M_IT`) updates — `send(...)` is
fire-and-forget, so there is no `InterrogationResult` analog for counters. Read the results off the
event stream (next section), not off a return value.

Build the `C_CI_NA_1` ASDU by hand and send it. The qualifier of counter interrogation (QCC) selects
which counter group to read and what freeze action to apply: the request field (RQT) is `1..4` for a
specific counter group or `5` for a general counter request, and the `FreezeMode` chooses the freeze
action (`READ`, `FREEZE_NO_RESET`, `FREEZE_WITH_RESET`, or `RESET`). The
[Information Object Address (IOA)](../reference/glossary.md) is `0` for `C_CI_NA_1`.

Imports omitted.

```java
// The high-level client has no counterInterrogate(...) method; build C_CI_NA_1 by hand
// and send it through the raw escape hatch. Frozen counters arrive on events() as M_IT.
int request = 5; // RQT: 1..4 select counter group 1..4; 5 is a general counter request
Asdu counterInterrogation =
    new Asdu(
        AsduType.C_CI_NA_1,
        false,                       // sequence (SQ)
        Cause.ACTIVATION,            // COT 6
        false,                       // negative
        false,                       // test
        OriginatorAddress.none(),
        station,                     // CommonAddress.of(1)
        List.of(
            new CounterInterrogationCommand(
                InformationObjectAddress.of(0),
                new QualifierOfCounterInterrogation(request, FreezeMode.READ))));

client.send(counterInterrogation);
// The station replies ACT_CON, streams M_IT (integrated-totals) objects, then ACT_TERM —
// all delivered asynchronously through events(); see "Receive results via the event listener".
```

For the general raw `send(Asdu)` pattern (building, encoding, and decoding ASDUs), see
[work with raw ASDUs](./work-with-raw-asdus.md). For the integrated-totals point model and the
`BinaryCounterReading` value type, see
[choosing a point type](../reference/choosing-a-point-type.md).

## Receive results via the event listener

The event listener is the alternative to the returned result, and it is how *all* asynchronous data
arrives: spontaneous updates, interrogated data, and the frozen counter readings from Step 5.
Subscribe a `Flow.Subscriber<ClientEvent>` to `client.events()` **before** connecting so no early
events are missed.

Imports omitted.

```java
client.events().subscribe(new Flow.Subscriber<ClientEvent>() {
  @Override public void onSubscribe(Flow.Subscription s) { s.request(Long.MAX_VALUE); }

  @Override public void onNext(ClientEvent event) {
    if (event instanceof ClientEvent.PointUpdated u) {
      // Fires once per information object, including interrogated data and frozen counters.
      System.out.println(u.address() + " [" + u.value().type() + " via " + u.asduType()
          + "] = " + u.value().value() + " (" + u.cause() + ")");
    }
  }

  @Override public void onError(Throwable t) { /* stream ended exceptionally */ }
  @Override public void onComplete() { /* stream completed */ }
});
```

A `ClientEvent.PointUpdated` fires once per information object of a received monitor ASDU and carries
the decoded `PointValue<?>`, the wire `asduType()`, and the `cause()` of the carrying ASDU.
Interrogated data carries cause [INTERROGATED_BY_STATION (COT 20)](../reference/glossary.md); counter
readings (`M_IT`) and ordinary interrogated data both surface here. A `ClientEvent.AsduReceived`
fires for every received ASDU.

Events are delivered serially on the callback executor — a subscriber never observes two events
concurrently. The full listener surface, the rest of the event taxonomy, and the threading rules live
in [handle events](./handle-events.md); subscribe before `connect()` so early events are not missed.

## Putting it together / Run the example

[`ClientExample`](../../../iec60870-examples/src/main/java/com/digitalpetri/iec60870/examples/ClientExample.java)
runs this whole flow: it subscribes to events, connects (which starts data transfer because
`startDataTransferOnConnect` defaults to `true`), interrogates CA 1 and prints its `pointValues()`,
sends a single command, synchronizes the clock, then lingers so spontaneous updates from the server
print through its `PrintingSubscriber` before the client closes.

Start the server in one shell and the client in another:

```bash
# shell 1
mise exec -- mvn -q -pl iec60870-examples exec:java \
    -Dexec.mainClass=com.digitalpetri.iec60870.examples.ServerExample

# shell 2
mise exec -- mvn -q -pl iec60870-examples exec:java \
    -Dexec.mainClass=com.digitalpetri.iec60870.examples.ClientExample
```

See [`iec60870-examples/README.md`](../../../iec60870-examples/README.md) for build prerequisites and the
`java -cp` alternative.

## See also

- [Getting Started](../getting-started.md) — the end-to-end happy path.
- [User Guide index](../README.md) — the guide's table of contents.
- [How-To: Send commands](./send-commands.md) — issue control commands after interrogation.
- [How-To: Host a server](./host-a-server.md) — group and counter-group assignment on the controlled
  station.
- [How-To: Handle events](./handle-events.md) — the full listener surface and threading rules.
- [How-To: Work with raw ASDUs](./work-with-raw-asdus.md) — the `send(Asdu)` pattern behind counter
  interrogation.
- [Reference: Glossary](../reference/glossary.md) — CA, QOI, COT, STARTDT, ACT_CON/ACT_TERM, IOA.
- [Reference: Timers & window](../reference/timers-and-window.md) — STARTDT/STOPDT and `t1`.
- [Reference: Choosing a point type](../reference/choosing-a-point-type.md) — integrated totals and
  `BinaryCounterReading`.
- [Reference: Error model](../reference/errors.md) — `NegativeConfirmationException`,
  `ProtocolTimeoutException`, `ConnectionClosedException`.
- Architecture: [Two-layer API](../../architecture/two-layer-api.md) and
  [APCI and timers](../../architecture/apci-and-timers.md).
