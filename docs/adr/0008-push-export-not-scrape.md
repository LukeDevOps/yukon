---
status: accepted
---

# Push hit data to a collector instead of exposing it for scraping

The agent pushes batched hit counts to a collector on a fixed interval, the shape of an OTLP exporter with a custom schema. A single instance can only say "not yet hit here". A real dead-code claim needs observations merged across every instance and every deploy, which only a central store can hold. Each flush sends a delta batch of changed probes and an incremental probe manifest. An empty delta batch still goes out as a liveness heartbeat, since a collector otherwise cannot tell an idle instance from a crashed one.

## Considered options

Prometheus, pull plus TSDB counters. Rejected: per-probe dead-code data is a high-cardinality set-membership problem (tens of thousands of method and branch IDs per service, "has this ever fired and when"), not a rate to average. That does not fit a numeric time-series model or its cardinality limits. A collector can still expose a small Prometheus-format aggregate endpoint derived from the merged store.

## Consequences

- The interval is fixed, default 60 seconds, not adaptive: the goal is an over-time picture, not freshness. Each instance randomises its first flush within the interval and runs at a fixed rate from there, so a fleet does not hit the collector on the same tick.
- The manifest is incremental. Only probes not yet included in a successfully delivered manifest are sent, so a class that loads late is picked up on the next flush rather than being absent forever.
- Coverage is not a first-class OTel signal, so the architecture is borrowed, not the OTLP protobufs.
