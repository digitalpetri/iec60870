# Choosing a point type

You have a signal in the field — a breaker status, a transformer tap, a kWh meter, a setpoint
register — and you need to know exactly which Java type to instantiate. This page is the translation
layer between "the thing in the field" and "the constructor I call." It is split by direction: the
[monitor direction](glossary.md) (values a controlled station *reports*) and the
[control direction](glossary.md) (commands a controlling station *issues*).

This is a lookup page, not a procedure. It tells you which type to create and stops; the how-to
pages own the surrounding mechanics ([host a server](../how-to/host-a-server.md),
[send commands](../how-to/send-commands.md)). For the full list of supported
[type identifications](glossary.md) — including the protection, packed-event, and system types this
page intentionally omits — see the [coverage matrix](coverage-matrix.md).

## How to read this page

Three facts make the tables below make sense:

1. **You choose a *logical* point type, not a raw [TypeID](glossary.md).** On a server you declare a
   `PointType` (for example `SINGLE_POINT`); on a client you read `PointValue.type()`. The raw
   TypeID (`M_SP_NA_1` = 1, `M_SP_TB_1` = 30, …) is what goes on the wire, and several TypeIDs map
   to one `PointType` — they differ only by [time tag](glossary.md). There are exactly eight
   monitor `PointType` constants.

2. **The time-tag variant is chosen for you, not picked by hand.**
   - *Server (monitor direction):* the server's configured `TimeTagStyle` (`NONE` / `CP24` / `CP56`,
     default **`CP56`**) decides whether a published value goes out as the untimed, CP24, or CP56
     record. You set it once in configuration; you never name `M_SP_TB_1` yourself. See
     [host a server](../how-to/host-a-server.md) and [tune the APCI session](../how-to/tune-apci.md).
   - *Client (control direction):* a `Command` whose `time()` is present is sent as the time-tagged
     TypeID (for example `C_SC_TA_1`); absent → the untimed TypeID (`C_SC_NA_1`). The
     `CommandService` picks the variant.

3. **[Quality](glossary.md) is a separate, uniform type.** Every monitor value carries a `Quality`
   (the five IEC flags: `overflow`, `blocked`, `substituted`, `notTopical`, `invalid`) regardless of
   point type — the uniform `Quality` column below is intentional, not a copy-paste slip. Commands
   do not carry quality; they carry a [qualifier](glossary.md) instead.

For the deeper "logical `PointType` vs. raw `AsduType`/records" split, see the
[two-layer API](../../architecture/two-layer-api.md) architecture document.

## Monitor direction — signals reported by the controlled station

These are values a server hosts and publishes, and a client reads from
[`PointUpdated`](../how-to/handle-events.md) events and
[interrogation](../how-to/connect-and-interrogate.md) results. Each row is one monitor `PointType`,
its three wire variants, the Java value type it carries, the `PointValue` factory that builds it,
and its quality type.

| Real-world signal | TypeID(s) — untimed / CP24 / CP56 | `PointType` | Value type `T` | `PointValue` factory | Quality |
|---|---|---|---|---|---|
| On/off status (breaker open/closed, alarm set/clear) | `M_SP_NA_1` (1) / `M_SP_TA_1` (2) / `M_SP_TB_1` (30) | `SINGLE_POINT` | `Boolean` | `PointValue.single(boolean)` | `Quality` |
| Double-point status (open / closed / intermediate / faulty) | `M_DP_NA_1` (3) / `M_DP_TA_1` (4) / `M_DP_TB_1` (31) | `DOUBLE_POINT` | `DoublePointState` | `PointValue.doublePoint(DoublePointState)` | `Quality` |
| Step / regulator position (transformer tap, with transient flag) | `M_ST_NA_1` (5) / `M_ST_TA_1` (6) / `M_ST_TB_1` (32) | `STEP_POSITION` | `Vti` | `PointValue.stepPosition(Vti)` | `Quality` |
| 32-bit bit string (packed digital I/O) | `M_BO_NA_1` (7) / `M_BO_TA_1` (8) / `M_BO_TB_1` (33) | `BITSTRING32` | `Integer` | `PointValue.bitstring(int)` | `Quality` |
| Analog measurement, normalized (fraction of range) | `M_ME_NA_1` (9) / `M_ME_TA_1` (10) / `M_ME_TD_1` (34) | `NORMALIZED` | `NormalizedValue` | `PointValue.normalized(NormalizedValue)` | `Quality` |
| Analog measurement, scaled (engineering integer) | `M_ME_NB_1` (11) / `M_ME_TB_1` (12) / `M_ME_TE_1` (35) | `SCALED` | `Short` | `PointValue.scaled(short)` | `Quality` |
| Analog measurement, short float (IEEE 754) | `M_ME_NC_1` (13) / `M_ME_TC_1` (14) / `M_ME_TF_1` (36) | `SHORT_FLOAT` | `Float` | `PointValue.shortFloat(float)` | `Quality` |
| Counter / integrated total (energy meter, kWh) | `M_IT_NA_1` (15) / `M_IT_TA_1` (16) / `M_IT_TB_1` (37) | `INTEGRATED_TOTALS` | `BinaryCounterReading` | `PointValue.counter(BinaryCounterReading)` | `Quality` + the reading's own `invalid` / `carry` / `adjusted` bits |

- **Time-tag column:** you do not pick a variant per value. The server's `TimeTagStyle` (default
  `CP56`) selects the untimed / CP24 / CP56 record when the value is published — see
  [host a server](../how-to/host-a-server.md) and [tune the APCI session](../how-to/tune-apci.md).
- **`M_ME_ND_1` (21):** a normalized measured value carried *without* a quality descriptor. The
  library decodes it into the same `NORMALIZED` point type (substituting good quality) on receipt,
  but there is no factory to publish it — it appears only on the extraction side, not as a row you
  instantiate.
- **Out of scope here:** packed-event and protection types (`M_EP_*`, `M_PS_NA_1`) and
  end-of-initialization (`M_EI_NA_1`) are supported as raw records but are not part of the
  high-level point model. See the [coverage matrix](coverage-matrix.md) and
  [work with raw ASDUs](../how-to/work-with-raw-asdus.md).

### Value types in detail (monitor)

Most value types are obvious from the column (`Boolean`, `Integer`, `Short`, `Float`). A few are
not:

- **Step position → `Vti`** — a *value with transient state indication*, not a plain int:
  `new Vti(int value, boolean transientState)` where `value` is the signed 7-bit position
  (`-64..63`, range-checked) and the flag marks equipment mid-transition.
- **Counter → `BinaryCounterReading`** — `new BinaryCounterReading(int value, int sequenceNumber,
  boolean carry, boolean adjusted, boolean invalid)`: a signed 32-bit reading, a 5-bit sequence
  number (`0..31`), and three status bits. Its `invalid` bit is the counter's *own*, separate from
  the surrounding `Quality`.
- **Normalized → `NormalizedValue`** — a signed fixed-point fraction in `[-1, 1 - 2^-15]`, not a raw
  short. Build it from a fraction with `NormalizedValue.of(double)` and read it back with
  `.doubleValue()`; `rawValue()` exposes the on-the-wire 16-bit integer.
- **Double-point → `DoublePointState`** — an enum modeling a 2-bit status (a breaker's
  open/closed/faulty), *not* two separate points. Two states are determined (`ON`, `OFF`) and two
  are indeterminate (`INDETERMINATE_OR_INTERMEDIATE`, `INDETERMINATE`).

## Control direction — commands the controlling station issues

These are commands a client builds with `client.commands()` and a server answers in
`ServerHandler.onCommand`. Each row is one command `PointType`, its untimed and CP56 wire variants,
the `Command` record, the direct-execute helper on `CommandService`, the value you supply, and the
command qualifier range. (There is no CP24 form for commands; the only time-tagged command variants
are CP56, TypeIDs 58–64.)

| Real-world command | TypeID — untimed / CP56 | `Command` record | `commands()` helper (direct execute) | Value type | Qualifier |
|---|---|---|---|---|---|
| On/off command (single) | `C_SC_NA_1` (45) / `C_SC_TA_1` (58) | `Command.SingleCommandRequest` | `single(PointAddress, boolean)` | `boolean` | `0..31` |
| On/off command (double) | `C_DC_NA_1` (46) / `C_DC_TA_1` (59) | `Command.DoubleCommandRequest` | `doublePoint(PointAddress, DoubleCommandState)` | `DoubleCommandState` | `0..31` |
| Regulating-step command (raise/lower a regulator) | `C_RC_NA_1` (47) / `C_RC_TA_1` (60) | `Command.RegulatingStepCommandRequest` | `regulatingStep(PointAddress, StepCommandState)` | `StepCommandState` | `0..31` |
| Setpoint, normalized | `C_SE_NA_1` (48) / `C_SE_TA_1` (61) | `Command.SetpointNormalizedRequest` | `setpointNormalized(PointAddress, NormalizedValue)` | `NormalizedValue` | `0..127` |
| Setpoint, scaled | `C_SE_NB_1` (49) / `C_SE_TB_1` (62) | `Command.SetpointScaledRequest` | `setpointScaled(PointAddress, short)` | `short` | `0..127` |
| Setpoint, short float | `C_SE_NC_1` (50) / `C_SE_TC_1` (63) | `Command.SetpointShortFloatRequest` | `setpointShortFloat(PointAddress, float)` | `float` | `0..127` |
| Bitstring command (32-bit) | `C_BO_NA_1` (51) / `C_BO_TA_1` (64) | `Command.BitstringCommandRequest` | `bitstring(PointAddress, int)` | `int` | always `0` |

- **Time-tag column:** an absent `Instant` → untimed; a present one → the CP56 form. Set it by
  building the `Command` record with `Optional.of(instant)` for its `time()` and calling
  `commands().send(cmd, mode)`. See [send commands](../how-to/send-commands.md) for the full
  procedure.
- **Helpers are direct-execute only.** For [select-before-operate](glossary.md), build a `Command`
  and call `commands().send(cmd, CommandMode.selectBeforeOperate())` — covered in
  [send commands](../how-to/send-commands.md).
- **System / parameter commands** (interrogation `C_IC_NA_1`, clock sync `C_CS_NA_1`, counter
  interrogation `C_CI_NA_1`, the `P_*` parameter loading commands) are not point commands. They have
  dedicated client methods or are raw-only — see
  [connect & interrogate](../how-to/connect-and-interrogate.md) and the
  [coverage matrix](coverage-matrix.md).

### Value types in detail (control)

- **On/off command → `boolean`** (`single`) or **`DoubleCommandState`** (`doublePoint`). A double
  command carries `OFF` or `ON`; the encodings `NOT_PERMITTED0` and `NOT_PERMITTED3` exist only so
  the exact wire value round-trips.
- **Regulating-step command → `StepCommandState`** — `NEXT_STEP_LOWER` or `NEXT_STEP_HIGHER` (plus
  the round-trip-only `INVALID0` / `INVALID3`). This is "nudge the regulator one step," distinct
  from the step-*position* monitor value above.
- **Setpoint commands** come in three flavors matching the three measured-value encodings:
  `setpointNormalized(NormalizedValue)`, `setpointScaled(short)`, `setpointShortFloat(float)`. Pick
  the one whose encoding matches the device's setpoint register.
- **Bitstring command → `int`** (32 bits). Its `qualifier()` is always `0`.

The command qualifier and select-vs-execute semantics live on
[send commands](../how-to/send-commands.md), not here.

## A worked mapping (the example station)

The runnable [`ServerExample`](../../../iec104-examples/src/main/java/com/digitalpetri/iec104/examples/ServerExample.java)
hosts, in one `Station.builder(...)` chain, exactly one point of each of the eight monitor types
plus one commandable single point — it is the executable companion to the monitor table above. The
[`ClientExample`](../../../iec104-examples/src/main/java/com/digitalpetri/iec104/examples/ClientExample.java)
connects to it and issues a single command against the commandable point. Read those two files when
you want a "one of every type" reference you can run.

### Declaring a point of a chosen type (server)

A row in the monitor table becomes a `PointDefinition`. This is the exact idiom from
`ServerExample`:

```java
import com.digitalpetri.iec104.address.PointAddress;
import com.digitalpetri.iec104.point.PointCapability;
import com.digitalpetri.iec104.point.PointType;
import com.digitalpetri.iec104.point.PointValue;
import com.digitalpetri.iec104.server.PointDefinition;

// An on/off status point at common address 1, IOA 100, reported in the monitor direction.
PointDefinition<Boolean> status =
    PointDefinition.of(
        PointAddress.of(1, 100),
        PointType.SINGLE_POINT,
        PointValue.single(false), // initial value, good quality
        PointCapability.REPORTED);
```

Swap `PointType.SINGLE_POINT` / `PointValue.single(false)` for any row in the monitor table to host
a different signal type. The `ServerExample` chain shows all eight side by side:

```java
.point(PointDefinition.of(SINGLE, PointType.SINGLE_POINT,
    PointValue.single(false), PointCapability.REPORTED))
.point(PointDefinition.of(DOUBLE, PointType.DOUBLE_POINT,
    PointValue.doublePoint(DoublePointState.OFF), PointCapability.REPORTED))
.point(PointDefinition.of(STEP, PointType.STEP_POSITION,
    PointValue.stepPosition(new Vti(0, false)), PointCapability.REPORTED))
.point(PointDefinition.of(BITSTRING, PointType.BITSTRING32,
    PointValue.bitstring(0), PointCapability.REPORTED))
.point(PointDefinition.of(NORMALIZED, PointType.NORMALIZED,
    PointValue.normalized(NormalizedValue.of(0.0)), PointCapability.REPORTED))
.point(PointDefinition.of(SCALED, PointType.SCALED,
    PointValue.scaled((short) 0), PointCapability.REPORTED))
.point(PointDefinition.of(SHORT_FLOAT, PointType.SHORT_FLOAT,
    PointValue.shortFloat(0f), PointCapability.REPORTED))
.point(PointDefinition.of(COUNTER, PointType.INTEGRATED_TOTALS,
    PointValue.counter(new BinaryCounterReading(0, 0, false, false, false)),
    PointCapability.READABLE))
```

The surrounding `Station` builder, capabilities, and interrogation groups belong to
[host a server](../how-to/host-a-server.md).

### Building a command for a chosen type (client)

A row in the control table becomes a single helper call. The type you chose *is* the helper you
invoke:

```java
import com.digitalpetri.iec104.address.PointAddress;
import com.digitalpetri.iec104.client.CommandResult;

PointAddress target = PointAddress.of(1, 300);
// Direct-execute single (on/off) command; the helper sends C_SC_NA_1 (untimed).
CommandResult result = client.commands().single(target, true);
boolean accepted = result.positive(); // false = station returned a negative confirmation
// Time-tagged or select-before-operate variants: see the send-commands how-to.
```

A station's negative confirmation is a non-positive `CommandResult`, not an exception; only
transport or session failures throw. The full command lifecycle (ACT_CON / ACT_TERM,
select-before-operate, qualifier semantics) lives on [send commands](../how-to/send-commands.md).

### Reading a value back (client)

A received monitor value arrives as a typed `PointValue<?>` whose `type()` is the `PointType` from
the monitor table and whose `value()` is the matching `T`:

```java
// inside Flow.Subscriber<ClientEvent>#onNext, given a ClientEvent.PointUpdated event:
PointValue<?> value = updated.value();
PointType type = value.type(); // e.g. SINGLE_POINT — the row in the monitor table
Object raw = value.value();    // e.g. Boolean; cast per the value-type column
```

Interrogation results expose the same pair through `InterrogationResult.PointEntry`
(`entry.value().type()` and `entry.value().value()`). The subscription mechanics — when events
fire and the threading rules — live on [handle events](../how-to/handle-events.md). There is no
counter-specific client method: frozen counter values arrive spontaneously, or through a counter
interrogation (`C_CI_NA_1`) issued via the raw `send(Asdu)` escape hatch — see
[connect & interrogate](../how-to/connect-and-interrogate.md). The generic `read(PointAddress)`
(a `C_RD_NA_1` read request) can also target a readable counter point.

## See also

- [Coverage matrix](coverage-matrix.md) — the full TypeID list, including the non-point types this
  page omits.
- [Glossary](glossary.md) — IEC 104 vocabulary (monitor/control direction, TypeID, time tag,
  quality, IOA, common address, qualifier) mapped to our Java types.
- [Send commands](../how-to/send-commands.md) — the procedure behind the control table: direct
  execute vs. select-before-operate, timed variants, and the command lifecycle.
- [Host a server](../how-to/host-a-server.md) — the procedure behind the monitor table: stations,
  capabilities, interrogation groups, and `TimeTagStyle`.
- [Connect & interrogate](../how-to/connect-and-interrogate.md) — reading monitor values back and
  running interrogation.
- [Handle events](../how-to/handle-events.md) — the listener surface and threading rules behind the
  read-back snippet.
- [Two-layer API](../../architecture/two-layer-api.md) — the logical `PointType` vs. raw
  `AsduType`/records split, in depth.
- [`ServerExample`](../../../iec104-examples/src/main/java/com/digitalpetri/iec104/examples/ServerExample.java)
  and
  [`ClientExample`](../../../iec104-examples/src/main/java/com/digitalpetri/iec104/examples/ClientExample.java)
  — the runnable companions to the tables.
