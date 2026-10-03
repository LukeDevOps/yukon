---
status: accepted
---

# Bytecode shape is read from the class file, and probes are woven into the received bytes

Decided on 2026-10-03 in a grilling session with Luke, after the deep review of that day found 64 tests failing only with JaCoCo's agent on the test JVM.

Every result this agent reads from a body's shape (ADR 0025's inlined copies and coroutine machinery, ADR 0026's and ADR 0048's generated methods, ADR 0037's conditions and guards, ADR 0038's switch lowering, ADR 0046's routine kinds, branch keys, the layout hash) was read from the bytes that reached its transformer. When an earlier transformer has rewritten the class, those are the earlier transformer's output, not the compiler's. JaCoCo puts a probe between a `finally` handler's `aload` and `athrow`, a `bastore` on a null-default path, and a probe between scalac's `hashCode` and its `lookupswitch`, so every exact-body rule falls back to plain code: generated plumbing reads as never-hit adopter code, and an instance with JaCoCo reports different branch keys from one without. That is the silent, confident, wrong failure ADR 0007 rules out, and the realistic place for it is an adopter's test task running the testkit beside Gradle's `jacoco` plugin.

So the analysis reads the class file, through the class's own loader, and the rewrite walks the received bytes, since another agent's changes must survive. Each method's sites pair by position: the Nth site in the class file with the Nth tracked jump or switch received, with its polarity: the same opcode keeps the outcome order, the inverse opcode swaps it, and anything else fails the pairing. When the two arrays are identical, which is every class with no earlier transformer, the analysis runs once and nothing is paired. Which methods get probes, which `<clinit>` gets one, the layout hash, marks, routine kinds, conditions, guards and keys all come from the class file, so an instance reports the same facts whatever ran ahead of it.

## Facts this rests on

Read in JaCoCo 0.8.13's source: its instrumentation replays a method's original instructions in order and adds no conditional jump or switch inside one; a conditional jump whose target needs a probe is inverted, with a `GOTO` carrying the original edge, so "taken" and "fell through" swap; a switch keeps its keys and case count with some targets redirected; `$jacocoInit` and `$jacocoData` are synthetic, and a Java 8 to 10 interface may gain a synthetic `<clinit>`. Subroutine inlining can duplicate jumps, but only in class files older than Java 7, which cannot hold `jsr`.

## Considered options

- **Teaching each rule to step over JaCoCo's probes.** Rejected: it puts a JaCoCo exception in every exact-body rule, breaks when JaCoCo's probe shape changes, and does nothing for AspectJ's weaver or any other earlier transformer. A few rules already tolerate JaCoCo's inverted jumps; they stay, and stop mattering.
- **Analysing the received bytes, as before.** Rejected: it is the failure itself, and it ties branch keys to whatever agents an adopter happens to run.
- **Telling adopters to list this agent first.** Rejected: JaCoCo identifies a class by a CRC64 of the bytes it receives and its report hashes the class file, so JaCoCo seeing woven bytes loses coverage for every woven class. ADR 0053 settles the order instead.

## Consequences

- A method whose sites do not pair (counts differ, or an opcode is neither the same nor the inverse, or the class file has a method the received bytes lack) keeps its METHOD probe and its marks, which come from the class file either way, and gets no branch probes. Its sites are left out of the manifest rather than reported at zero, with one INFO line per class. AspectJ's weaving is the realistic cause. Skipping the whole class was rejected as losing method coverage for every class such an adopter weaves.
- A class with no class file to read (a loader that serves no `.class` resource, bytes defined from memory) is analysed from its received bytes and logged at FINE. Without an earlier transformer those are the class file, which is the common case. What that leaves exposed falls under the open question of what an unrecognised body should read as, recorded in `STATUS.md`.
- Every woven class costs one more resource read, on top of the class files the cross-class lookups already read during a transform. The overhead measurement in `STATUS.md` covers it.
- `ClassBytesCapture` keeps its job: it supplies the received bytes the rewrite and the pairing need.
- The static baseline already reads class files, so with JaCoCo ahead its declared methods now agree with the manifest instead of disagreeing.
