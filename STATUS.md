# Status

Working notes on what is in flight and what is parked. Not a changelog; git
history covers that.

## TODO

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

Nothing in the transformer chain reports any of this back, so staging cannot
help and the fix needs a different signal. The `<clinit>` probe is a partial
one: a class that never initialised has it at zero, and the method probes
under it are then zero for a reason that says nothing about the adopter's
code. Two things stop that being the whole answer. Only a class with a
static initializer of its own gets that probe, since the agent's own woven
prelude does not add one, so a class without one carries no such signal. And
a class that loaded and was genuinely never initialised is a real finding
rather than a blind spot, so a collector cannot suppress every probe under a
zero `<clinit>` without losing it.

Rare in practice: it needs a second agent in the chain, or bytecode the
verifier rejects, which would break the application itself rather than only
the report.

### A class that loads while another class is being transformed

Both pipelines build on `AgentBuilder.Default()`, which shares one static
`CircularityLock`. It holds a per-thread entry for the whole of a transform,
so a class load triggered from inside one is handed straight back
untransformed, by both pipelines, with no listener call.

That is the right answer for the agent's own re-entrancy, and it leaves the
class unreported. It never registers, so it has no probes, and it never
reaches `recordSkipped`, so it is not in the manifest's skipped list either.
A complete static baseline that declared it then has no mention of it
anywhere, and a collector calls it never loaded when it loaded and ran.

Predates the staging work. The fix is to notice the case and record it the
way an unsafe class is recorded, rather than to remove the lock, which is
what keeps a transform from re-entering itself.

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
