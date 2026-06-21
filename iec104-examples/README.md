# IEC 104 Examples

Runnable, documentation-first examples for the IEC 60870-5-104 library. Each class has a
`public static void main` and is meant to be read as much as run.

| Class | What it shows |
| --- | --- |
| `ServerExample` | A controlled station: hosts a single point, a scaled measured value, and a commandable point; accepts a command and updates the point image; periodically publishes a measured value. |
| `ClientExample` | A controlling station: connects, STARTDT, subscribes to events, runs a general interrogation, sends a single command (direct execute), synchronizes the clock, then lingers for spontaneous updates. |
| `RawAsduExample` | The protocol layer without the facade: builds an `Asdu` by hand, encodes it to bytes, prints the hex, and decodes it back. |
| `TlsExample` | The client/server pair secured with TLS, configured from a keystore/truststore supplied via system properties. |

## Running

The examples module is not published; build the reactor first so the dependencies are available:

```bash
mise exec -- mvn -q -pl iec104-examples -am compile
```

### With `exec:java`

```bash
mise exec -- mvn -q -pl iec104-examples exec:java \
    -Dexec.mainClass=com.digitalpetri.iec104.examples.RawAsduExample
```

`RawAsduExample` needs no peer. For the client/server pair, start the server in one shell and the
client in another:

```bash
# shell 1
mise exec -- mvn -q -pl iec104-examples exec:java \
    -Dexec.mainClass=com.digitalpetri.iec104.examples.ServerExample

# shell 2
mise exec -- mvn -q -pl iec104-examples exec:java \
    -Dexec.mainClass=com.digitalpetri.iec104.examples.ClientExample
```

`ServerExample` binds port 2404 and runs until interrupted (Ctrl+C); `ClientExample` connects to
`127.0.0.1:2404`, exercises the request operations, lingers a few seconds for spontaneous updates,
then exits.

### With `java -cp`

After a build you can also run from the classpath directly:

```bash
CP=$(mise exec -- mvn -q -pl iec104-examples dependency:build-classpath \
    -Dmdep.outputFile=/dev/stdout -DincludeScope=runtime | tail -1)
java -cp "iec104-examples/target/classes:$CP" \
    com.digitalpetri.iec104.examples.ClientExample
```

### TLS example

`TlsExample` reads its key and trust material from system properties and prints instructions if
they are missing. Generate a self-signed PKCS#12 keystore and a matching truststore with `keytool`:

```bash
keytool -genkeypair -alias server -keyalg RSA -keysize 2048 \
    -dname "CN=localhost" -ext "san=dns:localhost,ip:127.0.0.1" \
    -keystore server.p12 -storetype PKCS12 -storepass changeit -validity 365
keytool -exportcert -alias server -keystore server.p12 -storepass changeit -file server.crt
keytool -importcert -noprompt -alias server -file server.crt \
    -keystore truststore.p12 -storetype PKCS12 -storepass changeit
```

Then run it (it starts a TLS server and client in-process and interrogates over the secured link):

```bash
mise exec -- mvn -q -pl iec104-examples exec:java \
    -Dexec.mainClass=com.digitalpetri.iec104.examples.TlsExample \
    -Dexec.args="" \
    -Diec104.tls.keystore=server.p12 -Diec104.tls.keystorePassword=changeit \
    -Diec104.tls.truststore=truststore.p12 -Diec104.tls.truststorePassword=changeit
```

## Test

`ExampleInteropTest` starts the `ServerExample` server on an ephemeral loopback port and runs the
client logic against it, asserting that interrogation, a command, and clock synchronization
succeed. Run it with:

```bash
mise exec -- mvn -q -pl iec104-examples -am test
```
