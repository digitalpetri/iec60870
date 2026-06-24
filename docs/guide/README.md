# IEC 60870-5-104 — User Guide

This is the task-oriented guide for *using* the `com.digitalpetri.iec60870` library, a Java 17
implementation of IEC 60870-5-104. Throughout the guide a **client** is the
[controlling station](reference/glossary.md) (the master) and a **server** is the
[controlled station](reference/glossary.md) (the outstation, or slave). Most users work through the
high-level facade — `Iec60870Client` and `Iec60870Server`, built with the Netty-backed `TcpIec104Client`
and `TcpIec104Server` — while a raw ASDU layer sits underneath for protocol types the facade does not
model. These pages are task-oriented and caller-focused; for the as-built internals (the wire model,
the APCI engine, the core-vs-transport split) see [`docs/architecture/`](../architecture/README.md).

## How this guide is organized

The guide is split into three layers, following the familiar tutorial / how-to / reference shape:

- **Getting Started (tutorial)** — one end-to-end happy path: install, the mental model, a
  hello-server and hello-client, and how to run them.
- **How-To guides (recipes)** — single-task, copy-paste recipes for the concrete jobs: interrogate a
  station, send commands, host a server, handle events, secure a link with TLS, tune the APCI
  session, and work with raw ASDUs. Each recipe links the runnable example that demonstrates it.
- **Reference** — lookup material you return to: the coverage matrix, the glossary, how to choose a
  point type, the timers and window, and the error model.

The runnable examples live under [`iec60870-examples/`](../../iec60870-examples/README.md), and each
How-To links the example it builds on.

## Recommended reading order

If you are new to the library, read in this order:

1. **[Getting Started](getting-started.md)** — read this first. It establishes the mental model (the
   two-layer API, the async/listener model, client-as-master / server-as-outstation) and walks
   through a working client↔server hello-world.
2. **[Reference: Glossary](reference/glossary.md)** and
   **[Reference: Coverage matrix](reference/coverage-matrix.md)** — the two questions every IEC 104
   user asks first: *what does this term mean?* and *which TypeIDs are supported?* Skim both, then
   keep them open as you read the How-Tos.
3. The **How-To** that matches your role:
   - Building a master? → [Connect & interrogate](how-to/connect-and-interrogate.md) →
     [Send commands](how-to/send-commands.md).
   - Building an outstation? → [Host a server](how-to/host-a-server.md), with
     [Choosing a point type](reference/choosing-a-point-type.md) alongside.
   - Either way → [Handle events](how-to/handle-events.md) for the listener/handler surface and the
     threading rules.
4. **[Secure with TLS](how-to/secure-with-tls.md)** before going to production.
5. Tuning and advanced topics: [Tune the APCI session](how-to/tune-apci.md),
   [Work with raw ASDUs](how-to/work-with-raw-asdus.md), and the
   [Timers & window](reference/timers-and-window.md) and [Error model](reference/errors.md)
   reference pages.

## Documents

### Getting started

- [Getting Started](getting-started.md) — the single end-to-end happy path: install, the mental
  model, a hello-server and hello-client, and how to run them.

### How-to guides

- [Connect & interrogate](how-to/connect-and-interrogate.md) — connect a client and run general and
  counter interrogation, then read the results.
- [Send commands](how-to/send-commands.md) — issue single, double, regulating-step, and setpoint
  commands; direct execute vs. select-before-operate.
- [Host a server](how-to/host-a-server.md) — build a controlled station: stations, points, the
  catalog, command handling, and spontaneous/periodic transmission.
- [Handle events](how-to/handle-events.md) — the listener/handler surface: subscribing to data and
  events, the threading rules, and what fires when.
- [Secure with TLS](how-to/secure-with-tls.md) — secure a link with `TlsOptions`: keystores,
  hostname verification, and handshake gating.
- [Tune the APCI session](how-to/tune-apci.md) — tune the k/w window, the t0–t3 timers, and the
  addressing field sizes, as a decision table.
- [Work with raw ASDUs](how-to/work-with-raw-asdus.md) — drop to the raw protocol layer: build,
  encode, and decode an `Asdu`, and handle unmodeled TypeIDs via the send/receive hooks.

### Reference

- [Coverage matrix](reference/coverage-matrix.md) — the user-facing ASDU TypeID coverage matrix with
  actionable columns.
- [Glossary](reference/glossary.md) — IEC 104 vocabulary mapped to our Java types.
- [Choosing a point type](reference/choosing-a-point-type.md) — map real-world signals to a TypeID,
  our model record, and the value/quality types.
- [Timers & window](reference/timers-and-window.md) — t0–t3 defaults and k/w window semantics for
  integrators aligning two stacks.
- [Error model](reference/errors.md) — typed exceptions vs. result objects; what each operation
  throws or returns.

## At a glance

| I want to… | Start here |
|---|---|
| Stand up a working client and server end to end | [Getting Started](getting-started.md) |
| Look up a TypeID or check what's supported | [Coverage matrix](reference/coverage-matrix.md) |
| Decode IEC 104 jargon (CA, IOA, COT, QOI, SBO) | [Glossary](reference/glossary.md) |
| Read values from a remote station | [Connect & interrogate](how-to/connect-and-interrogate.md) |
| Operate a switch or setpoint on a remote station | [Send commands](how-to/send-commands.md) |
| Expose my own points as an outstation | [Host a server](how-to/host-a-server.md) |
| React to spontaneous data and connection events | [Handle events](how-to/handle-events.md) |
| Encrypt the link | [Secure with TLS](how-to/secure-with-tls.md) |
| Match k/w window and t0–t3 timers to a peer stack | [Tune the APCI session](how-to/tune-apci.md) |
| Send or receive a TypeID the facade doesn't model | [Work with raw ASDUs](how-to/work-with-raw-asdus.md) |

## Beyond this guide

For the **as-built internals** — the wire/ASDU model, the `ApciSession` engine, buffer ownership and
threading, and the core-vs-transport split — read [`docs/architecture/`](../architecture/README.md),
starting with its [overview](../architecture/overview.md). The
[runnable examples](../../iec60870-examples/README.md) are the executable companion to this guide:
`ServerExample`, `ClientExample`, `RawAsduExample`, and `TlsExample`. The project
[README](../../README.md) has the Maven coordinates and the one-paragraph "what is this."
