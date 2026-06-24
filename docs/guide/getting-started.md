# Getting Started

Install the library, learn the two-layer mental model, then run a controlled station and a
controlling station end to end.

*A [controlling station](reference/glossary.md) (master) is an `Iec104Client`; a
[controlled station](reference/glossary.md) (outstation, or RTU) is an `Iec104Server`. This guide
uses "client" for the master and "server" for the outstation throughout.*

You are in the [user guide](README.md). This is the tutorial: read it first, then branch to the
[how-to guides](README.md#how-to-guides) for the concrete task you have.

## Install

Declare both modules: `iec104-core` (the Netty-free public API — the protocol model and the
`Iec104Client` / `Iec104Server` APIs) and `iec104-transport-tcp` (the Netty-backed TCP/TLS transport
and the `TcpIec104Client` / `TcpIec104Server` builders). The transport module pulls in `iec104-core`
transitively, but your code uses types from both, so declare both directly.

Maven:

```xml
<dependency>
  <groupId>com.digitalpetri.iec104</groupId>
  <artifactId>iec104-core</artifactId>
  <version>0.1.0-SNAPSHOT</version>
</dependency>
<dependency>
  <groupId>com.digitalpetri.iec104</groupId>
  <artifactId>iec104-transport-tcp</artifactId>
  <version>0.1.0-SNAPSHOT</version>
</dependency>
```

Gradle (Kotlin DSL):

```kotlin
implementation("com.digitalpetri.iec104:iec104-core:0.1.0-SNAPSHOT")
implementation("com.digitalpetri.iec104:iec104-transport-tcp:0.1.0-SNAPSHOT")
```

The library targets **Java 17**.

> **SNAPSHOT.** `0.1.0-SNAPSHOT` is a pre-release; the API may still change. It is not yet published
> to Maven Central, so build and install it into your local `~/.m2` first: clone this repository and
> run `mise exec -- mvn install` (see the project [README](../../README.md)), then depend on the
> coordinate above.

> **mise is for building this repository, not for consuming the library.** Contributors building the
> IEC 104 repository itself use [`mise`](https://mise.jdx.dev/) to get the pinned Java 17 + Maven
> toolchain (`mise install`, then `mise exec -- mvn …`), and the example run commands below use it
> because they run the in-repo examples. As a downstream consumer you only need Java 17, your own
> build tool, and the Maven coordinate above — but until the artifact is published, you must first
> build and install this repository locally with `mise exec -- mvn install` so it lands in your
> local `~/.m2`.

## Mental model (read this first)

Three ideas carry the whole library. Internalize them before the code.

### Two layers, one connection

The high-level facade — `Iec104Client` / `Iec104Server`, points, commands, events — sits on a raw
ASDU layer: `com.digitalpetri.iec104.asdu.Asdu`, `AsduType`, `Cause`,
`com.digitalpetri.iec104.asdu.InformationObject` records, and a co-located `Serde` for each type. The
facade never hides that layer. `client.send(Asdu)` and `client.events()` on the client, and
`ServerContext.send(Asdu)` and `ServerHandler.onRawAsdu(...)` on the server, are an always-present
escape hatch for [TypeIDs](reference/coverage-matrix.md) the facade does not model.

This tutorial stays entirely on the high-level layer; drop to the raw layer only when you need a type
the facade does not cover. For when to choose each layer, see
[Work with raw ASDUs](how-to/work-with-raw-asdus.md) and the "choosing a layer" table in
[two-layer-api.md](../architecture/two-layer-api.md) (full story →).

### Async by listener

Request operations return their result **directly**. The blocking methods `interrogate`,
`commands().single(...)`, and `read` return a result object; `synchronizeClock` returns `void` and
signals failure by throwing. Each has an `*Async` twin returning
`java.util.concurrent.CompletionStage<…>`.

*Spontaneous* data and connection/lifecycle changes do **not** come back from a method call. They
arrive on a listener. `client.events()` is a `java.util.concurrent.Flow.Publisher<ClientEvent>`, and
you register by calling `events().subscribe(subscriber)` — there is no add/remove-listener API.
Subscribe **before** you connect or start so early events are not missed.

Full story → [two-layer-api.md](../architecture/two-layer-api.md).

### The callback-serialization guarantee

Events are delivered **serially** on a configured callback executor: a subscriber never observes two
events concurrently, and they arrive in order. On the server, `ServerHandler` callbacks for one
connection are serialized the same way. The practical rule is that your subscriber and handler code
runs on the callback executor (by default the common `ForkJoinPool`), where blocking is fine — but
you must never block the transport I/O threads (the facade keeps you off them).

Full story → [buffers-and-threading.md](../architecture/buffers-and-threading.md).

> IEC 104 carries data only after a **STARTDT** handshake starts data transfer; nothing flows on a
> freshly opened connection until then. The high-level `connect()` performs STARTDT for you by
> default — see below. Background: [overview.md](../architecture/overview.md) and
> [apci-and-timers.md](../architecture/apci-and-timers.md).

## Hello, server

> **These Hello sections are an annotated reading of the runnable
> [`ServerExample`](../../iec104-examples/README.md) / [`ClientExample`](../../iec104-examples/README.md),
> not separate code you build here.** They walk a trimmed three-point excerpt so the moving parts are
> easy to see; [Run it](#run-it) executes those example files directly. That is why the transcript
> there reports **8** points (the example hosts one of every type) rather than the **3** shown below.

The server is a controlled station hosting three points on
[Common Address (CA)](reference/glossary.md) `1` (`com.digitalpetri.iec104.address.CommonAddress`):

| Point | Address (CA, IOA) | `PointType` | Capabilities |
|---|---|---|---|
| `status` | `1, 100` | `SINGLE_POINT` | `REPORTED` |
| `measurement` | `1, 150` | `SCALED` | `REPORTED` |
| `switchPoint` | `1, 300` | `SINGLE_POINT` | `REPORTED, COMMANDABLE` |

This is a deliberately trimmed mirror of the runnable
[`ServerExample`](../../iec104-examples/README.md), which hosts one point of *every* type. The three
addresses match the example so the client section and the "Run it" transcript line up. For how to map
a real-world signal to a `PointType`, see [Choosing a point type](reference/choosing-a-point-type.md).

Present the addresses as constants — in a real `main` they would be `static final` fields, as in
`ServerExample` — so the handler below can reference `switchPoint`:

```java
import com.digitalpetri.iec104.address.CommonAddress;
import com.digitalpetri.iec104.address.PointAddress;
import com.digitalpetri.iec104.point.PointCapability;
import com.digitalpetri.iec104.point.PointType;
import com.digitalpetri.iec104.point.PointValue;
import com.digitalpetri.iec104.server.PointDefinition;
import com.digitalpetri.iec104.server.Station;

CommonAddress station = CommonAddress.of(1);
PointAddress status = PointAddress.of(1, 100);       // single-point status
PointAddress measurement = PointAddress.of(1, 150);  // scaled measured value
PointAddress switchPoint = PointAddress.of(1, 300);  // commandable single point
```

Build the `com.digitalpetri.iec104.server.Station`. Each point is added with `.point(...)`, and the
interrogation-group assignment with `.group(...)` references a point that has already been added. A
point's common address must equal the station's.

```java
Station controlledStation =
    Station.builder(station)
        .point(PointDefinition.of(
            status, PointType.SINGLE_POINT, PointValue.single(false),
            PointCapability.REPORTED))
        .point(PointDefinition.of(
            measurement, PointType.SCALED, PointValue.scaled((short) 0),
            PointCapability.REPORTED))
        .point(PointDefinition.of(
            switchPoint, PointType.SINGLE_POINT, PointValue.single(false),
            PointCapability.REPORTED, PointCapability.COMMANDABLE))
        .group(1, status)
        .group(1, measurement)
        .group(2, switchPoint)
        .build();
```

Plug in a `com.digitalpetri.iec104.server.ServerHandler` that accepts the single command on
`switchPoint` and updates the station image, so the controlling station receives the new value as
return information. `ServerHandler` is a default-method interface, so override only `onCommand`; all
other operations fall through to the default outstation behavior, which answers interrogations and
reads from the station image. (The default `onCommand` itself rejects with
`UNKNOWN_INFORMATION_OBJECT_ADDRESS`, so handling commands means overriding it.)

```java
import com.digitalpetri.iec104.asdu.Cause;
import com.digitalpetri.iec104.asdu.object.SingleCommand;
import com.digitalpetri.iec104.server.CommandDecision;
import com.digitalpetri.iec104.server.CommandRequest;
import com.digitalpetri.iec104.server.ServerContext;
import com.digitalpetri.iec104.server.ServerHandler;

ServerHandler handler =
    new ServerHandler() {
      @Override
      public CommandDecision onCommand(ServerContext context, CommandRequest request) {
        if (request.target().equals(switchPoint)
            && request.commandObject() instanceof SingleCommand command) {
          return CommandDecision.acceptAndUpdate(PointValue.single(command.on()));
        }
        return CommandDecision.reject(Cause.UNKNOWN_INFORMATION_OBJECT_ADDRESS);
      }
    };
```

Build and start the `com.digitalpetri.iec104.transport.tcp.TcpIec104Server`. `bindAddress` defaults
to `"0.0.0.0"` and `port` to `2404` (the standard IEC 104 port); `build()` returns the core
`com.digitalpetri.iec104.server.Iec104Server` interface.

```java
import com.digitalpetri.iec104.server.Iec104Server;
import com.digitalpetri.iec104.transport.tcp.TcpIec104Server;

Iec104Server server =
    TcpIec104Server.builder()
        .bindAddress("0.0.0.0")
        .port(2404)
        .addStation(controlledStation)
        .handler(handler)
        .build();

server.start();   // binds and begins accepting connections
```

`start()` binds and begins accepting connections; it throws if the bind fails. `Iec104Server` is
`AutoCloseable`, so call `server.close()` (or use try-with-resources) to unbind and close
connections when you are done.

Publish a spontaneous value for the measurement. `publish(...)` updates the station image and pushes
a monitor ASDU to every connection that has started data transfer; connections that have not started
data transfer are skipped.

```java
server.publish(measurement, PointValue.scaled((short) 4242), Cause.SPONTANEOUS);
```

`publish` throws `IllegalArgumentException` if no station hosts the point or the value's runtime type
does not match the point's `PointType`. For periodic and spontaneous publishing patterns (a
`ScheduledExecutorService` driving updates), see [Host a server](how-to/host-a-server.md).

## Hello, client

The client is a controlling station that targets the same CA and the same point addresses as the
server above. Build the `com.digitalpetri.iec104.transport.tcp.TcpIec104Client`, then **subscribe a
`Flow.Subscriber<ClientEvent>` before connecting** so no early events are missed. `Iec104Client` is
`AutoCloseable`, so build it in a try-with-resources.

```java
import com.digitalpetri.iec104.client.ClientEvent;
import com.digitalpetri.iec104.client.Iec104Client;
import com.digitalpetri.iec104.transport.tcp.TcpIec104Client;
import java.util.concurrent.Flow;

try (Iec104Client client =
    TcpIec104Client.builder()
        .host("127.0.0.1")
        .port(2404)
        .startDataTransferOnConnect(true)   // default; STARTDT runs as part of connect()
        .build()) {

  // Subscribe before connecting so no early events are missed.
  client.events().subscribe(new PrintingSubscriber());

  client.connect();   // connects and, because of the flag above, also performs STARTDT
  // ... interrogation / command / clock sync below ...
}
```

`startDataTransferOnConnect` defaults to `true`, so `connect()` performs the
[STARTDT](reference/glossary.md) handshake before it returns; data transfer is already started when
the call returns. To gate STARTDT yourself, set `startDataTransferOnConnect(false)` and call
`client.startDataTransfer()` after `connect()`.

Run the requests inside the try block. First a [general interrogation](reference/glossary.md) of CA
`1`: `interrogate(...)` returns an `com.digitalpetri.iec104.client.InterrogationResult`, and
`pointValues()` projects the reported monitor objects onto domain values.

```java
import com.digitalpetri.iec104.client.CommandResult;
import com.digitalpetri.iec104.client.InterrogationResult;
import java.time.Instant;

InterrogationResult snapshot = client.interrogate(station);   // general interrogation of CA 1
for (InterrogationResult.PointEntry entry : snapshot.pointValues()) {
  System.out.printf("  %s [%s] = %s%n",
      entry.address(), entry.value().type(), entry.value().value());
}

CommandResult command = client.commands().single(switchPoint, true);   // direct execute
if (!command.positive()) {
  System.out.println("command rejected: " + command.cause());
}

client.synchronizeClock(station, Instant.now());
```

`commands().single(switchPoint, true)` is a [direct-execute](reference/glossary.md) helper that
returns a `com.digitalpetri.iec104.client.CommandResult`. A non-positive result is a **protocol
rejection, not an exception**: the controlled station refused the command, and
`command.positive()` is `false` with the reason in `command.cause()`. By contrast,
transport/session failures — a timeout, a dropped connection, a negative confirmation on a request —
throw `com.digitalpetri.iec104.*` exceptions (`ProtocolTimeoutException`, `ConnectionClosedException`,
`NegativeConfirmationException`). Only the command-level rejection is encoded in the result. See the
[error model](reference/errors.md) for the full split. (The example does not wrap these in a
try/catch; neither does the snippet above.)

`synchronizeClock(station, Instant.now())` performs a [clock synchronization](reference/glossary.md)
(`C_CS_NA_1`).

Finally, the subscriber. It requests every event and prints point updates and connection-close
events; the other lifecycle events are omitted for brevity.

```java
final class PrintingSubscriber implements Flow.Subscriber<ClientEvent> {
  @Override public void onSubscribe(Flow.Subscription subscription) {
    subscription.request(Long.MAX_VALUE);
  }

  @Override public void onNext(ClientEvent event) {
    if (event instanceof ClientEvent.PointUpdated updated) {
      System.out.printf("point updated %s [%s via %s] = %s (%s)%n",
          updated.address(), updated.value().type(), updated.asduType(),
          updated.value().value(), updated.cause());
    } else if (event instanceof ClientEvent.ConnectionClosed closed) {
      System.out.println("connection closed: " + closed.causeOptional());
    }
    // Other lifecycle events (ConnectionOpened, DataTransferStarted, ...) omitted here.
  }

  @Override public void onError(Throwable throwable) { /* stream failed */ }
  @Override public void onComplete() { /* stream ended */ }
}
```

Every received monitor ASDU yields one `ClientEvent.PointUpdated` per information object, plus an
always-published `ClientEvent.AsduReceived` for the ASDU itself. `updated.asduType()` is the exact
wire [TypeID](reference/coverage-matrix.md) (it distinguishes the time-tag variants), while
`updated.value().type()` is the logical `PointType`. For the full event catalog and the threading
rules, see [Handle events](how-to/handle-events.md).

## Run it

This runs the [`ServerExample`](../../iec104-examples/README.md) /
[`ClientExample`](../../iec104-examples/README.md) files that the Hello sections excerpt — not the
trimmed three-point snippets above. Because the example server hosts one point of *every* type, the
transcript below reports **8** points rather than the **3** in the walkthrough. Build the examples
module first:

```bash
mise exec -- mvn -q -pl iec104-examples -am compile
```

[`ServerExample`](../../iec104-examples/README.md) hosts one point of *every* type — a superset of
the three-point server above — and republishes a fresh value for every point every two seconds.
[`ClientExample`](../../iec104-examples/README.md) runs the same operations as the client section:
connect (with STARTDT), general interrogation, a single command, clock sync, then it lingers for
spontaneous updates.

Start the server in one shell and the client in another:

```bash
# shell 1 — controlled station
mise exec -- mvn -q -pl iec104-examples exec:java \
    -Dexec.mainClass=com.digitalpetri.iec104.examples.ServerExample

# shell 2 — controlling station
mise exec -- mvn -q -pl iec104-examples exec:java \
    -Dexec.mainClass=com.digitalpetri.iec104.examples.ClientExample
```

The server shell prints (the spontaneous "tick" lines repeat every two seconds):

```
[server] listening on port 2404
[server] running; press Ctrl+C to stop
[server] published a spontaneous value for every point (tick 1)
[server] published a spontaneous value for every point (tick 2)
[server] accepting single command on PointAddress[commonAddress=CommonAddress[value=1], objectAddress=InformationObjectAddress[value=300]] -> true
[server] published a spontaneous value for every point (tick 3)
...
```

The client shell prints the interrogation snapshot, the command result, the clock sync, then the
spontaneous updates (this transcript is illustrative and abbreviated — event interleaving on the
callback executor can reorder lines slightly, and the spontaneous values change every tick):

```
[client] connecting to 127.0.0.1:2404
[client] connected; data transfer started
[client] asdu received C_IC_NA_1
[client] asdu received M_SP_TB_1
[client] interrogation reported 8 points
[client]   PointAddress[commonAddress=CommonAddress[value=1], objectAddress=InformationObjectAddress[value=100]] [SINGLE_POINT] = true
[client]   PointAddress[commonAddress=CommonAddress[value=1], objectAddress=InformationObjectAddress[value=110]] [DOUBLE_POINT] = ON
[client]   ...
[client]   PointAddress[commonAddress=CommonAddress[value=1], objectAddress=InformationObjectAddress[value=150]] [SCALED] = 20
[client]   PointAddress[commonAddress=CommonAddress[value=1], objectAddress=InformationObjectAddress[value=300]] [SINGLE_POINT] = false
[client] command on PointAddress[commonAddress=CommonAddress[value=1], objectAddress=InformationObjectAddress[value=300]] positive=true
[client] clock synchronized
[client] waiting 30000 ms for spontaneous updates
[client] point updated PointAddress[commonAddress=CommonAddress[value=1], objectAddress=InformationObjectAddress[value=150]] [SCALED via M_ME_TE_1] = 21 (SPONTANEOUS)
[client] asdu received M_ME_TE_1
[client] ...
[client] closed
```

The general interrogation reports **8** points: the seven `REPORTED` monitor points the example puts
in group 1, plus the commandable switch in group 2. The integrated-totals counter at IOA `170` is
`READABLE` only, so it is *excluded* from general interrogation and arrives spontaneously instead —
the high-level client has no counter-interrogation method, and counter interrogation is covered in
[Connect & interrogate](how-to/connect-and-interrogate.md).

To prove the round trip with zero setup, run the in-process test, which starts the example server on
an ephemeral loopback port, runs the client logic, and asserts that interrogation, a command, and
clock sync all succeed:

```bash
mise exec -- mvn -q -pl iec104-examples -am test
```

## Where to next

You can now do real work on the high-level layer. Open the page that matches your task:

- [Connect & interrogate](how-to/connect-and-interrogate.md) — general and counter interrogation, and
  reading the results.
- [Send commands](how-to/send-commands.md) — single, double, regulating-step, and setpoint commands;
  direct execute vs. select-before-operate.
- [Host a server](how-to/host-a-server.md) — stations, points, the catalog, command handling, and
  periodic/spontaneous publishing.
- [Handle events](how-to/handle-events.md) — the full listener/handler surface and the threading
  rules.
- [Secure with TLS](how-to/secure-with-tls.md) — wrap the link in TLS: keystores, hostname
  verification, and handshake gating.
- [Tune the APCI session](how-to/tune-apci.md) — the k/w window and the t0–t3 timers, as a decision
  table.
- [Work with raw ASDUs](how-to/work-with-raw-asdus.md) — drop to the raw layer for unmodeled TypeIDs.

Reference pages you will return to: the [coverage matrix](reference/coverage-matrix.md), the
[glossary](reference/glossary.md), [choosing a point type](reference/choosing-a-point-type.md), the
[timers & window](reference/timers-and-window.md), and the [error model](reference/errors.md). The
runnable [examples](../../iec104-examples/README.md) are the executable companion to every page, and
the project [README](../../README.md) has the coordinates and the one-paragraph "what is this."

Power users who want the as-built internals — the wire model, the `ApciSession` engine, buffer
ownership and threading, and the core-vs-transport split — should read
[`docs/architecture/`](../architecture/README.md).
