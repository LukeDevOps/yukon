# Status

Working notes on what is in flight and what is parked. Not a changelog; git
history covers that. `CLAUDE.md` holds the design in long form, `docs/adr/`
one record per decision, and `CONTEXT.md` the glossary. Where this file and
`CLAUDE.md` disagree about the state of the code, this one is right.

## TODO

### The sweep's chunking and cadence are untested

Two paths the sweep added have no test. `computeManifestDeltas` weights an
unreported class at one and seals a chunk at the cap, but nothing drives it
with more unreported classes than the cap. And `ExportSchedulerTest` never
passes an `unreportedClassSweep`, so every scheduler test takes the null
branch in `maybeSweep` and "every tenth flush, always on the final one" is
asserted nowhere.

Neither is load-bearing for a dead-code claim: the worst a chunking bug does
is an oversized POST, and a cadence bug means sweeping too often or too
rarely. Worth closing when the file is next open.

### A transform that fails after ByteBuddy hands back the bytes

Probes and endpoint declarations are both committed from
`AgentBuilder.Listener.onTransformation`, which runs only once `make()` has
produced the class's bytes. That closes the window ADR 0007 is about, for
every failure ByteBuddy can see.

It does not cover what happens after `getBytes()` returns. The JVM verifier
can reject the woven class, a transformer registered later in the chain can
replace the bytes, and `defineClass` can fail with a `LinkageError` for a
supertype that is missing at load. In each case the registry already holds
that class's probes, the manifest carries them, and nothing ever increments
them: permanently zero, which is what a collector reads as dead code. An
endpoint the same class declared is in the same position.

The sweep ADR 0027 added does not catch this, and cannot as it stands. It
compares one way, loaded against what the registry knows, which is race-free
because a class is registered before the JVM defines it. These classes go the
other way: registered, then never loaded, so they never appear in
`getAllLoadedClasses()` and a loaded-against-known comparison sees nothing
wrong. The reverse check, registered against loaded, has no such property. A
class registered moments ago has not finished being defined, and a class
whose classloader has since been collected is gone for an ordinary reason.
Both would read as failures, so the reverse check needs a grace period and an
answer for unloading before it can run at all.

The `<clinit>` probe is a partial signal in the meantime: a class that never
initialised has it at zero, and the method probes under it are then zero for
a reason that says nothing about the adopter's code. Two things stop it being
the whole answer. Only a class with a static initializer of its own gets that
probe, since the agent's own woven prelude does not add one. And a class that
loaded and was genuinely never initialised is a real finding rather than a
blind spot, so a collector cannot suppress every probe under a zero `<clinit>`
without losing it.

Rare in practice: it needs a second agent in the chain, or bytecode the
verifier rejects, which would break the application itself rather than only
the report. Not started.

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

### The demo never produces a CGLIB proxy

`demo-spring` has no `@Bean` method and nothing `@Transactional`, so Spring
never generates a proxy in the demo's own packages, and how the agent treats
one is untested end to end. A proxy class is synthesized in memory under a
run-specific name, so on the face of it the type matcher takes it, the
static baseline cannot know it exists, and whatever probes it gets join to
nothing a collector holds by name.

One `@Bean`-bearing configuration class in `demo-spring` would show what
actually happens, which is the point: nobody has looked yet.

### The stub demos and the compose stack fight over port 4319

`:demo:runDemo` starts `StubCollectorMain` on 4319, the same port the
testkit's collector defaults to and the port the compose stack's collector
binds on the host. With the stack up, the stub dies on bind and the demo run
fails for a reason that has nothing to do with the agent. A port taken from
config, or a stub that picks a free port and tells the demo server which one,
would close it.

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
