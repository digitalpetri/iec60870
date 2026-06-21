# Java Coding Conventions

## Variables and Types

Choose type declarations that make the code's intent immediately clear to readers. While `var` reduces verbosity, explicit types often communicate intent more effectively.

### Type Declarations

Use `var` for local variable declarations ONLY when the type is immediately obvious from the
right-hand side. When in doubt, explicit types improve clarity and maintainability.

**Decision Checklist:**

- ✓ Can you tell the exact type in 1 second? → Use `var`
- ✗ Would you need to check documentation or method signatures? → Use explicit type
- ✗ Is the type generic, an interface, or complex? → Use explicit type
- ✗ Is the variable used far from its declaration? → Use explicit type
- ✗ Does the explicit type reveal important semantic information? → Use explicit type

**What counts as "obvious from the right-hand side":**

- Constructor calls with concrete types: `new ArrayList<String>()`, `new User(...)`
- Literals: strings, numbers, booleans, `null`
- Collection factory methods with only literals: `List.of(1, 2, 3)`, `Map.of("key", "value")`
- Standard library methods with obvious return types: `isEmpty()`, `size()`, `toString()`
- Builder patterns that return the same type: `User.builder().name("John").build()`

```java
// Good: Type is clear from the right-hand side
var list = new ArrayList<String>();
var name = "John";
var count = 42;
var user = new User(id, name, email);
var isEmpty = list.isEmpty();
var items = List.of("a", "b", "c");

// Good: Explicit type when not obvious
InputStream stream = getStream();
Result<User> result = repository.getUser(id);
Function<String, Integer> parser = Integer::parseInt;
List<Item> items = Stream.of(item1, item2).collect(toList());

// Good: Explicit type for interface/abstract return types
Map<String, Object> config = loadConfiguration();
Callable<Data> task = () -> fetchData();

// Good: Explicit type for method chains
ProcessedData result = data.transform().normalize();

// Good: Explicit type for factory methods
User user = User.create(name);
Order order = orderService.findById(id);

// Avoid: Unclear type from the right-hand side
var data = process(); // What type is returned?
var result = calculate(); // Not immediately obvious
var callback = createHandler(); // What functional interface?
```

**When in doubt, prefer explicit types.**

## Imports

Prefer importing classes and using their simple names over inline fully qualified class names.
Fully qualified names add visual clutter and make code harder to read.

**Use imports and simple names:**

```java
import com.inductiveautomation.ignition.gateway.redundancy.types.ProjectState;
import com.inductiveautomation.ignition.gateway.redundancy.types.HistoryLevel;

// Good: Clean and readable
return new RedundancyState(
    NodeRole.Backup,
    ProjectState.Unknown,
    HistoryLevel.Partial,
    activityLevel);
```

**Avoid inline fully qualified names:**

```java
// Avoid: Verbose and cluttered
return new RedundancyState(
    NodeRole.Backup,
    com.inductiveautomation.ignition.gateway.redundancy.types.ProjectState.Unknown,
    com.inductiveautomation.ignition.gateway.redundancy.types.HistoryLevel.Partial,
    activityLevel);
```

**Exception:** Use fully qualified names only when necessary to resolve ambiguity between classes
with the same simple name:

```java
import java.util.Date;

// Acceptable: Resolves ambiguity with java.util.Date
java.sql.Date sqlDate = new java.sql.Date(timestamp);
```

## Nullability

This codebase uses JSpecify. Assume non-null by default; use `@Nullable` only for parameters,
fields, or return types that genuinely accept or return null. Use explicit null checks and
validation at public API boundaries.

### Every package needs `@NullMarked`

Every package MUST carry a `package-info.java` annotated with JSpecify's `@NullMarked`. This applies
to **both the production and the test source trees** — there are no exceptions. When you create a new
package, or add the first class to a test package that has no `package-info.java` yet, add the file
before writing other code.

Test packages are easy to forget, but they matter just as much. A test class that implements or
extends a `@NullMarked` production type from a package that is not itself `@NullMarked` triggers "Not
annotated method overrides method annotated with @NullMarked" inspection warnings. Marking the test
package opts it into the same nullness contract as the code it exercises and clears the warnings.

Production packages additionally document themselves with a package-level Javadoc comment; keep that
convention. Test packages do not need a Javadoc description — the annotation and import alone are
enough:

```java
@NullMarked
package com.digitalpetri.iec104.client;

import org.jspecify.annotations.NullMarked;
```

### Placing `@Nullable` and narrowing nullable values

`@Nullable` is a `TYPE_USE` annotation. Per the [Google Java Style Guide on type-use
annotations](https://google.github.io/styleguide/javaguide.html#s4.8.5-annotations), it appears
immediately before the annotated type, after any modifiers (`private @Nullable Iec104Client client;`,
not `@Nullable private Iec104Client client;`). Place it on fields, method parameters, return types,
and type arguments — never on local variables (the IntelliJ inspection rejects `@Nullable` on a local
with "Nullability annotation is not applicable to local variables"). On a nested or fully-qualified
type the annotation binds to the simple type name, not the qualifier:

```java
AtomicReference<@Nullable Throwable> closeCause = new AtomicReference<>();
AtomicReference<Flow.@Nullable Subscription> subscription = new AtomicReference<>();
private java.net.@Nullable SocketAddress remoteAddress;
```

Because a local cannot be annotated, narrow a `@Nullable` value to non-null at the use site rather
than declaring a nullable local. Prefer `java.util.Objects.requireNonNull(value)` in production and
utility code; in tests use JUnit's `assertNotNull(value, message)` (the inspector honors its
contract). This commonly applies to a lifecycle field assigned in `@BeforeEach`/`@BeforeAll` and
guarded for `null` in teardown: annotate the field `@Nullable`, then capture a non-null reference
where the test body uses it.

```java
private @Nullable Iec104Client client;

@AfterEach
void tearDown() {
  if (client != null) {
    client.close();
  }
}

@Test
void connects() {
  client = TcpIec104Client.builder()./* ... */.build();
  Iec104Client client = requireNonNull(this.client);
  client.connect();
}
```

When a field is read across many test methods, a small private accessor that narrows once
(`private Iec104Client client() { return requireNonNull(this.client); }`) is cleaner than repeating
the narrowing in every method.

## Documentation

- Document public APIs with Javadoc

- Focus on why, not what (code should be self-documenting for "what")

- Keep documentation up to date with code changes

- Javadoc tag descriptions MUST begin with a lowercase letter and MUST end with a period

  ```java
  /**
   * Creates a new connection to the server.
   *
   * @param endpoint the server endpoint URL.
   * @param timeout the connection timeout in milliseconds.
   * @return the established connection.
   * @throws IOException if the connection fails.
   */
  ```

## Other

For any coding practices not explicitly covered by these conventions, defer to established Java best
practices and community standards. This codebase uses Java 17.
