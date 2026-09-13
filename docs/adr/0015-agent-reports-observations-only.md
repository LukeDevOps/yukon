---
status: accepted
---

# The agent reports observations; classification is the collector's job

The agent's only job is to report raw hit counts over time, accurately and cheaply. How long a path must go unhit before it is called dead, what confidence to attach, and how to label a finding all live at the collector, which is the one component holding every instance's data long enough to decide. Nothing in the agent carries a threshold or a "dead" flag.

The same line decides which methods get a probe. The matcher excludes only what has no observable body or outcome: abstract, native, synthetic and bridge methods, type initializers, and synthetic types. Kotlin's generated `componentN`, `copy`, and data-class `equals`/`hashCode`/`toString`, and its `$DefaultImpls` classes, are none of those and get ordinary probes. Filtering them is a labelling policy the collector can apply by name. Reading `kotlin.Metadata` on the transform path to do it in the agent would add cost for a policy call the agent should not be making.

## Consequences

- A native method gets no slot. ByteBuddy declines to weave advice into a method with no body, so a slot there would read zero forever and be reported as code that could never, by construction, be observed running.
- `yukon-testkit` stops at query primitives (`wasHit`, `hitCount`, `neverHit`, `skippedClasses`, `neverLoaded`). A CI threshold is an adopter's composition, not a built-in.
- The collector's merge policy for a genuine counter reset under a pinned instance ID (plain `max()` versus reset-aware accumulation) is a collector decision, recorded in `yukon-collector`.
