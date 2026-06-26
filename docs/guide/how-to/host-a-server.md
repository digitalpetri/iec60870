# Host a server

A server is a [controlled station](../reference/glossary.md): it hosts one or more
[`Station`](../reference/glossary.md)s, answers requests from a per-station *value image*, and
spontaneously publishes monitored values to connected masters. You build one with the
`TcpIec104Server` builder, which returns a `com.digitalpetri.iec60870.server.Iec60870Server`. Every
snippet on this page maps to the runnable [`ServerExample`](../../../iec60870-examples/README.md),
the canonical implementation to copy from. This page is the other end of the wire from a
[client](./connect-and-interrogate.md): the client (controlling station / master) drives the
connection; the server (controlled station / slave) hosts the data and accepts commands.

## What you build

| Concept | Type |
|---|---|
| Station identity | a [common address](../reference/glossary.md) → `CommonAddress`, carried by a `Station` |
| One signal (a point) | `PointDefinition<T>`: address + `PointType` + initial `PointValue` + a `PointCapability` set |
| The live value | the station's *value image* — one `PointValue<T>` per [IOA](../reference/glossary.md), updated by `publish(...)` or accepted commands |
| Incoming control | `ServerHandler.onCommand(...)` returning a `CommandDecision` |
| Reporting | interrogation (request/response) and spontaneous/periodic `publish(...)` |

For the signal → `PointType` mapping (and the value/quality type each carries), see
[choosing a point type](../reference/choosing-a-point-type.md) — this page does not re-derive it.

## Build and start the server

Build the server with `TcpIec104Server.builder()`, add at least one station, optionally set a
handler, then `start()` it. The returned `Iec60870Server` is `AutoCloseable`, so use
try-with-resources or call `close()` when you are done.

```java
import com.digitalpetri.iec60870.server.Iec60870Server;
import com.digitalpetri.iec60870.tcp.TcpIec104Server;

Iec60870Server server =
    TcpIec104Server.builder()
        .bindAddress("0.0.0.0")   // default; listen on all interfaces
        .port(2404)               // default IEC 104 port
        .addStation(station)      // built below
        .handler(handler)         // optional; see "Handle incoming commands"
        .build();                 // returns com.digitalpetri.iec60870.server.Iec60870Server

server.start();                   // bind + accept; throws Iec60870Exception on bind failure
// ... run ...
server.close();                   // stop: close connections, unbind; never throws checked
```

`start()` throws `com.digitalpetri.iec60870.Iec60870Exception` if the transport fails to bind (for
example, the port is already in use); see the [error model](../reference/errors.md). `close()` is
equivalent to `stop()` but never throws a checked exception, so it composes with try-with-resources.

The library is a `0.1.0-SNAPSHOT` pre-release and is not yet published; see
[Getting Started](../getting-started.md) or the [README](../../../README.md) for how to obtain it
(build and install it locally) and for the coordinates and pre-release caveat.

### Builder options

| Knob | Meaning | Default | When to change |
|---|---|---|---|
| `bindAddress(String)` | local interface to bind | `"0.0.0.0"` (all) | bind to one NIC or loopback only |
| `port(int)` | local TCP port | `2404` | run beside another stack, or use a test port |
| `addStation(Station)` | add a hosted station (distinct CA each) | none | every station you serve |
| `handler(ServerHandler)` | request hook | no-op default | accept commands, customize interrogation/read |
| `maxConnections(int)` | concurrent connection cap | `16` | allow many or few masters |
| `tls(TlsOptions)` | secure the link; `null` = plaintext | `null` | secure deployments — see [secure with TLS](./secure-with-tls.md) |
| `apci(ApciSettings)` | k/w window + t0–t3 timers | `ApciSettings.defaults()` | align with the peer — see [tune the APCI session](./tune-apci.md) |
| `profile(ProtocolProfile)` | CA / IOA / COT field widths | `ProtocolProfile.iec104Default()` | match the peer's addressing sizes — see [tune the APCI session](./tune-apci.md) |
| `callbackExecutor(Executor)` | event/handler executor | `ForkJoinPool.commonPool()` | use an order-preserving or dedicated pool |

> The `TcpIec104Server.Builder` has **no** `config(...)` method. Configure the server with the
> discrete builder methods above.

## Define stations and points

A `Station` carries one [common address](../reference/glossary.md) and owns a set of points. Build
it with `Station.builder(CommonAddress)`, add one `PointDefinition.of(...)` per signal, assign
points to [interrogation groups](../reference/glossary.md) with `.group(int, PointAddress)`, then
`.build()`.

A point's address is a [`PointAddress`](../reference/glossary.md) — a `CommonAddress` plus an
[information object address](../reference/glossary.md) (IOA) — built with
`PointAddress.of(commonAddress, objectAddress)`. Each point's `PointAddress.commonAddress()` **must**
equal the station's common address, and IOAs within a station must be unique.

```java
import com.digitalpetri.iec60870.address.CommonAddress;
import com.digitalpetri.iec60870.address.PointAddress;
import com.digitalpetri.iec60870.point.PointCapability;
import com.digitalpetri.iec60870.point.PointType;
import com.digitalpetri.iec60870.point.PointValue;
import com.digitalpetri.iec60870.server.PointDefinition;
import com.digitalpetri.iec60870.server.Station;

CommonAddress ca = CommonAddress.of(1);

PointAddress breakerStatus = PointAddress.of(1, 100);   // single-point status (monitor direction)
PointAddress flow          = PointAddress.of(1, 140);   // measured value (short float)
PointAddress breakerCmd    = PointAddress.of(1, 300);   // commandable single point

Station station =
    Station.builder(ca)
        .point(PointDefinition.of(
            breakerStatus, PointType.SINGLE_POINT,
            PointValue.single(false),
            PointCapability.REPORTED))
        .point(PointDefinition.of(
            flow, PointType.SHORT_FLOAT,
            PointValue.shortFloat(0.0f),
            PointCapability.REPORTED))
        .point(PointDefinition.of(
            breakerCmd, PointType.SINGLE_POINT,
            PointValue.single(false),
            PointCapability.REPORTED, PointCapability.COMMANDABLE))
        .group(1, breakerStatus)   // interrogation group 1 (1..16)
        .group(1, flow)
        .build();
```

`PointDefinition.of(...)` validates that the `PointValue`'s runtime type matches the `PointType`
(here `SINGLE_POINT` → `Boolean` and `SHORT_FLOAT` → `Float`); a mismatch throws
`IllegalArgumentException`. `.group(...)` requires that the point was already added with `.point(...)`
and that the group number is in `1..16`. See [choosing a point type](../reference/choosing-a-point-type.md)
for which `PointType` matches your signal and the value/quality type it carries, and the
[glossary](../reference/glossary.md) for common address, IOA, and interrogation group.

This page's station hosts a deliberately small set of points. For a station that hosts one point of
*every* monitor type, copy [`ServerExample`](../../../iec60870-examples/README.md) (see
[From the example](#from-the-example)).

### Capabilities

A point's `PointCapability` set declares which operations are meaningful against it.

| Capability | Effect | Set it when |
|---|---|---|
| `REPORTED` | included in interrogation responses and pushed by `publish(...)` in the [monitor direction](../reference/glossary.md) | the value should reach the master spontaneously or on interrogation |
| `READABLE` | answerable by a read request (`C_RD_NA_1`); the default dispatch rejects a read to a non-`READABLE` point | the master may poll this point on demand |
| `COMMANDABLE` | marks the point as accepting [control-direction](../reference/glossary.md) commands; your `ServerHandler.onCommand` decides each command | the master may operate this point |

`REPORTED` and `READABLE` are honored by the default dispatch. `COMMANDABLE` is a declarative marker:
the default command dispatch does not gate on it — your handler is responsible for accepting or
rejecting each command (see [Handle incoming commands](#handle-incoming-commands)).

### The catalog (optional, descriptive only)

The `com.digitalpetri.iec60870.catalog` package (`PointCatalog`, `CatalogEntry`) holds *descriptive
metadata* — browse names, display names, engineering units — **not** the live value image. It is
consumed primarily on the **client** side (attached via `ClientConfig.Builder.pointCatalog(...)`) to
label received values. A server author defines live behavior with `Station` and `PointDefinition`;
the catalog is optional naming and structure you usually do not need here. See the
[glossary](../reference/glossary.md) for the term.

## Update a point's value (the value image)

Each station holds a thread-safe *value image*: one current `PointValue` per IOA, seeded from each
point's `PointDefinition.initialValue`. Update it at runtime three ways:

- `server.publish(point, value, cause)` — updates the image **and** pushes a monitor ASDU to every
  started connection.
- `station.updateValue(ioa, value)` — updates the image only, with no push (useful to seed values
  before any master connects).
- a `CommandDecision.acceptAndUpdate(value)` returned from your handler — writes the image when an
  accepted command executes (see [Handle incoming commands](#handle-incoming-commands)).

Construct a `PointValue<T>` with its static factories, and refine it with `withQuality(...)` /
`withTimestamp(...)`.

```java
import com.digitalpetri.iec60870.asdu.Cause;
import com.digitalpetri.iec60870.point.Quality;

// Update the image and report to every started connection:
server.publish(breakerStatus, PointValue.single(true), Cause.SPONTANEOUS);

// Report a measured value with bad quality:
server.publish(
    flow,
    PointValue.shortFloat(42.0f).withQuality(Quality.invalidQuality()),
    Cause.SPONTANEOUS);

// Update the image only, without pushing (e.g. before the first interrogation):
server.stations().station(ca).ifPresent(s ->
    s.updateValue(breakerStatus.objectAddress(), PointValue.single(true)));
```

`publish(...)` throws `IllegalArgumentException` if no hosted station owns the point or the value's
runtime type does not match the point's `PointType`, and `NullPointerException` on null arguments;
see the [error model](../reference/errors.md). The [quality descriptor](../reference/glossary.md) is
carried with the value: `Quality.good()` (all flags clear) is the default from the factories, and
`Quality.invalidQuality()` marks the value invalid. See
[choosing a point type](../reference/choosing-a-point-type.md) for the value and quality types each
`PointType` carries.

## Handle incoming commands

Override `ServerHandler.onCommand(ServerContext, CommandRequest)`. Pattern-match
`request.commandObject()` on the concrete control object (for example `SingleCommand`), and return a
`CommandDecision`: `accept()` (confirm without changing the image), `acceptAndUpdate(value)`
(confirm and write the image), or `reject(cause)` (negative confirmation). `ServerHandler` is an
interface whose methods all have defaults, so implement only what you customize.

```java
import com.digitalpetri.iec60870.asdu.Cause;
import com.digitalpetri.iec60870.asdu.object.SingleCommand;
import com.digitalpetri.iec60870.point.PointValue;
import com.digitalpetri.iec60870.server.CommandDecision;
import com.digitalpetri.iec60870.server.CommandRequest;
import com.digitalpetri.iec60870.server.ServerContext;
import com.digitalpetri.iec60870.server.ServerHandler;

ServerHandler handler = new ServerHandler() {
  @Override
  public CommandDecision onCommand(ServerContext context, CommandRequest request) {
    if (request.target().equals(breakerCmd)
        && request.commandObject() instanceof SingleCommand command) {
      // Accept and write the image, so the new value is returned to the master:
      return CommandDecision.acceptAndUpdate(PointValue.single(command.on()));
    }
    return CommandDecision.reject(Cause.UNKNOWN_INFORMATION_OBJECT_ADDRESS);
  }
};
```

A few things to know:

- **The default `onCommand` rejects** every command with `UNKNOWN_INFORMATION_OBJECT_ADDRESS`, so a
  server hosting commandable points **must** override it.
- **`acceptAndUpdate` writes the station image** on an executing command, and the server emits a
  monitor ASDU carrying the new value as [return information](../reference/glossary.md), so the
  master sees the value it just set.
- **The server runs the confirmation handshake for you.** For
  [select-before-operate](../reference/glossary.md), it performs select → confirm → execute →
  confirm; your handler just returns a decision per phase and may branch on `request.isSelect()` (or
  `request.mode()`, the server-side `CommandMode` of `SELECT` / `EXECUTE`). An image update and
  return information are applied only on the *executing* phase.
- **Callbacks are serialized per connection** and run on the callback executor, off any transport
  I/O thread, so blocking inside a handler is permitted; see
  [buffers and threading](../../architecture/buffers-and-threading.md).

This is the mirror of the client-side [send commands](./send-commands.md) page.

## Report data: interrogation, spontaneous, and periodic

### Interrogation (request/response)

The default `ServerHandler.onInterrogation` answers a [general or group interrogation](../reference/glossary.md)
from the station image, reporting every `REPORTED` point selected by the qualifier; the default
`onRead` answers a read request from the image for `READABLE` points. Most servers need no code
here. Override only for custom selection — `ServerContext.defaultInterrogation(...)` and
`defaultRead(...)` give you the standard behavior to delegate to. This is the server side of
[connect and interrogate](./connect-and-interrogate.md).

### Spontaneous transmission

Call `publish(point, value, Cause.SPONTANEOUS)` whenever a value changes; the server pushes the
monitor ASDU to every started connection. This is the event-driven path most monitored data uses.

### Periodic / cyclic transmission

The library does **not** run a cyclic scanner for you — a server author owns the timer. Drive
`publish(...)` from your own `ScheduledExecutorService`, using `Cause.PERIODIC` for cyclic
measured-value reporting:

```java
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

ScheduledExecutorService scanner =
    Executors.newSingleThreadScheduledExecutor();

scanner.scheduleAtFixedRate(
    () -> server.publish(flow, PointValue.shortFloat(readFlowSensor()), Cause.PERIODIC),
    0, 5, TimeUnit.SECONDS);   // readFlowSensor() is your placeholder for the live reading

// Remember to shut the scanner down alongside the server.
```

[`ServerExample`](../../../iec60870-examples/README.md) uses exactly this pattern (a single-thread
scheduled executor on a two-second cycle).

### Which cause to use

| Cause | Constant | Use for |
|---|---|---|
| Spontaneous | `Cause.SPONTANEOUS` (3) | report a value the moment it changes (event-driven) |
| Periodic / cyclic | `Cause.PERIODIC` (1) | report measured values on a fixed cyclic timer |
| Background scan | `Cause.BACKGROUND_SCAN` (2) | slow background refresh of the full image |

See the [glossary](../reference/glossary.md) for [cause of transmission](../reference/glossary.md),
spontaneous, and periodic transmission.

## Host multiple points and stations

Add as many `PointDefinition`s as you need to one `Station`, and add as many `Station`s as you need
to the server with repeated `.addStation(...)` — **each station must use a distinct common address.**

```java
Iec60870Server server =
    TcpIec104Server.builder()
        .addStation(stationA)   // CommonAddress.of(1)
        .addStation(stationB)   // CommonAddress.of(2) — distinct CA
        .build();

server.stations().station(CommonAddress.of(2))
    .ifPresent(s -> s.updateValue(ioa, value));
```

`publish(...)` and `updateValue(...)` route by the point's common address. Inspect the hosted
stations at runtime through `server.stations()`, a `StationRegistry` with `stations()`,
`station(CommonAddress)`, and `contains(CommonAddress)`.

Integrated-totals (counter) points are hosted the same way — define them with
`PointType.INTEGRATED_TOTALS` and assign counter groups with `Station.Builder.counterGroup(...)`;
they are delivered spontaneously and by counter interrogation. The counter-interrogation side is
covered in [connect and interrogate](./connect-and-interrogate.md).

## Observe server events

Subscribe a `java.util.concurrent.Flow.Subscriber<ServerEvent>` to `server.events()` to watch
connection lifecycle and request traffic. `ServerEvent` is a sealed type whose variants include
`ConnectionAccepted`, `DataTransferStarted` / `DataTransferStopped`, `CommandReceived`,
`AsduReceived`, and `ConnectionClosed`.

```java
import com.digitalpetri.iec60870.server.ServerEvent;
// server.events() is a java.util.concurrent.Flow.Publisher<ServerEvent>.
// Subscribe a Flow.Subscriber before start() to catch early events; events arrive serially.
```

Subscribe before `start()` so you do not miss early events. The full treatment — subscription
mechanics, the threading rules, and what fires when — lives in [handle events](./handle-events.md).

## From the example

[`ServerExample`](../../../iec60870-examples/README.md)
(`iec60870-examples/src/main/java/com/digitalpetri/iec60870/examples/ServerExample.java`) is the
copy-from-able implementation of everything above. It hosts, on common address `1`:

- one `REPORTED` point of every monitor `PointType` at IOAs `100`–`160` (single-point,
  double-point, step position, 32-bit bit string, normalized / scaled / short-float measured value),
- a `READABLE` integrated-totals counter at IOA `170`,
- and a `COMMANDABLE` single point at IOA `300`.

The seven monitor points belong to interrogation group `1` and the commandable point to group `2`.
Its handler accepts the command on IOA `300` and writes the image with
`CommandDecision.acceptAndUpdate`, so the master receives the new value as return information. A
`ScheduledExecutorService` republishes a fresh value for every point every two seconds with
`Cause.SPONTANEOUS`. Run it from the [examples README](../../../iec60870-examples/README.md);
`ExampleInteropTest` drives a real client against it.

## See also

- [Getting Started](../getting-started.md) — the end-to-end happy path and dependency coordinates.
- [Connect and interrogate](./connect-and-interrogate.md) — the client (master) mirror.
- [Send commands](./send-commands.md) — the client side of command handling.
- [Handle events](./handle-events.md) — the listener surface and threading rules.
- [Secure with TLS](./secure-with-tls.md) — secure the link with `TlsOptions`.
- [Tune the APCI session](./tune-apci.md) — k/w window, t0–t3 timers, and field widths.
- [Work with raw ASDUs](./work-with-raw-asdus.md) — `ServerContext.send(...)` and unmodeled TypeIDs.
- [Choosing a point type](../reference/choosing-a-point-type.md) — map a signal to a `PointType`.
- [Coverage matrix](../reference/coverage-matrix.md) — which TypeIDs the server can report.
- [Error model](../reference/errors.md) — what `start()` and `publish(...)` throw.
- [Glossary](../reference/glossary.md) — IEC 104 vocabulary mapped to Java types.
- [User Guide index](../README.md).
