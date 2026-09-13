# Yukon

A Java agent that instruments a running JVM application to find code paths that are reachable but never exercised: unused endpoints, methods never invoked, conditionals that only ever go one way. The agent records and exports; a collector aggregates and judges.

## Language

### Recording

**Probe**:
One counter woven into a class at load time, at a method's entry or on one outcome of a branch.
_Avoid_: counter, hook, instrumentation point

**Probe index**:
A probe's slot in its class's count array, fixed when the class is woven.
_Avoid_: probe ID

**Class ID**:
A small integer the registry assigns to a class the first time it registers. Unique within one service instance only.

**Layout hash**:
A hash of a class's method and branch signatures in slot order. Two loads of a class with the same name and hash share a count array; a different hash gets a fresh one.

**Branch site**:
One conditional jump or switch in a method's bytecode. A conditional has two outcomes; a switch has one per distinct case target plus the default.
_Avoid_: decision, condition

**Skipped class**:
A class that matched the include rules but could not be instrumented, reported with a reason.
_Avoid_: failed class, excluded class (excluded means outside `includePackages`)

### Export

**Flush**:
One scheduled export tick, sending a delta batch and any manifest entries not yet delivered.

**Delta batch**:
The payload listing every probe whose hit total changed since the last confirmed delivery. An empty one is the liveness heartbeat.
_Avoid_: metrics, snapshot

**Hit total**:
A probe's cumulative count since process start, merged at the collector with max().
_Avoid_: delta, hits since last flush, increment

**Probe manifest**:
The payload mapping each (class ID, probe index) to its class, method, descriptor, line and branch index, plus the skipped classes. Delivered incrementally.
_Avoid_: metadata, symbol table

**Service instance**:
One running JVM, identified by `service.instance.id`, a fresh UUID per process by default. Class IDs and hit totals only mean something within one.
_Avoid_: node, pod, host

**Collector**:
Whatever receives the payloads and merges them across instances. `yukon-collector` is the production one; the demo's stub and the testkit's `YukonTestCollector` play the role in this repo.
_Avoid_: backend, server, ingest

### Static baseline

**Static baseline**:
A once-per-process inventory of the classes and methods on the classpath under the include rules, read from bytecode without loading, sent in chunks.
_Avoid_: inventory, classpath scan (the scan is the act; the baseline is the payload)

**Declared class**:
A class the baseline found, read, judged safe to instrument, and that has at least one method to probe.

**Statically unsafe class**:
A class the baseline found that the agent would skip on load, for the same annotation reason.

**Unreadable class**:
A class file the baseline could not read or resolve.

**Unprobed class**:
An in-scope class with no concrete method to probe, such as an interface with only abstract methods. It can never appear in a manifest.

**Never hit**:
A probe present in a manifest whose hit total has stayed at zero. The class loaded; the code did not run.

**Never loaded**:
A class declared by a complete static baseline that never appears in any manifest from that instance. The class was never constructed.
_Avoid_: dead (a collector's verdict, not an observation)
