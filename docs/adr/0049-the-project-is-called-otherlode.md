---
status: accepted
---

# The project is called Otherlode

Decided on 2026-09-30. This amends ADR 0016 and ADR 0012.

The project was called Yukon. Yukon is already a trade mark in software, and others hold it. So the name changed before the first release. The new name is Otherlode. The motherlode is the gold. The other lode is the load you do not need: the code nobody runs.

## The new names

These are the names that matter to this repo and to an adopter:

- The repository is `otherlodehq/otherlode-agent`. The collector is `otherlodehq/otherlode-collector`, and the server is `otherlodehq/otherlode-server`.
- The wire schema is the Buf Schema Registry module `buf.build/otherlode/otherlode`. Its package is `otherlode.v1`, in `otherlode/v1/otherlode.proto`. The Java bindings are in `dev.otherlode.proto`. Field numbers, field names and enum values stay the same.
- The JVM package and the Maven group are `dev.otherlode`. The shaded dependencies sit under `dev.otherlode.shaded`. The agent jar is `otherlode-agent-<version>.jar`, and the testkit is `otherlode-testkit`.
- System properties start with `otherlode.` and environment variables with `OTHERLODE_`, for example `otherlode.service.name` and `OTHERLODE_AUTH_TOKEN`.
- The ingest paths are `/v1/otherlode/deltas`, `/v1/otherlode/manifest` and `/v1/otherlode/static-baseline`.
- The testkit's collector is `OtherlodeTestCollector`, and its JUnit 5 extension is `OtherlodeExtension`.

## Consequences

- This is a clean cut. Nothing was released, so no adopter holds an old name. The agent reads no old property, environment variable or path, and it keeps no alias for them.
- ADR 0016 makes an option's name a compatibility surface and keeps no alias for an old name. This rename changes the prefix of every derived name. It amends ADR 0016 only in those names, which changed before any release. The rule that derives them stays the same.
- The proto package matches its directory and carries a version, so `buf.yaml` drops the two lint exceptions for the package rules that ADR 0012 recorded. The exceptions for enum value prefixes and for the zero value suffix stay.
- An agent sends to the new paths, so it needs a collector that serves them.
- The demo apps live in `com.example.demo`, not under `dev.otherlode`. The agent never instruments its own package, so a demo there would report nothing.
- Git history keeps the old name. The old registry module, `buf.build/lukedevops-oss/yukon`, gets no more pushes.
