---
status: accepted
---

# Optional parameters are counted where the compiler fills defaults, identified from bytecode, with overridable targets marked

A Kotlin function with default arguments compiles to the function itself plus a synthetic `f$default` that takes an `int` mask, one bit per value parameter, fills in the default for every set bit, and calls the function. The agent plants one omission probe per optional parameter at the entry of `f$default`, incremented for each set bit of the mask, and reports it as a probe of kind `OPTIONAL_ARGUMENT` on the target function with the parameter's index and, where debug info names it, its name. Which parameters are optional is read from the `mask & bit` tests in the `$default` body, one per optional parameter. Nothing is counted at call sites, and `$default` itself gets no method or branch probe.

Two findings follow at the collector. An optional parameter is never supplied when its omission total equals the target's hit total: every caller took the default, so the parameter can go. It is always supplied when its omission total stays at zero while the target was called: the default value is dead. The second holds for any target. The first needs the total number of calls, which for an open or interface method is spread across overrides the manifest cannot relate, since `Base.f$default` dispatches virtually; so each omission probe carries whether its target is overridable, and a collector claims "never supplied" only for a final target.

## Considered options

- Counting at call sites. Rejected: a call site sits in the caller's class, whose transform cannot know the callee's slot, and the callee may not be instrumented, loaded, or in scope at all. `$default` is the one place every omitting call passes through.
- Reading `kotlin.Metadata` through `kotlin-metadata-jvm` to learn which parameters declare a default. Rejected: a megabyte shaded into every adopter's JVM, a supported metadata version that must keep pace with adopters' compilers or fail on every class of a newer-compiled app, and the lookup string kept out of the jar's Kotlin relocation, to learn what the `$default` body already states. The mask tests are what that method exists for and have had the same shape since Kotlin 1.0. A `$default` whose body does not match the pattern gets no probes and one log line.
- Treating every mask bit as a parameter. Rejected: a required parameter's bit is never set, so it would read as always supplied.
- Shipping the class hierarchy so the collector can sum hits across overrides. Rejected: a much larger change than one flag, for a case Kotlin makes rare by making methods final unless declared `open`.
- Scala. Its compiler emits a public, non-synthetic `f$default$N()` per optional parameter, which the method tier already probes, so every omission is already counted under that name. Those probes are re-kinded onto the target by ADR 0023, which resolves the target from the getter's position and return type.

## Consequences

- Constructors are covered by the same rule: the synthetic `<init>(..., int mask, DefaultConstructorMarker)` has the same body shape and calls `this(...)`.
- Compiler-generated `copy$default` on a data class is probed like any other, and the collector filters by name, the policy already applied to `componentN` and `copy`. An interface's `$DefaultImpls` stub forwards without mask tests and so gets nothing.
- Only the first mask int is bound, so parameters past the 32nd get no probe and one INFO line.
- Omissions in a call from Kotlin to an inline function are invisible, because the call site inlines the body with the default already substituted. That is the same blind spot the method tier has for inline functions and is handled by ADR 0022.
- The static baseline says nothing about parameters. It answers whether a class ever loaded; once a class loads, its omission probes are in the manifest like any other.
