---
status: accepted
---

# Scala's case-class plumbing, static forwarders and object serialization are marked generated

Decided on 2026-09-26, after the first run of the Scala fixtures against the real collector and
server (`runShapesStack`). The never-hit list for each Scala service was mostly compiler output.
A case class `Cc` gave rows for `canEqual`, `copy`, `equals`, `hashCode`, `productElement` and its
`Name` variants, `unapply`, `fromProduct`, `curried` and `tupled`, and its companion's
`toString` and `writeReplace`; each also rooted its own uncalled cluster. Every method of an
`object` showed twice: once on `Driver$`, where the code is, and once on `Driver` at line -1, the
static forwarder scalac adds. Fourteen uncalled methods gave twenty-eight rows.

Kotlin had the same problems and settled them: ADR 0026 marks a data class's generated methods,
and ADR 0041 marks a multi-file facade's forwarders and passes calls through them. Scala gets the
same treatment, read from bytecode shape only, with no `ScalaSig` or TASTy decoding.

## The design

- **Three new `GeneratedBy` values.** `CASE_CLASS = 7` for case-class and companion plumbing,
  `STATIC_FORWARDER = 8`, and `SCALA_OBJECT = 9` for an object's serialization plumbing. Additive
  on the wire. Consumers judge findings on "not `NONE`", so a marked method is kept, counted and
  left out of never-hit rows and the cluster graph, as for every other value.
- **A static forwarder** is a method, in a class carrying a `Scala` or `ScalaSig` attribute, that
  is static, not synthetic, not a bridge, and whose body is exactly `getstatic
  <ThisClass>$.MODULE$`, each parameter loaded in order, `invokevirtual
  <ThisClass>$.<same name><same descriptor>`, and a return. The owner must be the class's own `$`
  twin. A static forwarder is a generated forwarder in ADR 0041's sense: a call into it passes
  through to the object's method, and it is never a graph node.
- **A case class** is a class carrying a Scala attribute that implements `scala.Product` and
  declares `canEqual(Object)` and `productArity()`. Its methods from a fixed set are marked
  `CASE_CLASS`: `canEqual`, `copy`, `equals`, `hashCode`, `toString`, `productArity`,
  `productElement`, `productElementName`, `productElementNames`, `productIterator`,
  `productPrefix`, and Scala 3's `_1`, `_2` and on. A field accessor (`a()` for `case class Cc(a:
  Int)`) stays ordinary code, as a Kotlin data class's getter does: the adopter declared it.
- **The line rule tells a hand-written override apart.** scalac gives each generated method a
  line table naming the `case class` declaration line, which is the first line of the primary
  constructor. A method from the set is marked only when every line in its table is that line. An
  override the adopter wrote on its own line (`override def toString = ...`) stays ordinary; one
  written on the declaration line itself is marked, an accepted miss.
- **The companion's plumbing** is recognised by reading the partner class's bytes through the
  companion's loader as a resource, never loading it, as ADR 0023 does for constructor getters.
  When the partner is a case class, the companion's `apply` and `unapply` whose descriptors match
  its primary constructor, its `toString`, `fromProduct` and `readResolve` are marked
  `CASE_CLASS`, again only when every line in their tables is the partner's constructor line. A
  hand-written `apply(String)` in an explicit companion differs in descriptor and line and stays
  ordinary. Unreadable partner bytes mark nothing.
- **An object's `writeReplace`**: in a Scala class with a static `MODULE$` field, a private
  `writeReplace()Ljava/lang/Object;` whose body constructs a `scala.runtime.ModuleSerializationProxy`
  is marked `SCALA_OBJECT`. Scala 3 gives every `object` one, and Scala 2 every case-class
  companion.
- **Omission probes follow.** An omission probe already takes its target method's mark, so
  `copy$default$N` and Scala 2's `apply$default$N` are marked with `copy` and `apply`; a
  constructor's own defaults stay the adopter's.

## Considered options

- **Not probing static forwarders at all**, as ADR 0047 does for Hibernate's enhancement methods.
  Agent-only and no wire change. Rejected for consistency: a static forwarder stands for the
  adopter's own object method, which is the Kotlin multi-file facade's situation, and ADR 0041
  kept and marked those. A consumer should see Scala and Kotlin forwarders the same way.
- **A name policy at the collector.** Rejected as in ADR 0026: it exists in no repo, and a name
  alone cannot tell a hand-written override from compiler output.
- **Decoding `ScalaSig` or TASTy for the case and synthetic flags.** Exact, but it is a pickle
  format per Scala major version, and the bytecode shape already carries what is needed.
- **Folding `writeReplace` into `CASE_CLASS`.** Rejected: a plain Scala 3 `object` is not a case
  class, and a label that says so misleads.

## Consequences

- Scala 3 `enum`s (`values`, `valueOf`, `ordinal`, `fromOrdinal`, `$new`), Scala 2
  `Enumeration` and `lazy val` plumbing are not covered. The fixtures have Scala 3 enums, but no
  run has loaded them; `STATUS.md` carries them until a run shows what they produce.
- `yukon-server` displays an unknown `GeneratedBy` as `none` until it gains labels for the three
  values, though it already leaves such methods out of findings.
- Every fact above was read from `javap` output for fixtures compiled with Scala 2.13.15 and
  3.3.4. The line rule for a hand-written override is pinned by a new fixture in both modules
  before it is relied on.
