# Glossary

IEC 60870-5 vocabulary — spanning both the 101 (serial/FT1.2) and 104 (TCP/APCI)
profiles and the ASDU application layer they share — mapped to this library's Java
types. Each entry gives a short plain-English definition and the concrete
`com.digitalpetri.iec60870.*` type you'll actually use, then links to the guide page
where the term is used in anger. For the deep mechanics behind a term, follow the
architecture links.

One framing underpins half the entries: in IEC 60870-5 a **controlling station**
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
via `com.digitalpetri.iec60870.tcp.TcpIec104Client`.
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

### ACD / DFC

**Access-demand bit (ACD) / data-flow-control bit (DFC)** — the two status bits a secondary station
sets in its [FT1.2](#ft12) response control field on an [unbalanced](#balanced--unbalanced-transmission)
link. ACD tells the master the secondary has class-1 (high-priority) data waiting, prompting it to
drain that data; DFC tells the master the secondary's buffers are full and it should hold off. They
occupy the same two control-octet bit positions a primary uses for [FCB / FCV](#fcb--fcv).
*In this library:* `com.digitalpetri.iec60870.cs101.LinkControlField` — `acd()` and `dfc()` on a
secondary (PRM `false`) frame, aliasing the `fcbOrAcd` / `fcvOrDfc` bits; consumed by the unbalanced
master engine.
*See:* [Link layer](link-layer.md),
[Connect over serial](../how-to/connect-over-serial.md).

### APCI

**Application Protocol Control Information** — the fixed frame header (start octet `0x68`,
length, four control octets) that layers connection start/stop, keep-alive, and a
sliding-window acknowledgement scheme on top of TCP.
*In this library:* package `com.digitalpetri.iec60870.cs104` — `ApciSession` (the engine),
`Apdu`, `ControlField`, `UFunction`; tuned via `ApciSettings`.
*See:* [Tune the APCI session](../how-to/tune-apci.md),
[Timers & window](timers-and-window.md),
architecture: [apci-and-timers.md](../../architecture/apci-and-timers.md).

### APDU

**Application Protocol Data Unit** — one complete IEC 104 frame on the wire: an APCI
header, plus an ASDU body **only** for I-format frames.
*In this library:* `com.digitalpetri.iec60870.cs104.Apdu` (with `Apdu.Serde` for the wire
form).
*See:* architecture [apci-and-timers.md](../../architecture/apci-and-timers.md).

### ASDU

**Application Service Data Unit** — the application payload carried inside an I-format
APDU: a TypeID, a cause of transmission, addressing, and one or more information objects.
*In this library:* `com.digitalpetri.iec60870.asdu.Asdu` (a record), with the `AsduType`
and `Cause` enums and the `InformationObject` type (its per-TypeID implementations in
`asdu.object` are records).
*See:* [Work with raw ASDUs](../how-to/work-with-raw-asdus.md),
[Coverage matrix](coverage-matrix.md).

### Balanced / Unbalanced transmission

The two [FT1.2](#ft12) transmission procedures. **Balanced** is a symmetric point-to-point link
between two combined stations on a full-duplex line where either may initiate a transfer;
**unbalanced** is an asymmetric master/secondary bus where one primary polls one or more secondaries
that never initiate. The two procedures are not interoperable with each other.
*In this library:* enum `com.digitalpetri.iec60870.cs101.LinkMode` (`BALANCED` / `UNBALANCED`),
chosen by `LinkSettings.balanced()` / `LinkSettings.unbalanced()`; the unbalanced master's poll list
and cadence live in `LinkSettings.PollConfig`.
*See:* [Link layer](link-layer.md),
[Connect over serial](../how-to/connect-over-serial.md).

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

### FCB / FCV

**Frame count bit (FCB) / frame count valid bit (FCV)** — the [FT1.2](#ft12) anti-duplication pair a
primary station sets in its link control field. When FCV is set the frame is counted and the FCB
**alternates** `0`/`1` on each such transaction; an unchanged FCB marks a retransmission, so the
secondary replays its cached response rather than re-delivering the data. This one-bit alternation is
FT1.2's [stop-and-wait](#stop-and-wait) equivalent of the 104 sequence numbers.
*In this library:* `com.digitalpetri.iec60870.cs101.LinkControlField` — `fcb()` and `fcv()` on a
primary (PRM `true`) frame; the alternation is driven by the FT1.2 link engine.
*See:* [Link layer](link-layer.md), [Stop-and-wait](#stop-and-wait).

### FT1.2

The format class **FT 1.2** of IEC 60870-5-1: the byte-oriented link layer of IEC 60870-5-101. It
defines three frame shapes — a variable-length frame carrying one [ASDU](#asdu), a fixed-length frame
carrying link control and the [link address](#link-address) only, and the single character `0xE5` as
a compact positive acknowledgement — plus a one-octet link control field and an arithmetic checksum.
It replaces the 104 [APCI](#apci) under the shared ASDU application layer.
*In this library:* package `com.digitalpetri.iec60870.cs101` — `Ft12LinkLayer` (the link engine, the
serial peer of `ApciSession`), `Ft12Frame`, `LinkControlField`, `Ft12Framer`; tuned via
`LinkSettings`.
*See:* [Link layer](link-layer.md),
[Connect over serial](../how-to/connect-over-serial.md).

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
I-frames after which it must send an acknowledgement. `w` must not exceed `k`. Contrast the serial
sibling IEC 60870-5-101, whose [FT1.2](#ft12) link layer has **no window**: it is strict
[stop-and-wait](#stop-and-wait) with a single outstanding frame.
*In this library:* `com.digitalpetri.iec60870.cs104.ApciSettings` components `k()` and `w()`;
defaults `k = 12`, `w = 8` (`ApciSettings.defaults()`).
*See:* [Tune the APCI session](../how-to/tune-apci.md),
[Timers & window](timers-and-window.md),
[Stop-and-wait](#stop-and-wait) and [Link layer](link-layer.md) for the 101 contrast,
architecture [apci-and-timers.md](../../architecture/apci-and-timers.md).

### Link Address

The [FT1.2](#ft12) address of a station **on the link**, distinct from the ASDU
[Common Address](#common-address-ca) that addresses a station/sector inside the application layer.
Carried in `0`, `1`, or `2` octets; a zero-octet (absent) address is
[balanced](#balanced--unbalanced-transmission)-only, and unbalanced links always carry one.
*In this library:* `com.digitalpetri.iec60870.cs101.LinkSettings` components `linkAddress()` and
`linkAddressLength()` (with `broadcastAddress()` for the unbalanced all-secondaries address); set via
the `LinkSettings.balanced()` / `unbalanced()` builders.
*See:* [Link layer](link-layer.md),
[Connect over serial](../how-to/connect-over-serial.md).

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
are `com.digitalpetri.iec60870.cs104.UFunction.STARTDT_ACT` / `STARTDT_CON` and
`STOPDT_ACT` / `STOPDT_CON`.
*See:* [Connect & interrogate](../how-to/connect-and-interrogate.md),
architecture [apci-and-timers.md](../../architecture/apci-and-timers.md).

### Stop-and-wait

The [FT1.2](#ft12) flow-control discipline: a primary may have **exactly one** unacknowledged frame
outstanding and must receive the secondary's acknowledgement before sending the next — in contrast to
the 104 [k / w window](#k--w-window)'s up-to-`k` outstanding I-frames. Lost-frame recovery rides on
the alternating [FCB](#fcb--fcv), and retransmission is bounded by the confirm/repeat timers and the
retry count.
*In this library:* implemented by `Ft12LinkLayer`; tuned by `com.digitalpetri.iec60870.cs101.LinkSettings`
components `confirmTimeout()`, `repeatTimeout()`, `maxRetries()`, and `linkStateTimeout()`.
*See:* [Link layer](link-layer.md), [FCB / FCV](#fcb--fcv),
[Connect over serial](../how-to/connect-over-serial.md).

### t0 / t1 / t2 / t3

The four APCI timeouts:

- **t0** — connection-establishment timeout.
- **t1** — timeout awaiting acknowledgement of a sent I/U frame. Its expiry is fatal to
  the connection.
- **t2** — maximum delay before acknowledging received I-frames (should be `< t1`).
- **t3** — idle period after which a `TESTFR` is sent.

*In this library:* `com.digitalpetri.iec60870.cs104.ApciSettings` components `t0()`–`t3()`
(`Duration`); defaults t0 = 30 s, t1 = 15 s, t2 = 10 s, t3 = 20 s
(`ApciSettings.defaults()`).
*See:* [Timers & window](timers-and-window.md),
[Tune the APCI session](../how-to/tune-apci.md),
architecture [apci-and-timers.md](../../architecture/apci-and-timers.md).

### TESTFR

The keep-alive: when a connection has been idle for `t3`, a station sends `TESTFR act`
and expects `TESTFR con`, proving the peer is alive.
*In this library:* `com.digitalpetri.iec60870.cs104.UFunction.TESTFR_ACT` / `TESTFR_CON`,
driven automatically by `ApciSession`.
*See:* [Timers & window](timers-and-window.md),
architecture [apci-and-timers.md](../../architecture/apci-and-timers.md).

### Frame formats — I-format / S-format / U-format

The three APDU control-field shapes:

- **I-format** — numbered information transfer; carries N(S), N(R), and an ASDU.
- **S-format** — numbered supervisory acknowledgement; carries N(R) only, no ASDU.
- **U-format** — unnumbered control function (STARTDT/STOPDT/TESTFR); no ASDU.

*In this library:* sealed `com.digitalpetri.iec60870.cs104.ControlField` with
`ControlField.TypeI`, `ControlField.TypeS`, and `ControlField.TypeU`; the U-format
function is a `UFunction`.
*See:* architecture [apci-and-timers.md](../../architecture/apci-and-timers.md).

## See also

- [Coverage matrix](coverage-matrix.md) — which TypeIDs this library models.
- [Choosing a point type](choosing-a-point-type.md) — map a real-world signal to a
  TypeID and point-model type.
- [Timers & window](timers-and-window.md) — deep reference for `t0`–`t3` and the `k`/`w`
  window when aligning two stacks.
- [Link layer](link-layer.md) — the IEC 60870-5-101 FT1.2 link layer: link mode, link address,
  FCB stop-and-wait, and the link timers.
- [Error model](errors.md) — typed exceptions vs. result objects, and what each
  operation throws or returns.
- [Getting Started](../getting-started.md) — the end-to-end mental model.
- [User guide index](../README.md) — the full table of contents.
- Architecture: [apci-and-timers.md](../../architecture/apci-and-timers.md),
  [overview.md](../../architecture/overview.md).
- [Project README](../../../README.md) and the
  [runnable examples](../../../iec60870-examples/README.md).
