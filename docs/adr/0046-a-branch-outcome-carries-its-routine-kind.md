---
status: accepted
---

# A branch outcome carries its routine kind

Decided on 2026-09-26 in a grilling session with `yukon-server` (server ADR 0039). This revises the last consequence of ADR 0025, and of ADR 0037, which left this call to the collector.

Some outcomes the adopter wrote are real but not worth a person's time when they never run. In the demo, four of the six conditions listed as never hit are null paths in `totalParam`: `query ?: return 0.0`, `?.getOrNull(1)`, `value?.toDoubleOrNull()` and `?: 0.0`. A backend sees only the condition text, and from text it would have to guess: a hand-written `if (x == null) throw …` reads like the compiler's own check. The agent reads the bytecode, where these shapes are exact. So the agent classifies each outcome, and the backend judges.

## The design

- `BranchOutcome` gains `RoutineKind routine = 7`, with `ROUTINE_KIND_NONE = 0` and three kinds. The agent sets it on each kept outcome.
- `NULL_DEFAULT`: the null side of a null check (`IFNULL`, `IFNONNULL`, or a compare against the `null` constant), when its path calls nothing before it rejoins the other path or leaves the method. It only yields null, a constant, or an early return. `value?.toDoubleOrNull()` on its null side, `?: 0.0` and `?: return 0.0` are routine. `?: loadFromDb(key)` runs a call, so it is not. The non-null side yields the value itself, so it is never `NULL_DEFAULT`. The rule applies to null checks only. `if (discounted > 100.0) total = 100.0` also calls nothing, but it is the adopter's logic.
- `THROW_ONLY`: an outcome whose path only builds an exception and throws it. The path may create the exception, build its message, and call the exception's constructor. Any other call, such as a log line, means it is not routine. This covers a `lateinit` check, the throwing `else` of an exhaustive `when`, `?: throw …`, `?: error("…")`, and a hand-written guard such as `if (amount < 0) throw BadRequest()`. A throw that never fired is a guard that held, not code to delete.
- `FINALLY_COPY`: an outcome of a site inside the exception-path copy of a `finally` body. That is a catch-any handler that ends by rethrowing what it caught, holding a copy of code the normal path also runs. Sites in an ordinary `catch` block are not routine: error handling that never ran can be a real lead.
- A site keeps its `site_index`, key and condition as before. Only the outcome carries the kind, so one side of a site can be routine and the other not.

## Considered options

- The backend classifies from the condition text. Rejected: text cannot tell a compiler's check from a hand-written one, and it cannot see what a path calls.
- No probe for routine outcomes, as ADR 0025 does for library copies. Rejected: a null path that never runs is still a fact (the value is never null), and a person may want it later. ADR 0026 kept generated methods' probes for the same reason.
- Every null side of `?.` and `?:` is routine. Rejected: `cache[key] ?: loadFromDb(key)` never taking its null side means the cache always hits, which is worth knowing.
- One `routine` flag with no kind. Rejected: a person who sees an outcome left out should see why.

## Consequences

- `!!` compiles to a call on current kotlinc, so it has no site. An older compiler's throwing branch is `THROW_ONLY`.
- The classification reads the path's instructions up to where it rejoins or leaves, so it runs where the guard analysis already walks the method's control flow.
