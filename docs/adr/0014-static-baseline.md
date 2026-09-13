---
status: accepted
---

# The static baseline is an opt-in classpath scan sent as its own chunked message

Every other tier is reactive: a class gets a manifest entry only once it loads. A class that never loads during the observation window is indistinguishable on the wire from one that does not exist. The static baseline is a second, load-independent inventory: a scan of `java.class.path` by bytecode reading through ByteBuddy's `TypePool`, sent as `StaticBaseline` so a collector can diff it against the reactive manifest. A class in the baseline that never appears in any manifest was never even constructed.

The scan is off by default (`staticBaselineEnabled=false`). It runs once per process on a daemon thread, so `premain` latency stays independent of classpath size. It reports classes and methods but not branches, since a branch cannot be counted correctly without the live control-flow context the rewriter has. It recognises `BOOT-INF/classes` and `WEB-INF/classes` as nested roots and does not open the jars under `BOOT-INF/lib` or `WEB-INF/lib`: dependency code is outside the tool's purpose.

## Considered options

- Extending `ProbeManifest`. Rejected: the manifest's incremental delivery is built for a list that grows as classes register. The baseline is computed once, possibly large, and sent as a snapshot. A separate message keeps the change additive.
- One POST for the whole scan. Rejected once the exporter stopped retrying a 413: one oversized POST would lose the baseline for the life of the process. `StaticBaselineChunker` packs whole classes into chunks of about 20000 entries. Every chunk carries `chunk_index` and `chunk_count`, and a collector may only diff a scan it holds completely.

## Consequences

- Three buckets sit beside the declared list: statically unsafe (the same `@Target` check as 0007, run against unloaded bytecode), unreadable, and unprobed (an interface with only abstract methods, which the dynamic tier never registers and which would otherwise read as never loaded).
- The scan is blind to code whose location another system decides at runtime: a WAR discovered by Tomcat from `server.xml`, an OSGi bundle, a plugin directory. The reactive tier has no such blind spot, since the JVM invokes `addTransformer` for every class from every loader. Any class that registers dynamically without having appeared in the scan is proof of a blind spot. `StaticBaselinePublisher` logs a warning per such class, sweeping classes already loaded when the scan finishes as well as those loaded later. Classifying that finding belongs to the collector (0015).
- In a Spring Boot fat jar the safety check cannot resolve annotation types packed under `BOOT-INF/lib`, and `TypePool` drops an unresolvable annotation silently, so a `@file:JvmName` class there is declared rather than marked unsafe. Only the label is lost: such a class always appears in the manifest's skipped list once it loads, so it can never produce a false "never loaded".
- The scan runs once. A plugin system loading jars later needs a restart to be captured, the same restart-to-adopt story as 0013.
