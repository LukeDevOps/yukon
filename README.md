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

## Attach it to an app

```
-javaagent:/path/to/yukon-<version>.jar=serviceName=my-service,endpoint=http://localhost:4319
```

Options (comma-separated `key=value`, `includePackages`/`excludePackages` use
`;` to separate multiple prefixes; a value cannot itself contain a comma, so
set one that needs to through the system property or environment variable
described below):

| Option | Default | Meaning |
|---|---|---|
| `serviceName` | `unknown-service` | Reported to the collector. |
| `serviceVersion` | *(none)* | Reported to the collector. |
| `serviceInstanceId` | random UUID | Reported to the collector. |
| `environment` | *(none)* | Reported to the collector. |
| `endpoint` | `http://localhost:4319` | Collector base URL. |
| `authToken` | *(none)* | Bearer token sent to the collector as `Authorization: Bearer <token>`. Prefer setting it through `YUKON_AUTH_TOKEN` rather than this option: agent arguments are visible to every user on the host via `ps`, and an environment variable is not. |
| `flushIntervalSeconds` | `60` | How often deltas/manifest updates are sent. |
| `includePackages` | *(all)* | Only instrument types whose name starts with one of these prefixes, `;`-separated. |
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

The last report groups those never-hit probes into unreached clusters: a
root that's either reached from code that does run or never called at all,
plus every never-hit method beneath it whose only callers are also in the
cluster. `/promo`'s handler calls a helper that calls a repository method
neither the handler nor anything else ever reaches, so it prints as one
five-method cluster: the handler, its helper, and the repository's method,
static initialiser and constructor, the last three marked never loaded
since that class never loads at all in this demo. A call into a class
counts as a call into its static initialiser, which is why the whole
class follows the handler into the cluster. The handler is a named class,
so the endpoint record names its `handle` method and the route is printed
beside the root; nothing in the demo's own code calls `handle`, only the
server does, which is why the root is uncalled rather than reached from
hit:

```
UNREACHED CLUSTER: root io.github.lukedevops.demo.server.PromoHandler#handle (uncalled), 5 methods, 1 never-loaded classes routes=[* /promo]
  io.github.lukedevops.demo.server.DemoServerMainKt#applyPromoCode
  io.github.lukedevops.demo.server.PromoHandler#handle
  io.github.lukedevops.demo.server.PromoRepository#<clinit> (never loaded)
  io.github.lukedevops.demo.server.PromoRepository#<init> (never loaded)
  io.github.lukedevops.demo.server.PromoRepository#find (never loaded)
```

`/checkout` is registered the other way, as a function reference that
Kotlin converts to the `HttpHandler` interface through `invokedynamic`.
That handler is a hidden class with no stable name, so its endpoint
carries no handler join and the two shapes sit side by side in the
endpoint report: `* /promo` names `PromoHandler#handle`, `* /checkout`
names nothing.

Three smaller clusters follow it, each reached from the checkout handler
under the branch the demo never takes: `LegacyDiscountCalculator`'s
constructor and `apply`, which the branch calls, and `LegacyRates`'
static initialiser, which the branch reaches only by reading a static
field. A static field read counts as a use of the class the same way a
call does. The checkout handler also calls its response helper through a
function reference; the class the compiler generates for that reference
is reached from the handler and never appears in a cluster.

## Run the demo against a real collector

```
./gradlew :demo:runDemoStack
```

Runs the same instrumented demo server and client, but against a real
collector instead of the stub, then prints what the backend behind it
reports: probe and class counts, the never-hit probes, and the never-loaded
classes. It expects a collector at `http://localhost:4319` and the
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
`com.acme.Outer$Inner` for a nested class).

Queries cover methods (`wasHit`, `hitCount`, `neverHit`, `skippedClasses`,
`unreportedClasses`), endpoints (`wasCalled`, `callCount`, `neverCalled`,
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
