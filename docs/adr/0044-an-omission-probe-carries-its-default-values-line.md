---
status: accepted
---

# An omission probe carries its default value's line

Decided on 2026-09-26. Amends ADR 0021 and ADR 0023, which gave an omission probe its target function's first line.

An optional parameter read `DemoServerMain.kt:103`, the body line of `formatTotal`, though `currency` is declared on line 101 and `decimals` on line 102. `Price`'s `scale` read `Price.kt:11`, the constructor's first line, not line 15. A person looking for the parameter landed on the wrong line, and every optional parameter of one function shared that line.

## The design

- **Kotlin.** An `OPTIONAL_ARGUMENT` probe's `line` is the line in effect at the first instruction of its parameter's fill block in the default-filling method: the code after the `mask & bit` test that stores the default. That is the last line-number-table entry at or before that instruction's offset. kotlinc writes the default expression's line there, for example `line 101` at `ldc "GBP"` and `line 102` at `iconst_2` in `formatTotal$default`, and `line 15` for `scale` in `Price`'s mask constructor. The same rule covers constructors.
- **Scala.** A re-kinded default getter's `line` is the getter's own first line. Its body is the default expression. A constructor default also has a public static forwarder on the class, and it is re-kinded too. scalac writes no line-number table for it, in Scala 2 and 3, so its line is -1.
- **No line.** The line is -1 when the method has no line-number table, as for every other probe. It never falls back to the target's line.

## Considered options

- **The parameter's declaration line.** Rejected: the class file does not hold it. The LocalVariableTable has no lines, and `kotlin.Metadata` holds no source positions. In normal formatting the default sits on the parameter's own line. A default on the next line reads as that line, which is still the code to change.
- **Copy the getter's line onto the Scala forwarder.** Rejected: the forwarder's transform would read the module class's bytes to state a fact the getter's own probe already carries. The consumer merges the two probes of one parameter and takes the line from the one that has it.
- **Keep the function's line and add a field for the parameter's line.** Rejected: `line` would mean two things across probe kinds. The function's own line is already on its method probe.
- **Only an entry placed exactly on the fill block's first instruction.** Rejected: kotlinc writes no new entry when two defaults share a line, as in `fun f(a: Int = 1, b: Int = 2)`, so the second default would have no line.

## Consequences

- No wire change. The `line` comment in `yukon.proto` states the meaning for this kind.
- Lines differ between builds as defaults move, so a consumer that merges runs takes the line from the newest run, as it does for method lines.
- A consumer that merges a parameter's probes takes the line from a probe whose line is not -1.
