# Security policy

## Reporting a vulnerability

Report a vulnerability privately through GitHub's private vulnerability
reporting:

https://github.com/otherlodehq/otherlode-agent/security/advisories/new

Do not open a public issue, pull request or discussion about it.

Please include:

- the affected version, or the commit if you built from source;
- steps to reproduce, with a minimal setup where you can;
- the impact: what an attacker can do, and under which conditions.

## Scope

The agent runs inside your JVM, attached with `-javaagent`. It rewrites
classes as they load and pushes probe data to a collector. A problem in the
agent jar, its instrumentation or its export is in scope.

The `testkit` module is an embedded collector for your own tests. It is
meant as a test-scope dependency and should never ship in production. It
is in scope. A problem in it is rated lower, since it runs only in tests.

Problems in the collector belong in the
[otherlode-collector](https://github.com/otherlodehq/otherlode-collector) repository.
Report problems in the hosted Otherlode service through the collector's
link:
https://github.com/otherlodehq/otherlode-collector/security/advisories/new

## Supported versions

Nothing is released yet. Fixes land on `master` and go into the next
release. Once releases exist, fixes go into the latest release.

## What to expect

You will get an acknowledgement of your report. After that you will get a
fix, or a reply that explains why no change is needed.
