---
status: accepted
---

# Compiler-generated methods keep their probes and are marked with what generated them, from bytecode shape rather than `kotlin.Metadata`

A data class's `componentN`, `copy`, `equals`, `hashCode` and `toString`, an enum's `values`, `valueOf` and `getEntries`, and every method of a `$DefaultImpls` class exist because of a declaration, not because anyone wrote them. A never-hit `component3` is a true observation the adopter cannot act on, since the compiler will emit it again, so it is a false dead-code finding in the same sense as a coroutine switch's default case (ADR 0025). The earlier position was that a collector would filter these by name. No collector did, and three consumers apply every judgement rule (the server, the testkit and the demo's stub collector), so a name list would have been written three times or drifted.

The agent marks the probe once, with a `GeneratedBy` reason (`ENUM`, `DATA_CLASS`, `DEFAULT_IMPLS`, `RECORD`) on `ProbeLocation` and on the static baseline's `DeclaredMethod`, and every consumer reads the mark: generated probes are left out of never-hit, stale-hit and the call graph by default, counted separately, and shown with the reason on request. The shape rules need no metadata decoding: an enum is a class whose superclass is `java.lang.Enum`; a data class is a class that declares `component1` and a `copy` whose parameters match a constructor's; a `$DefaultImpls` class is named so. A Java record (superclass `java.lang.Record`) gets `RECORD` on its `equals`, `hashCode` and `toString`, which javac emits with `invokedynamic` bodies; its accessors stay unmarked, since they are the adopter's component declarations, the way Kotlin property accessors are. The data-class rule requires all three of `equals`, `hashCode` and `toString` beside the constructor-matching `componentN` and `copy`, so a class that hand-writes a `copy` and a `component1` without them is not marked. These are compiler contracts of the same standing as the `$default` mask tests of ADR 0021, each read off `javap` output before being coded.

## Considered options

- A name list at the server. Rejected: three consumers, and a hand-written `copy` on an ordinary class would be hidden by name alone.
- Decoding `kotlin.Metadata` for the data-class flag. Rejected for the reasons in ADR 0021: a shaded metadata library on the transform path for a fact the method table already shows.
- Dropping the probes, as ADR 0025 does for branch sites. Rejected: these methods are real and callable, so their counts are evidence (a `copy` that is called is a `copy` that is used), and a never-loaded data class should still declare them.

## Consequences

- A hand-written `equals` or `toString` on a data class is marked generated anyway. The cost is a label on a row that is still counted, never a hidden finding about code the adopter wrote elsewhere.
- Kotlin property accessors are not generated in this sense: a never-called setter is real dead code and stays unmarked.
- kotlinx.serialization's `$serializer` classes and `write$Self` are not covered until an adopter using them appears.
- A generated method is not a call-graph node, so a data class that is only ever constructed cannot root an unreached cluster at its own `copy`.
- An omission probe carries its target's mark, the way it carries the target's inline flag under ADR 0022, so a collector makes no never-supplied or always-supplied claim about a data class's `copy` parameters: `copy(x = 1)` omitting `y` is how `copy` is meant to be used, not a dead default.
- A Kotlin `value class` (`box-impl`, `unbox-impl`, `equals-impl`, `hashCode-impl`) and kotlinx.serialization's output are not covered; they wait for an adopter who has them.
