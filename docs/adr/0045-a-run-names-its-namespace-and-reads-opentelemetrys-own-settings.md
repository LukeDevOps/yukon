---
status: accepted
---

# A run names its namespace and reads OpenTelemetry's own settings

Decided on 2026-09-26 in a grilling session that spanned this repo, the collector and `yukon-server` (server ADR 0038, collector ADR 0002). This extends ADR 0016.

The server keys a service by its namespace and its name, as OpenTelemetry's `service.namespace` and `service.name` do, so the agent has to send a namespace. An adopter who already runs OpenTelemetry has also set these values once, in OpenTelemetry's own settings. Asking them to set every value again in Yukon's names is extra work, and the two sets of names drift apart. A service's findings should carry the same name as its traces.

## The design

- A new `serviceNamespace` option, `yukon.service.namespace` / `YUKON_SERVICE_NAMESPACE` by ADR 0016's rule. It has no default. `ResourceAttributes` gains `optional string service_namespace = 6`, sent only when set.
- The service name, the namespace and the environment each resolve from the first source that gives a value that is not blank:
  1. Yukon's own sources, in ADR 0016's order: the `-javaagent` args, then the system property, then the `YUKON_*` environment variable.
  2. OpenTelemetry's own settings, resolved as its Java agent resolves them: each of `otel.service.name` and `otel.resource.attributes` from its system property, else its environment variable; the name from `otel.service.name`, else `service.name` in the attributes.
  3. For the name only, detection in the order OpenTelemetry's Java agent uses: the Spring Boot application name, then the main jar manifest's `Implementation-Title`, then the jar's file name.
  4. For the name only, OpenTelemetry's default `unknown_service:java`.

  The keys read from a resource-attributes list are `service.name`, `service.namespace`, and `deployment.environment.name` then the older `deployment.environment`. Each OpenTelemetry setting is taken whole from one place, as in OpenTelemetry's Java agent: a set `otel.resource.attributes` system property hides `OTEL_RESOURCE_ATTRIBUTES` entirely, and `OTEL_SERVICE_NAME` wins over a `service.name` inside that system property. Values are trimmed, and a blank one falls through to the next source.
- The agent never works out a namespace for itself. In Kubernetes the OpenTelemetry Operator derives `service.namespace` from a pod annotation or the pod's namespace, and it writes the result into `OTEL_RESOURCE_ATTRIBUTES`. The agent reads that like any other value.

## Considered options

- Only Yukon's own names. Rejected: every adopter already on OpenTelemetry would set each value twice, and a typo in one place would give Yukon and the traces two names for one service.
- Deriving the namespace from the pod's Kubernetes namespace when nothing names one. Rejected: many teams use one Kubernetes namespace per environment, so this would split one service in two. Without the Operator, OpenTelemetry derives no namespace either, and Yukon's names would then disagree with the traces.
- Keeping the default name `unknown-service`. Rejected: two services with no name on different hosts would share one identity, and OpenTelemetry's form lets the server mark such a service as "name not set".
- Reading only `deployment.environment.name`. Rejected: setups older than semantic conventions 1.27 still send `deployment.environment`.

## Consequences

- The name detection reads the application's own Spring Boot configuration and jar, so it runs when the agent starts. It never loads application classes.
- An adopter who sets both a Yukon name and an OpenTelemetry name gets the Yukon one. That is how the service can be named differently in Yukon on purpose.
