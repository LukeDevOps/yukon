---
status: accepted
---

# Scala default getters are re-kinded as omission probes on their target, with a cross-class target for constructors

Scala compiles each optional parameter to a public, non-synthetic default getter named `f$default$N`, one-based across every parameter list, which a call that omits the parameter invokes before calling `f`. The method tier already gives that getter a slot, so its hit total is already the parameter's omission total. The agent reports that same slot as an `OPTIONAL_ARGUMENT` probe on the target (`f`, its descriptor, `parameter_index = N - 1`, the name from the target's LocalVariableTable, `overridable` from the target's flags) and gives the getter no METHOD probe, the same shape Kotlin's omission probes already have on the wire. No new slot, no hot-path change, no layout-hash change: only the manifest row differs.

The target is resolved from bytecode in the getter's own class: a same-named non-getter method with at least N parameters, whose parameter N erases to the getter's return type (or is `scala.Function0`, since a by-name parameter's getter returns the value type), and whose parameters start with the getter's own, because a getter for a later parameter list takes every earlier list's parameters. The compiler forbids two overloads of one name both declaring defaults, so more than one survivor is a shape bytecode cannot tell apart. No survivor or several leaves the getter as an ordinary METHOD probe under its own name, with one INFO line. A Scala `inline def` with a default always lands there: its getter is emitted and called, but the function itself has no bytecode.

Constructor defaults cross a class boundary. The getter `$lessinit$greater$default$N` lives on the companion module class `Foo$`, with a static forwarder on `Foo`, while the target `<init>` lives on `Foo`. `ProbeLocation` gains an additive `target_class_name`, empty when the target is in the probe's own class and never set for Kotlin, and the agent resolves the constructor by reading `Foo`'s bytes through the module class's loader as a resource, never by loading it. A collector joins an omission probe to its target on the target class when set, otherwise the probe's own class. Wherever a person or a test names the parameter (the server's optional-parameter rows, the testkit's `omissionCount`), the class is the target's.

## Considered options

- Keeping the getter as a METHOD probe and adding a label. Rejected: the two findings need the parameter's identity (target name and descriptor, index, overridable), so the label would grow into every field `OPTIONAL_ARGUMENT` already carries, and every consumer would handle two shapes for one concept.
- Reporting a constructor getter's target as `<init>` and having the collector strip the module class's trailing `$` by convention. Rejected: a convention hidden on both sides of the wire, and the agent still needs `Foo`'s bytes for the descriptor.
- Skipping constructors in the first version. Rejected: case-class constructor defaults are the most common Scala default, and the field that covers them is additive.
- Special-casing Scala 2.13's `apply$default$N` onto the companion constructor. Rejected: the same rules resolve it against `Cc$.apply` in the same class; the resulting soft spot is documented below rather than patched.

## Consequences

- `parameter_index` counts a Scala extension receiver, because it is an ordinary first JVM parameter and the getter's N counts it. Kotlin's definition ("receivers not counted") is unchanged; the proto comment records the difference.
- A subclass can override the getter and the target independently, so a getter on `Base` may count omissions for a receiver whose `f` is `Sub`'s. The collector rule already handles this: never-supplied is claimed only for a non-overridable target.
- The trait's static accessor `t$default$1$` and forwarder `t$` are not getters and stay METHOD probes, a collector name policy like `componentN`. A trait implementer's own `t$default$1` is a synthetic bridge and gets no slot; the trait's default method does.
- `copy$default$N` on a case class re-kinds onto `copy` and is filtered by name at the collector, the existing `copy$default` policy.
- Scala 2.13 emits `apply$default$N` for the `Cc()` path and inlines `apply` at the call site, so that target is never hit and the constructor getters count only `new Cc()` omissions. Scala 3 routes both paths through the constructor getters. Documented, not corrected.
- The static baseline is unchanged: a getter is a declared method and never-loaded claims are class-level.
