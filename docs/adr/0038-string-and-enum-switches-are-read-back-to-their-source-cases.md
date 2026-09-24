---
status: accepted
---

# String and enum switches are read back to their source cases

Decided on 2026-09-24 with ADR 0037. This amends ADR 0031's rule that a switch outcome is keyed by its case key value.

A `when` or `switch` over a string or an enum does not reach the bytecode as one switch over the values the source names. Over an enum, javac and kotlinc switch on a synthetic mapping array (javac's `$SwitchMap$`, kotlinc's `$WhenMappings`) indexed by `ordinal()`. Its values run 1 to n in the order the cases appear. Over a string, the source switch becomes a `lookupswitch` on `hashCode()`, then `equals` checks, and in javac a second switch on an index those checks set. Each of those jumps is a branch site today. The person sees outcomes named by hash codes and mapping numbers, and the hash switch's outcomes say nothing about the source. The key suffers too: a mapping number is a case's position, so adding a case earlier in the `when` renumbers every case after it and gives each one a new key. ADR 0031 promised that adding a case leaves every other case's key alone. For an enum it does not hold.

## The design

- **One site per source switch.** The agent recognises each lowering and reports one site whose outcomes are the source's cases plus the default. An enum case is named by its constant, read from the mapping class's static initialiser. The class's bytes are read through the loader as a resource and never loaded, the way ADR 0023 reads a constructor's class. A string case is named by its literal, read from the `equals` checks. Two labels on one body are still two outcomes, as the glossary's branch site entry says.
- **The lowering's own jumps are machinery.** The `hashCode` switch, the `equals` checks and javac's index switch are dropped like coroutine machinery under ADR 0025. They keep their place in the numbering and get no probe. A compiler-added default that only throws, as for an exhaustive `when` or a switch expression, is dropped the same way. The source has no default there, and the branch cannot fire unless a class changes under a running program.
- **Keys from the source case.** A branch key for one of these outcomes digests the constant's name or the string literal in place of the case key value. The site key digests the subject expression's window. Adding, removing or reordering a case then leaves every other case's key alone, which is what ADR 0031 meant.
- **Case labels as condition parts.** An outcome's role carries its label as condition parts (ADR 0037). A string literal is a literal part, so collector redaction covers it. An enum constant is a code part.
- **Confirm every shape first.** Each lowering is confirmed with `javap` against the compilers this repo supports before it is coded: javac's mapping array, string switch and the `SwitchBootstraps` `invokedynamic` forms newer javac uses for pattern switches; kotlinc's `$WhenMappings` and string `when`; and whatever Scala 2 and Scala 3 emit for string and enum matches. A shape that does not match a confirmed one exactly stays as plain sites with numeric case keys. The agent never guesses a label.

## Considered options

- **Keeping the numeric keys and relying on each outcome's guarded lines.** Rejected: "case 3" is no more readable than "Branch 12", and the key keeps churning whenever a case is added.
- **Resolving labels on the server.** Rejected: the mapping lives in a class file the server never sees.
- **Keeping the lowering's jumps as sites beside the rebuilt one.** Rejected: a hash-collision fall-through that can never fire reads as never hit forever. That is the confident and wrong finding ADR 0025 exists to stop.

## Consequences

- Builds before and after this change give these outcomes different keys once, so their service dates start again.
- A mapping class that cannot be read, or a shape that differs from every confirmed one, leaves the numeric sites in place. The result is less readable, never wrong.
- A string label may be a value an adopter wants hidden. It travels as a literal part so that one redaction rule in the collector covers it along with every other literal.
