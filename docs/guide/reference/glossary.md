# Glossary

IEC 104 vocabulary mapped to this library's Java types. Each entry gives a short
plain-English definition and the concrete `com.digitalpetri.iec60870.*` type you'll
actually use, then links to the guide page where the term is used in anger. For the
deep mechanics behind a term, follow the architecture links.

One framing underpins half the entries: in IEC 60870-5-104 a **controlling station**
(master) talks to a **controlled station** (outstation). In this library the
controlling station is the **client** (`Iec60870Client`) and the controlled station is
the **server** (`Iec60870Server`). See [Stations, roles, and direction](#stations-roles-and-direction).

## How to read an entry

Each term is structured as:

- **Definition** — what the protocol word means, in one or two lines.
- **In this library** — the concrete Java type, enum, or record that embodies it,
  with the member that matters.
- **See** — where the term is used (a how-to or reference page), and the
  [architecture docs](../../architecture/README.md) for internals.

Most terms hang off one axis: whether a message flows in the **monitor direction**
(data up, from the controlled station) or the **control direction** (commands down,
from the controlling station). Those two terms, and the station roles, are defined
together first because everything else leans on them.

## Stations, roles, and direction

**Controlling station (master).** The station that initiates the connection and issues
control-direction messages — commands, interrogations, clock sync.
*In this library:* the **client**, `com.digitalpetri.iec60870.client.Iec60870Client`, built
via `com.digitalpetri.iec60870.transport.tcp.TcpIec104Client`.
*See:* [Connect & interrogate](../how-to/connect-and-interrogate.md),
[Send commands](../how-to/send-commands.md).

**Controlled station (outstation / RTU / slave).** The station that hosts process data
and answers the controlling station.
*In this library:* the **server**, `com.digitalpetri.iec60870.server.Iec60870Server`, built
via `TcpIec104Server`, hosting `Station`s of `PointDefinition`s.
*See:* [Host a server](../how-to/host-a-server.md).

**Monitor direction.** The flow of data from controlled station to controlling station —
measurements and status (the `M_*` TypeIDs).
*In this library:* surfaced as `ClientEvent.PointUpdated` on the client; produced on the
server via `Iec60870Server.publish(...)`.
*See:* [Handle events](../how-to/handle-events.md),
[Coverage matrix](coverage-matrix.md).

**Control direction.** The flow from controlling station to controlled station — commands
and requests (the `C_*` / `P_*` TypeIDs).
*In this library:* `CommandService` (`client.commands()`), `Iec60870Client.interrogate(...)`,
and `synchronizeClock(...)`; received on the server through `ServerHandler.onCommand(...)`.
*See:* [Send commands](../how-to/send-commands.md).

## Glossary

### APCI

**Application Protocol Control Information** — the fixed frame header (start octet `0x68`,
length, four control octets) that layers connection start/stop, keep-alive, and a
sliding-window acknowledgement scheme on top of TCP.
*In this library:* package `com.digitalpetri.iec60870.apci` — `ApciSession` (the engine),
`Apdu`, `ControlField`, `UFunction`; tuned via `ApciSettings`.
*See:* [Tune the APCI session](../how-to/tune-apci.md),
[Timers & window](timers-and-window.md),
architecture: [apci-and-timers.md](../../architecture/apci-and-timers.md).

### APDU

**Application Protocol Data Unit** — one complete IEC 104 frame on the wire: an APCI
header, plus an ASDU body **only** for I-format frames.
*In this library:* `com.digitalpetri.iec60870.apci.Apdu` (with `Apdu.Serde` for the wire
form).
*See:* architecture [apci-and-timers.md](../../architecture/apci-and-timers.md).

### ASDU

**Application Service Data Unit** — the application payload carried inside an I-format
APDU: a TypeID, a cause of transmission, addressing, and one or more information objects.
*In this library:* `com.digitalpetri.iec60870.asdu.Asdu` (a record), with `AsduType`,
`Cause`, and `InformationObject` records.
*See:* [Work with raw ASDUs](../how-to/work-with-raw-asdus.md),
[Coverage matrix](coverage-matrix.md).

### Cause of Transmission (COT)

Why an ASDU was sent — e.g. spontaneous, periodic, activation, activation confirmation,
or interrogated-by-station. A 6-bit field, optionally followed by the originator address.
*In this library:* enum `com.digitalpetri.iec60870.asdu.Cause` (e.g. `SPONTANEOUS`,
`ACTIVATION`, `ACTIVATION_CONFIRMATION`, `ACTIVATION_TERMINATION`,
`INTERROGATED_BY_STATION`); its field width is `ProtocolProfile.cotLength()` (1 or 2
octets).
*See:* [Error model](errors.md) (negative confirmations carry a `Cause`),
[Send commands](../how-to/send-commands.md).

### Common Address (CA)

The common address of an ASDU — the address of the station/sector an ASDU belongs to;
every point on a station shares its CA.
*In this library:* `com.digitalpetri.iec60870.address.CommonAddress`
(`record CommonAddress(UShort value)`, `CommonAddress.of(int)`); a `Station` is keyed by
it; field width is `ProtocolProfile.commonAddressLength()` (1–2 octets). Value `0` is
unused and `0xFFFF` is the global (broadcast) address.
*See:* [Host a server](../how-to/host-a-server.md),
[Tune the APCI session](../how-to/tune-apci.md).

### Direct execute

A control mode that sends a single activation the controlled station acts on
immediately (the S/E "select/execute" flag is clear). Contrast with
[Select-before-operate](#select-before-operate-sbo).
*In this library:* `com.digitalpetri.iec60870.client.CommandMode.directExecute()`
(`DIRECT_EXECUTE`); every `CommandService` convenience helper (`single`, `doublePoint`, …)
uses it.
*See:* [Send commands](../how-to/send-commands.md).

### Information Object Address (IOA)

The address of a single information object (one point) **within** a common address.
*In this library:* `com.digitalpetri.iec60870.address.InformationObjectAddress`
(`record … (UInteger value)`, `InformationObjectAddress.of(long)`,
`MAX_VALUE = 0x00FF_FFFF`); field width is `ProtocolProfile.ioaLength()` (1–3 octets). A
CA + IOA pair forms a [Point Address](#point-address).
*See:* [Choosing a point type](choosing-a-point-type.md),
[Host a server](../how-to/host-a-server.md).

### k / w window

The APCI flow-control window: **k** is the maximum number of I-frames a station may have
outstanding (unacknowledged) before it must stop sending; **w** is the number of received
I-frames after which it must send an acknowledgement. `w` must not exceed `k`.
*In this library:* `com.digitalpetri.iec60870.ApciSettings` components `k()` and `w()`;
defaults `k = 12`, `w = 8` (`ApciSettings.defaults()`).
*See:* [Tune the APCI session](../how-to/tune-apci.md),
[Timers & window](timers-and-window.md),
architecture [apci-and-timers.md](../../architecture/apci-and-timers.md).

### Monitor / control direction

See [Monitor direction](#stations-roles-and-direction) and
[Control direction](#stations-roles-and-direction) under
[Stations, roles, and direction](#stations-roles-and-direction).

### Originator Address (OA)

An optional second COT octet identifying which controlling application originated a
request, so confirmations can be routed back. Present only when COT is 2 octets.
*In this library:* `com.digitalpetri.iec60870.address.OriginatorAddress`
(`record … (UByte value)`, `OriginatorAddress.of(int)`, `OriginatorAddress.none()`);
enabled by `ProtocolProfile.cotLength() == 2` (the IEC 104 default) and set on the
builder via `originatorAddress(...)`.
*See:* [Tune the APCI session](../how-to/tune-apci.md).

### Point Address

This library's stable, cross-station key for one point: a
`(CommonAddress, InformationObjectAddress)` pair. It is a logical key, not a wire field.
*In this library:* `com.digitalpetri.iec60870.address.PointAddress`
(`PointAddress.of(int commonAddress, long objectAddress)`); used throughout the
high-level API — `read(...)`, command targets, `PointDefinition`, and `PointValue`
delivery.
*See:* [Choosing a point type](choosing-a-point-type.md).

### Qualifier of Interrogation (QOI)

Selects the scope of a general interrogation: the whole station, or one of interrogation
groups 1–16.
*In this library:* `com.digitalpetri.iec60870.asdu.element.QualifierOfInterrogation` —
constants `STATION` (value 20) and `GROUP_1` (21) … `GROUP_16` (36); passed to
`Iec60870Client.interrogate(station, qoi)`.
*See:* [Connect & interrogate](../how-to/connect-and-interrogate.md).

### Quality descriptor (IV / NT / SB / BL / OV)

Five independent quality bits attached to a monitored value:

- **IV** — invalid: the value could not be acquired.
- **NT** — not topical: the value is stale (not refreshed in time).
- **SB** — substituted: set by an operator or automation, not measured.
- **BL** — blocked: held, not transmitted.
- **OV** — overflow: out of range.

*In this library:* the wire-level descriptor is
`com.digitalpetri.iec60870.asdu.element.Qds`; the point-model equivalent is
`com.digitalpetri.iec60870.point.Quality`
(`record Quality(boolean overflow, boolean blocked, boolean substituted, boolean notTopical, boolean invalid)`),
with `Quality.good()`, `Quality.invalidQuality()`, `withInvalid(boolean)`, and
`toQds()` / `fromQds(...)` to convert between the two:

```java
Quality q = Quality.good().withInvalid(true); // mark a value as not trustworthy
PointValue<Float> v = PointValue.shortFloat(12.3f).withQuality(q);
```

*See:* [Choosing a point type](choosing-a-point-type.md),
[Handle events](../how-to/handle-events.md).

### Select-before-operate (SBO)

A two-step control mode: first send a *select* activation and wait for confirmation, then
send an *execute* activation — guarding against accidental operation. Contrast with
[Direct execute](#direct-execute).
*In this library:* `com.digitalpetri.iec60870.client.CommandMode.selectBeforeOperate()`
(`SELECT_BEFORE_OPERATE`), passed to `CommandService.send(command, mode)`; the returned
`CommandResult` reflects the **execute** confirmation. On the server,
`ServerHandler.onCommand(...)` sees `CommandRequest.isSelect()` and the server-side
`com.digitalpetri.iec60870.server.CommandMode` (`SELECT` / `EXECUTE`).
*See:* [Send commands](../how-to/send-commands.md).

### Spontaneous transmission

A monitor-direction report the controlled station sends on its own when a value changes,
rather than in answer to a request; carried with cause `SPONTANEOUS`.
*In this library:* on the server, `Iec60870Server.publish(point, value, Cause.SPONTANEOUS)`;
on the client, observed as `ClientEvent.PointUpdated` (and the underlying
`ClientEvent.AsduReceived`).
*See:* [Host a server](../how-to/host-a-server.md),
[Handle events](../how-to/handle-events.md).

### STARTDT / STOPDT

U-format control functions that gate user-data transfer. After connect the link is
*stopped* and no monitor data flows until the controlling station sends `STARTDT` and the
controlled station confirms; `STOPDT` reverses it.
*In this library:* `Iec60870Client.startDataTransfer()` / `stopDataTransfer()`; automatic
on connect when `startDataTransferOnConnect` is `true` (the default). The frame functions
are `com.digitalpetri.iec60870.apci.UFunction.STARTDT_ACT` / `STARTDT_CON` and
`STOPDT_ACT` / `STOPDT_CON`.
*See:* [Connect & interrogate](../how-to/connect-and-interrogate.md),
architecture [apci-and-timers.md](../../architecture/apci-and-timers.md).

### t0 / t1 / t2 / t3

The four APCI timeouts:

- **t0** — connection-establishment timeout.
- **t1** — timeout awaiting acknowledgement of a sent I/U frame. Its expiry is fatal to
  the connection.
- **t2** — maximum delay before acknowledging received I-frames (should be `< t1`).
- **t3** — idle period after which a `TESTFR` is sent.

*In this library:* `com.digitalpetri.iec60870.ApciSettings` components `t0()`–`t3()`
(`Duration`); defaults t0 = 30 s, t1 = 15 s, t2 = 10 s, t3 = 20 s
(`ApciSettings.defaults()`).
*See:* [Timers & window](timers-and-window.md),
[Tune the APCI session](../how-to/tune-apci.md),
architecture [apci-and-timers.md](../../architecture/apci-and-timers.md).

### TESTFR

The keep-alive: when a connection has been idle for `t3`, a station sends `TESTFR act`
and expects `TESTFR con`, proving the peer is alive.
*In this library:* `com.digitalpetri.iec60870.apci.UFunction.TESTFR_ACT` / `TESTFR_CON`,
driven automatically by `ApciSession`.
*See:* [Timers & window](timers-and-window.md),
architecture [apci-and-timers.md](../../architecture/apci-and-timers.md).

### Frame formats — I-format / S-format / U-format

The three APDU control-field shapes:

- **I-format** — numbered information transfer; carries N(S), N(R), and an ASDU.
- **S-format** — numbered supervisory acknowledgement; carries N(R) only, no ASDU.
- **U-format** — unnumbered control function (STARTDT/STOPDT/TESTFR); no ASDU.

*In this library:* sealed `com.digitalpetri.iec60870.apci.ControlField` with
`ControlField.TypeI`, `ControlField.TypeS`, and `ControlField.TypeU`; the U-format
function is a `UFunction`.
*See:* architecture [apci-and-timers.md](../../architecture/apci-and-timers.md).

## See also

- [Coverage matrix](coverage-matrix.md) — which TypeIDs this library models.
- [Choosing a point type](choosing-a-point-type.md) — map a real-world signal to a
  TypeID and point-model type.
- [Timers & window](timers-and-window.md) — deep reference for `t0`–`t3` and the `k`/`w`
  window when aligning two stacks.
- [Error model](errors.md) — typed exceptions vs. result objects, and what each
  operation throws or returns.
- [Getting Started](../getting-started.md) — the end-to-end mental model.
- [User guide index](../README.md) — the full table of contents.
- Architecture: [apci-and-timers.md](../../architecture/apci-and-timers.md),
  [overview.md](../../architecture/overview.md).
- [Project README](../../../README.md) and the
  [runnable examples](../../../iec60870-examples/README.md).
