---
status: accepted
---

# Write Advice classes in Java, not Kotlin

`MethodEntryAdvice` and `ProbeIndex` are Java sources in an otherwise Kotlin agent. Advice bytecode is inlined into the instrumented application's methods, so anything it references must be resolvable from the application's classloader. Kotlin emits calls to `kotlin.jvm.internal.Intrinsics` for null checks, and an inlined call to that class fails with `NoClassDefFoundError` inside the application's own method. Java has no such implicit dependency.

## Considered options

Kotlin with the intrinsics suppressed by compiler flags. Rejected because the failure returns silently the moment a flag is dropped or a compiler default changes. A separate language makes the constraint impossible to miss.

## Consequences

Advice classes stay tiny and live in their own package, `io.github.lukedevops.yukon.advice`. Everything else in the agent is Kotlin.
