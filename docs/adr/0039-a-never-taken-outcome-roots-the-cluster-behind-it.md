---
status: accepted
---

# A never-taken outcome roots the unreached cluster behind it

Decided on 2026-09-25 with `yukon-server`, whose ADR 0032 holds the server side. It extends ADR 0024's cluster rule with the guards ADR 0037 puts on every call edge.

ADR 0024 gives a cluster one of two roots: a never-hit method that is uncalled, or one with a caller that has hits. A method called only from the untaken side of an `if` in a method that ran was therefore a root of its own, "reached from hit". The demo's `LegacyDiscountCalculator.<init>`, `.apply` and `LegacyRates.<clinit>` are three such roots behind the one untaken outcome at `DemoServerMain.kt:58`, and no root named that line.

## The design

- **An untaken outcome is a node.** A never-hit outcome in a method with hits joins the graph. A call edge whose guard is that outcome counts as a call from the outcome, not from its method. An outcome node's own caller is the outcome that guards its site when that one is also never hit, and otherwise its method.
- **A third root kind.** A root is still a never-hit node that is uncalled or has a caller with hits. An outcome node whose method has hits is a root of kind *untaken outcome*. Members are methods only, and an untaken outcome with no method behind it is not a cluster.
- **A root reached from hit names its callers.** The report lists the callers with hits beside a *reached from hit* root. Once untaken outcomes are roots, such a root is mostly an override that a virtual call never reached, or a call an exception cut short.
- **Every place that applies the rule changes together.** `yukon-server` computes it across in-scope runs. The stub collector and `yukon-testkit`'s `unreachedClusters` apply it within one JVM, where a guard's outcome is found in the same class's probes. `runDemoStack` prints an untaken outcome root as its condition and result in its method, as it prints a site row.

## Considered options

- **Keep method roots and note the guard on each.** Rejected: one dead feature still reads as several clusters, and deleting one root removes only part of it.
- **Resolve which outcomes ran in the agent.** Rejected: whether an outcome was hit is known only across in-scope runs. The agent sends the guard, a fact about the bytecode, and the collector side judges it.

## Consequences

- A never-hit method called under two different untaken outcomes belongs to neither cluster, as a method shared by two method roots does.
- The glossary's unreached cluster and root change to name the third kind.
- No wire change: ADR 0037 already sends every guard.
