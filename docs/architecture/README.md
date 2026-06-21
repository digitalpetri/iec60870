# IEC 60870-5-104 Library — Architecture

This is the architecture reference for the `com.digitalpetri.iec104` library, a Java 17
implementation of IEC 60870-5-104 (the TCP/IP companion standard to IEC 60870-5-101). The library
is split into a protocol core (`iec104-core`) that owns the wire model, codecs, APCI session engine,
and the high-level client/server APIs, and a Netty-backed transport (`iec104-transport-tcp`) that
supplies the TCP/TLS plumbing and the user-facing `TcpIec104Client` / `TcpIec104Server` entry points.
Everything a caller touches above the socket lives in core; the transport module is the only place
that knows about channels, event loops, and TLS engines.

These documents describe the system **as built**. They are written for someone integrating the
library or extending it, not for someone editing the protocol internals.

## Documents

- [overview.md](overview.md) — what IEC 104 is, the two-layer API philosophy (raw protocol layer
  vs. high-level facade), and a component map of the modules and key packages.
- [modules-and-dependencies.md](modules-and-dependencies.md) — the core-vs-transport split, the
  dependency rules that keep Netty runtime types out of core, and the libraries each module pulls in.
- [two-layer-api.md](two-layer-api.md) — the raw layer (`Asdu`, `AsduType`, `Cause`,
  `InformationObject` records, addresses, co-located `Serde` codecs) and the high-level layer
  (`Iec104Client` / `Iec104Server`, station/point model, commands, events, catalog).
- [protocol-coverage.md](protocol-coverage.md) — the coverage matrix: every ASDU type identification
  in the 101/104 tables, its mnemonic, direction, and Java record name, plus the out-of-scope file
  transfer types and why they are raw-layer only.
- [apci-and-timers.md](apci-and-timers.md) — the APCI lifecycle (STARTDT/STOPDT/TESTFR), the I/S/U
  frame formats, the sequence-number method, the `k`/`w` window, and the `t0`–`t3` timers, and how
  `ApciSession` implements them.
- [buffers-and-threading.md](buffers-and-threading.md) — buffer ownership and `ByteBuf` release
  rules, plus the threading and callback-serialization model.
- [tls-and-configuration.md](tls-and-configuration.md) — the TLS approach (`TlsOptions`, handshake
  gating, peer certificate exposure) and the configuration/profile types (`ProtocolProfile`,
  `ApciSettings`).
- [errors-and-extensibility.md](errors-and-extensibility.md) — the typed exception model versus
  result objects, when each is used, and extensibility through `TypeCodecRegistry` and private
  TypeIDs.

## At a glance

| Concern | Where it lives |
|---|---|
| Wire model and codecs | `iec104-core` packages `.asdu`, `.apci`, `.address` |
| APCI flow-control engine | `iec104-core` `com.digitalpetri.iec104.apci.ApciSession` |
| High-level client | `iec104-core` `com.digitalpetri.iec104.client` |
| High-level server | `iec104-core` `com.digitalpetri.iec104.server` |
| Point / catalog model | `iec104-core` packages `.point`, `.catalog` |
| Transport interfaces (no Netty) | `iec104-core` `com.digitalpetri.iec104.transport` |
| Netty TCP/TLS transport + builders | `iec104-transport-tcp` |
