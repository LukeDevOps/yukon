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
help and the fix needs a different signal. One candidate is already on the
wire. A class that never initialised has its own `<clinit>` probe at zero,
and every method probe under it is then zero for a reason that says nothing
about the adopter's code. The open question is whether a collector can use
that without also silencing a class that genuinely loaded and was never
initialised, which is a real finding rather than a blind spot.

Rare in practice: it needs a second agent in the chain, or bytecode the
verifier rejects, which would break the application itself rather than only
the report.
