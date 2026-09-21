# Status

Working notes on what is in flight and what is parked. Not a changelog; git
history covers that. `CLAUDE.md` holds the design in long form, `docs/adr/`
one record per decision, and `CONTEXT.md` the glossary. Where this file and
`CLAUDE.md` disagree about the state of the code, this one is right.

## TODO

### Nothing is published anywhere

No build in this repo publishes an artifact. An adopter cannot depend on the
agent jar or on `yukon-testkit` except by building them, which also means the
testkit's whole reason for existing, letting someone else's test suite assert
on dead code, has no distribution. The wire schema is the one thing that is
published, to the Buf Schema Registry, and CI does that on every push that
touches the proto.

Two things fall out of it when it happens. The poms need licence metadata,
which nothing generates today. And a published testkit fixes its own API, so
the query surface is worth a look before it is frozen rather than after.

### Dependency usage: in progress

Grilled and settled on 2026-09-21; ADR 0030 holds the decision and
`CONTEXT.md` the terms (dependency, reference, absent reference, live
reference, and the unloaded, unreferenced and unreached statuses). Chunk 0
(wire and codec) has landed. Two wire choices the grill left open were
settled in it: a dependency's cross-instance identity is the sorted set of
its `group:artifact` pairs (one pair for an ordinary jar), and
`DependencyLocation.identity_source` says whether that came from
`pom.properties`, the jar manifest or the filename. The proto documents
`DeltaBatch.dependency_deltas` as one entry per dependency whose
`loaded_classes_total` changed since the last delivered batch, the probe-delta
rule; chunk 2 must send them that way.

Chunk 1 (listing, identity, registry, manifest delivery) has landed, with
three changes to the design the grill wrote down, all in ADR 0030. The listing
runs on a background thread, not in `premain`: streaming a fat jar's nested
jars to judge the adopter's-own rule took about 200 ms for `demo-spring`'s 20
MB. Identity falls back from `pom.properties` straight to the filename:
`Implementation-Title` is a display name, and three Tomcat jars share
`Apache Tomcat`. A fat jar's dependencies are every file under `BOOT-INF/lib/`
(a war's `WEB-INF/lib/` and `lib-provided/`), since a packaged launch never
reads `classpath.idx`. Against `demo-spring` the listing finds all 36 nested
jars, 14 identified by `pom.properties` and 22 by filename, every Spring jar
among the latter with an empty group. Chunk 2 matches loaded classes back
through `DependencyOrigin` and must count nothing until
`DependencyRegistry.isListingComplete`.

Chunk 2 (counting, discovery by load, `DependencyDelta`) has landed. On
`demo-spring` a nested class's code source is
`jar:nested:<outer>/!BOOT-INF/lib/<jar>!/`, parsed as Boot 4.1.1's
`NestedLocation` does (split at the last `/!`, `%` escapes decoded, the Windows
drive fix); 31 of the 36 dependencies load a class, none is discovered by load,
and the sweep reads no jar. Two things stay open. The Boot 2
`jar:file:<outer>!/<entry>!/` form is pinned only by unit tests: no Boot 2
loader is in the local caches to check it against. And a flat jar first seen at
load still goes through the agent-jar rule, which rightly turns away a
dynamically attached agent's jar but would also turn away `byte-buddy-agent`
sitting flat in an exploded war's `WEB-INF/lib`; it would then read as no
dependency rather than as one.

Chunk 3 (references in the analyser, `ExternalClass` on the manifest) has
landed, with one change to the design: only runtime-visible annotations are
references. Counting every annotation made `org.jetbrains:annotations` read as
referenced on every Kotlin service, through kotlinc's class-retention
`@NotNull`, though the jar can leave the runtime classpath with nothing
changing. On `demo-spring` 14 referenced classes map to five jars
(kotlin-stdlib, spring-boot, spring-boot-autoconfigure, spring-context,
spring-web), none absent, no JDK type listed. Every Kotlin class references
`kotlin.Metadata`, so kotlin-stdlib always reads as referenced and live, which
is true. A referenced class whose loader throws from `getResource` is recorded
as absent. The proto no longer promises every referenced name an
`ExternalClass` entry: one may arrive a manifest later, and a name that never
gets one belongs to no dependency. Landing order, one chunk and one commit
each:

0. Wire and codec: `DependencyLocation`, `DependencyDelta`,
   `referenced_classes`, `ClassReferences`, `ExternalClass`.
1. The startup listing and identity reading: flat jars, Boot's nested jars,
   nested `pom.properties`, the agent-jar rule, the adopter's-own-jar rule.
2. Sweep counting and `DependencyDelta`.
3. Analyser references, resolution to a dependency, absent references, in the
   manifest.
4. The same in the static baseline.
5. Stub collector and demo. `demo-spring` gains one runtime dependency nothing
   touches (unloaded); `jackson-databind`, which Spring loads and the demo never
   names, is the unreferenced case, which needs the baseline flag in `runDemo`;
   one reference in the never-hit promo branch is the unreached case.
6. Testkit.
7. `yukon-collector` bindings bump.
8. `yukon-server`: `GET /api/v1/services/{s}/dependencies` with a status
   filter, and a `dependencies` block on `report`.

### What the agent does when `includePackages` is unset

Parked during the dependency grill, not settled. Today the agent logs a
WARNING and instruments every class outside the JDK, third-party libraries
included, which puts probes on the hottest code in the process (serialisers,
servlet containers, dispatch) and multiplies transform time, memory and
manifest size, all to produce findings about code the adopter cannot delete.

The options weighed: refuse to start, logging an ERROR that names the flag and
suggests the main class's package, the same visible failure the holder-install
path already uses; infer the boundary from where each class came from
(directories and `BOOT-INF/classes` are the adopter's, jars are not), which
drops the adopter's own code silently in a multi-module build or a shaded jar;
infer it from the main class's package, which is a guess at how many segments
to keep; infer and announce the guess on the wire; or keep today's behaviour
and filter at the collector. The recommendation on the table was to refuse to
start. Skipping only private library methods was measured and set aside: 3.5 to
12 percent of methods in the three libraries checked, against a cost that is
mostly per class (ADR 0030).

ADR 0030 does not depend on the answer. Its origin rule already covers the
unset case for deciding which jars are dependencies.

### A branch inside a generated method is judged as the adopter's own

`YukonInstrumentation.kt:411` builds a branch `ProbeMeta` with no
`generatedBy`, while the method and omission probes around it pass
`analysis.generatedBy(...)`. So a data class's `equals` is marked and left out
of the judged set, and the conditionals inside that same `equals` are reported
as never-hit code the adopter wrote. Seen in the demo: one `data class` in
`demo-spring` printed six such rows, which is why `TaxRate` is a plain class
rather than the `data class` it would otherwise be.

Not simply an oversight, and not settled either. `yukon.proto` documents the
behaviour ("A BRANCH probe never carries this; see `GeneratedBy`"), so it was
known, but neither ADR 0026 nor the `GeneratedBy` comment it points to gives a
reason, and ADR 0026 never mentions branches at all. Both ADRs in the area
argue the other way: ADR 0026 calls a never-hit `component3` a false finding
because the compiler emits it regardless of what the adopter does, and that is
exactly as true of a jump inside `equals`.

So the first move is to decide between two answers, not to write the line:

- Mark them, passing `analysis.generatedBy(site.methodName,
  site.methodDescriptor)` into the branch `ProbeMeta`. No schema change
  (`ProbeLocation.generated_by` is field 16 already) and no consumer change:
  `StubCollectorMain.kt:405` partitions never-hit on the mark without looking
  at the probe's kind, and the testkit reads it the same way. The proto comment
  and ADR 0026 need amending, and `yukon-server` wants a look to confirm its
  own filter is kind-blind too.
- Drop them at the analyser, which is what ADR 0025 does for every other branch
  the adopter did not write, and cheaper at runtime. It costs a slot-layout
  change and the hit evidence ADR 0026 kept its methods for, and the two ADRs
  would then disagree about the same class's methods and its jumps.

Either way the proof is the demo: turn `TaxRate` back into a `data class` and
the report must stay at four never-hit rows.

### Generators other than Spring are not recognised

ADR 0029 turns away a runtime-generated class by the markers its generator puts
in the name, and `TypeMatchPolicy.RUNTIME_GENERATED_NAME_MARKERS` holds only
Spring's two spellings, the only ones confirmed against their own source and
run end to end here. Hibernate's `$HibernateProxy$`, ByteBuddy's own
`$ByteBuddy$`, javassist's `_$$_jvst` and JDK dynamic proxies (`$Proxy` in a
non-public interface's package) produce the same shape and are not covered.

Each is one entry in that list, one test beside the Spring ones in
`TypeMatchPolicyTest`, and an edit to ADR 0029's consequence bullet saying only
Spring is covered. `LoadedClassSweep` needs nothing: it calls
`TypeMatchPolicy.isRuntimeGenerated`, so it follows the list. What gates the
work is the project's own rule of confirming a library's naming against that
library rather than recalling it, and none of these four is a dependency of
this repo, so each needs its jar fetched and read the way spring-core's
`SpringNamingPolicy` was.

Hibernate is the one worth doing first: an entity package full of
`$HibernateProxy$` classes is the exact shape that took `demo-spring`'s report
to 81% dead. Its name comes from ByteBuddy's `NamingStrategy.SuffixingRandom`,
which builds `<prefix>$<suffix>$<random>`, so confirming it means finding where
Hibernate passes that suffix, not just grepping for the string.

### Follow-ups the branch-probe round left open

Each is recorded rather than started. The first needs evidence before it can
be designed; the rest are small and wait for a reason to touch the code.

- The true-but-uninteresting classification: the null path of a safe call or
  an elvis, `!!` and `lateinit` checks, `when` exhaustiveness throws, and
  `finally` copies on the exception path are all branches an adopter did not
  write in any useful sense. ADR 0025 dropped the two categories that could
  be measured; this one waits for a report from a real service to show how
  much of what remains is this shape.
- Kotlin `value class` `-impl` methods and kotlinx.serialization's generated
  output as further `GeneratedBy` values.
- javac's string-switch and try-with-resources shapes, which were zero in
  every Kotlin corpus measured and wait for a Java corpus to be worth
  recognising.

### Telling a failed load apart from an unreferenced class

A class woven and registered that the JVM never defined is withheld from the
manifest (ADR 0028), so a collector diffing the static baseline against it
calls the class never loaded, which is true. What it cannot say is why. "Never
loaded because nothing referenced it" is dead code to delete; "never loaded
because loading it failed" is a deployment to fix, and only the agent can tell
them apart.

Closing it means a bucket on `ProbeManifest` beside `unreported_classes`: a
proto field, a registry bucket, a chunk weight, codec work and a collector
change. That was judged out of proportion to a population nobody has shown
exists yet, so the agent logs a WARNING per class and nothing goes on the
wire. Build it when a report from a real service shows these classes turning
up. If they never do, this dies honestly.

### The endpoint tier has no confirmation

`EndpointInstrumentation` stages and commits declarations from its own
listener for the ADR 0007 reason, so a class that declares endpoints and then
fails to define leaves rows nothing will ever increment: a route reading as
never called, the endpoint form of what ADR 0028 fixed for probes.

Deferred with its reason in that ADR rather than left to omission. The
exposure is narrower than the probe tier's, which registers every class the
agent weaves: `JaxRsModule` is the only module that declares from inside a
transform, and every other module declares at runtime from a framework object
that already exists, so its class certainly loaded. The link, when it is
wanted, is `PendingDeclarations.begin()` taking the declaring class name and
`commit()` attaching it. Not `EndpointEntry.handlerClass`, which happens to
hold the same string for JAX-RS but is a display label: nullable, and set at
dispatch for the other modules.

### Endpoint follow-ups not started

- Static analysis of registration call sites, to declare an endpoint whose
  registration the agent never sees at runtime.
- A `runSpringDemoStack` counterpart to `runDemoStack`, so the Spring demo is
  proven against the real collector and server rather than only the stub.
- Per-framework disable flags. One `endpointsEnabled` switch and the
  self-disabling modules cover everything known so far; this is only worth
  building if an adopter needs to turn one module off by hand.

OpenAPI import was settled as `yukon-server` work and is tracked there.

## Parked

### A module disabled after it has already declared routes

A module that throws is switched off for the rest of the process, and its
dispatch advice stops counting from then on. Routes it declared for classes
it handled successfully keep their manifest rows, so their counts freeze
wherever they were: a route the framework still serves reads as never called
once its count sat at zero.

Settled in favour of the collector, not the agent. The agent keeps reporting
what it saw: the endpoints it declared, and the module it disabled, with the
time it happened. A collector joins the two, since a module's name is the
same string its endpoints carry as `framework`, and leaves every endpoint of
a module disabled everywhere it is known out of its dead-code claims, the
way it leaves out an inline probe. Withdrawing the endpoints instead would
need a tombstone on the wire and would throw away that the route existed and
was served at all, which is true whatever happened to the module later. Same
shape for a module disabled through the runtime `declare` walk, which
predates the transform-time staging.

### A named class implementing a framework interface reads as an uncalled root

ADR 0024's known gap. A call that leaves scope and comes back, a framework
invoking an adopter's class, shows as no edge, so such a class's never-hit
method appears as a cluster root with no caller. Lambdas are covered, since
a body is reached from its creator, and the endpoint join carries the route
to a handler class, which is what the demo's `PromoHandler` shows. What is
left is the shape with no endpoint beside it. If uncalled roots in a real
report turn out to be mostly this, recording out-of-scope callees is the
answer; until then it is a labelled root rather than a wrong one.

### A transformer later in the chain replacing the agent's bytes

The third failure past `onTransformation`, and the one ADR 0028 does not
cover. The class is defined and running, with the woven probes gone, so no
comparison against the loaded set can see it: it is present, and its counts
sit at zero exactly like a class that loaded and was never initialised.

The one signal that separates them is reading the loaded class for
`$yukonProbeCounts`, and `Class.getDeclaredFields()` resolves every field's
type. Confirmed on JDK 22: a field type `findLoadedClass` reported absent was
loaded by the call itself. A detector that manufactures class loads corrupts
the data it reports on, which is worse than the gap. It also needs a second
agent in the chain that discards its input bytes rather than building on them;
OpenTelemetry's agent carries the same exposure.

### JFR-sampled observed edges

An opt-in overlay marking which static call edges were actually taken, from
`jdk.ExecutionSample`. The wire shape leaves room for an additive marking on
an edge. It gets its own grill once real manifest sizes are known; per-call
dynamic edge tracking stays rejected.

### No CI-gate or threshold helper in the testkit

The testkit stops at query primitives on purpose. Baking in a rule for when
a count is low enough to fail a build is the confidence policy ADR 0015
keeps out of the agent, so composing one is an adopter's own call.

### Deliberate v1 boundaries

Not gaps, and not on anyone's list: static attach only (ADR 0013), the
static scan not opening `BOOT-INF/lib` nested dependency jars, and the
classpath blind spot for app-server, OSGi and plugin-loaded deployments.
Each has its own section in `CLAUDE.md` with the reasoning and what it would
take to change.
