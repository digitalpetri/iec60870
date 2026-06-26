# Protocol Coverage

This is the authoritative map of which ASDU type identifications the library models, and how. Every
type identification in the IEC 60870-5-101 tables and the IEC 60870-5-104 control-direction-with-time
extension has a constant in `com.digitalpetri.iec60870.asdu.AsduType`. A constant's `supported()` flag
reports whether the library provides a typed information object record (a record in
`com.digitalpetri.iec60870.asdu.object` implementing `InformationObject`):

- `supported() == true` — a typed record exists; the standard codec encodes/decodes it, and the
  high-level facade can work with it directly.
- `supported() == false` — no typed record exists, but the type is **not** dropped. It still has an
  `AsduType` constant and remains representable as a raw `Asdu`, reachable through the raw
  send/receive surface (see [errors-and-extensibility.md](errors-and-extensibility.md)).

`AsduType.fromId(int)` resolves an inbound type identification and throws
`UnsupportedAsduTypeException` for any value with no defined constant (including the unused value
`0`).

This matrix is link-layer-agnostic. The same `Asdu` application layer is carried unchanged whether
the link layer beneath it is the 104 APCI session (`ApciSession`, over TCP) or the 101 FT1.2 link
layer (`Ft12LinkLayer`, over a serial port or TCP). CS101 changes only the framing below the ASDU; it
adds no type identifications and removes none, so the coverage table applies identically to both
profiles. See [ft12-link-layer.md](ft12-link-layer.md) for the FT1.2 link layer itself.

In the direction column: **Mon** = monitor direction (controlled → controlling station), **Ctrl** =
control direction (controlling → controlled station), **Sys** = system information.

## Process information, monitor direction (Table 8)

| TypeID | Mnemonic | Description | Dir | Record (`com.digitalpetri.iec60870.asdu.object`) |
|---|---|---|---|---|
| 1 | M_SP_NA_1 | Single-point information | Mon | `SinglePointInformation` |
| 2 | M_SP_TA_1 | Single-point with CP24Time2a | Mon | `SinglePointWithCp24Time` |
| 3 | M_DP_NA_1 | Double-point information | Mon | `DoublePointInformation` |
| 4 | M_DP_TA_1 | Double-point with CP24Time2a | Mon | `DoublePointWithCp24Time` |
| 5 | M_ST_NA_1 | Step position information | Mon | `StepPositionInformation` |
| 6 | M_ST_TA_1 | Step position with CP24Time2a | Mon | `StepPositionWithCp24Time` |
| 7 | M_BO_NA_1 | Bitstring of 32 bits | Mon | `Bitstring32` |
| 8 | M_BO_TA_1 | Bitstring of 32 bits with CP24Time2a | Mon | `Bitstring32WithCp24Time` |
| 9 | M_ME_NA_1 | Measured value, normalized | Mon | `MeasuredValueNormalized` |
| 10 | M_ME_TA_1 | Measured value, normalized, CP24Time2a | Mon | `MeasuredValueNormalizedWithCp24Time` |
| 11 | M_ME_NB_1 | Measured value, scaled | Mon | `MeasuredValueScaled` |
| 12 | M_ME_TB_1 | Measured value, scaled, CP24Time2a | Mon | `MeasuredValueScaledWithCp24Time` |
| 13 | M_ME_NC_1 | Measured value, short float | Mon | `MeasuredValueShortFloat` |
| 14 | M_ME_TC_1 | Measured value, short float, CP24Time2a | Mon | `MeasuredValueShortFloatWithCp24Time` |
| 15 | M_IT_NA_1 | Integrated totals | Mon | `IntegratedTotals` |
| 16 | M_IT_TA_1 | Integrated totals with CP24Time2a | Mon | `IntegratedTotalsWithCp24Time` |
| 17 | M_EP_TA_1 | Event of protection equipment, CP24Time2a | Mon | `EventOfProtectionEquipment` |
| 18 | M_EP_TB_1 | Packed start events of protection, CP24Time2a | Mon | `PackedStartEventsOfProtection` |
| 19 | M_EP_TC_1 | Packed output circuit info of protection, CP24Time2a | Mon | `PackedOutputCircuitInfo` |
| 20 | M_PS_NA_1 | Packed single-point with status change detection | Mon | `PackedSinglePointWithStatusChange` |
| 21 | M_ME_ND_1 | Measured value, normalized, no quality descriptor | Mon | `MeasuredValueNormalizedNoQuality` |
| 30 | M_SP_TB_1 | Single-point with CP56Time2a | Mon | `SinglePointWithCp56Time` |
| 31 | M_DP_TB_1 | Double-point with CP56Time2a | Mon | `DoublePointWithCp56Time` |
| 32 | M_ST_TB_1 | Step position with CP56Time2a | Mon | `StepPositionWithCp56Time` |
| 33 | M_BO_TB_1 | Bitstring of 32 bits with CP56Time2a | Mon | `Bitstring32WithCp56Time` |
| 34 | M_ME_TD_1 | Measured value, normalized, CP56Time2a | Mon | `MeasuredValueNormalizedWithCp56Time` |
| 35 | M_ME_TE_1 | Measured value, scaled, CP56Time2a | Mon | `MeasuredValueScaledWithCp56Time` |
| 36 | M_ME_TF_1 | Measured value, short float, CP56Time2a | Mon | `MeasuredValueShortFloatWithCp56Time` |
| 37 | M_IT_TB_1 | Integrated totals with CP56Time2a | Mon | `IntegratedTotalsWithCp56Time` |
| 38 | M_EP_TD_1 | Event of protection equipment, CP56Time2a | Mon | `EventOfProtectionEquipmentWithCp56Time` |
| 39 | M_EP_TE_1 | Packed start events of protection, CP56Time2a | Mon | `PackedStartEventsOfProtectionWithCp56Time` |
| 40 | M_EP_TF_1 | Packed output circuit info, CP56Time2a | Mon | `PackedOutputCircuitInfoWithCp56Time` |

## Process information, control direction (Table 9)

| TypeID | Mnemonic | Description | Dir | Record |
|---|---|---|---|---|
| 45 | C_SC_NA_1 | Single command | Ctrl | `SingleCommand` |
| 46 | C_DC_NA_1 | Double command | Ctrl | `DoubleCommand` |
| 47 | C_RC_NA_1 | Regulating step command | Ctrl | `RegulatingStepCommand` |
| 48 | C_SE_NA_1 | Set-point command, normalized | Ctrl | `SetpointNormalized` |
| 49 | C_SE_NB_1 | Set-point command, scaled | Ctrl | `SetpointScaled` |
| 50 | C_SE_NC_1 | Set-point command, short float | Ctrl | `SetpointShortFloat` |
| 51 | C_BO_NA_1 | Bitstring of 32 bits command | Ctrl | `Bitstring32Command` |

## Process information, control direction with CP56Time2a (104 clause 8)

| TypeID | Mnemonic | Description | Dir | Record |
|---|---|---|---|---|
| 58 | C_SC_TA_1 | Single command with CP56Time2a | Ctrl | `SingleCommandWithCp56Time` |
| 59 | C_DC_TA_1 | Double command with CP56Time2a | Ctrl | `DoubleCommandWithCp56Time` |
| 60 | C_RC_TA_1 | Regulating step command with CP56Time2a | Ctrl | `RegulatingStepCommandWithCp56Time` |
| 61 | C_SE_TA_1 | Set-point, normalized, CP56Time2a | Ctrl | `SetpointNormalizedWithCp56Time` |
| 62 | C_SE_TB_1 | Set-point, scaled, CP56Time2a | Ctrl | `SetpointScaledWithCp56Time` |
| 63 | C_SE_TC_1 | Set-point, short float, CP56Time2a | Ctrl | `SetpointShortFloatWithCp56Time` |
| 64 | C_BO_TA_1 | Bitstring of 32 bits command, CP56Time2a | Ctrl | `Bitstring32CommandWithCp56Time` |

## System information, monitor direction (Table 10)

| TypeID | Mnemonic | Description | Dir | Record |
|---|---|---|---|---|
| 70 | M_EI_NA_1 | End of initialization | Sys/Mon | `EndOfInitialization` |

## System information, control direction (Table 11)

| TypeID | Mnemonic | Description | Dir | Record |
|---|---|---|---|---|
| 100 | C_IC_NA_1 | Interrogation command | Ctrl | `InterrogationCommand` |
| 101 | C_CI_NA_1 | Counter interrogation command | Ctrl | `CounterInterrogationCommand` |
| 102 | C_RD_NA_1 | Read command | Ctrl | `ReadCommand` |
| 103 | C_CS_NA_1 | Clock synchronization command | Ctrl | `ClockSynchronizationCommand` |
| 104 | C_TS_NA_1 | Test command | Ctrl | `TestCommand` |
| 105 | C_RP_NA_1 | Reset process command | Ctrl | `ResetProcessCommand` |
| 106 | C_CD_NA_1 | Delay acquisition command | Ctrl | `DelayAcquisitionCommand` |
| 107 | C_TS_TA_1 | Test command with CP56Time2a | Ctrl | `TestCommandWithCp56Time` |

## Parameter, control direction (Table 12)

| TypeID | Mnemonic | Description | Dir | Record |
|---|---|---|---|---|
| 110 | P_ME_NA_1 | Parameter of measured value, normalized | Ctrl | `ParameterNormalized` |
| 111 | P_ME_NB_1 | Parameter of measured value, scaled | Ctrl | `ParameterScaled` |
| 112 | P_ME_NC_1 | Parameter of measured value, short float | Ctrl | `ParameterShortFloat` |
| 113 | P_AC_NA_1 | Parameter activation | Ctrl | `ParameterActivation` |

## Out of scope — file transfer (Table 13), raw-layer only

The file-transfer `F_*` types have `AsduType` constants with `supported() == false`. They have **no
typed `.asdu.object` record and no standard codec-dispatch entry**.

| TypeID | Mnemonic | Description |
|---|---|---|
| 120 | F_FR_NA_1 | File ready |
| 121 | F_SR_NA_1 | Section ready |
| 122 | F_SC_NA_1 | Call directory / select file / call file / call section |
| 123 | F_LS_NA_1 | Last section, last segment |
| 124 | F_AF_NA_1 | Acknowledge file, acknowledge section |
| 125 | F_SG_NA_1 | Segment |
| 126 | F_DR_TA_1 | Directory |
| 127 | F_SC_NB_1 | Call/select/call section with time |

**Why they are out of scope.** File transfer in IEC 60870-5 is not a single message but a *stateful,
multi-ASDU procedure*: selecting a file, calling sections, streaming segments, acknowledging, and
detecting the last segment, each with its own ASDU and ordering rules. Modeling it correctly means a
file-transfer state machine, not just a set of records — a substantially different concern from the
stateless encode/decode the rest of the type table needs. Rather than ship a partial or incorrect
implementation, the library leaves these types present-but-unsupported.

They remain fully reachable through the raw layer. A caller who needs file transfer can send and
receive them as raw `Asdu`s with `Iec60870Client.send(Asdu)` / `events()` (or `ServerContext.send(Asdu)`
/ `ServerHandler.onRawAsdu(...)`), parsing the `F_*` object bodies directly from the raw `Asdu` and
driving the multi-ASDU procedure from application code.

This deferral is shared by both profiles. Because file transfer rides the same `F_*` ASDUs over
either link layer, a future file-transfer state machine would live in the shared application layer
and be inherited by the 101 (FT1.2) profile from the same CS104 file path, with no profile-specific
code — exactly as the rest of this coverage table is shared today.

## Private-range TypeIDs

Type identifications in the private ranges (the standard reserves `128..255`, with `136..255`
designated for private use) have no `AsduType` constant, so they fall outside the library's modeled
type table. `AsduType.fromId(int)` rejects an inbound private TypeID with
`UnsupportedAsduTypeException`, and there is no `AsduType` constant to construct one for sending —
exchanging private TypeIDs is not supported through the `Asdu` model; see
[errors-and-extensibility.md](errors-and-extensibility.md).
