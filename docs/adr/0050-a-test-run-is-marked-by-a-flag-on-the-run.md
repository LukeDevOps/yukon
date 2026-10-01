---
status: accepted
---

# A test run is marked by a flag on the run

Decided on 2026-10-01 in a grilling session with Luke, with server ADR 0047. It adds a last step to the environment's resolution order in ADR 0045.

A collector can name the tests that call production code only if it can tell a test run's call edges from production's. The agent already reads call edges from every class under the include rules. In a test JVM that includes the adopter's test classes, and with the static baseline on, it reads edges from test classes that never load too. So the agent needs no new reader. It only has to say that the run is a test run.

## The design

- A boolean option `testRun`, off by default, resolved like every other option. With it on, every payload of the run carries `test_run = true` in `ResourceAttributes`.
- With `testRun` on and no environment set from any source, the agent names the environment `test`. A collector that knows the flag ignores the name. A collector that strips the flag, such as one built before the field existed with redaction on, then keeps the run in its own environment, apart from production's.
- The agent behaves the same in a test run, with one exception. It probes, records edges and scans as it always does, and a collector decides what a test run is used for. The exception is shutdown: after the final flush, a test run waits up to 15 seconds for its static baseline scan to end. The flush goes first, so a test runner that halts a slow JVM still gets the final manifest. By default, Maven Surefire halts a forked JVM that has not exited 30 seconds after it calls `System.exit` (`forkedProcessExitTimeoutInSeconds`), and the flush can take 10. A test JVM often exits before the scan ends, and the scan is the only source of edges from test classes that never loaded.
- The README tells an adopter to set `includePackages` so it covers both the production and the test classes, to turn on `staticBaselineEnabled`, to pin `serviceInstanceId` per test task, and to leave the environment unset. Then each task's newest complete scan gives the full current set of test edges.
- The testkit's collector ignores the flag. The demo's stub collector logs a test run's payloads and keeps nothing from them.

## Considered options

- A test environment name with no flag. Rejected: a collector would treat it as one more deployment, with its own findings.
- The agent marks each test class, by the directory it loaded from or by its name. Rejected: the collector can tell a test class by the absence of the class from every production run, which needs no setting.
- Encoding the flag in a field old collectors keep, such as a run ID prefix. Rejected: a run ID is opaque, and the default environment covers the old-collector case.

## Consequences

- The old-collector protection holds only while the environment is the default. An environment set for the test JVM, by an option or by OpenTelemetry's settings that CI passes down, or a collector that stamps its environment with `upsert`, puts a stripped test run in that environment.
- A collector with an environment of its own and `insert` keeps the agent's `test`, and counts each of the run's payloads in its environment-mismatch metric.
- A scan that has not ended 15 seconds after the final flush may be lost. The collector then reads that run's manifest edges, and an earlier complete scan of the same pinned instance if there is one. That older scan may name test classes deleted since.
