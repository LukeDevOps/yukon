---
status: accepted
---

# Static attach only for v1

The agent attaches through `premain` and the `-javaagent` flag, and the jar declares no `Agent-Class`. Instrumenting every class as it loads is what makes startup-time code paths visible, and much of the "is this ever exercised" wiring in an application runs during bootstrap. A customer restarts once to add the flag to normal startup configuration, the same adoption path OpenTelemetry uses.

## Considered options

Dynamic attach through `agentmain`, deferred rather than rejected. It reduces trial friction, but it needs `retransformClasses`, which cannot add fields or methods (0003 and 0004 depend on adding a field), has edge cases with already-compiled code, fails on some bootstrap and generated classes, and misses whatever ran before attach. That is a second instrumentation path with its own coverage caveats, not worth the doubled test surface until the static path is proven.

## Consequences

The agent never retransforms an already-loaded class. Other decisions rely on that: fields can be added during a first-load transform (0007), and a count can only go backwards through a layout change that static attach never triggers (0010).
