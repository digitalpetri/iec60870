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
  void startDataTransfer(); void stopDataTransfer();// + *Async()
  Flow.Publisher<ClientEvent> events();
  InterrogationResult interrogate(CommonAddress station);
  InterrogationResult interrogate(CommonAddress station, QualifierOfInterrogation qoi);
  List<InformationObject> read(PointAddress point);
  CommandService commands();
  void synchronizeClock(CommonAddress station, Instant time);
  void send(Asdu asdu);
  boolean isConnected();
  void close();
}
```

Blocking methods are the primary surface; each has an `*Async` variant returning a
`CompletionStage`. The client is `AutoCloseable`.

**Commands.** `client.commands()` returns a `CommandService`. A `Command` is a sealed domain type
(`SingleCommandRequest`, `DoubleCommandRequest`, `RegulatingStepCommandRequest`, the three
`Setpoint*Request` records, and `BitstringCommandRequest`) carrying a `PointAddress`, a value, a
qualifier, and an optional `Instant` time tag — but **not** the select/execute flag. The service
chooses select-vs-execute from a `CommandMode` (`directExecute()` or `selectBeforeOperate()`) when it
builds the wire ASDU, and picks the time-tagged TypeID (e.g. `C_SC_TA_1`) when the command carries a
time. A command completes with a `CommandResult(target, positive, cause, confirmation)` rather than
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
`InterrogationResult(station, objects, terminated)`. `result.pointValues()` projects the monitor
objects onto `PointEntry(PointAddress, PointValue<?>)` entries, skipping non-monitor objects.

**Events.** `events()` is a `Flow.Publisher<ClientEvent>`. `ClientEvent` is a sealed interface whose
records cover the connection lifecycle and inbound data: `ConnectionOpened`, `ConnectionClosed`,
`DataTransferStarted`/`Stopped`, `PointUpdated(address, value, asduType, cause, timestamp)`,
`AsduReceived(asdu)`, `NegativeConfirmation(asdu, cause)`, and `ProtocolWarning(message)`. Every
received monitor ASDU yields one `PointUpdated` per information object **and** an always-published
`AsduReceived`, so a subscriber can work at the point level, the ASDU level, or both. Events are
delivered serially — a subscriber never observes two events concurrently.

```java
client.events().subscribe(subscriber);
// inside Flow.Subscriber#onNext(ClientEvent event):
switch (event) {
  case ClientEvent.PointUpdated u -> handle(u.address(), u.value());
  case ClientEvent.ConnectionClosed c -> reconnect(c.cause());
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
| Receiving an unmodeled or unsupported type | Raw: `ClientEvent.AsduReceived` / `ServerHandler.onRawAsdu` |
