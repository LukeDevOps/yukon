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
- **A case class** is a class carrying a Scala attribute that declares `canEqual(Object)`,
  `productArity()`, `productElement(int)` and `productPrefix()`. It is not required to list
  `scala.Product` among its interfaces: Scala 2.13.15 leaves it off when a supertype already brings
  it, as in the recommended ADT shape `sealed trait Shape extends Product with Serializable`. A case
  object's module class passes the same test, and its plumbing is marked the same way.
- **Each piece of plumbing is recognised by its body**, the fixed code scalac writes for it, never
  by where it sits in the source. `canEqual` is `instanceof` the class; `productPrefix` loads the
  class's name; `productArity` pushes the element count; `copy` and the companion's `apply`
  construct the class from their parameters through its primary constructor, never an auxiliary
  one; `toString` calls `ScalaRunTime._toString`;
  `hashCode` is the MurmurHash3 fold over `productPrefix` and each element (or
  `ScalaRunTime._hashCode` with no primitive element); `equals` is the reference shortcut,
  `instanceof`, a comparison per element and `canEqual` (left out, as scalac leaves it out, when
  the class is final and its `canEqual` is scalac's own, or in Scala 2 when a final class has no
  elements); `productElement` and
  `productElementName` dispatch over the elements and fail out of range; `productIterator` and
  `productElementNames` delegate to the runtime; Scala 3's `_N` and Scala 2's `<name>$access$<i>`
  read one element, `_N` only in a Scala 3 class and `$access$N` only in a Scala 2 one. The
  elements are the fields the primary constructor stores its leading parameters in, element i from
  parameter i, and `productArity` must push their count. A matcher allows nothing the shape does not name: one
  extra instruction and the method is ordinary code. The shapes were read with `javap -c` from
  fixtures compiled with Scala 2.13.15 and 3.3.4, across every primitive type, references,
  generics, one and zero elements, private parameters, inner and local classes, and several
  parameter lists; a body from another version, or a shape not read, stays unmarked. A field
  accessor (`a()` for `case class Cc(a: Int)`) is never plumbing: the adopter declared it.
- **A hand-written method is marked only when its body is exactly what scalac writes**, and then it
  is the generated code in all but authorship: a hand-written `copy` that only constructs the class
  from its parameters, as in the `HandCopy` fixture, is marked, and so is its default getter's
  omission probe. Any other override (`toString` returning a literal, `hashCode` returning a
  constant, `equals` comparing other or fewer elements, `apply` or `copy` going through an
  auxiliary constructor) stays ordinary. A class that is not a case class but implements `Product`
  by hand with the same four methods, written the way scalac writes them, is marked the same way:
  its label is wrong, and its code is the generated shape. `productArity` is marked only when
  `productElement` matched too, whose dispatch carries the real element count, so a hand-written
  `productArity` returning any other count stays ordinary.
- **The companion's plumbing**: in the class named `X$` whose partner `X` passes the case-class
  test, found by reading `X`'s bytes through the companion's loader as a resource, never loading
  it, as ADR 0023 does for constructor getters. A static `MODULE$` is not required, so the
  companions of inner and local case classes count. `apply` constructing `X`, `unapply` (Scala 3's
  identity or boolean; Scala 2's `None` or `Some` of the element or a tuple), `toString` loading the
  name, and Scala 3's `fromProduct` rebuilding `X` from `productElement` calls are marked
  `CASE_CLASS`, each by its body. A hand-written `apply(String)` in an explicit companion has a
  different body and stays ordinary. Unreadable partner bytes mark nothing.
- **An object's `writeReplace`**: in a Scala class with a static `MODULE$` field, a private
  `writeReplace()Ljava/lang/Object;` whose body constructs a `scala.runtime.ModuleSerializationProxy`
  is marked `SCALA_OBJECT`. Scala 3 gives every `object` one, and Scala 2 every case-class
  companion.
- **Omission probes follow.** An omission probe already takes its target method's mark, so
  `copy$default$N` and Scala 2's `apply$default$N` are marked with `copy` and `apply`; a
  constructor's own defaults stay the adopter's.

The rules above replaced a line rule before the first commit, on 2026-09-26. The first cut
marked a method from the plumbing set when its line table named the `case class` declaration line.
Three review rounds, each compiling scratch code with scalac 2.13.15 and 3.3.4, found a layout that
broke it: parameters wrapped over several lines (Scala 2 starts the constructor's table at the
first parameter), a span bounded by the constructor (a body `val` puts its initialiser there), by
the field accessors (a body `val` has one), and by the `copy` default getters (a hand-written `copy`
with a default puts its getter in the body), besides implicit second parameter lists and inner
companions. The line table only says where a method sits, and scalac's placement follows layout
the adopter controls; worse, a wrong span marked hand-written code and hid it. The body is fixed
compiler output whatever the layout, and it is how this project recognises every other generated
shape.

## Considered options

- **Not probing static forwarders at all**, as ADR 0047 does for Hibernate's enhancement methods.
  Agent-only and no wire change. Rejected for consistency: a static forwarder stands for the
  adopter's own object method, which is the Kotlin multi-file facade's situation, and ADR 0041
  kept and marked those. A consumer should see Scala and Kotlin forwarders the same way.
- **Position in the source: the declaration line, then a declaration span.** Rejected at review,
  above: it depends on the adopter's layout and fails by hiding hand-written code.
- **A name policy at the collector.** Rejected as in ADR 0026: it exists in no repo, and a name
  alone cannot tell a hand-written override from compiler output.
- **Decoding `ScalaSig` or TASTy for the case and synthetic flags.** Exact, but it is a pickle
  format per Scala major version, and the bytecode shape already carries what is needed.
- **Folding `writeReplace` into `CASE_CLASS`.** Rejected: a plain Scala 3 `object` is not a case
  class, and a label that says so misleads.

## Consequences

- A Scala 3 enum's parameterised case compiles to a final case class and is marked as one,
  companion included. A singleton case's `productPrefix` and `toString` read a field rather than
  load a constant and stay unmarked, and the enum class's own plumbing (`values`, `valueOf`,
  `ordinal`, `fromOrdinal`, `$new`) was not read. Nor are Scala 2 `Enumeration` and `lazy val`
  plumbing. The fixtures have Scala 3 enums, but no
  run has loaded them; `STATUS.md` carries them until a run shows what they produce.
- `yukon-server` displays an unknown `GeneratedBy` as `none` until it gains labels for the three
  values, though it already leaves such methods out of findings.
- Every fact above was read from `javap` output for fixtures compiled with Scala 2.13.15 and
  3.3.4. Other versions (2.12, later 3.x) degrade to unmarked plumbing until their shapes are read.
- Symbolic or backquoted names, value-class elements, an `Array` element (scalac compares it by
  reference, a shape not read), a `Unit` element, a `var` parameter not stored with the others, a
  parameter scalac aliases to a superclass `val`, a local case class that captures a local value
  (its constructor takes the capture beside the elements) are not recognised and stay unmarked. (Scala 2 writes no
  `unapply` past 22 elements, so there is nothing to recognise there.)
