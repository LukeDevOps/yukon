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
A small integer the registry assigns to a class the first time it registers. Unique within one run only.

**Layout hash**:
A hash of a class's method and branch signatures in slot order. Two loads of a class with the same name and hash share a count array; a different hash gets a fresh one.

**Branch site**:
One conditional jump or switch in a method's bytecode. A conditional has two outcomes; a switch has one per case entry plus the default, so two cases that share a body are still two outcomes. Only a site the adopter wrote gets probes; an inlined copy from out-of-scope code and coroutine machinery keep their place in the numbering and get none.
_Avoid_: decision

**Branch outcome**:
One way out of a branch site. A conditional's two are the taken jump and the fall-through; a switch's are its case entries and its default. Each kept outcome gets one probe.
_Avoid_: edge, branch (alone), arm

**Condition**:
The expression a branch site tests, written out by the agent in the source language, the way the fall-through side reads it. Absent when the agent cannot write it in source terms.
_Avoid_: test, predicate

**Branch index**:
A branch outcome's ordinal within its class for one build, counting every outcome of every site in bytecode order, dropped or kept. Says which outcome within this build, never which outcome across builds.
_Avoid_: branch id, stable branch index

**Branch key**:
An opaque token naming one branch outcome across builds and instances, compared only for equality. Absent when the agent cannot name the outcome safely, which it treats as a new outcome rather than risk giving it another's history.
_Avoid_: branch id, stable branch index, branch hash

**Site key**:
An opaque token naming one branch site across builds and instances, made the way a branch key is but without the outcome. Absent in the same cases.
_Avoid_: site id, condition hash

**Guarded code**:
The lines and calls of a method that run only through one branch outcome, by no other path from the method's entry. Every outcome has it, hit or not, and it can be empty, as for the skip side of an `if` with no `else`.
_Avoid_: region, dominated code, dead code

**Guard**:
The innermost branch outcome that a call edge or a branch site sits behind. Absent when nothing in the method stands between its entry and the edge or site.
_Avoid_: parent branch

**Inlined copy**:
A branch site inside code the compiler copied from an inline function into a caller, recognised from the class's SMAP. Kept and labelled with its origin class when that class is in scope; dropped otherwise.
_Avoid_: inlined branch, foreign branch

**Coroutine machinery**:
The jumps kotlinc adds to a suspend function or suspend lambda for its state machine (the switch on the continuation's label, the compare against the suspended marker, the preamble's re-entry tests) and the continuation class it emits per suspend function. Never a probed branch site; the continuation class is never probed or declared at all.
_Avoid_: coroutine noise, state-machine branches

**Generated method**:
A method the compiler emits from a declaration rather than from a body the adopter wrote: an enum's `values` and `valueOf`, a data class's `componentN`, `copy`, and the `equals`, `hashCode` and `toString` it did not override by hand, a `$DefaultImpls` method that only forwards to the interface's own default method, and an overload `@JvmOverloads` adds that only forwards to its `$default` twin. Probed and marked with what generated it, and so are the branch sites inside it; never reported as never hit. A `$DefaultImpls` method holding the interface method's real body, or an override the adopter wrote, is ordinary code.
_Avoid_: synthetic method (a JVM flag; these are not synthetic), compiler method

**Runtime-generated class**:
A class a framework synthesized in memory rather than compiled from source, named after the class it proxies so that it falls inside the adopter's own packages: a Spring CGLIB proxy and its fast-class helpers. Recognised by the generator's naming, never probed, never declared, and never reported at all, the way coroutine machinery is.
_Avoid_: proxy class (a Spring bean proxy is one shape of it), synthetic class (a JVM flag; these are not synthetic), skipped class (a skipped class is one the agent wanted and could not have)

**Skipped class**:
A class that matched the include rules but could not be instrumented, reported with a reason.
_Avoid_: failed class, excluded class (excluded means outside `includePackages`)

**Unconfirmed class**:
A class the agent wove whose definition it has not yet seen evidence of. Its probes are held out of the manifest until a count goes above zero or the JVM reports it loaded. One that never confirms is withheld for good and named in a log, so a class that failed to define is never reported as dead code.
_Avoid_: withheld class, undefined class, failed class (a skipped class is the one that failed)

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

**Service**:
One program, known by its namespace and its name together, as OpenTelemetry's `service.namespace` and `service.name` are. The same name in two namespaces is two services. Names keep their case and ignore surrounding spaces. A name that starts with `unknown_service` means the service was never given one.
_Avoid_: app, application, dataset

**Namespace**:
The group a service belongs to, such as one team's or one product's services. It is part of the service's identity.
_Avoid_: team, group, owner, project

**Unspecified namespace**:
The namespace of every service that names none, or a blank one. It is a namespace of its own and never matches a named one, even one called `none`.
_Avoid_: default namespace, no namespace, `none`

**Service instance**:
A JVM as the collector sees it, identified by `service.instance.id`, a fresh UUID per process by default. An adopter can pin the ID to a name that survives a restart. One instance then spans several runs.
_Avoid_: node, pod, host

**Run**:
One process's lifetime under one instance ID, from agent startup to exit. Class IDs, endpoint IDs and hit totals only mean something within one.
_Avoid_: session, boot, incarnation

**Run ID**:
A random ID the agent makes once per process at startup and stamps on every payload that process sends. A new process always gets a new one, even under a pinned instance ID, so a collector can keep each run's data apart.
_Avoid_: process ID (the operating system's PID), boot ID

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
A small integer the registry assigns to an endpoint the first time it is seen. Unique within one run only, like a class ID.

**Handler**:
The method or object the framework invokes for an endpoint. Recorded on the endpoint as a label naming a manifest method where the framework exposes one, or a class name where only the object is known. A handler the framework reports as a pass-through is recorded as the one probed method it forwards to, through the forwarder table.
_Avoid_: controller, action

**Forwarder table**:
The agent's own map from a pass-through a framework can report as a handler to the one probed method it forwards to, such as scalac's `$adapted` forwarder or a kotlinc reference class's `handle`. The method tier's analysis fills it, only for handler interfaces, and it is never sent.
_Avoid_: alias table, handler map

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
The counter for one optional parameter, incremented in the compiler's default-filling method or default getter when a call omits it. It reports the function the parameter belongs to, not the compiler's method it sits in. Its line is the line of the parameter's default value.
_Avoid_: argument probe, default probe

**Default-filling method**:
Kotlin's synthetic `f$default`, which takes a mask of omitted parameters, fills in every default, and calls `f`. One per function with optional parameters.

**Default getter**:
Scala's `f$default$N`, one per optional parameter, which returns that parameter's default and is called by every call that omits it. Its own hit total is the parameter's omission total.
_Avoid_: default method, default accessor

**Never supplied**:
An optional parameter whose omission total equals its function's hit total. Every caller took the default; the parameter can go. Only claimed for a function that cannot be overridden.

**Always supplied**:
An optional parameter whose omission total stayed at zero while its function was called. The default value is dead.

**Inline method**:
A Kotlin `inline` function, whose body is copied into Kotlin callers so its own probe only counts calls from Java or through a reference. Marked in the manifest and the baseline; never reported as never hit.

### Call graph

**Call edge**:
One caller method's static reference to one callee method, or its use of a class that runs that class's initializer, read from the caller's bytecode at transform time and deduplicated per caller. The callee is named as the bytecode names it: owner class, method name and descriptor. Only callees inside the include rules are recorded. Every call edge is either a call or a creation edge.
_Avoid_: call site (one invoke instruction; never on the wire), edge (one outcome of a branch site), dependency (a jar, never an edge)

**Creation edge**:
A call edge from a method to a body it hands to someone else to run: a lambda body, a method passed by reference, or a method of a body class. It reaches its target the way a call does, since the body can only run after its creator ran. One made by an `invokedynamic` names the interface the body implements, such as `Runnable`. One made by creating a body class names none, since that class states its own interfaces.
_Avoid_: defines edge, lambda edge

**Lambda body**:
A method the compiler made from a lambda the source never named, such as kotlinc's `main$lambda$0` or javac's `lambda$main$0`. It holds the adopter's own code, is probed like any method, and is named after the method that creates it.
_Avoid_: synthetic method (kotlinc's bodies are not synthetic), anonymous function

**Supertypes**:
A class's superclass and direct interfaces, sent with the class so a collector can widen a virtual call edge to the methods that override or inherit its callee.

**Pass-through**:
A compiler-generated method with no probe of its own, such as a bridge, an `access$` accessor, Kotlin's `$default`, or any method of a body class the agent does not probe, such as a Kotlin reference class's forwarding `invoke`, whose callees are attributed to whatever references it, in its own class or another. A generated forwarder is a pass-through too, though it keeps a probe for its hits: a `@JvmOverloads` overload, a multi-file facade's function, or a `$DefaultImpls` forwarder. Lambda bodies are not pass-throughs: they hold the adopter's own code and get probes.

**File facade**:
The class kotlinc makes for a source file's top-level functions and properties, such as `DemoServerMainKt` for `DemoServerMain.kt`. It is named after the file, or by `@file:JvmName`. The agent reads it from the class's `@kotlin.Metadata` kind, never from the name. A person knows it as the file.
_Avoid_: Kt class, facade class, file class

**Multi-file facade**:
The class that `@file:JvmMultifileClass` makes to join several files under one name. It holds only forwarders to its parts, one part class per source file, where the code lives.
_Avoid_: combined facade

**Body class**:
A class that exists only to carry a body its creator hands to someone else: a function or property reference, a Kotlin lambda compiled to a class (every suspend lambda is one), an object expression, an anonymous or local class. Its creator is treated as the caller of every method it declares. A body class the agent does not probe (a Kotlin function or property reference, a `$sam$` wrapper, a suspend function's continuation) is a pass-through instead, so the creator reaches the function or accessor it names.
_Avoid_: lambda class for every body class (it names only the Kotlin lambda kind, and a Java lambda is a hidden class with no class file), adapter (Scala's boxing forwarder), callback

**Unreached cluster**:
A root plus every never-hit method reachable from it through call edges whose every in-scope caller is itself in the cluster. A call edge behind a never-hit outcome in a method that ran counts as a call from that outcome. Deleting the root removes the whole cluster.
_Avoid_: dead cluster, dead code (a collector's verdict, not an observation)

**Root**:
The never-hit code that starts an unreached cluster. A method root is *reached from hit* when one of its callers has hits, and names those callers; it is *uncalled* when nothing in scope calls it. An *untaken outcome* root is a never-hit outcome in a method that ran, with at least one method behind it. See ADR 0039. A *class finding* root is a class that holds a class finding, with no caller or a caller that has hits. Its cluster is listed only when it holds a method or class besides the methods the finding folds. A `<clinit>` is never a root. See yukon-server ADR 0034.
_Avoid_: entry point (a root may be deep inside the code), node

### Dependencies

**Dependency**:
One jar on the classpath that is not the adopter's own code, identified by the `groupId:artifactId` its `pom.properties` names, with the version carried beside the identity rather than in it. A jar that bundles several libraries is one dependency labelled with all of them, since it cannot be half-removed. The same identity loaded by two classloaders is one dependency. A jar whose classes all fall under the include rules is the adopter's own and is not a dependency.
_Avoid_: library, artifact, jar (as synonyms), call edge (a dependency is never an edge)

**Reference**:
Any mention of a class in the adopter's bytecode that the running JVM can need: a call, a field access, a construction, a cast or type test, a catch type, a class literal, a type in a descriptor or generic signature, a supertype, or a runtime-visible annotation. Wider than a call edge, so a dependency used only through its annotations or a base class still counts as referenced. An annotation the compiler keeps out of runtime reach (class retention, such as `@NotNull`) is not a reference, since removing its jar changes nothing at runtime.
_Avoid_: usage, import (a source-level notion with no bytecode trace)

**Absent reference**:
A reference to a class no loader can find, such as code guarded by a check for an optional library. Reported rather than dropped, since it maps to no dependency.

**Live reference**:
A reference held by a method with hits, or by a class itself (an annotation, a supertype, a field type) when that class loaded. An inline method's own references never count, since its callers carry copies.

**Unloaded dependency**:
A dependency on an instance's startup classpath from which no class has loaded.
_Avoid_: never loaded (a class's status), unused dependency (the product phrase, not the observation)

**Unreferenced dependency**:
A dependency with at least one loaded class that nothing in the adopter's code references. Often a library another library needs, or one reached only through a service lookup.

**Unreached dependency**:
A dependency the adopter's code references only from methods never hit or classes never loaded.
_Avoid_: dead dependency (a collector's verdict, not an observation)

**Dependencies listed**:
The state an instance reaches once every dependency its startup listing found, and every reference mapping recorded before the listing ended, has reached the collector. Each dependency's entry arrives only after its first counts, so a collector can judge it as soon as the entry is there. Before this state, an empty list of dependencies or absent references means "not listed yet", not "none".
_Avoid_: listing complete (the agent's own state, before anything is sent)

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
A probe present in a manifest whose hit total has stayed at zero. The class loaded; the code did not run. A collector claims it only for a probe that is neither inline nor generated.

**Final flush**:
The delta batch the agent's shutdown hook sends, marked as such so a collector can tell an instance that ended cleanly from one that went silent.
_Avoid_: last batch, shutdown batch

**Never loaded**:
A class declared by a complete static baseline that never appears in any manifest from that instance. The class was never constructed.
_Avoid_: dead (a collector's verdict, not an observation)

**Class finding**:
A finding about a whole class rather than a method in it: never loaded, never initialised or never instantiated. A class holds at most one, the strongest that applies, and a method that can only run through it is not listed on its own. A lambda body folds with the methods that create it: a never-hit lambda body is not listed when every creator is covered by a class finding or is a never-hit method that is listed. A collector judges it, and the agent sends the facts it rests on, such as each method's static and lambda-body flags. See yukon-server ADR 0034 and ADR 0040.
_Avoid_: unused class, dead class

**Never initialised**:
A class that some in-scope run loaded, that has a static initialiser, and whose static initialiser no in-scope run ran. Nothing used its statics and nothing created an instance. A class with no static initialiser can be loaded or never loaded, but never judged never initialised. A never-loaded class is not also never initialised.
_Avoid_: uninitialised class, dead class, never used

**Never instantiated**:
A class that some in-scope run loaded, that declares at least one constructor and at least one instance method, and none of whose constructors any in-scope run ran. No instance of it or of a subclass ever existed, since a subclass's constructor runs its superclass's. A class with only static methods is never judged, since nobody meant to create one, and neither is an interface, which has no constructor. A class with a stronger finding, never loaded or never initialised, is not also never instantiated. Its static methods can still run, so they stay listed.
_Avoid_: unused class, never constructed, never created

**Unused overload**:
A never-hit constructor of a class that some in-scope run created through another of its constructors. It is the only case in which a constructor is itself a finding. A `@JvmOverloads` forwarder is generated, so it is never one.
_Avoid_: dead constructor, unused constructor (a lone constructor that never ran says the class was never instantiated, not that the constructor is unused)

**Unreported class**:
A class the JVM has loaded that reached no manifest, neither as a probed class nor as a skipped one. Found by comparing the loaded classes against what the registry knows, never by a transformer, since the ones this catches are the ones no transformer was offered.
_Avoid_: deflected class (names one cause of several), missed class, unknown class

**Sweep**:
One pass over every class the JVM holds, keeping the ones in scope that the registry has never heard of. Runs on a flush, not on a transform.
_Avoid_: scan (the static baseline's own act), audit
