---
status: accepted
---

# Endpoints are counted at the framework's dispatch point and declared by its registration hooks

The method tier can say a handler method ran, but not that `GET /promo` was ever served: a handler may back two endpoints, be a lambda the framework invokes through a hidden class, or live outside `includePackages`. Endpoints therefore get their own probe. An endpoint probe counts one endpoint, identified by verb and normalised route template, and is incremented at the point where the framework has matched a request to that endpoint, before the handler runs. The declared list of endpoints comes from the same framework's registration hooks (Spring's handler-method mapping, Ktor's `handle`, `HttpServer.createContext`), plus transform-time annotation reading for JAX-RS, which has no registration API. This is the same technique OpenTelemetry's agent uses to produce `http.route`, extended with a declared list, which OpenTelemetry never needs because a span nobody creates is not a gap.

The handler join (which manifest method backs an endpoint) is a label on the endpoint record, recorded at registration where the framework hands over a method and at first dispatch otherwise. It is never a second counter and never a field on the method probe's own record.

## Considered options

- Attribution only: no new counter, method probes gain a route label. Rejected: the endpoint claim would depend on whether the method tier happened to catch the handler, which fails for Java lambdas (hidden classes), handlers in excluded packages, and one method serving two endpoints.
- OpenAPI as the source of endpoints. Set aside: a document gives an inventory and labels, not a join, and is scarce exactly where annotations are absent. It remains a candidate for a separate contract-diff feature.
- Static analysis of registration call sites (`LDC "/path"` followed by an `invokedynamic` naming the handler). Set aside: fragile on computed paths and nested prefixes, which registration hooks handle for free.
- Reading `http.route` off the current OpenTelemetry span. Rejected for v1: it couples correctness to the OpenTelemetry version on the bootstrap path and to its source-priority rules, and gives no declared list. Both agents coexist since each only reads framework state.
- Muzzle-style reference checking per framework version, as OpenTelemetry does across its two hundred modules. Rejected at seven modules: each module compiles against its oldest supported version, is never shaded, and disables itself once with a warning on the first `LinkageError`, reporting that on the wire.

## Consequences

- Framework classes are matched by a second matcher path, gated on the framework class being present on the loader, untouched by `includePackages` and `excludePackages`, which describe the adopter's code. One switch, `endpointsEnabled`, on by default.
- A hit means "matched", not "completed" or "2xx". A handler that throws still ran. Requests matching no endpoint are not counted.
- Identity is `(verb, normalised route template)` and nothing else, so two servers in one JVM serving the same template merge, and JAX-RS identity excludes `@ApplicationPath`, which is not known when the resource class transforms. The framework's own spelling is kept beside the identity for display.
- A dispatch for an endpoint no registration saw creates the entry lazily, marked as discovered by dispatch, so the hit is never dropped.
- Every endpoint the framework serves is reported, including Actuator and static-resource handlers. Narrowing to the adopter's code is a collector-side filter on the handler class.
- Advice reaches the registry through a seam in the bootstrap module, as in 0004, with a bounded replay buffer for registrations that arrive before the resolver is installed, and a module read edge added for `jdk.httpserver`. Any helper that touches a framework is Java and never names a `kotlin.*` type, since the shaded jar relocates the Kotlin standard library and would rewrite such a reference to a class the framework does not have.
- The wire change is additive on `ProbeManifest` and `DeltaBatch`. Endpoint records carry a "changed since delivered" state so a join learned after delivery is re-sent; class records stay send-once.
