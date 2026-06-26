<!-- SPDX-License-Identifier: GPL-3.0-or-later -->

# IEC 60870-5-101 BALANCED Interop Contract

This file is the **source of truth** for the lib60870-C CS101 **balanced** interop peer used by the
`iec60870-interop` Testcontainers test `Cs101BalancedInteropTest`. It is the serial sibling of
[`INTEROP-CONTRACT.md`](INTEROP-CONTRACT.md) (CS104): the application-layer point image,
interrogation groups, command partitioning, and spontaneous traffic are **reused verbatim** from
that contract. This document pins only the things that differ for a balanced serial link: the link
mode and addressing, the serial parameters, the PTY/TCP bridge topology, the FT1.2 behaviors, and
the log markers / run env vars.

The custom C driver `lib60870c/interop_cs101.c` implements exactly this contract. lib60870-C is
GPLv3; the driver `#include`s its headers and statically links `liblib60870.a`, so it forms a
combined work and is distributed only as a standalone serial peer binary inside the interop Docker
image. Everything under `docker/` is therefore GPL-3.0-or-later (see `docker/LICENSE.md` and the
`SPDX-License-Identifier` headers).

- Peer library: **lib60870-C** (MZ Automation), pinned tag **v2.3.5** (see `lib60870c/Dockerfile`).
- Transport: **IEC 60870-5-101 balanced (FT1.2) over an emulated serial line**, 9600 8E1.
- Application-layer sizing: **COT = 1 octet (no originator address), CA = 1 octet, IOA = 2 octets**,
  matching the Java side's `ProtocolProfile(1, 1, 2, 255)`. This is narrower than the CS104 defaults
  (CA = 2, IOA = 3, COT = 2); the CS101 peer sets `sizeOfCOT = 1`, `sizeOfCA = 1`, `sizeOfIOA = 2` on
  its `CS101_AppLayerParameters`.

> **Logging note:** as in CS104, the C peer logs with `printf` (fully buffered to a pipe under
> `docker logs`), so it is run under **`stdbuf -oL -eL`** — the container entrypoint does this — so
> log lines stream for Testcontainers `Wait.forLogMessage(...)`.

---

## 1. Link mode and addressing

| Parameter | Value |
|-----------|-------|
| Link mode | **BALANCED** (`IEC60870_LINK_LAYER_BALANCED`) |
| Link (FT1.2) address | **1** |
| Link address length | **1 octet** (`LinkLayerParameters.addressLength = 1`) |
| Common address (CA) | **1** |

Both stations use the **same** link address `1` (the Java side's `LinkSettings.balanced()` default
`linkAddress = 1`). In balanced point-to-point there is exactly one peer in each direction, so a
single shared link address is sufficient; the C peer sets both its own address and the other
station's address to `1`:

- **C master:** `CS101_Master_setOwnAddress(m, 1)` + `CS101_Master_useSlaveAddress(m, 1)`.
- **C slave:** `CS101_Slave_setLinkLayerAddress(s, 1)` + `CS101_Slave_setLinkLayerAddressOtherStation(s, 1)`.

**DIR bit.** The controlling station sends DIR = 1, the controlled station sends DIR = 0:

- When the C peer is the **master** (controlling), `CS101_Master_setDIR(m, true)`; the Java side is the
  controlled **server** (`Ft12LinkLayer.Role.SERVER`, DIR = 0).
- When the C peer is the **slave** (controlled), `CS101_Slave_setDIR(s, false)`; the Java side is the
  controlling **client** (`Ft12LinkLayer.Role.CLIENT`, DIR = 1).

---

## 2. Serial parameters and FT1.2 behaviors

| Parameter | Value |
|-----------|-------|
| Baud rate | **9600** |
| Data bits / parity / stop bits | **8 / EVEN / 1** ("8E1", required by FT1.2) |
| Single-character ACK | **enabled** (`useSingleCharACK = true`, the `0xE5` short frame) |
| ACK timeout | 1000 ms (`timeoutForAck`) — generous for the bridged link |
| Repeat timeout | 2000 ms (`timeoutRepeat`) |

The serial port is created with `SerialPort_create(device, 9600, 8, 'E', 1)`. The Java side uses the
matching 8E1 defaults of `SerialPortConfig` / `LinkSettings.balanced()` (single-char ACK on).

**Balanced bring-up.** The link is brought up by the FT1.2 balanced link-reset handshake
(reset-of-remote-link, FC 0). The controlling station initiates it; the controlled station reacts.
The C peer drives its link layer with a background thread (`CS101_Master_start` /
`CS101_Slave_start`), which retries the bring-up until the peer responds, so the peer tolerates
startup ordering (it may come up before the Java side is reading the line).

---

## 3. PTY / TCP bridge topology

A serial line is point-to-point, but Testcontainers exposes TCP ports, not serial devices. socat
bridges the gap on **both** ends. The container entrypoint
(`lib60870c/cs101-entrypoint.sh`) runs, in the background:

```
socat PTY,link=/dev/ttyCS101,raw,echo=0,b9600  TCP-LISTEN:2404,reuseaddr,nodelay
```

It creates a pseudo-terminal, symlinks the slave side to `/dev/ttyCS101` (the device the C peer
opens), and exposes the other end as `TCP-LISTEN:2404`. Testcontainers maps container port 2404 to a
host port. The **host** test then runs its own:

```
socat PTY,link=<host pty>,raw,echo=0  TCP:localhost:<mappedPort>
```

so the complete octet path is:

```
Java <-> host PTY <-> host socat <-> TCP <-> (docker port map) <-> container socat <-> /dev/ttyCS101 <-> C peer
```

The Java `SerialIec101Client` / `SerialIec101Server` opens `<host pty>` as its serial device.

> **Host socat is required.** The test that drives this bridge `Assumptions.assumeTrue`s on
> `which socat` and **skips cleanly** when the host has no `socat` (install it with `brew install
> socat` or the platform equivalent to run the test).

---

## 4. Application-layer contract (reused from CS104)

The point image, interrogation groups, counter interrogation, read, command accept/reject
partitioning, return information, and the 2-second periodic update are **identical** to
[`INTEROP-CONTRACT.md`](INTEROP-CONTRACT.md) sections 2–8, with the CS101 sizing from above (CA = 1,
IOA = 2, COT = 1). In summary:

- Monitor image at IOAs `1000`–`1071`, fixed values per CS104 contract section 2.
- Station interrogation (QOI 20) → all seven non-time points, one ASDU per point; group 1 (QOI 21)
  the same; group 2 (QOI 22) the seven time-tagged points reported via their non-time TypeIDs.
- Counter interrogation (general / group 1) → both integrated totals (IOAs 1070/1071, value 1000).
- Read (C_RD_NA_1) of any monitor IOA → that point at its native (time/non-time) TypeID, COT REQUEST.
- Commands: IOA **2000–2999 → ACCEPT** (ACT_CON positive), IOA **3000 → REJECT** (ACT_CON negative).
  A single command accepted mirrors onto IOA 1000, a setpoint-short onto IOA 1060 (RETURN_INFO_REMOTE).
- Periodic scaled measured value (M_ME_NB_1) at IOA 1050, COT PERIODIC, every 2 s.

The C slave enqueues spontaneous / return-info / periodic ASDUs with
`CS101_Slave_enqueueUserDataClass1` (the balanced slave's send path); the per-handler confirmation
flow (`IMasterConnection_sendACT_CON` / `sendACT_TERM` / `sendASDU`) is identical to the CS104 driver.

---

## 5. Roles, run env vars, and log markers

One binary, `interop_cs101`, selects its role from `INTEROP_CS101_ROLE` (`slave` is the default):

| Role | C peer is | Java side under test | Driven by |
|------|-----------|----------------------|-----------|
| `slave`  | controlled station (outstation) | `SerialIec101Client` (master)  | the Java client's requests |
| `master` | controlling station             | `SerialIec101Server` (slave)   | the C master's scripted sequence |

Env vars consumed by the peer / entrypoint:

| Env | Default | Meaning |
|-----|---------|---------|
| `INTEROP_CS101_ROLE`   | `slave` | role to run (`slave` or `master`) |
| `INTEROP_CS101_DEVICE` | `/dev/ttyCS101` | serial device the peer opens (created by socat) |
| `INTEROP_CS101_BAUD`   | `9600`  | baud rate |
| `INTEROP_CS101_TCP_PORT` | `2404` | container-side TCP-LISTEN port (entrypoint) |
| `INTEROP_CA`           | `1`     | common address |
| `INTEROP_ACCEPT_IOA`   | `2000`  | IOA expected to be ACCEPTED (master role) |
| `INTEROP_REJECT_IOA`   | `3000`  | IOA expected to be REJECTED (master role) |

Stable, greppable log markers (anchor `Wait.forLogMessage(...)` on these substrings):

| Marker | Meaning |
|--------|---------|
| `INTEROP-CS101-BRIDGE ... ready` | socat created the PTY; the peer is being launched |
| `INTEROP-CS101-PEER READY role=<slave\|master>` | the C peer opened the serial port and started its run loop |
| `LINK available` / `LINK idle` / `LINK error` | FT1.2 link-layer state changes |
| (slave) `IC station`, `CMD ... ACCEPT\|REJECT ...`, `PERIODIC ioa=1050 value=N`, `RETURN-INFO ioa=N` | same handler markers as the CS104 server |
| (master) `MASTER RECVD type=... cot=... neg=... elems=... ioa0=...` | each received ASDU |
| (master) `PASS:` / `FAIL:` | per-step scripted result |
| (master) `INTEROP-CS101-MASTER RESULT pass=<n> fail=<n>` | final scripted result line |

### Master scripted sequence (role = master)

The C master, once `LINK available`, runs and logs `PASS:`/`FAIL:` for:

1. `link available` — the balanced link reached the available state.
2. `station interrogation (ACT_CON + data)` — station IC returns ACT_CON + data.
3. `accept command confirmed (P/N=0)` — single command ON, direct, to `INTEROP_ACCEPT_IOA`.
4. `reject command negatively confirmed (P/N=1)` — single command ON, direct, to `INTEROP_REJECT_IOA`.
5. `spontaneous data observed` — at least one COT SPONTANEOUS/PERIODIC ASDU from the slave.

It then prints `INTEROP-CS101-MASTER RESULT pass=<n> fail=<n>` and exits non-zero if `fail != 0`.

---

## 6. Exact run commands

Image (built by `lib60870c/build.sh`): `lib60870c-interop:v2.3.5`. The CS101 peer and its entrypoint
live in `/usr/local/bin` alongside the CS104 binaries.

**C slave (our Java CLIENT tests against this):**

```bash
docker run --rm -p 2404:2404 \
  -e INTEROP_CS101_ROLE=slave \
  lib60870c-interop:v2.3.5 \
  cs101-entrypoint.sh
# then on the host:
#   socat PTY,link=/tmp/ttyHOST,raw,echo=0 TCP:localhost:<mappedPort>
#   SerialIec101Client.builder().serialPort("/tmp/ttyHOST")...
```

**C master (drives our Java SERVER):**

```bash
docker run --rm -p 2404:2404 \
  -e INTEROP_CS101_ROLE=master \
  lib60870c-interop:v2.3.5 \
  cs101-entrypoint.sh
```

The CS104 binaries (`interop_server`, `interop_client`, stock `cs104_server` / `simple_client`) and
their contract are unchanged.
