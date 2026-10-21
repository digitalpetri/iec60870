# The Two-Layer API

The library exposes the same connection at two levels of abstraction. A caller chooses the level that
fits the task and can mix them freely: the high-level facade is built on top of the raw layer and
never hides it.

## The raw protocol layer

The raw layer is a faithful, immutable model of the wire. Its center is the `Asdu` record in
`com.digitalpetri.iec60870.asdu`:

```java
public record Asdu(
    AsduType type, boolean sequence, Cause cause, boolean negative, boolean test,
    OriginatorAddress originatorAddress, CommonAddress commonAddress,
    List<InformationObject> objects) { ... }
```

The supporting types:

- **`AsduType`** — an enum constant for every type identification in the 101/104 tables (e.g.
  `M_SP_NA_1(1)`, `C_SC_NA_1(45)`). Each constant reports its numeric `typeId()` and whether the
  library provides a typed object record via `supported()`. `AsduType.fromId(int)` resolves an
  inbound type and throws `UnsupportedAsduTypeException` for undefined values.
- **`Cause`** — the cause of transmission (Table 14): `SPONTANEOUS(3)`, `ACTIVATION(6)`,
  `ACTIVATION_CONFIRMATION(7)`, `INTERROGATED_BY_STATION(20)`, and so on. The `test` and
  positive/negative (`negative`) bits of the cause octet are modeled separately on `Asdu`, not inside
  `Cause`.
- **`InformationObject`** — a sealed-by-convention interface (`address()` only) implemented by one
  record per supported TypeID in `com.digitalpetri.iec60870.asdu.object`. Examples:
  `SinglePointInformation`, `MeasuredValueScaledWithCp56Time`, `SingleCommand`,
  `InterrogationCommand`. Each carries exactly the fields that follow the information object address
  on the wire.
- **Addresses** in `.address`: `CommonAddress` (`UShort`), `InformationObjectAddress` (`UInteger`),
  `OriginatorAddress` (`UByte`), and `PointAddress` (a `CommonAddress` + `InformationObjectAddress`
  pair used as a stable point key).
- **Elements and time tags** in `.asdu.element` and `.asdu.time`: reusable pieces such as `Qds`
  (quality descriptor), `Vti` (value with transient state), `NormalizedValue`, `BinaryCounterReading`,
  the `QualifierOf*` types, and `Cp56Time2a` / `Cp24Time2a` / `Cp16Time2a`.

### Co-located Serde

Every wire type nests a `public static final class Serde` that encodes and decodes it. `Asdu.Serde`
handles the full data unit identifier and dispatches to a per-type `InformationObjectCodec` for the
elements; `InformationObjectCodec` implementations only encode/decode the elements *after* the IOA,
because `Asdu.Serde` frames the IOA centrally. The codecs are `ByteBuf`-based and never allocate or
release the buffer — see [buffers-and-threading.md](buffers-and-threading.md).

Below the raw layer sits the **octet transport SPI** (`.transport`): `ClientTransport.send(ByteBuf)`
and `TransportListener.onFrame(ByteBuf)` exchange one complete, length-delimited frame each. The
`Apdu`↔`ByteBuf` translation (`ApduFramer`, built on `Apdu.Serde`) lives *above* this SPI, so the SPI
itself is protocol-agnostic and reusable. A caller of the high-level facade never sees either an
`Apdu` or a `ByteBuf` — the facade does the framing and deframing.

A caller rarely calls `Serde` directly. The escape hatch into the raw layer is on the high-level
client and server:

```java
// Build any ASDU and send it as-is; the response arrives via events().
Asdu raw = new Asdu(AsduType.C_SC_NA_1, false, Cause.ACTIVATION, false, false,
    OriginatorAddress.none(), CommonAddress.of(1),
    List.of(new SingleCommand(InformationObjectAddress.of(100),
        /* ...elements... */)));
client.send(raw);
```

## The high-level layer

The facade turns the wire model into a domain API. It correlates requests with responses, runs the
command procedures, projects monitor objects onto point values, and delivers everything serially on a
callback executor.

The high-level layer lives in its own module, `iec60870-application`, which depends on
`iec60870-core` only and carries **no Netty**. The facades speak purely in terms of `Asdu` and the
neutral `Session` SPI: a `DefaultIec60870Client` is built around an injected `Session`, and a
`DefaultIec60870Server` around a per-connection session factory. The protocol-specific session (a
104 `ApciSession`) and its `Apdu`/`ByteBuf` framing are assembled outside the facade — by
`Cs104Binding` in `iec60870-cs104`, which the `TcpIec104Client`/`TcpIec104Server` builders invoke —
so the high-level layer itself never names a wire frame. See
[modules-and-dependencies.md](modules-and-dependencies.md).

### Client

`Iec60870Client` (interface; `DefaultIec60870Client` is the implementation) drives one connection:

```java
public interface Iec60870Client extends AutoCloseable {
  void connect();                                   // + connectAsync()
  void disconnect();                                // + disconnectAsync()
  ConnectionState connectionState();
  void startDataTransfer(); void stopDataTransfer();// + *Async()
  Flow.Publisher<ClientEvent> events();
  InterrogationResult interrogate(CommonAddress station);
  InterrogationResult interrogate(CommonAddress station, QualifierOfInterrogation qoi);
  List<InformationObject> read(PointAddress point);
  CommandService commands();
  void synchronizeClock(CommonAddress station, Instant time);
  CounterInterrogationResult counterInterrogate(CommonAddress station, QualifierOfCounterInterrogation qcc);
  TestResult test(CommonAddress station, AsduType type);  // C_TS_TA_1 or C_TS_NA_1
  void resetProcess(CommonAddress station, QualifierOfResetProcess qrp);
  ParameterService parameters();                    // P_ME load, P_AC activate/deactivate
  void send(Asdu asdu);
  boolean isConnected();
  void close();
}
```

Blocking methods are the primary surface; each has an `*Async` variant returning a
`CompletionStage`. The client is `AutoCloseable`.

**Connection lifecycle.** The client facade owns reconnect; the transport dials once per request and
never on its own. `connect()` starts a managed connection and returns once the first connection is
established (and `STARTDT` confirmed, when `startDataTransferOnConnect` is set), or throws the first
attempt's failure. After that, any unplanned loss — a peer FIN or RST, an I/O or send failure, a
protocol self-close such as `t1` expiry or an invalid N(R), an unconfirmed `STARTDT`, or a failed
attempt — ends the current connection. The client then fails pending requests with
`ConnectionClosedException` (they are never replayed), closes the transport connection, and waits for
the delay its `ReconnectPolicy` gives. Each attempt resets the session (V(S) and V(R) to zero), runs
`STARTDT` when configured, and publishes the same events as `connect()`.

`connectionState()` reports the lifecycle:

```
DISCONNECTED --connect()--> CONNECTING --established--> CONNECTED
                                 ^   |                       |
                     timer fires |   | attempt fails         | loss / self-close
                                 |   v                       v
                             RECONNECT_WAIT <----------------+
any state --disconnect()--> DISCONNECTED        any state --close()--> CLOSED
```

With `ReconnectPolicy.disabled()` a loss or failed attempt goes to `DISCONNECTED` instead of
`RECONNECT_WAIT`. `connect()` during `RECONNECT_WAIT` dials at once; during `CONNECTING` it joins the
attempt in progress. `ReconnectPolicy.exponentialBackoff(initial, max)` doubles the delay from
`initial` up to `max`, and an established connection resets it; the default is 1 s up to 32 s.
`isConnected()` is `true` only in `CONNECTED` while the transport connection is open.

The facade serializes the lifecycle through one operation queue that also runs session
initialization, so a connection's closing events always follow its `ConnectionOpened` and precede the
next connection's. A new attempt dials only after the previous connection's transport close has
finished, which keeps late callbacks from the old socket away from the new session. The transport,
for its part, reports a loss only for the connection it established and still holds; a connection
closed through `disconnect()` or `closeConnection()` is not reported. A transport-owned Netty event
loop group is shut down only by the client's `close()`.

Lifecycle events follow the connection:
`ConnectionOpened → DataTransferStarted → … → DataTransferStopped → ConnectionClosed`, then
`ReconnectScheduled(attempt, delay, cause)` when the policy schedules an attempt. `ConnectionClosed`
carries a cause for every unplanned close, including a peer that closed the connection normally;
`disconnect()` and `close()` publish it with no cause, and `close()` does so before the event stream
completes. A failed attempt that never opened a connection publishes only `ReconnectScheduled`. All
of these events go through the lossless event stream's lifecycle reserve, so none is dropped.

**Request correlation.** IEC 60870-5 carries no request identifier, so every correlated method
registers a pending request that matches replies by their ASDU fields. Correlation runs in the
session callback before events are published, so it never depends on a subscriber keeping up. Each
request is matched on its common address and test flag, and on an originator address of zero or the
client's own. Its confirmation must echo the sent object at IOA 0: the QOI or QCC, the reset
qualifier (QRP), the fixed test pattern (FBP), or the parameter activation qualifier (QPA). For
C_TS_TA_1 it echoes the test sequence counter (TSC), which the client draws from a wrapping 16-bit
counter; the echoed time is not compared. A parameter load matches on type and IOA only, because its
confirmation carries the value now in operation.

| Request | Completes on | Timeout | In flight at once |
|---|---|---|---|
| `interrogate` (C_IC_NA_1) | ACT_TERM, collecting COT = QOI after ACT_CON | `requestTimeout` | one per CA |
| `counterInterrogate`, `FreezeMode.READ` (C_CI_NA_1) | ACT_TERM, collecting M_IT with COT 37 (RQT 5) or 37 + RQT after ACT_CON | `requestTimeout` | one per CA |
| `counterInterrogate`, freeze or reset | ACT_CON | `commandTimeout` | (same as above) |
| `read` (C_RD_NA_1) | the response with COT 5 | `requestTimeout` | one per point |
| `synchronizeClock` (C_CS_NA_1) | ACT_CON | `commandTimeout` | one per CA |
| `test` (C_TS_TA_1, C_TS_NA_1) | ACT_CON | `commandTimeout` | one per CA |
| `resetProcess` (C_RP_NA_1) | ACT_CON | `commandTimeout` | one per CA |
| `parameters().load` (P_ME_NA_1..NC_1) | ACT_CON | `commandTimeout` | one per CA and IOA |
| `parameters().activate` / `deactivate` (P_AC_NA_1) | ACT_CON / DEACT_CON | `commandTimeout` | one per CA and IOA |

Requests of different kinds never conflict, so a general and a counter interrogation of the same
station run side by side: their confirmations differ by TypeID and their data by COT. A conflicting
request fails with `RequestInProgressException` before anything is sent. A timeout is one deadline
armed at send. Connection loss fails every pending request with `ConnectionClosedException`.
`maxInterrogationResponseObjects` bounds the objects that both interrogation kinds collect.

A negative confirmation fails the request with `NegativeConfirmationException`, except a command
or a parameter load, which return a result with `positive() == false`. The counter interrogation,
test, reset, and parameter services also treat causes 44 to 47 (unknown type, cause, CA, or IOA) on
a matching reply as a rejection even when P/N is 0, so a station that answers that way fails fast.
`interrogate` and `synchronizeClock` keep their earlier rule and need P/N set.

The methods that take a station reject the global common address (255 or 65535, by
`ProtocolProfile.commonAddressLength()`) with `IllegalArgumentException`. Each station answers a
broadcast with its own address, so the client cannot know when a broadcast is complete; broadcasts
go through `send(Asdu)`.

**Commands.** `client.commands()` returns a `CommandService`. A `Command` is a sealed domain type
(`SingleCommandRequest`, `DoubleCommandRequest`, `RegulatingStepCommandRequest`, the three
`Setpoint*Request` records, and `BitstringCommandRequest`) carrying a `PointAddress`, a value, a
qualifier, and an optional `Instant` time tag — but **not** the select/execute flag. The service
chooses select-vs-execute from a `CommandMode` (`directExecute()` or `selectBeforeOperate()`) when it
builds the wire ASDU, and picks the time-tagged TypeID (e.g. `C_SC_TA_1`) when the command carries a
time. One pending operation reserves the command family and point through both SELECT and EXECUTE.
A positive SELECT confirmation queues EXECUTE while retaining that reservation; connection loss
invalidates it before any delayed continuation can use a replacement connection. Each confirmation
must mirror the transmitted command's exact type, object (including phase, value, qualifier, and
time) and test flag. The reply's originator address must match the command or be zero (unused).
A one-octet cause of transmission omits the originator octet, so its decoded replies use zero;
zero is also accepted from peers that do not use originator addresses. A different nonzero
originator is rejected. ACT_CON and mirrored negative error causes are accepted; DEACT_CON does
not confirm an activation. Identical stale replies remain indistinguishable because the protocol
carries no request identifier.

A command completes with a `CommandResult(target, positive, cause, confirmation)` rather than
throwing on a protocol-level rejection: `positive() == false` means the station returned a negative
confirmation. Transport and session failures (timeout, disconnect) still surface as exceptions.

```java
CommandResult r = client.commands().single(point, true);              // direct execute
if (!r.positive()) handleRejection(r.cause());
CommandResult sbo = client.commands()
    .send(Command.single(point, true), CommandMode.selectBeforeOperate());
```

**Interrogation.** `interrogate` sends `C_IC_NA_1 ACT`, awaits the positive `ACT_CON` (else a
`NegativeConfirmationException`), collects the reported objects until `ACT_TERM`, and returns an
`InterrogationResult(station, objects, terminated)`. Confirmations and terminations must echo the
requested qualifier and IOA; termination is accepted only after confirmation. Collected data must
carry the cause for the requested station or group (20..36), and all replies must match the request's
test flag. With a two-octet cause of transmission, replies may carry the requested originator or the
default originator (OA 0); another nonzero originator is ignored. With a one-octet cause of
transmission, no originator field is transmitted or compared. A late reply for another group cannot
complete or add data to the request.
`result.pointValues()` projects the monitor objects onto `PointEntry(PointAddress, PointValue<?>)`
entries, skipping non-monitor objects.

**Events.** `events()` is a `Flow.Publisher<ClientEvent>`. `ClientEvent` is a sealed interface whose
records cover the connection lifecycle and inbound data: `ConnectionOpened`, `ConnectionClosed`,
`ReconnectScheduled(attempt, delay, cause)`, `DataTransferStarted`/`Stopped`,
`PointUpdated(address, value, asduType, cause, timeTag)`, `AsduReceived(asdu)`,
`UndecodableAsdu(asdu, cause)`, `NegativeConfirmation(asdu, cause)`, and
`ProtocolWarning(message)`. Every
received monitor ASDU yields one `PointUpdated` per information object **and** an always-published
`AsduReceived`, so a subscriber can work at the point level, the ASDU level, or both. Events are
delivered serially — a subscriber never observes two events concurrently. `ProtocolWarning` is
published when the session ignores a U-frame without closing (an unsolicited TESTFR con, STARTDT
con, or STOPDT con, or a STARTDT act or STOPDT act sent to the client), reported through
`Session.Events.onProtocolWarning`. It is best effort: while the slowest subscriber's backlog is at
its data capacity the warning is dropped and reported to the diagnostics listener instead. Counts,
timer expiries, and frames below the event stream come from the diagnostics listener described in
[buffers-and-threading.md](buffers-and-threading.md#diagnostics-listeners).

```java
client.events().subscribe(subscriber);
// inside Flow.Subscriber#onNext(ClientEvent event):
switch (event) {
  case ClientEvent.PointUpdated u -> handle(u.address(), u.value());
  case ClientEvent.ConnectionOpened o -> refreshImage();
  default -> {}
}
```

### Server

`Iec60870Server` (interface; `DefaultIec60870Server` is the implementation) is the controlled station. It
hosts `Station`s, answers requests from a per-station value image, and spontaneously publishes
values:

```java
StationRegistry stations();
Flow.Publisher<ServerEvent> events();
void publish(PointAddress point, PointValue<?> value, Cause cause);
```

A `Station` (built with `Station.builder(CommonAddress)`) holds a set of `PointDefinition<T>` records
— each an `address`, a logical `PointType`, an `initialValue`, and a set of `PointCapability`
(`READABLE`, `COMMANDABLE`, `REPORTED`) — plus a thread-safe current-value image and an assignment of
points to interrogation groups (1..16). The server reads the image to answer interrogations and reads,
and writes it as values are published or commands accepted.

Application logic plugs in through `ServerHandler`, a default-method interface. Each operation
(`onInterrogation`, `onRead`, `onCommand`, `onClockSync`, `onReset`, `onRawAsdu`) has a default that
implements the standard outstation behavior and an `*Async` variant. A handler is handed a
`ServerContext` exposing the connection's `remoteAddress()`, the matched `station()`, default-answer
helpers (`defaultInterrogation`, `defaultRead`), and a raw `send(Asdu)` escape hatch. A command is
answered with a `CommandDecision` (`accept()`, `acceptAndUpdate(value)`, or `reject(cause)`).
Solicited replies preserve the request's originator address, including negative replies and
interrogation data. Published updates and command return information use the default originator
address (zero).

```java
ServerHandler handler = new ServerHandler() {
  @Override public CommandDecision onCommand(ServerContext ctx, CommandRequest request) {
    boolean on = request.commandObject() instanceof SingleCommand sc && sc.on();
    return CommandDecision.acceptAndUpdate(PointValue.single(on));
  }
};
```

`ServerEvent` mirrors `ClientEvent` on the server side: `ConnectionAccepted`, `ConnectionClosed`,
`DataTransferStarted`/`Stopped`, `CommandReceived`, and `AsduReceived`, each carrying the connection's
`remoteAddress()`.

### Point and catalog model

`PointValue<T>` pairs a typed value with a `Quality` (the five IEC quality flags, bridged to/from the
wire `Qds`) and an optional acquisition `Instant`. The type parameter follows the `PointType`:
`Boolean` for single-point, `DoublePointState` for double-point, `Vti` for step position, `Integer`
for a 32-bit bit string, `Short` for scaled, `NormalizedValue` for normalized, `Float` for
short-float, and `BinaryCounterReading` for integrated totals. Factories (`single`, `doublePoint`,
`scaled`, `normalized`, `shortFloat`, `counter`, …) create good-quality values, refined with
`withQuality(...)` / `withTimestamp(...)`.

### Time tags

IEC 60870-5 time tags carry wall-clock fields with no time zone. `TimePolicy` (in `.point`) is the
single conversion path between those fields and `Instant`. It names the zone of the peer's clock and
the rules for the SU bit, the day-of-week field, the two-digit year's century, and CP24Time2a
completion. `TimePolicy.utc()` is the default for both facades and reproduces fixed-UTC encoding byte
for byte.

- **Receive.** `MonitorMapping.extract(object, policy, receivedAt)` returns a `PointValueExtraction`
  whose `TimeTag` keeps the raw `Cp56Time2a` or `Cp24Time2a`, its IV, SU, and RES1/GEN flags, and the
  resolved `instant()`. The client captures `receivedAt` once per ASDU and copies the `TimeTag` onto
  `PointUpdated`. The `timestamp()` accessors on the extraction, the event, and `PointValue` drop the
  instant of a tag marked invalid. A CP24 tag has an empty instant unless the policy completes it from
  the receive time, in which case `TimeTag.inferred()` is set. `TimeTag.from(object, policy,
  receivedAt)` resolves the tag of any record that implements `Cp56TimeTagged` or `Cp24TimeTagged`
  (core interfaces on every timed record), including the protection-event types the point model does
  not map.
- **Send.** The client encodes command time tags (types 58–64) and clock synchronization with
  `ClientConfig.timePolicy()`. The server encodes spontaneous publishes, read answers, counter
  interrogation answers, and return information with `ServerConfig.timePolicy()`, and exposes it to
  handlers as `ServerContext.timePolicy()` so they can read a `ClockSyncRequest` or command time tag
  with the same rules.
- **Echo matching.** A command confirmation must echo the sent objects. For time-tagged commands
  `ClientConfig.commandTimeTagMatch()` decides how the echoed tag is compared: `INSTANT` (default)
  compares milliseconds through year and ignores day of week, IV, SU, and RES1/GEN; `EXACT` compares
  every field; `IGNORE` skips the tag. Type, address, value, qualifier, and S/E must match in every
  mode. Clock-sync confirmations are correlated by family, common address, cause, and IOA, and do not
  compare the tag.

The `.catalog` package adds an optional, dependency-free model of *which points exist*. A
`PointCatalog` is an immutable, address-keyed set of `CatalogEntry` records; because IEC 104 has no
authoritative browse operation, a client gathers evidence with an `ObservationMode` (sealed:
`GeneralInterrogation`, `GroupInterrogation(int)`, `PassiveTraffic(Duration)`,
`GeneralThenPassive(Duration)`) into an `ObservedCatalog`, then folds that evidence into a catalog
with `merge(observed, MergePolicy)`. The catalog describes naming and structure; live traffic supplies
the values.

## Choosing a layer

| Need | Use |
|---|---|
| Read points, issue standard commands, interrogate | High-level: `Iec60870Client` facade |
| Host an outstation with a value image | High-level: `Iec60870Server` + `Station` |
| Per-point values with quality and timestamps | `PointValue<T>` and `ClientEvent.PointUpdated` |
| Conformance / exact wire control of a modeled type | Raw: build `Asdu`, `client.send(asdu)` / `events()` |
| Receiving a supported type the facade does not act on | Raw: `ClientEvent.AsduReceived` / `ServerHandler.onRawAsdu` |
| Receiving a private or `F_*` type, or any ASDU that fails to decode | Raw octets: `ClientEvent.UndecodableAsdu` / `ServerEvent.UndecodableAsdu` |
