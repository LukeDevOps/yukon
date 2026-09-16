---
status: accepted
---

# The agent reports observations; classification is the collector's job

The agent's only job is to report raw hit counts over time, accurately and cheaply. How long a path must go unhit before it is called dead, what confidence to attach, and how to label a finding all live at the collector, which is the one component holding every instance's data long enough to decide. Nothing in the agent carries a threshold or a "dead" flag.

The same line decides which methods get a probe. The matcher excludes only what has no body of its own: abstract and native methods, bridges, and the synthetic forwarders a compiler emits beside the method they belong to, such as Kotlin's `$default` and `access$` accessors, synthetic constructors and function-reference adapter classes. Two synthetic shapes hold the adopter's own code and do get probes: a lambda body, which javac names `lambda$<method>$N` and scalac names `$anonfun$<method>$N` in a class carrying a `Scala` or `ScalaSig` attribute, excluding the `$adapted` boxing forwarder Scala 2 emits beside a body without marking it as a bridge. That is the same allow-list JaCoCo's `SyntheticFilter` applies, plus the one exclusion. Kotlin needs no entry, since its lambda bodies are plain private static methods. A type initializer gets a probe when the original bytecode has a `<clinit>` of its own, incremented in the woven prelude that already runs there rather than by advice; a type with none gets no probe, so a marker interface, which the JVM never initialises however many classes implement it, cannot register only to read as never hit.

Kotlin's generated `componentN`, `copy`, and data-class `equals`/`hashCode`/`toString`, and its `$DefaultImpls` classes, are none of those and get ordinary probes. Filtering them is a labelling policy the collector can apply by name. Reading `kotlin.Metadata` on the transform path to do it in the agent would add cost for a policy call the agent should not be making.

## Consequences

- A native method gets no slot. ByteBuddy declines to weave advice into a method with no body, so a slot there would read zero forever and be reported as code that could never, by construction, be observed running.
- `yukon-testkit` stops at query primitives (`wasHit`, `hitCount`, `neverHit`, `skippedClasses`, `neverLoaded`). A CI threshold is an adopter's composition, not a built-in.
- The collector's merge policy for a genuine counter reset under a pinned instance ID (plain `max()` versus reset-aware accumulation) is a collector decision, recorded in `yukon-collector`.
- Probing every synthetic method was rejected. In this repo's own output about a thousand synthetic and bridge methods are forwarders. A bridge reads zero whenever callers use the exact signature, so it would be a false never-hit on a method the compiler is obliged to emit, and `$default` already belongs to the omission tier.
- The lambda allow-list is a compiler contract. If a spelling changes, the failure is a body with no probe, which is the earlier behaviour, never a wrong claim.
- A Kotlin `object` holding only `const val`s has a `<clinit>` that never runs, because callers inline the constants. Its probe reads zero honestly; whether that is dead code is the collector's call, as for every other row.
