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

### Endpoints

**Endpoint**:
One HTTP verb and route template a framework serves, as registered with that framework. Identity is the pair; nothing about the server, port, or handler is part of it.
_Avoid_: route (the template is the route; the endpoint is verb plus template), mapping, handler

**Route template**:
The normalised path pattern of an endpoint: leading slash, no trailing slash, `{name}` for a path parameter with any regex dropped, `*` for any wildcard or tail. The framework's own spelling is kept beside it for display only.
_Avoid_: path, pattern, URL

**Endpoint probe**:
The counter for one endpoint, incremented when the framework matches a request to it, before the handler runs.
_Avoid_: route probe, request counter

**Endpoint ID**:
A small integer the registry assigns to an endpoint the first time it is seen. Unique within one service instance only, like a class ID.

**Handler**:
The method or object the framework invokes for an endpoint. Recorded on the endpoint as a label naming a manifest method where the framework exposes one, or a class name where only the object is known.
_Avoid_: controller, action

**Discovery source**:
How the agent learned of an endpoint: registration (the framework declared it) or dispatch (a request matched an endpoint no registration had declared).

**Endpoint module**:
The per-framework unit that hooks one framework's registration and dispatch. A module that hits a linkage failure disables itself once and is reported as disabled.

**Never called**:
An endpoint that at least one instance registered and whose hit total has stayed at zero across every instance in scope. The framework would serve it; no request ever matched it.
_Avoid_: unused endpoint (the product phrase, not the observation), dead

**Route bridge**:
An endpoint module that counts the route another instrumentation already resolved for a request, rather than hooking the framework itself. It only ever discovers endpoints by dispatch and never declares one.
_Avoid_: OTel module, adapter

**Inherited annotation**:
A JAX-RS annotation a resource method takes from the method it overrides or implements because it carries none of its own. Only a method the concrete class declares can inherit.

### Optional parameters

**Optional parameter**:
A value parameter declared with a default, so a caller may leave it out.
_Avoid_: default argument, defaulted parameter

**Omission**:
One call that left an optional parameter to its default.

**Omission probe**:
The counter for one optional parameter, incremented in the compiler's default-filling method when a call omits it. It reports the function the parameter belongs to, not the synthetic method it sits in.
_Avoid_: argument probe, default probe

**Never supplied**:
An optional parameter whose omission total equals its function's hit total. Every caller took the default; the parameter can go. Only claimed for a function that cannot be overridden.

**Always supplied**:
An optional parameter whose omission total stayed at zero while its function was called. The default value is dead.

**Inline method**:
A Kotlin `inline` function, whose body is copied into Kotlin callers so its own probe only counts calls from Java or through a reference. Marked in the manifest and the baseline; never reported as never hit.

### Testkit

**Settled**:
The state in which every hit made before a given moment has reached the test collector: two consecutive heartbeats have arrived since then.
_Avoid_: flushed, synced

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
