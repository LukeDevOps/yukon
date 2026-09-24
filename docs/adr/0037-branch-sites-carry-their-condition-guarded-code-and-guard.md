---
status: accepted
---

# Branch sites carry their condition, and each outcome its guarded code

Decided on 2026-09-24 in a grilling session that spanned this repo, `yukon-collector` and `yukon-server`.

A never-hit branch outcome reaches a person as "Branch 12, `DemoServerMain.kt:121`". That says nothing about which condition it belongs to, which side never ran, or what code that side leads to. Line 122 of the demo has two never-hit outcomes that cannot be told apart. `branch_index` is a per-build counter and `branch_key` is a digest, and ADR 0031 designed both for machines. Nothing on the wire says which outcomes share a site. The agent reads everything a person needs from the bytecode, so it sends it: the site as a unit, the site's condition in source terms, and for each outcome the code that runs only through it.

## The design

- **Sites on the wire.** A METHOD probe's `ProbeLocation` lists the method's kept branch sites as `BranchSite` messages. A site carries its `site_index` (the per-class ordinal the analyser already assigns), its site key, its line, its condition and its guard. A BRANCH probe's `ProbeLocation` names its site by `site_index` and its role within the site: taken, fall-through, a case label or the default.
- **Site key.** A digest made the way ADR 0031 makes a branch key, from the class, method, descriptor and condition fingerprint, without the outcome. It is absent in exactly the cases a branch key is absent. A consumer groups a site's outcomes across builds by it, and within one run by `site_index` when it is absent.
- **Condition.** The expression the site tests, written out in the class's source language: Kotlin, Java, Scala 2 or Scala 3. It is read from the same window the fingerprint uses. It is stated the way the fall-through side reads it, which is the jump's own test negated. javac, kotlinc and scalac all compile `if (c) A` as "jump past `A` when not `c`", so in the usual case this matches the source, and when a compiler lowered the code another way the sentence is still true. Compiler idioms are read back to source: Kotlin `==` for `Intrinsics.areEqual`, a property for a getter call from Kotlin, a string template for a concatenation `invokedynamic`. The condition is a list of parts, each either code or a literal, so a collector can redact literals without parsing source text. A part the agent cannot write in source terms becomes a placeholder (`…`). When the placeholder would be the whole expression, the site has no condition.
- **Guarded code.** The analyser builds each probed method's control-flow graph, exception edges included, and computes dominators. An outcome's guarded code is the instructions that its outcome edge dominates. Its guarded lines are whole line ranges, where every instruction on the line is guarded, plus partly guarded lines, where the line also holds code reached another way. Each range names its source file and is mapped through the SMAP, so an outcome inside an in-scope inlined copy points at the inline function's own file. Guarded lines go on the BRANCH probe's `ProbeLocation`. An outcome can guard nothing, as the skip side of an `if` with no `else` does.
- **Guards.** A `CallEdge` and a `BranchSite` each carry their guard: the `branch_index` of the innermost kept outcome that dominates them, or none. Only kept outcomes can be guards. A dropped site, whether coroutine machinery or an out-of-scope inlined copy, is looked through to the next kept outcome, since it has no probe for a consumer to resolve. A catch handler has no guard unless a kept outcome dominates its whole `try`. An edge that takes a pass-through's place (ADR 0024) keeps the guard of the call to the pass-through. Edges are deduplicated by callee and guard together, so one callee called under two guards gives two edges.
- **The static baseline too.** A `DeclaredMethod`'s call edges carry guards by the same rule, and it lists its sites the same way. ADR 0024 has a collector treat baseline and manifest edges as one graph, so a class that was both scanned and loaded must describe itself the same way in both.
- **In the transform pass.** The analysis runs beside the fingerprinting, on the bytes the analyser already reads a second time for ADR 0031, so a class has one numbering and one view of itself. A transform-time benchmark on this repo's demos and a Spring Boot application is added before the analysis lands and read after.
- **Literals leave the process.** Before this decision the agent sent names and digests, never the adopter's constants. A condition sends string literals in clear text to the collector. Redaction belongs to the collector, where data leaves the adopter's network (collector ADR 0001). The agent has no redaction setting.

## Considered options

- **The outcome's destination line only.** Rejected: both outcomes of `?: return 0.0` land on line 115, and for an `if` with no `else` the skip side's destination runs anyway, so the line reads as dead when it is not.
- **Labelling outcomes as the `then` and `else` of the source `if`.** Rejected: the jump tests the negation of the source, and mapping one to the other means guessing each compiler's lowering. The fall-through reading is true in every case.
- **Sending an expression tree, or the raw window, for the server to render.** Rejected: how each compiler lowers code already lives only in the agent (SMAP, coroutine machinery, lambda names). A second copy in Go would serve a value that consumers display and never compute on.
- **Condition and guard copied onto each outcome's `ProbeLocation`.** Rejected: each fact belongs to the site, and copies are copies that consumers must check agree.
- **Guards on manifest edges only.** Rejected: a class both scanned and loaded would give an unguarded baseline edge beside a guarded manifest edge to the same callee, and in a merged graph the unguarded one wins.
- **An agent-side redaction switch.** Rejected: one policy in one place, at the network boundary. Two switches for one rule is how a team believes it is covered when it is not.

## Consequences

- A site key and a branch key digest the condition's constants. Redacting a literal stops it from being shown or stored, but anyone holding the keys and the rest of the condition can test guesses against a weak secret offline. Keys have to stay a pure function of the bytecode to join builds, so this is accepted. A secret compared in a condition is for the adopter's own secret scanning to find.
- Guarded calls prepare the ground for rooting an unreached cluster at a never-taken outcome, not at each callee behind it. The cluster rules are `yukon-server`'s decision.
- A consumer that ignores the new fields keeps working. `branch_index`, `branch_key` and `line` keep their meaning.
- Each compiler shape the condition writer reads back to source must be confirmed against `javap` output before it is coded, as ADR 0025 requires for coroutine shapes.
- Deciding which outcomes are real but not worth a person's time (the null path of `?.`, `!!`, a `finally` copy) stays the collector's job under ADR 0025. Readable conditions make it possible. They do not make it.
