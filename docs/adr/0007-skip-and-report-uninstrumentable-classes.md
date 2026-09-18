---
status: accepted
---

# Skip and report classes that cannot be instrumented safely

ByteBuddy refuses to redefine a class carrying an annotation whose `@Target` does not include `TYPE`. Kotlin's `@file:JvmName` is the common case: kotlinc attaches it to the class file even though its target list does not allow that. Yukon's type matcher excludes such a class before ByteBuddy commits to rebasing it, and records the skip with a reason. Any other transform failure is caught by `TransformResultListener`, which records the skip the same way. Nothing reaches the registry until that same listener sees the rewrite succeed, so a class whose bytes never passed validation was never published to withdraw: a rollback could not have helped, since a flush landing before it would already have sent the probes. Skipped classes travel to the collector in the manifest's `skipped_classes` list. "Not instrumented" is an honest state to report; "in the manifest, permanently zero" is not.

The wider rule this sets: missing data must never read as a confident dead-code answer. The static baseline (0014) puts classes it cannot read, cannot instrument, or has nothing to probe into their own buckets rather than declaring them. The test collector throws `UnknownProbeException` for a probe it has never seen instead of returning false, and refuses to answer `neverLoaded` until a scan has arrived in full.

## Considered options

- `TypeStrategy.DECORATE`, which OpenTelemetry's agent uses and which skips the validation that fails here. Rejected: DECORATE does not allow `defineField`, which 0003 depends on. OTel can use it because it keeps state in a per-instance virtual field, the wrong shape for per-class counters.
- A generated companion class per instrumented class holding the array. Rejected: the failure comes from redefining the target class at all, not from adding a field, so a companion changes nothing and doubles the loaded class count.
- Checking inside the transform callback. Does not work: `AgentBuilder` commits to rebasing a type the moment it matches `.type(...)`, so only the type matcher can keep a class out of the path that fails.

## Consequences

The blast radius is small. `@file:JvmName` is mostly a library-author idiom, not something controllers and handlers reach for. Adding a field during a first-load transform is well established (JaCoCo has done it for over a decade); the ByteBuddy restriction on adding fields applies to retransforming already-loaded classes, which Yukon never does (0013).
