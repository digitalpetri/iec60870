# Running Tests

This is a multi-module project. When running specific tests, target the module using `-pl`
to avoid applying the filter to all modules.

## Running Specific Tests

**Run a specific test class:**

```bash
mise exec -- mvn -q -pl iec60870-core test -Dtest=ClassName
```

**Run a specific test method:**

```bash
mise exec -- mvn -q -pl iec60870-core test -Dtest=ClassName#methodName
```

**Run tests matching a pattern:**

```bash
mise exec -- mvn -q -pl iec60870-core test -Dtest=*CodecTest
```

Use the module that owns the code under test, for example `iec60870-core`,
`iec60870-transport-tcp`, or `iec60870-tests`.

## Module Targeting Flags

- **`-pl <module>`** — build/test only the specified module(s)
- **`-am`** (also-make) — also build modules that the `-pl` target depends on
- **`-amd`** (also-make-dependents) — also build modules that depend on the `-pl` target

### When to use each

**`-pl` alone** — the change is entirely within the module being tested:

```bash
# Changed and testing iec60870-core only
mise exec -- mvn -q -pl iec60870-core test -Dtest=ClassName
```

**`-pl ... -am`** — the change is in a dependency of the module being tested. This
rebuilds the dependency chain so tests run against the latest code:

```bash
# Changed core and testing the Netty module
mise exec -- mvn -q -pl iec60870-transport-tcp -am test -Dtest=ClassName
```

**`-pl ... -amd`** — the change is in a low-level module and you want to test all modules
that depend on it:

```bash
# Changed a shared module, run tests in it and everything that depends on it
mise exec -- mvn -q -pl shared-module -amd test
```

### Rule of thumb

If the code you changed and the tests you're running are in **different modules**, add
`-am` so the changed module gets rebuilt. Omitting `-am` in this case means tests run
against stale (previously compiled) code.

## Run All Tests

```bash
mise exec -- mvn -q clean verify
```

## Shared Test Fixtures

Core-level test doubles reused across modules live in the internal `iec60870-test-support` module
(`com.digitalpetri.iec60870.testsupport`): the deterministic `ManualScheduler` virtual clock, the
`RecordingEvents` `Session.Events` recorder, the frame-capturing `RecordingClientTransport` /
`RecordingServerConnection`, the in-JVM `LoopbackOctetTransport` / `FaultInjectingOctetTransport`
octet transports (which carry their own unit tests in the module), and the `ParanoidLeakDetection`
JUnit extension. Modules consume it at
`test` scope; a module opts into paranoid `ByteBuf` leak detection by shipping a
`META-INF/services/org.junit.jupiter.api.extension.Extension` resource naming
`com.digitalpetri.iec60870.testsupport.ParanoidLeakDetection`. Fixtures that need types above core
(for example the `ClientEvent`-aware `EventCollector`, or the TLS certificate helpers) stay in the
module that owns those types.

`iec60870-test-support` is a reactor dependency like any other, so a targeted `-pl <consumer> test`
run needs it available: add `-am` (which rebuilds it from source) or run `mvn install` once
beforehand. Running `-pl` alone against a stale `~/.m2` can surface as a `NoSuchMethodError` from an
out-of-date `iec60870-core` — the same staleness `-am` exists to avoid.
