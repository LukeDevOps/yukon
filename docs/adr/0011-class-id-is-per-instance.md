---
status: accepted, amended by ADR 0032
---

# class_id is per instance, so the manifest carries the instance ID

The registry assigns `class_id` by incrementing a counter the moment a class first registers. Two instances running identical code load classes in different orders, so the same `class_id` names different classes in each. `ProbeManifest` therefore carries `service_instance_id`, and a collector must key manifest lookups by instance the same way it already must for delta batches. The wire pair `(class_id, probe_index)` exists only so delta batches avoid repeating fully qualified names.

## Considered options

Deterministic assignment, for example by sorted class name. Rejected: nothing in the agent knows the full set of classes a process will load until it has loaded them, and `class_id` must be stable from first use because the manifest is delivered incrementally. The static baseline's inventory is close, but it only exists behind an opt-in flag and runs on a background thread that classes can register ahead of.

## Consequences

The demo's stub collector and the test collector both key on `(service_instance_id, class_id, probe_index)`. Any collector keying on `class_id` alone will merge unrelated classes from different instances.
