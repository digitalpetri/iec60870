# IEC 104

IEC 104 is a Maven / Java 17 implementation of IEC 60870-5-104.

## Tech Stack

- Java 17, managed through `mise` using `.mise.toml`.
- Maven multi-module build.
- Netty for the TCP/TLS transport module.
- JUnit 5 for tests.
- JSpecify for nullness annotations.

## Project Layout

- `pom.xml` is the parent Maven project, artifact `iec104-parent`.
- `iec104-core/` owns the protocol model, serializers/codecs, client/server APIs, and transport
  interfaces.
- `iec104-transport-tcp/` owns the Netty-backed TCP/TLS transport implementation.
- `iec104-tests/` owns cross-module integration tests.

Keep new modules under the parent build and centralize dependency/plugin versions in the root POM.

## Common Commands

- Install pinned tools: `mise install`
- Format the codebase: `mise exec -- mvn spotless:apply`
- Verify the build: `mise exec -- mvn clean verify`
- Compile without tests: `mise exec -- mvn clean compile`
- Download dependency sources: `mise exec -- mvn -q package -DskipTests -Pdownload-external-src`
- Run a specific test class: `mise exec -- mvn -q -pl <module> test -Dtest=ClassName`
  (see `docs/running-tests.md` for module-targeting patterns)
- Run the lib60870-C interop tests (Docker required, excluded by default):
  `TESTCONTAINERS_RYUK_DISABLED=true mise exec -- mvn -pl iec104-interop -am -Pinterop test`
  (see `iec104-interop/README.md` for details)

## Additional References

Before writing code or Javadoc, read the applicable references below. Treat them as required
project instructions, not optional background material.

- Java coding conventions: `docs/java-coding-conventions.md`
- Documentation guidelines: `docs/documentation-guidelines.md`

### Dependency Source Code

To examine dependency source code, check the `external/src` directory at the project root. This
directory contains unpacked source files from all dependencies, organized by package structure for
easy browsing and searching.

#### Setup

If the directory doesn't exist or content is missing, run this command from the project root to
download and unpack all dependency sources:

```bash
mise exec -- mvn -q package -DskipTests -Pdownload-external-src
```

This creates the `external/src` directory with sources from all dependencies in a single top-level
location.

## Verification

- Run `mise exec -- mvn spotless:apply` after code changes and before final validation. Include any
  formatting changes it makes.
- Run `mise exec -- mvn clean verify` before handing off normal code changes; this also runs the
  Spotless check.
- While iterating, prefer targeted checks such as
  `mise exec -- mvn -q -pl iec104-core test -Dtest=ClassName` or
  `mise exec -- mvn -q -pl iec104-transport-tcp -am test -Dtest=ClassName`.
- For documentation-only changes, run the relevant lightweight check when one exists and clearly
  state if the full Maven build was skipped.
- If a verification command cannot be run, report the command, the reason it was skipped or failed,
  and any follow-up needed.
