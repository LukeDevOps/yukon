---
status: accepted
---

# The testkit's JUnit extension runs one collector per test JVM, on a fixed port, against a real `-javaagent`

The JUnit 5 extension in `yukon-testkit` starts a single `YukonTestCollector` for the life of the test JVM, held in JUnit's root extension store, and binds it to a fixed port that defaults to 4319, the agent's own default `endpoint`. The adopter attaches the agent to the test task with the ordinary `-javaagent` flag; the extension never self-attaches. Before the first test runs it waits for the agent's first liveness heartbeat and fails with a message naming the flag and the flush interval option if none arrives.

## Considered options

- One collector per test class, on a random port. Rejected: the agent sends each class's manifest entry once, on first confirmed delivery, and never again. A second collector would never learn the classes the first one was told about, and every query in the second class would throw `UnknownProbeException`. The manifest's send-once rule is the right one for production and is not worth bending for tests.
- Self-attaching the agent from the extension with `ByteBuddyAgent`, so no `-javaagent` flag is needed. Rejected: a transformer only weaves a class as it loads, and JUnit's discovery loads test classes, and often the classes under test, before any extension runs. The Ktor modules' own tests had to move to a real `-javaagent` for exactly this reason. The flag on the test task is also the same adoption path production uses.

## Consequences

- The agent starts before the collector exists. The export design already tolerates this: nothing is marked delivered until a 2xx arrives, so the first flushes retry until the extension has bound the port.
- Counts and manifests are cumulative across every test class in the JVM. A test asserts on what happened since the JVM started, not since the test began; `callCount` and `hitCount` are best compared as deltas around the action under test.
- A test must let the agent flush before asserting. The collector's `awaitSettled` waits for two consecutive heartbeats, since a flush already mid-compute when the last request completes can land without that hit, and one tick's manifest and delta go out concurrently.
- `junit-jupiter-api` is a `compileOnly` dependency of the testkit module, so adopters who do not use JUnit pay nothing for it, the same shape as OpenTelemetry's `opentelemetry-sdk-testing`.
