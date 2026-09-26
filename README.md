# Yukon

A Java agent that instruments a running JVM application to find code that's
reachable but never actually exercised at runtime: unused endpoints, methods
that are never invoked, and conditionals that only ever take one branch.

Attach it with `-javaagent`, run your app, and it reports which of those
probes never fired.

## How it works, briefly

- Method-level probes (ByteBuddy `Advice`) catch unused methods, endpoints,
  and classes.
- Branch-level probes (raw ASM, woven into the same transform pass) catch
  conditionals and switches that only ever take one path.
- The agent batches hit counts and pushes them to a collector on a fixed
  interval (OTLP-style: delta batches plus an incremental probe manifest)
  instead of exposing them for scraping, so a collector can aggregate
  results across every instance in a fleet.

## Build

```
./gradlew build
```

Produces a shaded agent jar at `build/libs/yukon-<version>.jar` with
ByteBuddy and protobuf relocated, so it won't collide with copies already on
the target application's classpath.

### Benchmark the transform-time analysis

```
./gradlew jmh
./gradlew jmh -Pyukon.benchmark.corpus=demo
./gradlew jmh -Pyukon.benchmark.corpus=demo,scala
```

A JMH benchmark in `src/jmh/kotlin` times the branch analyser over five
corpora of real class files: `demo`, `demo-spring`, `scala` (both Scala
fixture modules), `spring-webmvc` (the jar `demo-spring` resolves) and
`ktor-server-core` (the jar `endpoints-ktor-3` tests against). The first command runs all five, about 40
seconds each. `-Pyukon.benchmark.corpus` picks one or more by name. The
score is the average time to analyse the whole corpus once. Each run prints
the corpus's class count, so the time per class is the score divided by that
count. Results go to `build/results/jmh/results.txt`.

## Attach it to an app

```
-javaagent:/path/to/yukon-<version>.jar=serviceName=my-service,endpoint=http://localhost:4319,includePackages=com.acme.myservice
```

Options (comma-separated `key=value`, `includePackages`/`excludePackages` use
`;` to separate multiple prefixes; a value cannot itself contain a comma, so
set one that needs to through the system property or environment variable
described below):

| Option | Default | Meaning |
|---|---|---|
| `serviceName` | detected, else `unknown_service:java` | Reported to the collector. Falls back to OpenTelemetry's settings and then to detection; see "Reading OpenTelemetry's settings" below. |
| `serviceNamespace` | *(none)* | The group the service belongs to, as OpenTelemetry's `service.namespace`. A service is known by its namespace and its name together. Falls back to OpenTelemetry's settings. With none, the service is in the unspecified namespace. |
| `serviceVersion` | *(none)* | Reported to the collector. |
| `serviceInstanceId` | random UUID | Reported to the collector. |
| `environment` | *(none)* | Reported to the collector. Falls back to OpenTelemetry's `deployment.environment.name`, then `deployment.environment`. |
| `endpoint` | `http://localhost:4319` | Collector base URL. |
| `authToken` | *(none)* | Bearer token sent to the collector as `Authorization: Bearer <token>`. Prefer setting it through `YUKON_AUTH_TOKEN` rather than this option: agent arguments are visible to every user on the host via `ps`, and an environment variable is not. |
| `flushIntervalSeconds` | `60` | How often deltas/manifest updates are sent. |
| `includePackages` | *(required)* | Only instrument types whose name starts with one of these prefixes, `;`-separated. Without it the agent logs an ERROR and stays disabled for the life of the JVM: nothing is instrumented and nothing is exported. The ERROR suggests the main class's package when it can find one. |
| `excludePackages` | *(none)* | Never instrument types whose name starts with one of these prefixes, `;`-separated, even if `includePackages` also matches them. Exclusion always wins. |
| `staticBaselineEnabled` | `false` | Scan the classpath once at startup (async, off the critical path) for classes under `includePackages` that never load at all. Off by default: unlike every other option here, a full classpath walk has a cost that scales with the classpath's size. |
| `enabled` | `true` | Set to `false` to turn the agent off entirely: nothing is instrumented and nothing is exported. Meant to be set from `YUKON_ENABLED` so a deployment can disable the agent without rebuilding the image that bakes in `-javaagent`. |
| `endpointsEnabled` | `true` | Set to `false` to switch off every framework endpoint module (Spring MVC, Ktor, JAX-RS, the JDK's `HttpServer`) at once. There are no per-framework flags. |
| `otelBridgeEnabled` | `false` | Also count the route OpenTelemetry's own HTTP server instrumentation resolved, for a framework no endpoint module covers. Off by default because it hooks OpenTelemetry internals rather than a framework's public registration API. |

## Where an option's value comes from

Every option above can be set three ways, in this precedence order: the
agent-args string wins, then a JVM system property, then an environment
variable, then the option's own default. A blank value at any level counts
as unset and falls through to the next one.

The property and environment variable names are derived mechanically from
the option name: split it on camelCase boundaries, then join with `.` and
lowercase it for the property (prefixed `yukon.`), or join with `_` and
uppercase it for the environment variable (prefixed `YUKON_`). For example:

- `serviceName` → system property `yukon.service.name`, environment variable `YUKON_SERVICE_NAME`
- `flushIntervalSeconds` → system property `yukon.flush.interval.seconds`, environment variable `YUKON_FLUSH_INTERVAL_SECONDS`

## Reading OpenTelemetry's settings

A service that already runs OpenTelemetry has named itself once in
OpenTelemetry's settings. Yukon reads those, so its findings carry the same
name as the service's traces. The service name, the namespace and the
environment each come from the first of these sources that gives a value
that is not blank, and each value is trimmed:

1. Yukon's own three sources above: the agent-args string, the `yukon.*`
   system property, then the `YUKON_*` environment variable.
2. OpenTelemetry's own settings, resolved as its Java agent resolves them:
   each of `otel.service.name` and `otel.resource.attributes` from its
   system property, else its environment variable (`OTEL_SERVICE_NAME`,
   `OTEL_RESOURCE_ATTRIBUTES`); the name from `otel.service.name`, else
   `service.name` in the attributes.
3. For the name only, detection in the order OpenTelemetry's Java agent
   uses: the Spring Boot application name (a `--spring.application.name`
   argument, the `spring.application.name` system property,
   `SPRING_APPLICATION_NAME`, then `application.properties`,
   `application.yml` and `application.yaml` in the working directory and on
   the class path, then `bootstrap.*` on the class path), then the main
   jar's `Implementation-Title`, then the main jar's file name.
4. For the name only, OpenTelemetry's default `unknown_service:java`.

The resource-attribute keys are `service.name`, `service.namespace`, and
`deployment.environment.name`, then the older `deployment.environment`.
Each OpenTelemetry setting comes whole from one place, so a set
`otel.resource.attributes` system property hides `OTEL_RESOURCE_ATTRIBUTES`
entirely, and `OTEL_SERVICE_NAME` wins over a `service.name` inside that
system property. A list is `key=value` pairs split by commas, with
percent-encoded values. If any pair is not `key=value`, Yukon ignores the
whole list and logs a warning, as the OpenTelemetry specification says. Set
a Yukon option to name the service differently in Yukon on purpose, since
Yukon's own sources win.

In Kubernetes, the OpenTelemetry Operator sets `service.namespace` from the
pod's `resource.opentelemetry.io/service.namespace` annotation, or else from
the pod's Kubernetes namespace, and passes it in `OTEL_RESOURCE_ATTRIBUTES`.
Yukon reads it from there. Set `YUKON_SERVICE_NAMESPACE` to override it.
Yukon never works out a namespace for itself.

Yukon needs somewhere to send data to. See the `demo` module below for a
minimal stub, or point it at a real collector.

## Try the demo

```
./gradlew :demo:runDemo
```

Runs a stub collector, an instrumented demo server (`/checkout`, always hit
one way; `/promo`, never called), and a client that drives the server, all as
separate JVM processes. On shutdown, the stub collector prints a report of
probes that were never hit and any classes it had to skip.

A never-hit branch prints as its condition, the result that never happened,
and the lines that run only through that result. Its branch index stays in
brackets at the end. A condition reads the way the code just after the check
sees it, which for a plain `if` is the condition the source wrote. The demo's
two checkout conditions print like this:

```
NEVER HIT: handleCheckout (DemoServerMain.kt:71) `System.getenv("ENABLE_LEGACY_DISCOUNT") == "true"` was never true, only path to DemoServerMain.kt:72 (instance 17e3afc3-76f1-435c-b30a-5e594350530f, class 0, probe 10) [BRANCH branch#1]
NEVER HIT: handleCheckout (DemoServerMain.kt:78) `discounted > 100.0` was never true, only path to DemoServerMain.kt:79, partly to DemoServerMain.kt:92 (instance 17e3afc3-76f1-435c-b30a-5e594350530f, class 0, probe 12) [BRANCH branch#3]
```

A top-level function prints with its file, as `handleCheckout
(DemoServerMain.kt)`, rather than as a member of `DemoServerMainKt`, the
class kotlinc makes for the file. The agent reads that from the kind kotlinc
writes into each class's `kotlin.Metadata`, never from the name (ADR 0041).
The demo also has two files that `@file:JvmMultifileClass` joins into one
class, `DemoText`. That class holds only forwarders, one per function, and
the code lives in one part class per file. The checkout handler calls
`checkoutGreeting` through the forwarder, and the agent follows the call to
the part, so the forwarder is marked generated and never reported. Nothing
calls `farewellNote`, so its part never loads and prints by its file:

```
NEVER LOADED: DemoTextFarewell.kt (methods: farewellNote)
```

Some findings are about a whole class, not a method in it (yukon-server's
ADR 0034). `main` names `AuditLog` and `ReceiptPrinter` through class
literals, which load a class without initialising it. `AuditLog` is an
object, so its static initialiser is where its one instance is made; that
never runs, so the class is never initialised. `ReceiptPrinter` has no
static initialiser, but it declares an instance method and none of its
constructors ran, so it is never instantiated. The report prints both, and
the never-hit list leaves out the methods that can only run through them:

```
NEVER INITIALISED: io.github.lukedevops.demo.server.AuditLog (methods: constructor, record) (instances loading: 1)
NEVER INSTANTIATED: io.github.lukedevops.demo.server.ReceiptPrinter (methods: constructor, print, print$lambda$0) (instances loading: 1)
```

A static initialiser is never a row of its own, and a constructor is a row
only as an unused overload: one that never ran while another constructor
of its class did. The checkout handler builds every `Money` from pence, so
`Money`'s pounds-and-pence constructor is one. `Price` has one constructor
with `@JvmOverloads`, and the checkout always passes its scale, so the
extra overload kotlinc adds never runs either. The agent marks that
overload generated, so it is never reported. Constructors print with their
parameter types:

```
NEVER HIT: io.github.lukedevops.demo.server.Money#constructor(int, int):10 [CONSTRUCTOR, unused overload] (instance 17e3afc3-76f1-435c-b30a-5e594350530f, class 5, probe 1)
```

The last report groups those never-hit probes into unreached clusters: a
root that's either reached from code that does run, never called at all, a
branch outcome that never ran in a method that did, or a class that holds a
class finding, plus every never-hit method beneath it whose only callers are
also in the cluster. `/promo`'s handler calls a helper that calls a
repository method neither the handler nor anything else ever reaches, so it
prints as one four-method cluster: the handler, its helper, and the
repository's method and constructor. The repository class never loads at
all in this demo, and the cluster holds every method of it, so it prints
once as a whole class with its finding. A call into a class counts as a
call into its static initialiser, which is why the whole class follows the
handler into the cluster; the initialiser itself is never listed or
counted. The handler is a named class, so the endpoint record names its
`handle` method and the route is printed beside the root; nothing in the
demo's own code calls `handle`, only the server does, which is why the root
is uncalled rather than reached from hit:

```
UNREACHED CLUSTER: root io.github.lukedevops.demo.server.PromoHandler#handle (uncalled), 4 methods, 1 never-loaded classes routes=[* /promo]
  io.github.lukedevops.demo.server.PromoRepository (whole class, never loaded, 2 methods)
  applyPromoCode (DemoServerMain.kt)
  io.github.lukedevops.demo.server.PromoHandler#handle
```

`/checkout` is registered the other way, as a function reference that
Kotlin converts to the `HttpHandler` interface through `invokedynamic`.
That handler is a hidden class with no stable name. The agent records
which method it calls as the JDK spins it (ADR 0035), so the two shapes
sit side by side in the endpoint report: `* /promo` names
`PromoHandler#handle`, and `* /checkout` names
`DemoServerMainKt#handleCheckout`.

A second cluster starts at the branch the demo never takes. The untaken
side of the checkout handler's `if` constructs `LegacyDiscountCalculator`,
calls its `apply`, and reads a static field of `LegacyRates`, which runs
that object's static initialiser and so its constructor. Each of those
calls sits behind that one outcome, so the outcome is the root, printed the
way the never-hit report prints it, followed by the method that holds it.
Deleting that side of the `if` removes both classes whole:

```
UNREACHED CLUSTER: root `System.getenv("ENABLE_LEGACY_DISCOUNT") == "true"` was never true, only path to DemoServerMain.kt:72, in handleCheckout (DemoServerMain.kt:71) (untaken outcome), 3 methods, 2 never-loaded classes routes=[* /checkout]
  io.github.lukedevops.demo.server.LegacyDiscountCalculator (whole class, never loaded, 2 methods)
  io.github.lukedevops.demo.server.LegacyRates (whole class, never loaded, 1 methods)
```

A class that holds a class finding roots a cluster of its own when nothing
calls it, or when a method that ran does. It is listed only when the
cluster holds more than the methods the finding folds, since the class
findings report already names the class. A lambda body goes with the
methods that create it: `ReceiptPrinter`'s `print` hands a lambda to
`joinToString`, and kotlinc compiles that lambda to a static method of the
class, which folds into the class finding with `print`. So neither
`AuditLog` nor `ReceiptPrinter` roots a listed cluster.

A never-hit method called from a method that ran, and not from behind an
untaken outcome, is a root "reached from hit", and the report names the
callers after it. The checkout handler also calls its response helper
through a function reference; the class the compiler generates for that
reference is reached from the handler and never appears in a cluster.

## Run the demo against a real collector

```
./gradlew :demo:runDemoStack
```

Runs the same instrumented demo server and client, but against a real
collector instead of the stub, then prints what the backend behind it
reports: probe and class counts, the never-hit probes, the never-loaded,
never-initialised and never-instantiated classes, and the unreached
clusters. It expects a collector at `http://localhost:4319` and the
yukon-server read API at `http://localhost:4320`, which is what
yukon-server's `docker compose --profile stack up --build` provides. Every
address and credential can be overridden:

| Property | Default |
|---|---|
| `-PyukonEndpoint` | `http://localhost:4319` |
| `-PyukonAgentToken` | `local-stack-agent-token` |
| `-PyukonServerUrl` | `http://localhost:4320` |
| `-PyukonServerApiKey` | `yk_local-stack-api-key` |
| `-PyukonServiceVersion` | `stack-demo` |

The defaults match the compose stack's own development defaults, so with
the stack up it works with no arguments. Each run registers as a new
instance, and the server keeps everything it has seen, so the report
covers every run of that service and version so far; pass a fresh
`-PyukonServiceVersion` to start a clean slate.

The demo runs in the unspecified namespace. To run it in a named one, set
`YUKON_SERVICE_NAMESPACE` in the environment of the Gradle command; the
task passes it to the demo server, and the report names the namespace.

## Test your app against the agent

The `testkit` module is an embeddable collector for your own tests. It
speaks the agent's real wire protocol, so the agent under test runs exactly
as it does in production: start the collector, point the agent's `endpoint=`
at it, exercise your app, then ask the collector what it saw.

```kotlin
YukonTestCollector.start().use { collector ->
    // Launch your app with
    // -javaagent:yukon.jar=endpoint=${collector.endpoint},flushIntervalSeconds=1,includePackages=com.acme
    // and exercise it, then:
    collector.awaitProbe("com.acme.OrderService", "checkout", Duration.ofSeconds(10))
    collector.awaitNextFlush(Duration.ofSeconds(10))

    assertTrue(collector.wasHit("com.acme.OrderService", "checkout"))
    assertFalse(collector.wasHit("com.acme.OrderService", "applyLegacyPromo"))
    assertTrue(collector.neverHit().isEmpty())
}
```

Class names are the dotted binary names the manifest carries
(`com.acme.OrdersKt` for a Kotlin file's top-level functions,
`com.acme.Outer$Inner` for a nested class). `kotlinKind` says what kind of
class kotlinc made, so a test can tell a file facade from a class without
reading the name. A function in a `@file:JvmMultifileClass` file lives in
its part class, such as `com.acme.Orders__OrderTotalsKt`, not in the facade
Kotlin callers name.

Queries cover methods (`wasHit`, `hitCount`, `neverHit`, `skippedClasses`,
`unreportedClasses`), classes (`neverInitialised`, `neverInstantiated`,
`kotlinKind`), endpoints (`wasCalled`, `callCount`, `neverCalled`,
`endpoints`, `disabledEndpointModules`), optional parameters
(`omissionCount`, `neverSupplied`, `alwaysSupplied`), the call graph
(`callEdges`, `unreachedClusters`), a clean shutdown (`endedCleanly`,
`instancesEndedCleanly`) and, when the agent runs with
`staticBaselineEnabled=true`, `neverLoaded`. Asking
about a probe the collector has never seen throws `UnknownProbeException`
rather than answering `false`; the message says whether the class was
skipped, declared by the static baseline but never loaded, instrumented but
without that method, or never mentioned at all. That keeps "genuinely dead"
and "no idea" from ever looking the same.

The module isn't published yet. Use it from a multi-project build as
`testImplementation(project(":testkit"))`, or build the jar with
`./gradlew :testkit:jar`.

## Design notes

`docs/adr/` records the decisions behind the agent that are hard to reverse
or surprising without context (why probes are a dense per-class array, why
counts are cumulative and merged with `max()`, why some classes are skipped
and reported rather than silently zero). `CONTEXT.md` is the glossary the
agent, the collector, and those records share.

## Status

- Static attach (`-javaagent`) only; no dynamic/runtime attach yet.
- Nothing is published to a package repository yet, the agent jar and
  `testkit` included; both are built from this repo.
- Branch tracking covers two-outcome conditional jumps and
  `TABLESWITCH`/`LOOKUPSWITCH`; `GOTO`/`JSR` aren't tracked (no second
  outcome to observe).
- A small number of classes can't be safely instrumented (for example,
  Kotlin files using `@file:JvmName`). These are skipped and reported, not
  silently dropped from coverage.
- Wire schema (`src/main/proto/yukon.proto`) is published to the Buf Schema
  Registry as `buf.build/lukedevops-oss/yukon` for external consumers (e.g.
  a separately-versioned collector).

## Licence

Apache License 2.0; see `LICENSE`. The agent jar bundles Byte Buddy (with
ASM), protobuf-java, the Kotlin standard library, and the JetBrains
annotations, relocated; their licence texts are under `licenses/` here and
`META-INF/licenses/` in the jar, and `NOTICE` lists them.
