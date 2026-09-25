---
status: accepted
---

# A creation edge carries the interface it implements

Decided on 2026-09-25 with `yukon-server`, whose ADR 0036 holds how a lambda or body class is named.

Two lambdas in the demo's `main` both read "lambda in `main`": the `/__shutdown` handler and the `Thread { }` nested in it. What each is for was visible only in the source. At the `invokedynamic` that creates a lambda, the call site's return type is the interface the lambda implements, such as `com.sun.net.httpserver.HttpHandler` or `java.lang.Runnable`. That is a fact the agent reads when it records the creation edge (ADR 0034), and it dropped it.

## The design

- **`implemented_interface` on a `CREATES` edge.** A `CallEdge` of kind `CREATES` from an `invokedynamic` carries the dotted name of the interface its call site returns. It is empty on a `CALL` edge, and on a `CREATES` edge from a body class's `new`, since a body class's own interfaces already travel on its `ClassLocation`.
- **As the bytecode names it.** The agent sends every interface, Kotlin's `kotlin.jvm.functions.FunctionN` included. Whether a name helps a reader is the server's decision (ADR 0036), as every other naming rule is.
- **Parity.** `yukon-testkit` and the stub collector keep the field on the edges they store.

## Considered options

- **Leave it to the lambda body's signature.** Rejected: `(HttpExchange)` and `()` hint at it, but two lambdas with one signature still collide, and a parameter list does not say "handler" or "task".

## Consequences

- A wire change: one field on `CallEdge`, published to the Buf registry, and a binding bump in `yukon-server`.
