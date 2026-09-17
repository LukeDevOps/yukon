---
status: accepted
---

# Send cumulative totals merged with max(), with no separate send buffer

`ProbeDelta.hits_total` is the probe's cumulative count since process start, not the amount it changed by since the last flush. A collector merges it with `max()`, which is commutative, associative and idempotent, so a batch delivered twice or out of order is a no-op. That hazard is real at the agent: `HttpOtlpStyleExporter` resends the identical payload when a response is lost after the collector has processed the request, and nothing on the receiving side deduplicates. The registry keeps a per-probe "last successfully sent" value and reports the live count for anything that differs, advancing that value only on a 2xx.

There is no bounded queue or drop policy. The data is not a stream. It is a set of arrays bounded by code size, not traffic, so it cannot grow no matter how long the collector is unreachable. A failed flush leaves the last-sent value where it was and the next flush reports the live count again.

## Considered options

- Incremental deltas with a per-flush idempotency key deduplicated at the collector. Rejected: that needs a dedup cache per instance with its own eviction policy, on top of the value itself.
- Disk spill for counts not yet acknowledged. Rejected: a crash during an outage loses at most one flush interval, since earlier flushes are already durable centrally.

## Consequences

- A cumulative count reopens the counter-reset problem Prometheus lives with. Yukon closes it by construction: `service.instance.id` defaults to a fresh UUID per process, so a restart is a new merge stream. An adopter who pins the instance ID to something that survives a restart (a fixed pod name) takes on that hazard themselves. The agent does not enforce this; it is a documented invariant.
- A count that goes backwards should not happen with static attach, but the registry logs a one-time warning per probe if it does, rather than sending the lower value silently.
- Delta batches are capped at 20000 probes and manifest chunks at 5000 entries, split on class boundaries, so one oversized POST cannot fail forever. Each chunk is confirmed on its own.
- Staged state is bound to the snapshot it was computed from and applied newest-wins per class, so a shutdown flush racing a scheduled one cannot mark hits as delivered that only went out in the send that failed.
- The shutdown-hook flush marks its delta batch as the instance's final flush. A collector can then tell an instance that ended cleanly from one that went silent, and report how many instances may have lost up to one flush interval of hits; the window itself is still not recovered.
