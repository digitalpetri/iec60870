# IEC 60870-5-104 Interop Contract

This file is the **source of truth** for the lib60870-C interop peers used by the
`iec60870-test-interop` Testcontainers tests. The Java client/server tests assert against the
addresses, values, and confirmation behaviour documented here. The custom C driver programs
(`interop_server.c`, `interop_client.c`) implement exactly this contract.

- Peer library: **lib60870-C** (MZ Automation), pinned tag **v2.3.5** (see `lib60870c/Dockerfile`).
- Transport: **plaintext IEC 60870-5-104 over TCP, port 2404**. TLS is **not** compiled into the image.
- Application-layer sizing: lib60870-C CS104 defaults — **CA = 2 octets, IOA = 3 octets, COT = 2 octets**
  (COT field carries originator address). The interop server uses the stack defaults; do not assume
  1-octet CA/COT.

> **Logging note:** the lib60870-C examples and these drivers log with `printf`, which is fully
> buffered when stdout is a pipe (as under `docker logs`). **Every peer must be run under
> `stdbuf -oL -eL`** so log lines stream in real time for Testcontainers `Wait.forLogMessage(...)`.

---

## 1. Common Address

| Parameter | Value |
|-----------|-------|
| Common Address (CA) | **1** |
| Server originator address (OA) accepted | any (echoed back in confirmations) |
| Client originator address (OA) used by `interop_client` | **3** |

A request to any CA other than 1 is answered by the stack with COT = `UNKNOWN_CA (46)`.

---

## 2. Monitor point image (server -> client)

All monitor points live on **CA 1**. The IOA scheme is deterministic:

```
IOA = 1000 + (typeBlock * 10) + timeVariant
  typeBlock : 0..7  (one per monitored data type, see table)
  timeVariant: 0 = without time tag, 1 = with CP56Time2a time tag
```

So every data type occupies a pair of adjacent IOAs: the even/`...0` IOA is the non-time form and
the `...1` IOA is the CP56Time2a form, both carrying the **same fixed value**.

| typeBlock | Data type | Non-time IOA | Non-time TypeID | Time-tagged IOA | Time TypeID | Fixed value |
|----------:|-----------|-------------:|-----------------|----------------:|-------------|-------------|
| 0 | Single point | **1000** | M_SP_NA_1 (1)  | **1001** | M_SP_TB_1 (30) | `true` (ON) |
| 1 | Double point | **1010** | M_DP_NA_1 (3)  | **1011** | M_DP_TB_1 (31) | `2` = DOUBLE_POINT_ON |
| 2 | Step position | **1020** | M_ST_NA_1 (5)  | **1021** | M_ST_TB_1 (32) | value `7`, transient `false` |
| 3 | Bitstring 32 | **1030** | M_BO_NA_1 (7)  | **1031** | M_BO_TB_1 (33) | `0x12345678` |
| 4 | Normalized | **1040** | M_ME_NA_1 (9)  | **1041** | M_ME_TD_1 (34) | `0.5` (normalized, +0.5 of full scale) |
| 5 | Scaled | **1050** | M_ME_NB_1 (11) | **1051** | M_ME_TE_1 (35) | `12345` |
| 6 | Short float | **1060** | M_ME_NC_1 (13) | **1061** | M_ME_TF_1 (36) | `3.14159` |
| 7 | Integrated totals | **1070** | M_IT_NA_1 (15) | **1071** | M_IT_TB_1 (37) | counter `1000`, seq `0`, no carry/adjust/invalid |

Quality for all monitor points is `IEC60870_QUALITY_GOOD (0x00)` unless a test explicitly changes it.
Time-tagged points carry a CP56Time2a timestamp set to the server's wall-clock at send time
(non-deterministic; tests should assert on value + IOA + TypeID, not on the timestamp contents).

Normalized value note: lib60870-C `MeasuredValueNormalized` stores a float in `[-1.0, +1.0)`. The
fixed value `0.5` is encoded by the library to the nearest representable normalized integer
(`0x4000`), so a decoded value of approximately `0.5` (within one LSB, ~3.05e-5) is expected.

---

## 3. Interrogation group assignments

The server answers **station interrogation** and **two group interrogations**. Counter values are
delivered only via counter interrogation (see section 4), never via station/group interrogation.

| Interrogation | QOI | Returned response COT | Points returned |
|---------------|----:|-----------------------|-----------------|
| Station       | 20  | INTERROGATED_BY_STATION (20)  | **all** monitor points except integrated totals (IOAs 1000,1001,1010,1011,1020,1021,1030,1031,1040,1041,1050,1051,1060,1061) |
| Group 1       | 21  | INTERROGATED_BY_GROUP_1 (21)  | all **non-time** monitor points (IOAs 1000,1010,1020,1030,1040,1050,1060) |
| Group 2       | 22  | INTERROGATED_BY_GROUP_2 (22)  | all **time-tagged** monitor points (IOAs 1001,1011,1021,1031,1041,1051,1061) |
| Other groups  | 23..36 | n/a | rejected: ACT_CON with P/N = 1 (negative) |

For every accepted interrogation the server emits, in order:
1. `ACT_CON` (positive) echoing the interrogation command,
2. **one non-sequence data ASDU per point** with the response COT above (one information object per
   ASDU),
3. `ACT_TERM` echoing the interrogation command.

> **One ASDU per point (lib60870-C packing constraint).** An IEC 60870-5-104 ASDU carries a single
> TypeID for all of its information objects, and lib60870-C's `CS101_ASDU_addInformationObject`
> **silently drops** any object whose TypeID differs from the ASDU's first object. Because the
> monitor image is multi-typed, `interop_server` therefore sends **each interrogation point as its
> own non-sequence `CS101_ASDU`** (created with `CS101_ASDU_create(..., responseCOT, ...)`, one
> object added, then `IMasterConnection_sendASDU`). This delivers every point at its correct TypeID;
> packing them into a single shared ASDU would collapse the response on the wire to just the first
> point.

Per the CS101/CS104 spec, interrogation responses carry **non-time-tagged** type IDs only. Group 2
therefore re-sends the values of the time-tagged points using their corresponding non-time type IDs
(e.g. the value at IOA 1001 is reported as M_SP_NA_1 with COT INTERROGATED_BY_GROUP_2). The IOA still
identifies which logical point it is. The CP56Time2a-tagged TypeIDs (M_SP_TB_1, M_ME_TF_1, ...) are
exercised instead through the **read** command (section 5), which returns each IOA at its native
(time or non-time) TypeID.

---

## 4. Counter interrogation (C_CI_NA_1, TypeID 101)

| Request | QCC | Response COT | Points returned |
|---------|-----|--------------|-----------------|
| General counter | RQT_GENERAL (5) + any FRZ | REQUESTED_BY_GENERAL_COUNTER (37) | both integrated totals (IOAs 1070, 1071) reported as M_IT_NA_1 |
| Group 1 counter | RQT_GROUP_1 (1) + any FRZ | REQUESTED_BY_GROUP_1_COUNTER (38) | integrated totals (IOAs 1070, 1071) |
| Other (groups 2..4) | RQT_GROUP_2..4 | n/a | rejected: ACT_CON with P/N = 1 |

Sequence: `ACT_CON` (positive), then the counter data ASDU(s), then `ACT_TERM`. The freeze qualifier
(FRZ bits) is accepted but the counter value is **not** mutated by freeze in this fixed image — the
counter at IOA 1070 always reads **1000** and at IOA 1071 always reads **1000**.

---

## 5. Read command (C_RD_NA_1, TypeID 102)

A read of any IOA in the monitor image (section 2) returns a single data ASDU with **COT = REQUEST (5)**
carrying that one point at its documented value and native (time or non-time) TypeID. A read of an
unknown IOA returns `ACT_CON`/negative semantics via the stack (`UNKNOWN_IOA`), logged by the server.

---

## 6. Clock sync, test, reset, end-of-init

| Request | TypeID | Server behaviour |
|---------|-------:|------------------|
| Clock synchronization | C_CS_NA_1 (103) | Accept; stack auto-sends `ACT_CON` (positive). Server logs the received time. |
| Test command | C_TS_NA_1 (104) | Accept; reply `ACT_CON` (positive) echoing the command. |
| Test command w/ timestamp | C_TS_TA_1 (107) | Accept; reply `ACT_CON` (positive) echoing the command. |
| Reset process | C_RP_NA_1 (105) | Accept; reply `ACT_CON` (positive) echoing the command, then re-issue End-of-Initialization. |
| End of initialization | M_EI_NA_1 (70) | Emitted **once at startup** (COT = INITIALIZED, COI = 0) and again after a reset-process command. |

---

## 7. Command handling (client -> server)

Command IOAs are partitioned into a documented **accept** range and a single documented **reject** IOA,
independent of command type. The same IOA partitioning applies to every command type ID.

```
ACCEPT IOA range : 2000 .. 2999  (inclusive)
REJECT IOA       : 3000          (single, well-known)
```

The server inspects each control ASDU's IOA:

- **IOA in 2000..2999 -> ACCEPT.** The server confirms with `ACT_CON` **positive (P/N = 0)**.
  - **Direct execute** (S/E = 0): single `ACT_CON` positive. For commands that map onto a monitor
    point (see below) the server also enqueues a **return-information** monitor update
    (COT = RETURN_INFO_REMOTE, 11) reflecting the commanded value.
  - **Select/execute** (S/E = 1 on the first ASDU = select): the server replies `ACT_CON` positive to
    the **select**, then expects a matching **execute** (S/E = 0, same IOA/value) and replies `ACT_CON`
    positive to the execute, followed by `ACT_TERM`, then the return-information update.
- **IOA == 3000 -> REJECT.** The server replies `ACT_CON` **negative (P/N = 1)** and performs no update.
- **IOA anywhere else** (outside 2000..2999 and not 3000) -> stack returns `UNKNOWN_IOA (47)`.

Supported command type IDs (all both direct-execute and select-before-operate):

| Command | TypeID | Value used by `interop_client` accept test |
|---------|-------:|--------------------------------------------|
| Single command            | C_SC_NA_1 (45) | ON (`true`) |
| Double command            | C_DC_NA_1 (46) | ON (`2`) |
| Regulating step command   | C_RC_NA_1 (47) | HIGHER (`2`) |
| Setpoint normalized       | C_SE_NA_1 (48) | `0.25` |
| Setpoint scaled           | C_SE_NB_1 (49) | `4321` |
| Setpoint short float      | C_SE_NC_1 (50) | `2.71828` |
| Bitstring 32 command      | C_BO_NA_1 (51) | `0x0F0F0F0F` |

Return-information mapping (only meaningful for accepted commands in 2000..2999): the server mirrors a
single-command accept to single-point IOA **1000**, and a setpoint-short accept to short-float IOA
**1060**, as a RETURN_INFO_REMOTE spontaneous update so passive/return-info scenarios have observable
traffic. Other accepted command types are confirmed (ACT_CON positive) but do not mutate a monitor
point. (lib60870-C imposes no requirement to mirror; this is a contract convenience for the tests.)

---

## 8. Spontaneous / periodic traffic

While running, the server enqueues a **periodic** measured-value update every **2 seconds**:

- TypeID `M_ME_NB_1` (scaled), **IOA 1050**, COT = **PERIODIC (1)**.
- The value cycles `12345, 12346, 12347, ...` (monotonic increment each tick) so passive scenarios can
  observe changing data. Station/group interrogation always reports the **base** value `12345` for IOA
  1050 regardless of the live periodic counter (the interrogation image is fixed).

This guarantees ASDU traffic even when no client request is outstanding.

---

## 9. Server log lines (for Testcontainers `Wait.forLogMessage`)

The server prints stable, greppable markers. Anchor waits on these substrings:

| Marker | Meaning |
|--------|---------|
| `INTEROP-SERVER READY` | server bound and listening on 0.0.0.0:2404 |
| `END-OF-INIT sent` | end-of-initialization emitted |
| `CONN opened` / `CONN activated` / `CONN closed` | connection lifecycle |
| `IC station` / `IC group=N` | station / group N interrogation handled |
| `CI general` / `CI group=N` | counter interrogation handled |
| `READ ioa=N` | read command handled |
| `CLOCKSYNC` | clock sync accepted |
| `TEST` | test command accepted |
| `RESET-PROCESS` | reset process accepted |
| `CMD <type> ioa=N <ACCEPT|REJECT> <DIRECT|SELECT|EXECUTE> sel=<0|1>` | command handled |
| `RETURN-INFO ioa=N` | return-information update enqueued |
| `PERIODIC ioa=1050 value=N` | periodic update enqueued |

The client prints `PASS:` / `FAIL:` prefixed lines (see section 11).

---

## 10. Exact run commands

Image (built by `lib60870c/build.sh`): `lib60870c-interop:v2.3.5`. Both custom binaries
(`interop_server`, `interop_client`) and the stock examples (`cs104_server`, `simple_client`) live in
`/usr/local/bin`.

**Server (controlled station, our Java CLIENT tests against this):**

```bash
docker run -d --name iec104-srv --network <net> -p 2404:2404 \
  lib60870c-interop:v2.3.5 \
  stdbuf -oL -eL interop_server
```

`interop_server` takes no required arguments. Optional env:
- `INTEROP_PORT` (default `2404`)
- `INTEROP_CA` (default `1`)

**Client (controlling station, drives our Java SERVER — the LIMITED scenario):**

```bash
docker run --rm --network <net> \
  lib60870c-interop:v2.3.5 \
  stdbuf -oL -eL interop_client <server-host> <server-port>
```

`interop_client` arguments / env (all optional, documented defaults):

| Position | Env | Default | Meaning |
|---------:|-----|---------|---------|
| argv[1] | `INTEROP_HOST` | `127.0.0.1` | target server host |
| argv[2] | `INTEROP_PORT` | `2404` | target server port |
| (n/a)   | `INTEROP_CA`   | `1` | common address to address |
| (n/a)   | `INTEROP_ACCEPT_IOA` | `2000` | IOA expected to be ACCEPTED |
| (n/a)   | `INTEROP_REJECT_IOA` | `3000` | IOA expected to be REJECTED |

---

## 11. Client scripted sequence (LIMITED scenario driver)

`interop_client` runs this scripted sequence against the target server and logs `PASS:`/`FAIL:` lines:

1. Connect TCP, send `STARTDT`, wait for `STARTDT_CON`.
2. Station interrogation (QOI 20) -> expect ACT_CON + data + ACT_TERM.
3. Counter interrogation (general) -> expect ACT_CON + data + ACT_TERM.
4. Clock synchronization -> expect ACT_CON.
5. Read command on `INTEROP_ACCEPT_IOA`'s monitor neighbour (IOA 1000) -> expect a data ASDU.
6. Single command (C_SC_NA_1, ON, direct execute) to `INTEROP_ACCEPT_IOA` -> expect ACT_CON **positive**;
   print `PASS: accept command confirmed (P/N=0)`.
7. Single command (C_SC_NA_1, ON, direct execute) to `INTEROP_REJECT_IOA` -> expect ACT_CON **negative**;
   print `PASS: reject command negatively confirmed (P/N=1)`.
8. Idle long enough to trigger a `TESTFR` keepalive (t3), then send an explicit test command.
9. `STOPDT`, wait for `STOPDT_CON`, close.

The client exits non-zero if any mandatory step fails, and prints a final
`INTEROP-CLIENT RESULT pass=<n> fail=<n>` line.

> When driving our **Java server**, the Java server must implement at least the accept/reject IOA
> convention (section 7) and station/counter interrogation; otherwise the corresponding client steps
> log `FAIL:` and the test asserting on them is expected to account for the Java server's documented
> capability level.
