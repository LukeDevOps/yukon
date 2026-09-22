---
status: accepted
---

# Every payload carries a run id, and consumers keep each run apart

The agent makes a random run id once per process at startup, a `UUID.randomUUID()` string, and stamps it on `ResourceAttributes.run_id`. A process sends the same resource on every delta batch, every manifest chunk and every static baseline chunk, so each payload names the one run of one instance that produced it. A new process always gets a new run id, whatever `service.instance.id` is set to. `class_id`, `endpoint_id`, dependency ids and every cumulative total mean something only within one run, so a consumer keys them on instance and run together and merges with `max()` only within a run (ADR 0010). An empty `run_id` means the field is missing, and a consumer rejects the payload.

The reason is a pinned instance id. An adopter can set `service.instance.id` to something that survives a restart, such as a pod name. `class_id` is assigned in load order (ADR 0011), so after a restart under a pinned id the same `class_id` can name a different class, and the totals start again from zero. Without a process id on the wire a consumer cannot tell the new process from the old one. It also cannot tell a restart from a late or reordered batch that the old process sent before it stopped. A run id answers both: the new process names a different run, and a late batch names the run it came from.

`ProbeManifest` drops its own `service_name`, `service_version` and `service_instance_id` (fields 1, 2 and 5, reserved by number and name) and carries `ResourceAttributes resource = 14` instead. Each payload then says who sent it in one way. The manifest gains `environment` from the resource, which it did not carry before.

## Considered options

- A sequence number on each batch. Rejected: within one run totals only rise, so `max()` already makes a late or repeated batch harmless. A sequence number also cannot tell a restart from a late batch unless the payload names its process, and a run id alone does that.
- Inferring a restart from a falling total, or from a probe's `first_seen_at` moving later. Rejected: the inference breaks without warning if another field's meaning changes. It also cannot tell a late batch from the old process, landing after the new process has reported, from a restart.
- A run id on `DeltaBatch` only. Rejected: a manifest carries its run's class ids, and a baseline belongs to the process that scanned. Every payload must name its run, or a consumer cannot join a batch to the manifest that explains it.
- Keeping the manifest's own identity fields and adding a run id field beside them. Rejected: that leaves two ways to say who sent a payload, and they can disagree.

## Consequences

- A pinned instance id is not a hazard the adopter takes on. Consumers keep each run apart, so a restart under a pinned id starts a new run instead of freezing under the old run's totals or mixing two processes' class ids.
- The run id is not configurable. It exists to differ between processes, and a configured value could repeat.
- `yukon-collector` shards its forwarding queue by service name plus instance id (`instanceKey` in `internal/forward/forward.go`). Adding the run id to the resource does not move an instance to another shard, so one instance's payloads keep their order across a restart.
- `yukon-collector`'s ingest handler must require a non-empty `run_id` on every payload, and must read the manifest's identity from its resource. `yukon-server` must key its per-instance rows on the run as well. Nothing is released, so the proto change is breaking on purpose. `buf breaking` runs only on pull requests, so a push to `master` is not blocked.
- The demo's stub collector follows the consumer rule. It keys every class id, endpoint id and dependency id on instance and run, judges each run's dependencies on their own, and answers 400 to any payload with an empty run id. Its report lines still name the instance, since that is the name a person knows.
- The testkit collector keys on instance alone. That equals keying on run only while each instance id names one run, which holds for the one agent in its own test JVM (ADR 0018). It answers 400 to a payload with an empty run id, and to a second run id under an instance id it has already heard from, keeps nothing from either, and records the reason in `rejectedPayloads()`. From then on every query and wait throws `IllegalStateException` listing the reasons, so a test fails even if it never asks.
