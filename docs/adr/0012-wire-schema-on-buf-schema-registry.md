---
status: accepted
---

# Publish the wire schema to the Buf Schema Registry

`src/main/proto/yukon.proto` is the contract between this agent and `yukon-collector`, a separately versioned Go repo. The schema is published as `buf.build/lukedevops-oss/yukon`. The collector consumes generated Go bindings from BSR instead of a hand-copied `.proto`, and `buf breaking` runs in CI against the pull request's base branch so a change that would break the collector is caught before it ships. This repo's own Gradle build still generates Java bindings locally from the same file.

## Consequences

- `buf.yaml` excludes three STANDARD lint rules (package-directory match, package version suffix, enum value prefix). Satisfying them would rename the proto package and the `ProbeKind` constants, a breaking change to generated code in both repos.
- Field comments in the proto are the canonical description of wire semantics, such as the `max()` merge for `hits_total` and the chunk completeness rule for a static baseline. They are not duplicated in prose elsewhere.
