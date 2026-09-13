---
status: accepted
---

# Every option resolves from three sources, in a fixed precedence order

Every agent option can be set from the `-javaagent` args string, a JVM system property, or an environment variable, in that precedence order, falling back to the option's own default if none is set. A blank value at any level counts as unset and falls through to the next one. The property and environment variable names are derived mechanically from the option's camelCase name, split on word boundaries and rejoined (`yukon.service.name`, `YUKON_SERVICE_NAME`), so a name exists at exactly one place and cannot drift between the two derived forms.

## Considered options

Environment overriding the agent option, so an operator could override a value baked into an image's command line without rebuilding it. Rejected: the most explicitly written, most launch-specific value should win, the same ordering OpenTelemetry's Java agent uses (system properties over environment). The operator's real need, turning the agent off without a rebuild, is already covered by `enabled` itself being settable from the environment when the command line does not set it.

## Consequences

An option's name is a compatibility surface beyond the agent-args key: renaming an option renames its derived property and environment variable names too, with no separate alias to preserve the old ones.
