---
status: accepted, amended by ADR 0049
---

# Publish the wire schema to the Buf Schema Registry

`src/main/proto/otherlode/v1/otherlode.proto` is the contract between this agent and `otherlode-collector`, a separately versioned Go repo. The schema is published as `buf.build/otherlode/otherlode`. The collector consumes generated Go bindings from BSR instead of a hand-copied `.proto`, and `buf breaking` runs in CI against the pull request's base branch so a change that would break the collector is caught before it ships. This repo's own Gradle build still generates Java bindings locally from the same file.

## Consequences

- `buf.yaml` excludes two STANDARD lint rules. The enum value prefix rule would put the enum's name in front of every value, so each generated constant would repeat the type name and the value names the collector and server read would change. The enum zero value suffix rule would misname `GeneratedBy`'s zero value, which means "not generated". The package is `otherlode.v1` at `otherlode/v1/otherlode.proto`, so the package rules need no exception (ADR 0049).
- Field comments in the proto are the canonical description of wire semantics, such as the `max()` merge for `hits_total` and the chunk completeness rule for a static baseline. They are not duplicated in prose elsewhere.
