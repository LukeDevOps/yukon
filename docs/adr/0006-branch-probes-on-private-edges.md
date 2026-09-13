---
status: accepted
---

# Place branch probes on private edges, never on jump targets

Every two-outcome conditional jump (`IFEQ` through `IF_ACMPNE`, `IFNULL`, `IFNONNULL`) is rewritten into two private edges, one per outcome, each incrementing its own slot before jumping to the original target. `TABLESWITCH` and `LOOKUPSWITCH` get one edge per distinct case target plus one for the default. A probe is never planted at an original jump target: that point is usually also the if/else merge point, or shared by several switch cases, so a single probe there would fold several outcomes into one counter.

## Consequences

- `GOTO` and `JSR` are not tracked. Each has one successor, so there is no second outcome to observe.
- javac fills gaps in a `TABLESWITCH` with entries that jump to the default label. Those are routed to the default edge, so a switch over 1, 2, 3 and 5 does not report "case 4 never hit" for a case that does not exist. Two real cases that share one body are still two probes.
- A site's slot count varies (two for a conditional, cases plus one for a switch), so sites are packed by a running total, and that total is part of the layout hash (0005).
- The analyser and the rewriter must see the same bytes. `ClassBytesCapture` stashes exactly what ByteBuddy is about to rewrite, and the rewriter refuses to allocate past its slot capacity, logging a per-class warning instead of throwing inside an application method.
