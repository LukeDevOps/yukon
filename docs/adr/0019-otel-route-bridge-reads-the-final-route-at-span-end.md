---
status: accepted
---

# The OpenTelemetry route bridge reads the final `http.route` at span end, is off by default, and never counts an endpoint another module owns

For frameworks Yukon has no endpoint module for, an opt-in bridge module counts the route the OpenTelemetry Java instrumentation resolved. It weaves advice onto the exit of `HttpServerAttributesExtractor.onEnd`, the one point that runs once per server span with the final route after OpenTelemetry's source-priority rules have settled, and reads the route and request method from the same `HttpRouteState` the extractor reads. A span with no route is not counted. The module's type matcher accepts both the javaagent's relocated copy of `instrumentation-api` and the unshaded library artifact, with the advice's own references remapped to the relocated prefix at bind time, so one module covers both ways of deploying OpenTelemetry. It is enabled by `otelBridgeEnabled`, default false, and an endpoint it reads whose identity already exists from another module is left alone.

## Considered options

- Reading the route through a public OpenTelemetry API from the current span. Not available: the application-facing `Span` is write-only, `ReadableSpan` lives in the relocated SDK, and the only sanctioned way to observe span attributes is a `SpanProcessor` registered through the extension SPI, which requires shipping as an OpenTelemetry extension rather than as a separate agent.
- Advice on `HttpServerRoute.update`. Rejected: it fires several times per request as sources of rising priority override one another, so no single call knows the winner, and reproducing the priority rules would need per-request state.
- Enabled by default, like the framework modules. Rejected: the hook is on OpenTelemetry internals and its correctness is tied to that version, the coupling ADR 0017 declined for v1. An adopter enables it knowingly, for a framework nothing else covers.
- Counting every route the bridge sees. Rejected: a request the Spring module already counted would count twice. Identity is already the cross-module key, so the bridge checks it and hits only entries it created itself, under framework `otel`.

## Consequences

- The bridge counts after the handler ran, not before. "A hit means matched" still holds, since the span ends on an exception too.
- The bridge only ever produces endpoints discovered by dispatch. It has no declared list, so "never called" is not computable for them; that is what a framework module is for.
- A servlet-only application gets the route OpenTelemetry reports for it, which may be a servlet mapping such as `/api/*`, exactly as `http.route` would show.
- The module compiles against the public `opentelemetry-instrumentation-api` artifact at its oldest supported 2.x version; the relocated names are verified against a real agent jar, and any drift trips the module's own linkage-failure disable and lands in `disabled_endpoint_modules`.
