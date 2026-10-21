# How-To: Monitor connections

The event stream tells you what data arrived. It does not tell you how the link below it is doing:
how many frames went each way, whether `t1` keeps expiring, why a background reconnect keeps
failing, or what the bytes of a bad frame were. For that, give the client or server a
**diagnostics listener**. It is off by default and costs nothing until you configure it.

This page covers four tools:

- `ApciDiagnostics`, the listener interface. Override only the callbacks you need.
- `ApciCounters`, a ready-made listener that counts everything, with a snapshot you can read from
  any thread.
- `FrameTrace.logger(Logger)`, a listener that writes each frame as one trace-level log line.
- `loggingContext(Map<String, String>)`, MDC entries that tag every library log line for a
  connection.

## Count frames and errors on a client

```java
ApciCounters counters = new ApciCounters();

Iec60870Client client = TcpIec104Client.builder()
    .host("10.0.0.5")
    .port(2404)
    .diagnostics(counters)
    .build();

client.connect();

// Later, from a health check on any thread.
ApciCounters.Snapshot s = counters.snapshot();
log.info("I out={} in={} t1={} reconnects={} queued={}",
    s.iFramesSent(), s.iFramesReceived(), s.t1Expired(), s.reconnectsScheduled(),
    client.pendingSendCount());
```

One listener instance receives everything for the connection, including every automatic reconnect:
connect attempts and their causes from the transport, frame and timer events from the session, and
closed connections, reconnects, and inbound pauses from the client.

The snapshot fields:

| Field | Counts |
|---|---|
| `iFramesSent`, `iFramesReceived`, `sFramesSent`, `sFramesReceived`, `uFramesSent`, `uFramesReceived` | APDUs by format and direction |
| `bytesSent`, `bytesReceived` | frame octets, including the start and length octets |
| `testFramesSent`, `testFramesReceived` | TESTFR act and con U-frames |
| `t1Expired`, `t2Expired`, `t3Expired` | timer expiries; each `t1` expiry closed a connection |
| `windowFull` | stalls with `k` unacknowledged I-frames and ASDUs waiting |
| `framesIgnored` | U-frames the session ignored, such as an unsolicited TESTFR con |
| `sequenceErrors`, `stateErrors`, `decodeErrors` | bad N(S) or N(R), I-frames while stopped, frames or ASDUs that failed to decode |
| `connectAttempts`, `connectFailures`, `connectionsOpened`, `connectionsClosed`, `reconnectsScheduled` | the connection lifecycle |
| `inboundPauses` | times the client paused reading because a subscriber fell behind |
| `eventsDropped`, `inboundAsdusDropped`, `outboundAsdusDropped` | events and ASDUs discarded instead of delivered or sent |

Each field is a `LongAdder`, so updates are lock-free. A snapshot reads the fields one after another;
under load, an update that runs during the call may show in one field and not yet in another.

Two gauges live on the client rather than the listener: `pendingSendCount()` is the session's send
queue depth (a depth that keeps growing means the peer stopped acknowledging), and
`pendingRequestCount()` is the number of requests awaiting a response.

## Count across every server connection

The server builder takes a function from the remote address to a listener. It runs once per
accepted connection, so return a shared instance for totals or a new one per connection:

```java
ApciCounters totals = new ApciCounters();

Iec60870Server server = TcpIec104Server.builder()
    .port(2404)
    .addStation(station)
    .diagnostics(remote -> totals)
    .loggingContext(Map.of("site", "substation-7"))
    .build();
```

The server also reports what it would otherwise discard silently: connections rejected at
`maxConnections`, inbound ASDUs dropped because the dispatch backlog reached `maxInboundQueue` (the
link layer has already acknowledged those, so the peer will not resend them), monitor ASDUs dropped
by the queue policy, and events dropped for a slow subscriber.

## Write your own listener

Override the callbacks you care about. This one alarms on reconnect causes and keeps the bytes of the
last frame that failed to decode:

```java
ApciDiagnostics alarms = new ApciDiagnostics() {
  @Override
  public void onConnectFailed(SocketAddress remote, Throwable cause) {
    alarmQueue.offer("connect to " + remote + " failed: " + cause);
  }

  @Override
  public void onDecodeError(ByteBuf frame, RuntimeException cause) {
    lastBadFrame = ByteBufUtil.getBytes(frame); // copy; the buffer is only lent
  }
};

Iec60870Client client = TcpIec104Client.builder()
    .host("10.0.0.5")
    .diagnostics(ApciDiagnostics.of(counters, alarms))
    .build();
```

`ApciDiagnostics.of(...)` combines listeners. Rules for every listener:

- **Return quickly and never block.** Callbacks run on Netty I/O threads, the timer thread, or the
  thread that wrote a frame, often while the session lock is held. Hand slow work to your own
  executor.
- **Do not call back into the client, server, or session** from a callback.
- **Copy, never keep, a lent buffer.** `onDecodeError` and `onFrame` lend a read-only view for the
  duration of the call. Do not retain or release it.
- **Exceptions are contained.** The library catches and logs what a listener throws; it never closes
  a connection because of one.

## Trace raw frames

`FrameTrace.logger(Logger)` writes one line per frame while the logger has trace enabled, so you can
turn the trace on and off at runtime through your logging configuration:

```java
Logger traceLogger = LoggerFactory.getLogger("iec104.trace.rtu-5");

Iec60870Client client = TcpIec104Client.builder()
    .host("10.0.0.5")
    .diagnostics(ApciDiagnostics.of(counters, FrameTrace.logger(traceLogger)))
    .build();
```

```
OUT U(STARTDT_ACT) 68 04 07 00 00 00
IN U(STARTDT_CON) 68 04 0b 00 00 00
OUT I(N(S)=0 N(R)=0) 68 0e 00 00 00 00 64 01 06 00 01 00 00 00 00 14
```

To capture frames yourself, override `traceFrames()` and `onFrame(Direction, ByteBuf)`. The
library creates the frame view only while `traceFrames()` returns `true`. Trace lines carry the
connection's [logging context](#tag-log-lines-with-a-logging-context), so one trace logger can serve
many connections. Unlike a packet capture, the trace shows plaintext frames on a TLS connection.

## Tag log lines with a logging context

Library log lines for a connection carry SLF4J MDC entries. The client always sets
`iec60870.remote=<host>:<port>`, and the server sets `iec60870.remote` to each accepted connection's
address. Add your own entries:

```java
TcpIec104Client.builder()
    .host("10.0.0.5")
    .loggingContext(Map.of("device", "rtu-5"))
    .build();
```

Show them with your logging backend's MDC pattern, for example `%X{device} %X{iec60870.remote}` in
Logback. The library sets the entries only around its own log calls, and only when the level is
enabled, so they never leak onto shared I/O threads. Messages also name the remote address, for
patterns that omit the MDC.

## Manual assembly

When you assemble a client or server by hand, pass the same knobs to each piece:
`NettyClientTransportConfig.Builder.diagnostics(...)` and `.loggingContext(...)`, the six-argument
`Cs104Binding` constructor, and `ClientConfig.Builder.diagnostics(...)` and `.loggingContext(...)`.
On the server, use `NettyServerTransportConfig.Builder` and `ServerConfig.Builder`, and implement
the four-argument `DefaultIec60870Server.SessionFactory.create(...)` to pass each connection's
listener to `Cs104Binding.bindServer(...)`.

## See also

- [Handle events](./handle-events.md) — the event stream, including `ProtocolWarning`.
- [Tune the APCI session](./tune-apci.md) — the `k`/`w` window and timers these counters report on.
- [Reference: Timers & window](../reference/timers-and-window.md) — what `t1`, `t2`, and `t3` mean.
