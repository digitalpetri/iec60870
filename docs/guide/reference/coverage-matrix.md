# Reference: Coverage matrix

This page answers the first question every IEC 104 integrator asks: **do you support type
identification *X*, and if so, what do I call and what do I get?** The authoritative support flag
lives in `AsduType.supported()` and is mirrored from the architecture doc
[Protocol coverage](../../architecture/protocol-coverage.md), the as-built map of which
[type identifications (TypeIDs)](glossary.md) the library models. This page keeps that matrix and
adds the columns a *user* needs: the Java record, and — most usefully — **where the high-level
`Iec60870Client` / `Iec60870Server` facade actually surfaces each type**, which is not the same thing as
`supported()`.

In the direction column, **Mon** = [monitor direction](glossary.md) (controlled → controlling
station), **Ctrl** = [control direction](glossary.md) (controlling → controlled station), and
**Sys** = system information.

> **The one insight this page exists to convey:** `supported() == true` means a typed
> `com.digitalpetri.iec60870.asdu.object` record and a standard codec exist. It does **not** mean the
> high-level facade has a dedicated method for that type. Some supported types — parameter loading,
> delay acquisition, end-of-initialization, counter interrogation from the client side — are
> codec-enabled but reachable only through the **raw** send/receive surface. The **Facade surface**
> column below is where that distinction lives.

## How to read this page

The tables stay terse because every column is defined once here:

- **TypeID** — the numeric type identification (octet 1 of the ASDU); `AsduType.typeId()`.
- **Mnemonic** — the IEC 60870-5-101/104 short name, which is also the `AsduType` constant name.
- **Dir** — Mon / Ctrl / Sys, as above.
- **Modeled** — *yes* = a typed [information object](glossary.md) record plus a standard codec exist
  (`AsduType.supported() == true`); *no* = the type has an `AsduType` constant but no record or
  codec.
- **Record** — the `com.digitalpetri.iec60870.asdu.object` record class (and its nested `Serde`), when
  one exists.
- **Facade surface** — how you reach it through the high-level `Iec60870Client` / `Iec60870Server`, or
  "raw only at facade" when the facade has no dedicated entry point. **This is the column to read.**
- **See** — the how-to or reference page for that capability.

Two universal facts hold for every modeled type, so they are not repeated per row:

- Every modeled record lives in package `com.digitalpetri.iec60870.asdu.object` and has a nested
  `public static final class Serde`; the codec mapping is registered in `InformationObjectCodecs`.
  See [The two-layer API](../../architecture/two-layer-api.md) for how the raw record layer and the
  high-level facade relate.
- Any inbound, decodable ASDU is **always** also published raw as `ClientEvent.AsduReceived(Asdu)`
  on the client, and offered to `ServerHandler.onRawAsdu(...)` on the server — so even a type that is
  "raw only at the facade" is observable, never silently dropped. See
  [How-To: Work with raw ASDUs](../how-to/work-with-raw-asdus.md).

## Process information, monitor direction

These 32 types (TypeIDs 1–40, with gaps) report measured and status values from the controlled
station. The library models them with the **point/value model**, not as records you construct: the
server publishes a value with `Iec60870Server.publish(...)`, and the client observes it as a
`ClientEvent.PointUpdated` event or inside the `InterrogationResult` from `client.interrogate(...)`.
Which time-tag variant (untimed / CP24 / CP56) goes on the wire is selected by the server's
configured `TimeTagStyle`, not chosen per call.

### By logical point type

Eight of these monitor families map to a `PointType` constant — the value kind you pick when hosting
or reading a point. Each family has three [time-tag](glossary.md) variants that share one logical
value. This sub-table is what you select from; see
[Choosing a point type](choosing-a-point-type.md) for the deep companion.

| `PointType` | Java value (`valueClass()`) | Untimed | CP24Time2a | CP56Time2a |
|---|---|---|---|---|
| `SINGLE_POINT` | `Boolean` | M_SP_NA_1 (1) | M_SP_TA_1 (2) | M_SP_TB_1 (30) |
| `DOUBLE_POINT` | `DoublePointState` | M_DP_NA_1 (3) | M_DP_TA_1 (4) | M_DP_TB_1 (31) |
| `STEP_POSITION` | `Vti` | M_ST_NA_1 (5) | M_ST_TA_1 (6) | M_ST_TB_1 (32) |
| `BITSTRING32` | `Integer` | M_BO_NA_1 (7) | M_BO_TA_1 (8) | M_BO_TB_1 (33) |
| `NORMALIZED` | `NormalizedValue` | M_ME_NA_1 (9) | M_ME_TA_1 (10) | M_ME_TD_1 (34) |
| `SCALED` | `Short` | M_ME_NB_1 (11) | M_ME_TB_1 (12) | M_ME_TE_1 (35) |
| `SHORT_FLOAT` | `Float` | M_ME_NC_1 (13) | M_ME_TC_1 (14) | M_ME_TF_1 (36) |
| `INTEGRATED_TOTALS` | `BinaryCounterReading` | M_IT_NA_1 (15) | M_IT_TA_1 (16) | M_IT_TB_1 (37) |

`DoublePointState`, `Vti`, `NormalizedValue`, and `BinaryCounterReading` are in package
`com.digitalpetri.iec60870.asdu.element`.

The remaining monitor types — the protection-equipment and packed types (17, 18, 19, 20, 38, 39, 40)
and the no-quality normalized value (21) — have records and codecs but **no `PointType`**, so the
high-level point model does not host or report them. They are raw only at the facade: decodable and
surfaced as `ClientEvent.AsduReceived`, but not `publish`-able and not reported as `PointUpdated`.

### Full TypeID table (monitor)

For the 24 `PointType`-backed types the **Facade surface** is the same: publish with
`Iec60870Server.publish`, observe as `ClientEvent.PointUpdated` or inside `InterrogationResult`. The
table marks those "monitor point"; the rows that read "raw only at facade" are the
protection/packed/no-quality types above.

| TypeID | Mnemonic | Dir | Record | Facade surface |
|---|---|---|---|---|
| 1 | M_SP_NA_1 | Mon | `SinglePointInformation` | monitor point |
| 2 | M_SP_TA_1 | Mon | `SinglePointWithCp24Time` | monitor point |
| 3 | M_DP_NA_1 | Mon | `DoublePointInformation` | monitor point |
| 4 | M_DP_TA_1 | Mon | `DoublePointWithCp24Time` | monitor point |
| 5 | M_ST_NA_1 | Mon | `StepPositionInformation` | monitor point |
| 6 | M_ST_TA_1 | Mon | `StepPositionWithCp24Time` | monitor point |
| 7 | M_BO_NA_1 | Mon | `Bitstring32` | monitor point |
| 8 | M_BO_TA_1 | Mon | `Bitstring32WithCp24Time` | monitor point |
| 9 | M_ME_NA_1 | Mon | `MeasuredValueNormalized` | monitor point |
| 10 | M_ME_TA_1 | Mon | `MeasuredValueNormalizedWithCp24Time` | monitor point |
| 11 | M_ME_NB_1 | Mon | `MeasuredValueScaled` | monitor point |
| 12 | M_ME_TB_1 | Mon | `MeasuredValueScaledWithCp24Time` | monitor point |
| 13 | M_ME_NC_1 | Mon | `MeasuredValueShortFloat` | monitor point |
| 14 | M_ME_TC_1 | Mon | `MeasuredValueShortFloatWithCp24Time` | monitor point |
| 15 | M_IT_NA_1 | Mon | `IntegratedTotals` | monitor point |
| 16 | M_IT_TA_1 | Mon | `IntegratedTotalsWithCp24Time` | monitor point |
| 17 | M_EP_TA_1 | Mon | `EventOfProtectionEquipment` | raw only at facade |
| 18 | M_EP_TB_1 | Mon | `PackedStartEventsOfProtection` | raw only at facade |
| 19 | M_EP_TC_1 | Mon | `PackedOutputCircuitInfo` | raw only at facade |
| 20 | M_PS_NA_1 | Mon | `PackedSinglePointWithStatusChange` | raw only at facade |
| 21 | M_ME_ND_1 | Mon | `MeasuredValueNormalizedNoQuality` | raw only at facade |
| 30 | M_SP_TB_1 | Mon | `SinglePointWithCp56Time` | monitor point |
| 31 | M_DP_TB_1 | Mon | `DoublePointWithCp56Time` | monitor point |
| 32 | M_ST_TB_1 | Mon | `StepPositionWithCp56Time` | monitor point |
| 33 | M_BO_TB_1 | Mon | `Bitstring32WithCp56Time` | monitor point |
| 34 | M_ME_TD_1 | Mon | `MeasuredValueNormalizedWithCp56Time` | monitor point |
| 35 | M_ME_TE_1 | Mon | `MeasuredValueScaledWithCp56Time` | monitor point |
| 36 | M_ME_TF_1 | Mon | `MeasuredValueShortFloatWithCp56Time` | monitor point |
| 37 | M_IT_TB_1 | Mon | `IntegratedTotalsWithCp56Time` | monitor point |
| 38 | M_EP_TD_1 | Mon | `EventOfProtectionEquipmentWithCp56Time` | raw only at facade |
| 39 | M_EP_TE_1 | Mon | `PackedStartEventsOfProtectionWithCp56Time` | raw only at facade |
| 40 | M_EP_TF_1 | Mon | `PackedOutputCircuitInfoWithCp56Time` | raw only at facade |

All modeled; see [Host a server](../how-to/host-a-server.md) for publishing and
[Handle events](../how-to/handle-events.md) for receiving.

## Process information, control direction

These are the control commands. The facade surface for all 14 is the command service:
`client.commands().send(Command, CommandMode)` (with `sendAsync` and per-type convenience helpers
such as `commands().single(...)`), passing the matching `Command.*Request`. Whether the untimed or
the CP56Time2a twin goes on the wire is driven by `Command.time()`: a present time selects the
time-tagged TypeID (58–64), an absent time the untimed one (45–51). On the server, all 14 route to
`ServerHandler.onCommand(...)`, which returns a `CommandDecision`. See
[Send commands](../how-to/send-commands.md).

| TypeID | Mnemonic | Dir | Record | Facade surface |
|---|---|---|---|---|
| 45 | C_SC_NA_1 | Ctrl | `SingleCommand` | `CommandService.send` (`SingleCommandRequest`) / server `onCommand` |
| 46 | C_DC_NA_1 | Ctrl | `DoubleCommand` | `CommandService.send` (`DoubleCommandRequest`) / server `onCommand` |
| 47 | C_RC_NA_1 | Ctrl | `RegulatingStepCommand` | `CommandService.send` (`RegulatingStepCommandRequest`) / server `onCommand` |
| 48 | C_SE_NA_1 | Ctrl | `SetpointNormalized` | `CommandService.send` (`SetpointNormalizedRequest`) / server `onCommand` |
| 49 | C_SE_NB_1 | Ctrl | `SetpointScaled` | `CommandService.send` (`SetpointScaledRequest`) / server `onCommand` |
| 50 | C_SE_NC_1 | Ctrl | `SetpointShortFloat` | `CommandService.send` (`SetpointShortFloatRequest`) / server `onCommand` |
| 51 | C_BO_NA_1 | Ctrl | `Bitstring32Command` | `CommandService.send` (`BitstringCommandRequest`) / server `onCommand` |
| 58 | C_SC_TA_1 | Ctrl | `SingleCommandWithCp56Time` | `SingleCommandRequest` with `time()` / server `onCommand` |
| 59 | C_DC_TA_1 | Ctrl | `DoubleCommandWithCp56Time` | `DoubleCommandRequest` with `time()` / server `onCommand` |
| 60 | C_RC_TA_1 | Ctrl | `RegulatingStepCommandWithCp56Time` | `RegulatingStepCommandRequest` with `time()` / server `onCommand` |
| 61 | C_SE_TA_1 | Ctrl | `SetpointNormalizedWithCp56Time` | `SetpointNormalizedRequest` with `time()` / server `onCommand` |
| 62 | C_SE_TB_1 | Ctrl | `SetpointScaledWithCp56Time` | `SetpointScaledRequest` with `time()` / server `onCommand` |
| 63 | C_SE_TC_1 | Ctrl | `SetpointShortFloatWithCp56Time` | `SetpointShortFloatRequest` with `time()` / server `onCommand` |
| 64 | C_BO_TA_1 | Ctrl | `Bitstring32CommandWithCp56Time` | `BitstringCommandRequest` with `time()` / server `onCommand` |

The CP56Time2a twins (58–64) are produced by the same `Command.*Request` carrying a present
`time()`; you do not select the TypeID directly.

## System information

This is where the modeled-versus-facade distinction bites hardest, so the **Facade surface** column
is exact and per-row. Several of these types are modeled (record + codec) yet have no client facade
method — the server answers them automatically, or they are reachable only by raw send.

| TypeID | Mnemonic | Dir | Record | Facade surface |
|---|---|---|---|---|
| 70 | M_EI_NA_1 | Sys/Mon | `EndOfInitialization` | Modeled record; raw only at facade |
| 100 | C_IC_NA_1 | Ctrl | `InterrogationCommand` | `Iec60870Client.interrogate` / server answers ([`onInterrogation`](../how-to/host-a-server.md)) |
| 101 | C_CI_NA_1 | Ctrl | `CounterInterrogationCommand` | Server answers automatically; **client raw only** |
| 102 | C_RD_NA_1 | Ctrl | `ReadCommand` | `Iec60870Client.read` / `readAsync` / server answers (`onRead`) |
| 103 | C_CS_NA_1 | Ctrl | `ClockSynchronizationCommand` | `Iec60870Client.synchronizeClock` / server answers (`onClockSync`) |
| 104 | C_TS_NA_1 | Ctrl | `TestCommand` | Server answers automatically; **client raw only** |
| 105 | C_RP_NA_1 | Ctrl | `ResetProcessCommand` | Server answers (`onReset`); **client raw only** |
| 106 | C_CD_NA_1 | Ctrl | `DelayAcquisitionCommand` | Modeled record; raw only at facade |
| 107 | C_TS_TA_1 | Ctrl | `TestCommandWithCp56Time` | Server answers automatically; **client raw only** |

The client exposes facade methods for interrogation (`interrogate`), read (`read` / `readAsync`),
and clock sync (`synchronizeClock`) only. There is **no** client method for counter interrogation
(C_CI_NA_1), test (C_TS_NA_1 / C_TS_TA_1), or reset process (C_RP_NA_1): to drive those from the
controlling station, build the `Asdu` and call `client.send(...)` (see the snippet below). The server
side still understands all of them — its standard dispatch answers C_IC, C_CI, C_RD, C_CS, C_TS, and
C_RP — but `M_EI_NA_1` (70) and `C_CD_NA_1` (106) fall through to the raw hooks on both sides.

### Reaching a "raw only at facade" type from the client

Counter interrogation (C_CI_NA_1) has a typed record but no client facade method, so you send it
raw. The same pattern works for the other raw-only control types — construct the matching record (or,
for an unmodeled body, a raw header) and call `Iec60870Client.send(Asdu)`:

```java
import com.digitalpetri.iec60870.address.CommonAddress;
import com.digitalpetri.iec60870.address.InformationObjectAddress;
import com.digitalpetri.iec60870.address.OriginatorAddress;
import com.digitalpetri.iec60870.asdu.Asdu;
import com.digitalpetri.iec60870.asdu.AsduType;
import com.digitalpetri.iec60870.asdu.Cause;
import com.digitalpetri.iec60870.asdu.element.FreezeMode;
import com.digitalpetri.iec60870.asdu.element.QualifierOfCounterInterrogation;
import com.digitalpetri.iec60870.asdu.object.CounterInterrogationCommand;
import java.util.List;

// C_CI_NA_1 has a typed record but no client facade method — send it raw.
// RQT 5 is a general counter request; FreezeMode.READ reads without freezing or resetting.
Asdu counterInterrogation =
    new Asdu(
        AsduType.C_CI_NA_1,
        false, // SQ
        Cause.ACTIVATION,
        false, // P/N
        false, // test
        OriginatorAddress.none(),
        CommonAddress.of(1),
        List.of(
            new CounterInterrogationCommand(
                InformationObjectAddress.of(0),
                new QualifierOfCounterInterrogation(5, FreezeMode.READ))));

client.send(counterInterrogation);
```

The `Asdu` constructor field order — `type, sequence, cause, negative, test, originatorAddress,
commonAddress, objects` — matches the [`RawAsduExample`](../../../iec60870-examples/src/main/java/com/digitalpetri/iec60870/examples/RawAsduExample.java)
in the examples module. The IOA is `0` for counter interrogation. Use the [common address (CA)](glossary.md)
of the target station. See [How-To: Work with raw ASDUs](../how-to/work-with-raw-asdus.md) and
[How-To: Connect & interrogate](../how-to/connect-and-interrogate.md).

## Parameter, control direction

The four parameter-loading types are modeled (record + codec) but **raw only at the facade**: there
is no client method, and on the server they fall through to the raw hook (`onRawAsdu`, otherwise
`handleUnknown`). Drive them from raw send/receive — see
[How-To: Work with raw ASDUs](../how-to/work-with-raw-asdus.md).

| TypeID | Mnemonic | Dir | Record | Facade surface |
|---|---|---|---|---|
| 110 | P_ME_NA_1 | Ctrl | `ParameterNormalized` | Modeled record; raw only at facade |
| 111 | P_ME_NB_1 | Ctrl | `ParameterScaled` | Modeled record; raw only at facade |
| 112 | P_ME_NC_1 | Ctrl | `ParameterShortFloat` | Modeled record; raw only at facade |
| 113 | P_AC_NA_1 | Ctrl | `ParameterActivation` | Modeled record; raw only at facade |

## Out of scope — file transfer (raw-layer only)

The file-transfer `F_*` types have `AsduType` constants with `supported() == false`. They have **no
record and no codec entry**.

| TypeID | Mnemonic | Dir | Record | Facade surface |
|---|---|---|---|---|
| 120 | F_FR_NA_1 | Ctrl | — (none) | Out of scope — raw header only |
| 121 | F_SR_NA_1 | Ctrl | — (none) | Out of scope — raw header only |
| 122 | F_SC_NA_1 | Ctrl | — (none) | Out of scope — raw header only |
| 123 | F_LS_NA_1 | Ctrl | — (none) | Out of scope — raw header only |
| 124 | F_AF_NA_1 | Ctrl | — (none) | Out of scope — raw header only |
| 125 | F_SG_NA_1 | Ctrl | — (none) | Out of scope — raw header only |
| 126 | F_DR_TA_1 | Mon | — (none) | Out of scope — raw header only |
| 127 | F_SC_NB_1 | Ctrl | — (none) | Out of scope — raw header only |

**Why they are out of scope, and the consequence.** File transfer in IEC 60870-5 is a *stateful,
multi-ASDU procedure* (select file, call sections, stream segments, acknowledge, detect the last
segment), not a stateless record, so the library leaves it present-but-unmodeled rather than ship a
partial state machine. The consequence: a file-transfer `Asdu` carries its raw header (TypeID, COT,
CA, IOA) fine, but its **element body does not decode** — `InformationObjectCodecs.codecFor(F_*)`
throws `UnsupportedAsduTypeException`, because no codec is registered. To use file transfer you parse
and produce the `F_*` object bodies yourself and drive the procedure from the raw send/receive hooks.
See [How-To: Work with raw ASDUs](../how-to/work-with-raw-asdus.md), the architecture doc
[Protocol coverage](../../architecture/protocol-coverage.md), and
[Errors and extensibility](../../architecture/errors-and-extensibility.md).

## Private-range TypeIDs

Type identifications in the private range (the standard reserves `128..255`, with `136..255`
designated for private use) have **no `AsduType` constant at all**. `AsduType.fromId(int)` rejects an
inbound private TypeID with `UnsupportedAsduTypeException`, and there is no constant to construct one
for sending — so private TypeIDs cannot be exchanged through the `Asdu` model. This is a hard
boundary, distinct from the file-transfer case above (which at least has a constant and a raw
header). See [Protocol coverage](../../architecture/protocol-coverage.md).

## Causes of transmission (COT) — note

The [cause of transmission (COT)](glossary.md) field is modeled by the
`com.digitalpetri.iec60870.asdu.Cause` enum, which covers the standard COT range. Private COT values
(`48..63`) are not modeled and are rejected on decode: `Cause.fromValue(int)` throws
`AsduDecodeException` for a private, reserved, or undefined value. For the COT concept see the
[glossary](glossary.md); for what a bad COT raises see the [error model](errors.md).

## Observing any decodable inbound type raw

Even a type that is raw only at the facade is delivered to the client — every decodable inbound ASDU
is published as `ClientEvent.AsduReceived(asdu)` alongside any `PointUpdated` events:

```java
client.events().subscribe(new Flow.Subscriber<ClientEvent>() {
  // ... onSubscribe requests items ...
  @Override
  public void onNext(ClientEvent event) {
    if (event instanceof ClientEvent.AsduReceived received) {
      Asdu asdu = received.asdu(); // every decodable inbound ASDU, including raw-only types
      // inspect asdu.type(), asdu.objects() yourself
    }
  }
  // ... onError / onComplete ...
});
```

See [How-To: Handle events](../how-to/handle-events.md) for the full subscriber pattern and the
threading rules. On the server, the mirror hook is `ServerHandler.onRawAsdu(...)`.

## See also

- [Reference: Choosing a point type](choosing-a-point-type.md) — which monitor/command type maps to
  a real-world signal; the deep companion to the point-type sub-table.
- [Reference: Glossary](glossary.md) — Mon/Ctrl/Sys directions, COT, CA, IOA, TypeID, and the
  mnemonics.
- [Reference: Error model](errors.md) — `UnsupportedAsduTypeException`, `AsduDecodeException`, and
  what a raw-only or file-transfer type raises.
- [How-To: Connect & interrogate](../how-to/connect-and-interrogate.md) — C_IC, C_CI, and C_RD at the
  facade.
- [How-To: Send commands](../how-to/send-commands.md) — the 45–51 / 58–64 command surface.
- [How-To: Host a server](../how-to/host-a-server.md) — publishing monitor points and handling
  commands.
- [How-To: Handle events](../how-to/handle-events.md) — `ClientEvent.AsduReceived` and threading.
- [How-To: Work with raw ASDUs](../how-to/work-with-raw-asdus.md) — the escape hatch for every
  raw-only, file-transfer, or private type.
- [Getting Started](../getting-started.md) — the mental model behind the two-layer columns.
- [User Guide index](../README.md).
- Architecture: [Protocol coverage](../../architecture/protocol-coverage.md) — the authoritative
  as-built map; [The two-layer API](../../architecture/two-layer-api.md);
  [Errors and extensibility](../../architecture/errors-and-extensibility.md).
- Examples: [`RawAsduExample`](../../../iec60870-examples/src/main/java/com/digitalpetri/iec60870/examples/RawAsduExample.java)
  and the [examples README](../../../iec60870-examples/README.md).
