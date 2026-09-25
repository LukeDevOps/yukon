# Status

Working notes on what is in flight and what is parked. Not a changelog; git
history covers that. `CLAUDE.md` holds the design in long form, `docs/adr/`
one record per decision, and `CONTEXT.md` the glossary. Where this file and
`CLAUDE.md` disagree about the state of the code, this one is right.

## TODO

### Readable branch findings: landed in all three repos

Settled 2026-09-24 in a grilling session across all three repos, after
reading the `yukon-server` UI against the demo. A never-hit outcome reached a
person as "Branch 12, `DemoServerMain.kt:121`": no condition, no side, and
nothing about what code it leads to. ADRs 0037 (sites, conditions, guarded
code, guards) and 0038 (string and enum switches read back to source
cases), collector ADR 0001 (redaction processor), server ADR 0030 (read by
site, newest run describes a row). Terms: condition, site key, guarded code,
guard.

Landing order, one chunk per commit:

1. Agent: a transform-time benchmark over the demos and a Spring Boot app,
   with numbers recorded here before any analysis lands.
2. Agent: sites on the wire. `BranchSite` on METHOD probes and on
   `DeclaredMethod`, with site index, site key, line and its outcomes
   (branch index, role); site index on BRANCH probes. Switch roles carry
   the numeric case key until chunk 5.
3. Agent: guarded code and guards. Control-flow graph and dominators per
   method; guarded and partly guarded line ranges, SMAP-mapped, on each
   site's outcomes; a guard on each site and call edge, in the manifest
   and the baseline. Benchmark read again.
4. Agent: conditions as code and literal parts, for Kotlin, Java, Scala 2
   and Scala 3, each idiom confirmed with `javap` first.
5. Agent: string and enum switches read back to source cases, with the
   lowering's jumps and throw-only defaults dropped as machinery.
6. Collector: bindings bump and the redaction processor.
7. Server: bindings bump, migration 0002, and storing every new fact.
8. Server: reads by site, display fields from the newest in-scope run
   (method lines included), and the report counting sites.
9. Server: the web UI's site rows in never-hit and stale-hit and in the
   graph's expanded method nodes; the branch index and key no longer
   shown.
10. End to end: the compose stack plus `runDemoStack`, and each repo's
    STATUS brought up to date.

Chunk 1 landed: `./gradlew jmh` times `BranchSiteAnalyzer.analyze` over
five corpora (README, "Benchmark the transform-time analysis"). Baseline on
an Apple M3 Pro, JDK 21.0.4, 1 fork, 3 warmup and 5 measurement iterations
of 5 s:

| Corpus | Classes | ms per corpus | Per class |
|---|---|---|---|
| demo | 61 | 5.811 ± 0.088 | 95 µs |
| demo-spring | 9 | 0.124 ± 0.002 | 14 µs |
| scala | 56 | 1.045 ± 0.194 | 19 µs |
| spring-webmvc | 545 | 41.239 ± 0.668 | 76 µs |
| ktor-server-core | 477 | 35.488 ± 0.309 | 74 µs |

The review replaced a corpus of the agent's own classes: `TypeMatchPolicy`
never includes the agent's package, so the analyser dropped their inlined
copies and call edges, a shape no adopter class has. Ktor's server core
stands in as the large Kotlin corpus. `demo` costs more per class than the
others, which is worth a look when chunk 3 reruns this.

Chunk 2 landed: `BranchSite`, `BranchOutcome` and `BranchRole` are on the
wire, on METHOD probes and on `DeclaredMethod`, and a BRANCH probe names its
site. `KeptBranchSite.of` is the one numbering the manifest and the baseline
share, and a site key uses the branch key's collision rules under its own
`site-v1` tag. Two things the design did not predict: a one-case switch also
has two outcomes, so `BranchSite.isSwitch` tells it from a conditional; and
the review found the manifest and baseline chunk weights ignored the new
sites, so `BranchSite.chunkWeight` (one per site and one per outcome) now
counts in both, and chunk 3 extends it to line ranges. ADR 0037 was amended
before this chunk to list outcomes inside their site, since the baseline has
no BRANCH probes to carry them.

Chunk 3 landed: `GuardAnalysis` builds each method's control-flow graph
with one node per kept outcome, computes dominators, and gives each outcome
its guarded and partly guarded `LineRange`s and each site and call edge its
guard, in the manifest and the baseline alike. `InstructionRecorder` sits in
front of the analyser's visitor, so call candidates and graph nodes share one
instruction ordinal, pinned by `InstructionRecorderTest`. Facts the design did
not predict: a suspend function's label switch keeps only its first case in
the graph (recorded in ADR 0037), since the resume cases rejoin mid-method and
would bypass every outcome; and `ExportScheduler`'s rider packing also had to
count site weight. The review made ranges merge across lines with no code, so
a blank line or comment no longer splits an arm. A same-file inline function
called from both arms leaves its line partly guarded by each, which is true.

Benchmark after chunk 3, same settings:

| Corpus | Baseline ms | Chunk 3 ms | Change |
|---|---|---|---|
| demo | 5.811 | 7.362 ± 0.107 | +27% |
| demo-spring | 0.124 | 0.132 ± 0.001 | +6% |
| scala | 1.045 | 1.026 ± 0.013 | none |
| spring-webmvc | 41.239 | 48.645 ± 0.346 | +18% |
| ktor-server-core | 35.488 | 39.825 ± 0.170 | +12% |

Chunk 4 landed: each kept site carries its condition as `CODE`,
`STRING_LITERAL` and `PLACEHOLDER` parts, written by `ConditionWriter` from
the fingerprinter's own window, in Kotlin, Java or Scala. The demo's stub
collector prints `System.getenv("ENABLE_LEGACY_DISCOUNT") == "true"` was never
true, only path to `DemoServerMain.kt:59`. Every idiom was confirmed with
`javap` first. Facts the design did not predict, now in ADR 0037: a Kotlin
template is written as a `+` concatenation, since a template's constant pieces
could not stay separate literal parts for redaction; and `if_acmp` reads as
`===` in Kotlin, since kotlinc compiles `===` and enum `==` alike, except
between enums (checked through `ACC_ENUM` by the class lookup), where `==` is
exact. The review made widening conversions transparent and narrowing ones
read as `x.toInt()`, `(int) x` or `x.toInt`.

Open, from chunk 4: a Kotlin extension call reads as its static facade call
(`StringsKt.toDoubleOrNull(value)`, not `value.toDoubleOrNull()`), and a
mapped built-in as a Java call (`this.length()`). Telling an extension apart
needs the callee's Kotlin metadata, which the agent does not parse. A Scala
`object` read through `MODULE$` is a placeholder until its shape is confirmed.

| Corpus | Chunk 3 ms | Chunk 4 ms | Change |
|---|---|---|---|
| demo | 7.362 | 8.267 ± 0.204 | +12% |
| demo-spring | 0.132 | 0.137 ± 0.002 | +4% |
| scala | 1.026 | 1.188 ± 0.026 | +16%, two more fixture classes |
| spring-webmvc | 48.645 | 51.362 ± 0.662 | +6% |
| ktor-server-core | 39.825 | 44.429 ± 0.453 | +12% |

Chunk 5 landed: `SwitchLowering` recognises javac's and kotlinc's enum
mapping switches, javac's string index switch and javac's `SwitchBootstraps`
pattern switches, and rebuilds each as one site named by its constants,
literals or types, with the lowering's own jumps dropped as `SWITCH_LOWERING`.
kotlinc's and scalac's string matches have no index switch, so only their hash
switch and null check are dropped, and each `equals` check stays a site that
reads `status != "Aa"`. A throwing default (`MatchException`,
`IncompatibleClassChangeError`, `NoWhenBranchMatchedException`) keeps its
branch index but gets no slot, no probe and no graph node. A case's key comes
from its label, so it survives a case being added and javac renumbering its
`$SwitchMap$` holder from `$1` to `$2`, pinned by compiling two fixture
versions. ADR 0038 was brought in line with what the `javap` evidence showed:
the two rules by shape, `null` as a label, cases in instruction order, and the
rule-two keys moving when kotlinc swaps `ifeq` and `ifne`.

| Corpus | Chunk 4 ms | Chunk 5 ms | Change |
|---|---|---|---|
| demo | 8.267 | 8.288 ± 0.117 | none |
| demo-spring | 0.137 | 0.137 ± 0.001 | none |
| scala | 1.188 | 1.578 ± 0.025 | 72 classes, up from 58: +7% per class |
| spring-webmvc | 51.362 | 50.880 ± 0.649 | noise |
| ktor-server-core | 44.429 | 42.794 ± 0.207 | noise |

The agent side of the landing order is done. Across chunks 2 to 5 the
analysis costs 11 to 43 percent more per class than the baseline.

Chunk 6 landed in `yukon-collector` (`a09f9cd`): a `Redaction`
processor replaces `STRING_LITERAL` parts a blocked pattern matches, or all
of them, and clears unknown fields while it is on. The collector's bindings
moved to `v1.36.12-20260924225937-af331e73c211.2`, generated from `b2618cd`.
The review kept one deviation and recorded it in collector ADR 0001: an
unreadable `YUKON_COLLECTOR_REDACT_ALL_LITERALS` stops startup instead of
reading as false.

Chunk 7 landed in `yukon-server` (`8f28a79`): migration 0002 stores sites,
outcomes, conditions, case labels, guarded lines and guards per run and per
scan, and the guard joins each call edge table's key through `UNIQUE NULLS NOT
DISTINCT`. The review found Postgres cannot store NUL in jsonb or text, so a
part whose text holds NUL is stored as a placeholder instead of failing its
payload on every retry.

Chunk 8 landed in `yukon-server` (`3c1990f`): never-hit and stale-hit
return `rows`, each a method row or a site row carrying every outcome with
`in_finding`, and paging counts rows. A merged row's display fields come from
the newest in-scope run. The report has `methods` and `branch_sites` in place
of the mixed probe triple. An outcome with no site forms a site of its own
rather than dropping out.

Chunk 9 landed in `yukon-server` (`b6c649b`): the never-hit and stale-hit
tables, the graph's expanded method nodes and the headline read sites through
one module, and the branch index and key are shown nowhere. The browser check
found the mono font drawing `!=` as one glyph, which blurs `==` against
`===`, so mono text has ligatures off.

Checked end to end on 2026-09-25 against the rebuilt compose stack, with
`runDemoStack` (`53ff83c` taught its report printer the server's site rows):
the server reports 2 methods and 7 conditions with an untaken path, and
reads `System.getenv("ENABLE_LEGACY_DISCOUNT") == "true"` was never true,
only path to `DemoServerMain.kt:59`. The browser shows the same rows, and the
graph's `totalParam` node lists its four conditions with a true and false
marker each.

Left for later, in `yukon-server`'s STATUS: folding a dead method's branches
into its row, rooting clusters at a never-taken outcome, telling
real-but-uninteresting outcomes apart, and redaction at the server's ingest.

### Readable names and findings, UI items 3 and 6 to 9: landed in both repos

Settled 2026-09-25 and 2026-09-26 in grilling sessions driven by
`yukon-server`'s STATUS list "Names and findings a person can act on",
each checked end to end with `runDemoStack` and in the browser. The
server side of each is in that repo's ADRs 0032 and 0034 to 0037; the
agent side, one ADR and chunk each:

- ADR 0039 (`2077d68`): a never-taken outcome roots the unreached
  cluster behind it; the testkit and stub collector apply the rule.
- ADR 0040 (`4f8cea6`): each method's `static` flag on the wire, and
  `@JvmOverloads` forwarders marked `JVM_OVERLOADS`. Parity for server
  ADR 0034's class findings in `15c49f9`. Demo shapes in `70441f3`
  (`AuditLog`, `ReceiptPrinter`, `Money`, `Price`).
- ADR 0041 (`e34eb72`): each class's Kotlin kind from
  `@kotlin.Metadata`'s `k`; multi-file facade forwarders marked
  `MULTIFILE_FACADE`; generated forwarders pass calls through; multi-file
  parts, which kotlinc marks synthetic, are probed. Demo gains the
  two-file `DemoText` shape.
- ADR 0042 (`c774947`): a `CREATES` edge carries the interface its
  `invokedynamic` implements.
- ADR 0043 (`2df937b`): each method's parameter names, generic
  signature and extension-receiver flag.

`ConditionInstrumentationTest` finds the demo checkout's condition
lines in the demo source (`52392b1`), so demo edits no longer break it.

Open from these:

- A `@file:JvmName` single-file facade is never probed: ByteBuddy
  refuses to redefine a class carrying `@kotlin.jvm.JvmName`, so the
  agent reports it skipped. Its code is never judged.
- `LoadedClassSweep` still drops every synthetic class, so a multi-file
  part that reached no transformer is not reported.
- A javac inner-class constructor with no generic types has no
  `Signature` to drop its outer instance by, so `yukon-server` shows
  `this$0`. A flag read from the class's `InnerClasses` attribute would
  close it.
- A receiver lambda whose captured values come first gets no
  `extension_receiver` flag, since only the first parameter is read.
- The testkit and stub collector do not fold the sites under a
  never-hit method, which the server does (its ADR 0031).
- `runDemoStack`'s printer still prints JVM names on its endpoint and
  optional-parameter lines.

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

### Naming hidden code: landed in all three repos

ADR 0034, with server ADR 0028. A1 (`bcd30a8`) sends `CallEdge.kind`
(`CREATES` for invokedynamic targets and body-class methods),
`captured_count`, `lambda_body` and each class's `source_file`, with
`ClassSupertypes` renamed `ClassLocation`. A2 (`07e95d7`) sends
`body_kind` and `source_name`, and makes every body class the agent
does not probe a pass-through: kotlinc's reference classes, `$sam$`
wrappers and suspend-function continuations. The collector follows the
rename in `a64ab18`, and the server stores, names and shows the facts.

Checked end to end on 2026-09-23 with `runDemoStack`: `main$lambda$0`
(the `/__shutdown` handler) reads as a lambda body created in `main` at
`DemoServerMain.kt:48`, `main` creates `handleCheckout`, and
`handleCheckout` creates `respond` through `val send = ::respond`.

### Naming a hidden handler: landed in both repos

ADR 0035. An endpoint whose handler reaches the framework through an
`invokedynamic`, such as `/checkout` (`::handleCheckout`) and
`/__shutdown` (a lambda) in the demo, gets its handler name from the
JDK's lambda factory. Advice on `spinInnerClass()`, installed by
retransformation after a reflective shape check, records the method
each `HttpHandler` lambda class calls in a weak map in the bootstrap
seam, and the three `HttpServer` advices read it back. A miss stays
null. The members were checked on JDK 21, 22, 25, 26 and 27, and CI
runs the suite on 21 and 25.

A1 landed on 2026-09-23 (`365cbe7`): the hook, the seam,
`EndpointModule.handlerInterfaces`, the `HttpServer` switch and the CI
matrix. `runDemo` names `/checkout` as `DemoServerMainKt#handleCheckout`
and `/__shutdown` as `DemoServerMainKt#main$lambda$0`.

A2 landed on 2026-09-23 (`da06d7a`): the forwarder table. The analyser records a
pass-through a handler can be reported as, only for a handler interface
and only when the call-edge walk reaches one concrete target: scalac's
`$adapted` forwarder named by an `invokedynamic`, and the `handle` of a
kotlinc reference class under class-based SAM conversion, written when
its creator is analysed. `RegistryResolver` applies the table on
`register` and `attachHandler`. Spring's functional module names
`HandlerFunction` and asks the seam for a hidden handler in its declare
walk and its dispatch advice. A `$sam$` wrapper keeps its own name,
since it only calls the function value it holds. No wire or collector
change in either chunk.

`yukon-server` followed on 2026-09-24 (`37f690b`): `/endpoints` rows
carry the handler's `created_in`, lambda-body flag and captured count,
so its endpoints table reads `/__shutdown`'s handler as "lambda in
`main`". Checked end to end on 2026-09-24 with `runDemoStack` against
the rebuilt compose stack: `/checkout` joins `handleCheckout`,
`/__shutdown` joins `main$lambda$0`, and neither handler is an entry
root on the graph.

### Run id on every payload: landed in all three repos

ADR 0032. The agent makes a random run id once per process and stamps it on
`ResourceAttributes.run_id` (field 5), and every delta batch, manifest chunk
and baseline chunk from one process carries the same value. `ProbeManifest`
carries `ResourceAttributes resource = 14` in place of its own
`service_name`, `service_version` and `service_instance_id`, which are
reserved by number and name, so the manifest also gains `environment`.
`ResourceAttributes.forNewRun` is the one place the id is made; `Agent.start`
calls it once and hands the result to the scheduler and the baseline scan.
The change is breaking on purpose, since nothing is released. `buf lint`
passes. `buf breaking` flags the three removed manifest fields, and CI runs it
only on pull requests, so a push to `master` publishes to BSR.

`ExportScheduler` takes the resource as a required parameter, so a caller
cannot get a second run id by leaving it out; tests build theirs with
`TestResources.forConfig`. The demo's stub collector keys every class,
endpoint and dependency map on (instance, run) and answers 400 to an empty
run id. The testkit collector keys on instance alone, which holds for the one
agent in its own JVM (ADR 0018). It answers 400 to an empty run id and to a
second run id under a known instance id, and lists both in
`rejectedPayloads()`. After any rejection, `awaitSettled` and every other
public query and wait throw `IllegalStateException` listing the reasons, so a
test fails even if it never checks `rejectedPayloads()`.

The other two repos followed on 2026-09-22. `yukon-collector` (`b40113a`)
bumped its bindings, reads the manifest's identity from `resource`, and
rejects any payload with an empty run id; its shard key stays service plus
instance, so a restart does not move an instance to another shard.
`yukon-server` (`704a8ff`, its ADR 0024) keeps each run as its own row
under its instance, with every per-run table hanging off the run, and
merges with `max()` within a run. That replaced its reset-aware merge and
its wipe on a version change. `runDemoStack` passes against the rebuilt
stack.

### Dependency usage: landed, with follow-ups

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
and the sweep reads no jar. The Boot 2 `jar:file:<outer>!/<entry>!/` form was
checked on 2026-09-24 against the real `spring-boot-loader` 2.7.18 and 3.1.12,
each in a fat jar built by hand around one app class and three libraries. Both
loaders gave exactly that form for `BOOT-INF/classes` and for a nested jar. The
used library counted its one loaded class, the other two read as unloaded, and
all three came from the startup listing. One thing stays open: a flat jar first
seen at load still goes through the agent-jar rule, which rightly turns away a
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
gets one belongs to no dependency.

Chunk 4 (references in the static baseline) has landed. The scan has no
defining loader and in a fat jar cannot see `BOOT-INF/lib`, so it maps names
through a class index the listing builds only when the baseline is enabled
(11,356 names for `demo-spring`), released as soon as the baseline has read
it. The publisher waits for the listing, which releases the wait on failure as
well as success, with a two-minute backstop. Two resolution choices: a name
seen at the root of a jar counts as the adopter's own only when no dependency
holds it, since on a flat `-cp` classpath the scan walks dependency jars too;
and the baseline never records a name as absent, because it cannot tell a
missing class from one in a jar only a runtime loader opens, and the first
recording of a name wins. `StaticBaseline.external_classes` stays empty; every
mapping travels on the manifest.

Chunk 5 (`references_recorded`, the stub's dependency report, the Spring demo)
has landed. `ProbeManifest.references_recorded` is set on every manifest when
the include rules are set, so a collector can tell an instance that records
nothing from one that references nothing (since ADR 0033 a running agent
always has include rules, so it is always true; the field stays on the wire
for the collectors that read it); the collector judges each dependency
only by the recording instances that list it. `runSpringDemo` reports 5
unloaded (commons-lang3 among them), 26 unreferenced (spring-webmvc and the
rest of the framework), jackson-databind unreached from the never-loaded
`LegacyPricing#apply`, and 5 used. Two agent-side fixes came out of the demo
runs: a filename version must contain a dot (`endpoints-ktor-2.jar` had read
as `endpoints-ktor` at version 2 and merged with ktor-3; `jsr305-3.jar` now
reads as one artifact with no version), and a jar holding the agent's own
package is never a dependency, since an unshaded agent build carries no
`Premain-Class`. The demo server and client run on a classpath of their own
classes and kotlin-stdlib, so the agent runs from the shaded jar and the plain
demo lists no agent library as its dependency.

Chunk 6 (testkit) has landed: `dependency(group, artifact)`,
`awaitDependency`, `unloadedDependencies()`, `unreferencedDependencies()`,
`unreachedDependencies()` and `absentReferences()`, applying a port of the
demo's rules, with the demo's cases ported too and three more added to both.
The split queries throw rather than return an empty list when the include
rules or a complete baseline are missing, so an assertion of "none" cannot
pass on missing data. The two-batch gate `dependency()` had at first is
replaced by ADR 0036's delivery order; see "Dependency delivery order". The
`agentTest` suite runs with `staticBaselineEnabled=true` at no measurable cost
and proves unloaded, used and the split end to end against two fixture jars.
Chunk 7 has landed in `yukon-collector` (`4bc51e7`): its generated bindings
are bumped to BSR commit `df061083`, which carries every dependency field, it
forwards them untouched, and `LogSink` logs their counts and
`references_recorded`; the relay round-trip tests carry each field.

Chunk 8 has landed in `yukon-server` (`f417d3b`): migration 0012, a port of
the rules with all 25 of the demo's cases as store subtests, `GET
/api/v1/services/{s}/dependencies` with a status filter, `GET
/absent-references`, and a `dependencies` block on `report` with a
`split_available` flag. Proved end to end with the compose `stack` profile
and `:demo:runDemoStack`: the server's answer for the plain demo matches the
stub's (annotations unloaded, protobuf-java and byte-buddy unreferenced,
kotlin-stdlib used). The server judges a baseline complete from each
instance's latest complete scan, as its never-loaded rule does, and counts a
sweep-reported class as loaded; the stub and the testkit differ from it only
in the "never loaded" mark on a site, never in a status. Protobuf-java and
byte-buddy were the agent's own libraries, on the demo server's classpath by
mistake; since `94d64af` the plain demo lists only annotations (unloaded) and
kotlin-stdlib (used). The stack run was repeated on 2026-09-24, after
`yukon-server` took up the flag, and the server's answer matched.

Open, recorded rather than started:
- A `byte-buddy-agent` jar sitting flat in an exploded war's `WEB-INF/lib`
  is turned away by the agent-jar rule and reads as no dependency.
- Every Kotlin service reads kotlin-stdlib as used through `kotlin.Metadata`,
  which is true but says nothing about the adopter's own use of it.

Landing order as built, one chunk and one commit each:

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

### Dependency delivery order: landed in both repos

The testkit's `dependency()` gate counted two delta batches after the listing's
manifest, which assumes one batch per flush; a flush over 20,000 deltas sends
several beside its manifest, so a used jar could read as unloaded.
`absentReferences()` had no gate and returned an empty list until the listing
arrived. ADR 0036 settles it (2026-09-24): the agent sends a dependency's entry
only after a confirmed delta send carries its first counts, holds a reference
mapping until its dependency's entry is delivered, and stamps
`ProbeManifest.dependencies_listed` once the startup listing and the mappings
recorded before it ended are delivered. `CONTEXT.md` has the term.

Landing order, one chunk and one commit each, built with `/chunked-build`:

0. Wire and codec: `ProbeManifest.dependencies_listed`.
1. Agent: counting generations in `DependencyRegistry`, delivery recorded when
   every delta send of a flush is confirmed, entries and mappings held until
   then, one more manifest send in the same flush for what that releases, the
   flag, and an empty manifest to carry it.
2. Testkit and stub collector: a dependency is judged once its entry arrives;
   list queries and `absentReferences()` throw until every instance sent the
   flag; `awaitDependenciesListed`; the stub logs the flag.
3. `yukon-collector`: bindings bump, `LogSink` logs the flag.

Chunk 0 has landed (`c243ff3`): field 15 on `ProbeManifest`, the agent's model
and codec. Chunk 1 has landed (`761a475`): counting generations in `DependencyRegistry`,
entries and mappings held until a flush's delta sends are all confirmed, a
second manifest send in that flush for what it releases, and the flag with an
empty manifest to carry it. The independent review found three things the
brief did not predict. Every sweep after the listing counts, so the delivered
generation rises on most flushes, and the second send is keyed on an entry
waiting to go out, not on the generation alone. The empty flag manifest runs
only after a flush whose sends were all confirmed, so an outage never adds a
send. And the sweep's confirmation pass could throw before the count, which
would have held every dependency back for good; the count runs in a `finally`.
On the plain demo the releasing flush sends three manifests: probes, then the
dependencies and mappings, then the flag.

Chunk 2 has landed (`0f6fcd2`): the testkit judges a dependency once its entry arrives,
the list queries and `absentReferences()` throw until every instance heard
from has sent the flag (and while none has been heard from), and
`awaitDependenciesListed` waits for it. The stub collector logs the flag and
names, after the counts line, any run that never sent it. The review found
that the testkit stored a manifest's entries before its mappings, on a handler
thread outside the query lock, so a query woken between the two could read a
used jar as unreferenced. The store writes mappings first, entries next and
the flag last. `dependency()` judges on the instances whose entry has arrived;
with several instances, `awaitDependenciesListed` first covers the rest.

Chunk 3 has landed in `yukon-collector` (`74d7a9b`): bindings bumped to BSR commit
`084ba94b`, `LogSink` logs the flag, and the relay round-trip test carries it.

`yukon-server` takes the flag up under its ADR 0029: dependency reads carry
`listing_complete` and the instance list `dependencies_listed`.

Recorded, not planned:
The testkit's `awaitSettled` counts delta batches the same way and keeps the
split-flush weakness for hit totals.

### The agent refuses to start without include rules: landed

Grilled and settled on 2026-09-23; ADR 0033 holds the decision, and ADR 0030's
consequences were amended. With `includePackages` parsing to no prefixes
(absent, empty, only separators, or only `excludePackages` set) the agent logs
one ERROR and disables itself entirely, endpoint tier and exporter included.
The ERROR suggests the main class's package when it can be found
deterministically (`sun.java.command`, a jar's `Start-Class` then
`Main-Class`, a module launch's class) and suggests nothing for a main class
in `org.springframework.boot.loader`, `io.ktor.server` or `org.apache.catalina`.
`enabled=false` stays one INFO line. No `*` escape hatch; a broad prefix such
as `com` is accepted.

Landing order, one Opus chunk and one commit each:

1. The refusal and the suggestion: `Agent.start` checks the include list right
   after `enabled`, before anything installs; the main-class resolution as a
   pure, unit-tested function; `AgentConfig.parse` drops its WARNING, since the
   ERROR replaces it; an `Agent.start` test for each refused shape.
2. One meaning for "no include rules": `TypeMatchPolicy.isIncluded` matches
   nothing for an empty list; `JarClassifier` loses its unset branch; the tests
   that relied on an empty list (`LoadedClassSweepTest`,
   `DeflectedClassLoadTest`, `LoadedDependencySweepTest`) set a prefix; the
   testkit's startup-timeout message names `includePackages`; the testkit KDoc
   and README attach examples set it, and the README lists it as required.

Progress:

- Chunk 1 landed: `Agent.start` refuses right after the `enabled` check, and
  `MainClassSuggestion` resolves the main class. Review added two cases the
  brief missed: a `.war` is read like a jar, and `java -m com.acme.shop`, which
  puts the bare module name in `sun.java.command`, resolves through the boot
  layer to the module's declared main class instead of reading the module
  name as a class. Both checked on JDK 22, the boot layer from a real
  `premain`. A launch of the scratch Hibernate program and of a `-jar` with no
  include rules logged the ERROR with the right suggestion and wove nothing.
- Chunk 2 landed: an empty include list matches nothing in
  `TypeMatchPolicy.isIncluded`, traced through every caller (live matcher,
  class-bytes capture, static scanner, sweep, `JarClassifier`, the analyser's
  three scope checks); `JarClassifier` lost its unset branch with nothing
  observable changing, pinned by a new `JarClassifierTest`. Three tests that
  relied on the old default set explicit prefixes that keep them testing the
  same gate, and two that only made sense for it were rewritten to pin the new
  meaning. The testkit's timeout message and KDoc example, and the README's
  attach example and options table, name `includePackages` as required.
  `runDemo` reports as before. No change in `yukon-collector` or
  `yukon-server`: `references_recorded` keeps its meaning.

### Generated methods: branches marked, two over-marks closed: landed in both repos

Grilled and settled on 2026-09-23 as an amendment to ADR 0026; `CONTEXT.md`'s
"generated method" was reworded. The question was the branch sites inside a
generated method: the branch `ProbeMeta` passed no `generatedBy`, so a data
class's `equals` was marked while its jumps read as never-hit code the adopter
wrote, which is why `demo-spring`'s `TaxRate` had been a plain class. They are
marked, not dropped: their counts are evidence the way the method's are, and
marking keeps the slot layout. Every consumer already read the mark whatever
the probe kind (the server's `judgeableProbePredicate`, the testkit's
`neverHit`, the stub's partition), so no consumer logic changed.

Checking the shapes with `javap` on Kotlin 2.2.21 found two places ADR 0026
already hid code the adopter wrote, method probes included:

- Under `-jvm-default=disable`, the default up to language version 2.1, an
  interface default method's real body lives in `$DefaultImpls` and the
  interface method is abstract. Every `$DefaultImpls` method was marked, so
  every such body was hidden. Only a forwarder (load arguments, one
  `invokestatic` on the interface, return) is marked.
- A hand-written `equals`, `hashCode` or `toString` on a data class was marked
  with the generated ones. kotlinc emits the generated ones with no
  line-number table and the adopter's with body lines; only one with no table
  is marked. Stripped debug info leaves all three marked.

Only a data class's `equals` and `hashCode` hold branch sites among generated
methods: enum and record methods have none, and `copy$default` is synthetic.
The static scanner calls the same analyser, so `DeclaredMethod` follows both
refinements with no scanner change.

Landing order, one Opus chunk and one commit each:

1. `$DefaultImpls` forwarders only, with fixtures compiled under `disable` and
   the default mode.
2. Data-class `equals`, `hashCode` and `toString` marked only with no line
   table, with a hand-written-`equals` fixture.
3. Branch probes carry their method's mark; the `ProbeLocation.generated_by`
   proto comment and the testkit's `ProbeRef` KDoc follow; an integration test
   asserts `DATA_CLASS` on the branches of a generated `equals` and `NONE` on
   an ordinary method's; `TaxRate` goes back to a `data class` and the Spring
   demo's report stays at four never-hit rows.
4. `yukon-server`: the `read.go` comments that say a BRANCH probe never
   carries the mark. No logic change.

Progress:

- Chunk 1 landed: `BranchSiteAnalyzer.defaultImplsForwarders` accepts a
  body that loads each parameter once in order, makes one `invokestatic` on
  the interface, and returns; anything else is `NONE`. Every forwarder kotlinc
  2.2.21 emits under `enable` fits, generic, `long`/`double`, accessor,
  `$default` and suspend shapes included. `:fixtures-kotlin-jvm-default-disable`
  compiles the `disable` case and is wired in like the Scala fixtures. Review
  also fixed the proto's `GeneratedBy` comment, which still named every
  `$DefaultImpls` method.
- Chunk 2 landed: `markDataClassMembers` marks `equals`, `hashCode` and
  `toString` only when the method saw no `visitLineNumber`, from a
  `methodsWithLineNumbers` set collected for every method whatever the method
  filter says, so the baseline follows without a scanner change. The new
  `GeneratedPointCustomEquals` fixture holds a hand-written `equals` with two
  conditional sites for chunk 3. Stripped debug info leaves all three marked,
  pinned by a test.
- Chunk 3 landed: the branch `ProbeMeta` passes `analysis.generatedBy(...)`
  like the probes beside it; the mark is outside the layout hash and the slot
  order. `TaxRate` is a `data class` again. `runSpringDemo` measured three
  ways: before, 22 probes and 4 never hit; `data class` without the mark, 34
  and 10, the six extra rows all `TaxRate#equals` branches at line -1; with
  it, 34 and the same 4, with 11 generated and not judged. A single-`Double`
  data class's `hashCode` has no branch site, so only `equals` showed.
- Chunk 4 landed in `yukon-server` (`e5f53f4`): the `Probe` comment in
  `read.go` and the README say a branch probe carries its method's mark. No
  logic change and no bindings bump, since the proto changed only in comments.

### Generators other than Spring and Hibernate are not recognised

ADR 0029 turns away a runtime-generated class by the markers its generator puts
in the name. Spring's CGLIB and Hibernate are covered, the only two confirmed
against their own source and run end to end here. ByteBuddy's own
`$ByteBuddy$`, javassist's `_$$_jvst` and JDK dynamic proxies (`$Proxy` in a
non-public interface's package) produce the same shape and are not covered.

Each is a rule in `TypeMatchPolicy.isRuntimeGenerated`, a test beside the
others in `TypeMatchPolicyTest`, and an edit to ADR 0029's consequences.
`LoadedClassSweep` follows the rule with no change of its own. What gates the
work is confirming a library's naming against that library rather than
recalling it: none of the three is a dependency of this repo.

Hibernate landed on 2026-09-23. Its guessed marker, `$HibernateProxy$`, was
wrong for 6.6 and 7.4, which name the proxy `<Entity>$HibernateProxy` with
nothing after it; ADR 0029 lists the four kinds of class and each version's
spelling. The suffixes are matched as whole `$`-separated parts of the name,
not as substrings, so an adopter's `Util$HibernateProxyUnwrapper` is kept. A
scratch program driving hibernate-core 7.4.10's own generators under the agent
showed all four kinds woven before the rule and none after.

Not covered, and not checked: Hibernate's bytecode enhancement rewrites the
entity class itself, adding `$$_hibernate_` methods to a class the adopter
wrote. Whether those methods are synthetic, and so already out of the method
tier, has not been looked at.

### Stable branch identity: landed in both repos

`branch_index` is a class-wide ordinal. Sites are numbered in bytecode order
across every method of the class (`YukonInstrumentation.kt`, `BranchSite.kt`),
and dropped sites keep their place. The number holds still when the scope or
a drop rule changes. It does not hold still when the code changes: one new
conditional in an early method shifts every later branch in the class,
including branches in other methods. Nothing on the wire ties branch 14 in
one release to branch 12 in the one before.

`yukon-server` needs that tie to date a branch across releases. Its fix for
the capped location dates keeps one row per location for the whole service,
but it cannot key a branch that way: a row keyed on `branch_index` would move
an old date onto a new branch and call it dead for years. So branch probes
keep per-instance dates there, capped by scope and marked `dates_capped`,
and `known_for_days` still drops the ones whose capped date is too recent.
See the service-wide dates entry in `yukon-server`'s STATUS.md.

The server side landed on 2026-09-22 (`yukon-server` `4ee1026`, its ADR
0025): a keyed branch outcome gets a service-wide date row and groups by
its key across builds, and only keyless outcomes stay capped.

A grilling session settled the design in ADR 0031: each kept branch outcome
gets a branch key, a digest of its class, method, descriptor, condition
fingerprint and outcome, sent as `branch_key` on `ProbeLocation` beside
`branch_index`. Sites that share a fingerprint in one method get no key, and
neither does any case the analyser cannot fingerprint with confidence.

Landing order:

1. ADR 0031 and the glossary terms (branch outcome, branch index, branch key).
2. Stack-depth tracking and the condition fingerprint in `BranchSiteAnalyzer`,
   stored on `BranchSite`.
3. The key itself, with collision handling and switch case keys, carried
   through `ProbeMeta` into the manifest, and the proto field.
4. Tests from v1/v2 fixture pairs: each kind of edit keeps or changes the key
   as the ADR says, plus the collision and inlined-copy cases.

Progress:

- Step 1 landed with ADR 0031 (`7de11e8`, amended in `46269de` to name
  local variables from the `LocalVariableTable`).
- Step 2 landed: `ConditionFingerprinter`, a second read of the class
  bytes beside the analyser, gives every tracked site a fingerprint or
  null, and every switch its case keys in the rewriter's outcome order.
  Stack depth is tracked by hand, since ByteBuddy's shaded ASM has no
  `AnalyzerAdapter`. Review found that a condition holding its own
  branches (ternary, elvis, the earlier `&&` operand) empties the stack
  partway, so its window starts late and an edit before that point keeps
  the key; ADR 0031's consequences say so.
- Step 3 landed: `BranchKeys` applies the collision rule and derives each
  kept outcome's key (SHA-256 of a `v1`-tagged text, first 16 bytes as
  hex), `ProbeMeta` and the manifest carry it, and `ProbeLocation` has
  `optional string branch_key = 18`. The layout hash is pinned by a test
  and unchanged. The testkit's `ProbeRef` exposes `branchKey`. `buf` was
  not available locally; the change is a new field only, and CI's `buf`
  workflow passed on it.
- Step 4 landed: v1/v2 fixture pairs under `com.example.target.keypairs`,
  renamed to one class name with `ClassRemapper`, prove each edit ADR
  0031 names keeps or changes the key, plus an end-to-end check through
  the real transform in two class loaders. No main-code bug turned up.
  Review replaced the ternary pair, which only put a statement between
  the ternary and the condition, with one where the `if` expression sits
  inside the condition: an edit to its true arm keeps the outer key and
  an edit to its false arm changes it, as the Consequences say.

The server work this unblocked landed on 2026-09-22 (`yukon-server`
`4ee1026`, its ADR 0025, BSR `15053c3b6627`): a keyed branch outcome gets a
service row and groups by its key across builds, and a keyless one stays capped,
with never-hit under `known_for_days` and stale-hit reporting what they hide as
`capped_hidden`. Nothing here is open.

Two things a fresh cloud session needs for this repo's build. Maven Central
has answered 429 through the sandbox proxy, and a session-local Gradle init
script pointing at Google's mirror of it
(`maven-central.storage-download.googleapis.com/maven2`) worked once the
user approved it. And the `ktlint` CLI the chunked-build command runs is not
preinstalled; the 1.5.0 release binary from GitHub works.

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

### Deletion manifest over MCP, and why the CI skip was rejected

Explore, not designed. This started as a CI idea: use the fleet's never-hit
set to skip tests that only exercise dead paths. That form is rejected. What
survives is a deletion manifest served to an adopter's own coding agent.

Why the CI skip was rejected, any one of these being enough:

- It reverses ADR 0015. Classification and confidence live at the collector,
  and the testkit stops at query primitives on purpose, as the note below on
  the CI-gate helper already says. A skip is that same policy call made
  louder: a gate fails a build where a human looks, a skip quietly stops
  running something.
- The incentive runs backwards. A skip makes dead code cheaper to keep,
  because it stops costing CI time, which takes away the reason to delete
  it. Deleting the code gets the same CI saving and keeps the pressure on.
- The saving is unmeasured and probably small. A test is skippable only when
  every probe it reaches is never hit in the fleet, and shared setup code
  that production runs sits in almost every test's path.
- A wrongly skipped test is invisible. Nothing in the build says a
  regression went unguarded.

The report form, "these tests guard only dead code", was weighed as the safer
half and rejected too. On the JVM the compiler already finds every test that
names a deleted method, and IntelliJ's Safe Delete lists those tests and
offers to remove them in the same refactor, so the report restates what the
adopter's own tools give for free. It has the dilution problem as well: tests
reaching only never-hit methods is a near-empty set, and relaxing it to
"mostly dead" sets a threshold, which is the ADR 0015 line again.

One case survives both arguments: a test that reaches dead code without
naming it, usually an end-to-end test whose whole point was that path.
Deleting the code leaves it compiling and often passing, and it guards
nothing. Phase two below is the only part that needs per-test coverage.

What replaces it. Serve the server's dead-code findings as a manifest an
adopter's coding agent reads to open a deletion PR. The compiler argument
above is IDE-shaped: it holds for a human with a usage index and Safe
Delete, not for an agent writing a patch, which has neither until it
compiles.

Most of the data is already there. `/unreached-clusters` returns a rooted
connected group of never-hit methods with its routes, members and
never-loaded classes, which is already a deletion unit. With `/never-hit`,
`/never-loaded` and the unloaded rows from `/dependencies`, the manifest is a
composition of endpoints the server serves today, not a new data path. Scope
it per cluster rather than per service, so one cluster is one reviewable PR:
a service-wide manifest invites an agent to open a 400-deletion PR nobody
reads properly. A REST endpoint under `/api/v1` is the contract and the MCP
server a thin wrapper over it, so the contract outlives changes to the MCP
spec.

Age metadata, so the threshold stays the adopter's. The manifest carries how
long each finding has been dead and sets no threshold of its own. Yukon
cannot know about a month-end batch job, a disaster recovery path or a flag
that is off until it is not, and the adopter can. This is the ADR 0015
posture on a new surface: report the observation and its span, and let the
consumer decide what is old enough to act on. This also answers most of the
trust problem with agent-raised PRs: the judgement sits with the party that
has the context for it.

The store mostly holds this already. `probes.first_seen_at` (migration 0002)
dates a probe, `instances.first_seen_at` and `last_seen_at` bound the
observation window per instance, and `last_hit_at` is derived already as
`max(probe_counts.updated_at) FILTER (WHERE hits_total > 0)` for the
stale-hit finding, null when nothing ever hit it. Putting these on the
never-hit rows is read-path work, not a schema change. Two facts have to
travel together, because either alone misleads: how long the finding has been
dead, and how long Yukon has been watching. "Dead for 90 days" says nothing
until the reader knows whether the window is 90 days or three years.

The age gap this once waited on is closed. `yukon-server` keeps location
dates once per service (its "Service-wide dates" entry), so a method's age no
longer stops at the oldest in-scope instance or the retention window, and
every reply carries `watched_since`. Branch-level ages are still capped
there until the server keys branch rows on ADR 0031's branch key, so a first
cut of the manifest is method level, which the JaCoCo join below already
assumes.

Phase two, JaCoCo for the vacuous test case. Needed only for the surviving
case above, and only once the manifest stands on its own. JaCoCo's runtime
dumps and resets execution data per test (`IAgent.getExecutionData(true)`),
which gives a test-to-method map to intersect with the never-hit set. Yukon
never runs in the test JVM: the join is offline on class, method name and
descriptor, the key both already carry. The two agree more than they appear
to, since ADR 0003 takes its per-class count array from JaCoCo, ADR 0015 uses
JaCoCo's own `SyntheticFilter` allow-list with one refinement, and
`BranchSiteAnalyzer` already tolerates JaCoCo-instrumented bytecode as input,
confirmed against 0.8.13's offline `Instrumenter`. Branch level will not
join, because JaCoCo's branch identity is merge-point based while Yukon's
slots are allocated in encounter order, so a first cut is method level only.
Inline probes stay excluded, as ADR 0025 and the server's rules exclude them
already. Set JaCoCo's `includes` to the agent's `includePackages`: it bounds
the per-test dump cost and lines the two sets up by construction.

Open before any of this is built:

- Branch-level ages, which wait on the server keying branch rows on the
  branch key.
- What the manifest says about a finding whose observation window has holes,
  an instance absent for a month.
- Whether the manifest carries the exclusions the server already tracks
  (inline probes, generated methods, disabled-module endpoints) as evidence
  beside a finding, rather than filtering them out where no reviewer sees
  them.
- Whether any of it sits behind publishing. Nothing is published anywhere,
  so no adopter can run the agent yet, let alone point an agent at its
  output.


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
static scan not opening `BOOT-INF/lib` nested dependency jars, the
classpath blind spot for app-server, OSGi and plugin-loaded deployments, and
include rules being required, with no `includePackages=*` to ask for every
class (ADR 0033).
Each has its own section in `CLAUDE.md` with the reasoning and what it would
take to change.
