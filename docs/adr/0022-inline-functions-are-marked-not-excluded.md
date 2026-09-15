---
status: accepted
---

# Inline functions keep their probes and are marked as inline, so a collector abstains rather than reporting them never hit

A Kotlin call to an `inline` function copies the body into the caller; the function's own method is only invoked from Java, through a function reference, or from a place the compiler could not inline. Its method probe therefore reads close to zero no matter how often the function runs. The agent still probes it, and marks the probe as inline in the manifest and the method as inline in the static baseline. A collector then makes no "never hit" claim for an inline method and no "never loaded" claim for a class whose every declared method is inline, since a file facade made only of inline functions is never loaded by Kotlin callers at all.

Inline is detected from the function's own bytecode: the compiler plants a fake local variable named `$i$f$<function name>` in an inline function's LocalVariableTable, starting at the body's first instruction and covering the whole body, for the debugger's step-into-inline support. An inlined copy at a call site carries the same name over a sub-range, which is how a non-inline overload that inlines its sibling is told apart.

## Considered options

- Excluding inline methods from probing. Rejected: it drops the one real signal the probe still gives, a call from Java or through a reference, and gives a collector nothing to explain why a class made of inline functions never loads.
- Counting inlined bodies at their call sites. Rejected: the callee's slot lives in another class's array, which may not be instrumented or loaded when the caller is transformed.
- Reading `kotlin.Metadata` for the `isInline` flag. Rejected for the reasons in ADR 0021; the marker is a compiler contract of the same standing as the `$default` mask tests.

## Consequences

- With debug info stripped (ProGuard, R8), the marker is gone, nothing is marked, and an inline method reads as never hit exactly as it did before this decision.
- `neverHit()` in the testkit lists no inline method; `wasHit` and `hitCount` still answer with the raw count.
- The omission probes of ADR 0021 carry the same flag, since a Kotlin call to an inline function with defaults never reaches `f$default` either.
