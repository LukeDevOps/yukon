---
status: accepted
---

# Instrument with ByteBuddy: Advice for methods, raw ASM for branches

Yukon instruments classes through a single ByteBuddy `AgentBuilder`, the same shape as OpenTelemetry's Java agent. Method probes are ByteBuddy `Advice`, which inlines to bytecode with no reflection or dispatch on the hot path. Branch probes need code inserted at jump targets inside a method body, which `Advice` cannot express, so that tier is a raw ASM `MethodVisitor` hosted in the same transform through `AsmVisitorWrapper`. One agent, one transform pass per class.

## Considered options

- Raw ASM throughout. Rejected: ByteBuddy provides declarative type and method matching and the whole `premain` wiring. Hand-written visitors are kept for the one thing `Advice` cannot do.
- Forking or embedding JaCoCo. Rejected: JaCoCo's probe insertion is the technique Yukon borrows for branches, but its runtime, agent, and report model are built for test coverage, not for a long-running export to a collector.

## Consequences

ByteBuddy runs with its default REBASE type strategy rather than DECORATE, because the design adds a field to each class (see 0003). That rules out some classes ByteBuddy cannot redefine; 0007 covers how those are handled.
