# Send commands

Issue control-direction commands from a connected client and read whether the controlled station
accepted or rejected them. This page assumes you already have a connected
[`Iec60870Client`](../../architecture/two-layer-api.md) — the
[controlling station](../reference/glossary.md) (master). If you do not yet have one, start with
[Getting Started](../getting-started.md) and the
[Connect & interrogate](connect-and-interrogate.md) recipe, which cover connecting, starting data
transfer, and reading monitor data. Everything on this page hangs off `client.commands()`.

A command operates an output on the controlled station (the [outstation](../reference/glossary.md), or
server): close a breaker, move a tap changer, write a set-point. The high-level facade does the
correlation for you — you describe *what* and *where* with a `Command`, choose *how* with a
`CommandMode`, and read the station's *answer* from a `CommandResult`. None of this re-teaches the
two-layer design; for that, see the architecture's
[two-layer API](../../architecture/two-layer-api.md) document.

## The command surface at a glance

`client.commands()` returns a `CommandService`. It exposes one convenience helper per output kind plus
a general `send(Command, CommandMode)` for full control. The seven control-direction output kinds map
to helpers and wire types as follows:

| Output kind | Helper on `CommandService` | Value type | Untimed TypeID |
|---|---|---|---|
| Single (on/off) | `single(target, boolean on)` | `boolean` | C_SC_NA_1 (45) |
| Double | `doublePoint(target, DoubleCommandState)` | `DoubleCommandState` | C_DC_NA_1 (46) |
| Regulating / step | `regulatingStep(target, StepCommandState)` | `StepCommandState` | C_RC_NA_1 (47) |
| Set-point, normalized | `setpointNormalized(target, NormalizedValue)` | `NormalizedValue` | C_SE_NA_1 (48) |
| Set-point, scaled | `setpointScaled(target, short)` | `short` | C_SE_NB_1 (49) |
| Set-point, short float | `setpointShortFloat(target, float)` | `float` | C_SE_NC_1 (50) |
| Bit-string (32-bit) | `bitstring(target, int bits)` | `int` | C_BO_NA_1 (51) |

The mental model is three small pieces:

- a **`Command`** carries *what* and *where* — the target [`PointAddress`](../reference/glossary.md),
  the command value, the [qualifier](../reference/glossary.md), and an optional
  [time tag](../reference/glossary.md);
- a **`CommandMode`** carries *how* — [direct execute](../reference/glossary.md) versus
  [select-before-operate](../reference/glossary.md);
- a **`CommandResult`** carries the station's *answer* — accepted or rejected, plus the
  [cause of transmission](../reference/glossary.md) of the confirming ASDU.

Every snippet below assumes an in-scope, connected `Iec60870Client client` and these constants, matching
the shipped [`ClientExample`](../../../iec60870-examples/src/main/java/com/digitalpetri/iec60870/examples/ClientExample.java)
and [`ServerExample`](../../../iec60870-examples/src/main/java/com/digitalpetri/iec60870/examples/ServerExample.java):

```java
import com.digitalpetri.iec60870.address.CommonAddress;
import com.digitalpetri.iec60870.address.PointAddress;
import com.digitalpetri.iec60870.asdu.element.DoubleCommandState;
import com.digitalpetri.iec60870.asdu.element.NormalizedValue;
import com.digitalpetri.iec60870.asdu.element.StepCommandState;
import com.digitalpetri.iec60870.client.Command;
import com.digitalpetri.iec60870.client.CommandMode;
import com.digitalpetri.iec60870.client.CommandResult;
import com.digitalpetri.iec60870.client.CommandService;
import com.digitalpetri.iec60870.client.Iec60870Client;
import java.time.Instant;
import java.util.Optional;

CommonAddress STATION = CommonAddress.of(1);
PointAddress SWITCH = PointAddress.of(1, 300);   // commandable single point in ServerExample
```

## Your first command (direct execute)

The simplest path is a single command with [direct execute](../reference/glossary.md): build it, send
it, read the result.

```java
CommandService commands = client.commands();

CommandResult result = commands.single(SWITCH, true);   // direct execute, close the switch
if (result.positive()) {
  System.out.println("command accepted");
} else {
  System.out.println("command rejected: " + result.cause());
}
```

This is exactly what `ClientExample.run(...)` does — it calls `client.commands().single(SWITCH, true)`
and prints `command.positive()`. The `single`, `doublePoint`, and the other helpers all use direct
execute and return a `CommandResult` synchronously; they are the fast path for routine controls.

## The command types

Each output kind has a one-line convenience helper. All helpers take the target
[`PointAddress`](../reference/glossary.md) plus the value, use direct execute, and return a
`CommandResult`:

```java
commands.single(point, true);
commands.doublePoint(point, DoubleCommandState.ON);
commands.regulatingStep(point, StepCommandState.NEXT_STEP_HIGHER);
commands.setpointNormalized(point, NormalizedValue.of(0.75));
commands.setpointScaled(point, (short) 1234);
commands.setpointShortFloat(point, 21.5f);
commands.bitstring(point, 0x0000_00FF);
```

A few notes on the value types:

- **`DoubleCommandState`** has four constants, but only `OFF` and `ON` are operable; `NOT_PERMITTED0`
  and `NOT_PERMITTED3` exist so the reserved wire encodings round-trip and are not normally sent.
- **`StepCommandState`** likewise: use `NEXT_STEP_LOWER` or `NEXT_STEP_HIGHER`; `INVALID0` and
  `INVALID3` are the reserved "not permitted" codes.
- **`NormalizedValue.of(double)`** scales a fraction in the range `[-1, 1 - 2^-15]` to the 16-bit wire
  value, rounding and saturating outside those bounds — so `NormalizedValue.of(2.0)` clamps rather than
  round-trips. For exact wire control use the `new NormalizedValue(short rawValue)` record directly.

For which command type maps to a given real-world signal, see
[Choosing a point type](../reference/choosing-a-point-type.md).

## Reading the result

A `CommandResult` is a record with four components:

```java
record CommandResult(PointAddress target, boolean positive, Cause cause, Optional<Asdu> confirmation)
```

- `target()` — the point the command addressed.
- `positive()` — `true` when the controlled station confirmed the command positively (the confirming
  ASDU's P/N bit was clear); `false` on a negative confirmation.
- `cause()` — the [cause of transmission](../reference/glossary.md) of the confirming ASDU.
- `confirmation()` — the confirming `Asdu`, if one was received.

The key rule: **a rejection is not an exception.** A station that understands a command but declines it
returns a non-positive `CommandResult` — you read `positive()`, you do not catch anything. Only
transport- or session-level failures (a timeout waiting for confirmation, or a dropped link) throw.
Both thrown types are unchecked, so the `try`/`catch` is optional — add it only where you want to react
to transport failure:

```java
try {
  CommandResult result = commands.single(SWITCH, true);
  if (!result.positive()) {
    // Station understood the command but declined it; cause() carries why.
    handleRejection(result.cause());
  }
} catch (ProtocolTimeoutException e) {
  // No confirmation arrived within the configured command timeout.
} catch (ConnectionClosedException e) {
  // The link dropped while the command was in flight.
}
```

For the full picture of which operations throw versus return a result object, and why, see the
[Error model](../reference/errors.md) reference.

## Direct execute vs select-before-operate

IEC 104 defines two command procedures, distinguished on the wire by the select/execute (S/E) flag.

[**Direct execute**](../reference/glossary.md) sends a single activation with S/E = 0; the station acts
immediately. This is what the convenience helpers do.

[**Select-before-operate (SBO)**](../reference/glossary.md) is a two-step procedure: a *select*
activation (S/E = 1) that the station confirms, followed by an *execute* activation (S/E = 0) that the
station confirms. The select step lets the station reserve and validate the output before it operates.

The mode is chosen by the `CommandMode` you pass to `send`, not by the `Command`. Build a command and
ask for SBO:

```java
Command command = Command.single(SWITCH, true);
CommandResult result = commands.send(command, CommandMode.selectBeforeOperate());
// The returned result reflects the EXECUTE confirmation.
if (!result.positive()) {
  handleRejection(result.cause());
}
```

The returned `CommandResult` reflects the **execute** confirmation. If the select step is *not*
confirmed positively, the operate step is skipped and the non-positive select confirmation is reported
in the result — you still read it the same way, through `positive()` and `cause()`, and nothing is
thrown.

Choose the mode to match the station and the criticality of the output:

| Mode | What goes on the wire | Use when | Consequence |
|---|---|---|---|
| `CommandMode.directExecute()` (helpers' default) | One activation, S/E = 0; the station acts immediately | Routine controls; the station does not require reservation; lowest latency | No reservation step — a mistargeted command operates immediately |
| `CommandMode.selectBeforeOperate()` | Select activation (S/E = 1) → confirm → execute activation (S/E = 0) → confirm | Safety-critical outputs (breakers, tap changers) where the station must reserve or validate the output first; or a station that requires SBO | Two round-trips; a station that requires SBO rejects a direct execute, and vice-versa — the two ends must agree |

## The activation lifecycle

A command is an [activation](../reference/glossary.md). You send an ASDU with cause
`ACTIVATION`; the station answers with an `ACTIVATION_CONFIRMATION` whose P/N bit reports accept or
reject. Unlike a [general interrogation](connect-and-interrogate.md), a command does **not** produce a
separate activation-termination step on the client side — the activation confirmation *is* the result.
`CommandResult.cause()` is the [cause of transmission](../reference/glossary.md) of that confirming
ASDU, and `CommandResult.positive()` is the inverse of its P/N bit. For the underlying timing and
window mechanics, see the [Timers & window](../reference/timers-and-window.md) reference; you do not
need them to send a command.

## Command qualifiers

Every command except bit-string carries a qualifier value: `QU` for the single, double, and
regulating-step commands, and `QL` for the set-points. It is exposed as a plain `int` on the `Command`,
separate from the select/execute flag:

| Command family | Qualifier field | Range | Helper default |
|---|---|---|---|
| Single / Double / Regulating-step | `QU` | 0..31 | 0 |
| Set-point (normalized / scaled / short-float) | `QL` | 0..127 | 0 |
| Bit-string | — | always 0 | 0 |

The convenience helpers and the `Command` static factories all use qualifier `0`. To send a non-zero
qualifier, construct the request record directly and call `send`:

```java
// A QU/QL value other than 0 — build the request record directly.
Command shortPulse =
    new Command.SingleCommandRequest(SWITCH, true, 1, Optional.empty()); // QU = 1
CommandResult result = commands.send(shortPulse, CommandMode.directExecute());
```

The compact constructor validates the qualifier against its range (here `0..31`) and throws
`IllegalArgumentException` if it is out of bounds. The library models the qualifier as a raw `int` and
does not enumerate its meanings (for example "short pulse" / "long pulse" / "persistent" for the
single-command `QU`); consult the companion IEC 60870-5-101 standard for the `QU`/`QL` semantics, and
see the [Glossary](../reference/glossary.md) for the terms.

## With or without a time tag

A `Command` carries an `Optional<Instant>` time tag via `time()`. When it is present, the service uses
the time-tagged TypeID (for example `C_SC_TA_1`); when it is absent, the untimed TypeID (for example
`C_SC_NA_1`). The convenience helpers and static factories all produce untimed commands; to attach a
time tag, build the request record with `Optional.of(...)`:

```java
Command timed =
    new Command.SingleCommandRequest(SWITCH, true, 0, Optional.of(Instant.now()));
CommandResult result = commands.send(timed, CommandMode.directExecute());
// Because time() is present, the service uses C_SC_TA_1 instead of C_SC_NA_1.
```

The time-tagged variants exist for each command family (`C_SC_TA_1`, `C_DC_TA_1`, `C_RC_TA_1`,
`C_SE_TA_1`, `C_SE_TB_1`, `C_SE_TC_1`, `C_BO_TA_1`); see the
[Coverage matrix](../reference/coverage-matrix.md) for the full list and their records.

## Async variant

Every send has a non-blocking form. `sendAsync(Command, CommandMode)` returns a
`CompletionStage<CommandResult>`: transport and session failures complete the stage exceptionally, and
a station rejection is still delivered as a non-positive result on the normal path.

```java
commands.sendAsync(Command.single(SWITCH, true), CommandMode.directExecute())
    .whenComplete((result, ex) -> {
      if (ex != null) {
        // transport/session failure (timeout or disconnect)
      } else if (!result.positive()) {
        handleRejection(result.cause());
      }
    });
```

The stage completes on the client's callback executor, and completions for a connection are serialized
there alongside event delivery — see [Handle events](handle-events.md) and the architecture's
[buffers and threading](../../architecture/buffers-and-threading.md) note for the serialization
guarantee.

## Server side, briefly

The positive or negative confirmation you read on the client is produced by the controlled station's
handler. A `ServerHandler` decides on each command in `onCommand`, returning a `CommandDecision`:
`accept()` to confirm without changing the value image, `acceptAndUpdate(value)` to confirm and write
back (the new value returns to you as monitor data), or `reject(cause)` to confirm negatively. This is
exactly what `ServerExample` does for its commandable switch:

```java
ServerHandler handler = new ServerHandler() {
  @Override
  public CommandDecision onCommand(ServerContext context, CommandRequest request) {
    if (request.target().equals(SWITCH)
        && request.commandObject() instanceof SingleCommand command) {
      return CommandDecision.acceptAndUpdate(PointValue.single(command.on()));
    }
    return CommandDecision.reject(Cause.UNKNOWN_INFORMATION_OBJECT_ADDRESS);
  }
};
```

Note that `CommandRequest` carries a server-side `CommandMode` whose constants are `SELECT` and
`EXECUTE` (with `request.isSelect()`) — a different enum from the client-side `CommandMode`
(`DIRECT_EXECUTE` / `SELECT_BEFORE_OPERATE`) used above, distinguishing the two SBO phases as the
station sees them. Building a controlled station, handling the two SBO phases, and emitting return
information are covered in full by [Host a server](host-a-server.md); this snippet is only here so the
client-side result makes sense.

## See also

- [Connect & interrogate](connect-and-interrogate.md) — connect a client and read monitor data (the
  companion recipe; how you get a connected client).
- [Host a server](host-a-server.md) — the mirror image: `ServerHandler.onCommand`, `CommandDecision`,
  and SBO on the outstation side.
- [Handle events](handle-events.md) — the threading rules and the `NegativeConfirmation` /
  `ProtocolWarning` events; async result delivery.
- [Error model](../reference/errors.md) — rejection-as-result versus thrown transport failures.
- [Choosing a point type](../reference/choosing-a-point-type.md) — which command type maps to your
  output signal.
- [Coverage matrix](../reference/coverage-matrix.md) — the timed and untimed command TypeIDs and their
  records.
- [Glossary](../reference/glossary.md) — SBO, S/E, `QU`/`QL`, COT, activation confirmation.
- [Two-layer API](../../architecture/two-layer-api.md) — how the high-level facade correlates a command
  with its confirmation.
- [Examples README](../../../iec60870-examples/README.md) — how to run `ClientExample` and
  `ServerExample`.
