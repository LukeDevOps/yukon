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

Options (comma-separated `key=value`, `includePackages` uses `;` to
separate multiple prefixes):

| Option | Default | Meaning |
|---|---|---|
| `serviceName` | `unknown-service` | Reported to the collector. |
| `serviceVersion` | *(none)* | Reported to the collector. |
| `serviceInstanceId` | random UUID | Reported to the collector. |
| `environment` | *(none)* | Reported to the collector. |
| `endpoint` | `http://localhost:4319` | Collector base URL. |
| `authToken` | *(none)* | Bearer token sent to the collector as `Authorization: Bearer <token>`. Prefer the `YUKON_AUTH_TOKEN` environment variable: agent arguments are visible to every user on the host via `ps`, and the option only exists for setups where the environment cannot carry it. |
| `flushIntervalSeconds` | `60` | How often deltas/manifest updates are sent. |
| `includePackages` | *(all)* | Only instrument types whose name starts with one of these prefixes, `;`-separated. |
| `staticBaselineEnabled` | `false` | Scan the classpath once at startup (async, off the critical path) for classes under `includePackages` that never load at all. Off by default: unlike every other option here, a full classpath walk has a cost that scales with the classpath's size. |

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

Queries are `wasHit`, `hitCount`, `neverHit`, `skippedClasses`, and, when
the agent runs with `staticBaselineEnabled=true`, `neverLoaded`. Asking
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
