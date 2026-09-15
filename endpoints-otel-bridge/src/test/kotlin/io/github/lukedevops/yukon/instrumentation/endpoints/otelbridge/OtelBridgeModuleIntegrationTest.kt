package io.github.lukedevops.yukon.instrumentation.endpoints.otelbridge

import io.github.lukedevops.yukon.export.EndpointDiscoverySource
import io.github.lukedevops.yukon.instrumentation.endpoints.EndpointInstrumentation
import io.github.lukedevops.yukon.registry.EndpointRegistry
import io.opentelemetry.context.Context
import io.opentelemetry.context.propagation.TextMapGetter
import io.opentelemetry.instrumentation.api.instrumenter.Instrumenter
import io.opentelemetry.instrumentation.api.instrumenter.SpanNameExtractor
import io.opentelemetry.instrumentation.api.semconv.http.HttpServerAttributesExtractor
import io.opentelemetry.instrumentation.api.semconv.http.HttpServerAttributesGetter
import io.opentelemetry.instrumentation.api.semconv.http.HttpServerRoute
import io.opentelemetry.instrumentation.api.semconv.http.HttpServerRouteSource
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import net.bytebuddy.agent.ByteBuddyAgent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private data class FakeRequest(
    val method: String,
)

private class FakeResponse

private object FakeGetter : HttpServerAttributesGetter<FakeRequest, FakeResponse> {
    override fun getHttpRequestMethod(request: FakeRequest): String = request.method

    override fun getHttpRequestHeader(
        request: FakeRequest,
        name: String,
    ): List<String> = emptyList()

    override fun getHttpResponseStatusCode(
        request: FakeRequest,
        response: FakeResponse,
        error: Throwable?,
    ): Int = 200

    override fun getHttpResponseHeader(
        request: FakeRequest,
        response: FakeResponse,
        name: String,
    ): List<String> = emptyList()

    override fun getUrlScheme(request: FakeRequest): String = "http"

    override fun getUrlPath(request: FakeRequest): String = "/ignored"

    override fun getUrlQuery(request: FakeRequest): String? = null
}

private object FakeTextMapGetter : TextMapGetter<FakeRequest> {
    override fun keys(carrier: FakeRequest): Iterable<String> = emptyList()

    override fun get(
        carrier: FakeRequest?,
        key: String,
    ): String? = null
}

/**
 * Proves [OtelBridgeModule] end to end: real advice woven onto a real OpenTelemetry
 * [Instrumenter]'s own `HttpServerAttributesExtractor.onEnd`, driving real spans through a plain
 * [OpenTelemetrySdk] (no `OpenTelemetryExtension` convenience) with an [InMemorySpanExporter], and
 * checking the resulting counts land in a real [EndpointRegistry].
 *
 * [EndpointInstrumentation.install] must run before `HttpServerAttributesExtractor` loads for the
 * first time in this JVM: an `AgentBuilder` transformer only weaves advice into a class as it
 * loads, not into one already loaded. Every OpenTelemetry type this test touches is therefore
 * referenced only from inside the one test method below, never from a field, a companion object,
 * or a top-level `val`, so nothing in this file can trigger that load before `install` runs. The
 * getter and request/response types above are the one necessary exception: they implement
 * OpenTelemetry interfaces by name, which the JVM resolves the moment this test class itself
 * loads. That is safe here specifically because resolving an interface a class implements does
 * not force that interface's own implementation classes, such as `HttpServerAttributesExtractor`,
 * to load, the same distinction [io.github.lukedevops.yukon.instrumentation.endpoints.api.AdviceBinder]
 * itself relies on to describe a class without loading it.
 */
class OtelBridgeModuleIntegrationTest {
    @Test
    fun `a route read through a real Instrumenter is counted per request, and never for an identity another framework owns`() {
        val instrumentation = ByteBuddyAgent.install()
        val registry = EndpointRegistry()
        // A framework module already owns this identity; the bridge must never touch it.
        val ownedKey = Any()
        registry.register(key = ownedKey, framework = "fake", verb = "GET", verbatimTemplate = "/legacy/{id}")

        val endpointInstrumentation = EndpointInstrumentation(registry, listOf(OtelBridgeModule()))
        val transformer = endpointInstrumentation.install(instrumentation)

        try {
            val spanExporter = InMemorySpanExporter.create()
            val tracerProvider = SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(spanExporter)).build()
            val openTelemetry = OpenTelemetrySdk.builder().setTracerProvider(tracerProvider).build()

            val spanNameExtractor = SpanNameExtractor<FakeRequest> { "test-span" }
            val instrumenter =
                Instrumenter
                    .builder<FakeRequest, FakeResponse>(openTelemetry, "test", spanNameExtractor)
                    .addAttributesExtractor(HttpServerAttributesExtractor.create(FakeGetter))
                    .addContextCustomizer(HttpServerRoute.create(FakeGetter))
                    .buildServerInstrumenter(FakeTextMapGetter)

            fun serve(
                method: String,
                route: String?,
            ) {
                val request = FakeRequest(method)
                val context = instrumenter.start(Context.root(), request)
                if (route != null) {
                    HttpServerRoute.update(context, HttpServerRouteSource.CONTROLLER, route)
                }
                instrumenter.end(context, request, FakeResponse(), null)
            }

            serve("GET", "/users/{id}")
            serve("GET", "/users/{id}")
            serve("POST", "/users/{id}")
            serve("GET", null)
            serve("GET", "/legacy/{id}")

            val endpointsByIdentity = registry.endpoints().associateBy { "${it.verb} ${it.routeTemplate}" }
            assertEquals(setOf("GET /users/{id}", "POST /users/{id}", "GET /legacy/{id}"), endpointsByIdentity.keys)

            val getEndpoint = endpointsByIdentity.getValue("GET /users/{id}")
            assertEquals("otel", getEndpoint.framework)
            assertEquals(EndpointDiscoverySource.DISPATCH, getEndpoint.discoverySource)
            assertNull(getEndpoint.handlerClass)

            val postEndpoint = endpointsByIdentity.getValue("POST /users/{id}")
            assertEquals("otel", postEndpoint.framework)
            assertEquals(EndpointDiscoverySource.DISPATCH, postEndpoint.discoverySource)

            val deltasById = registry.computeDeltas(maxPerBatch = 10).flatMap { it.deltas }.associateBy { it.endpointId }
            assertEquals(2L, deltasById.getValue(getEndpoint.endpointId).hitsTotal)
            assertEquals(1L, deltasById.getValue(postEndpoint.endpointId).hitsTotal)

            // Dedup: the identity another framework already owns stays owned, and unhit, by that
            // framework. The bridge's own read of it produced no second entry and no count.
            val legacyEndpoint = endpointsByIdentity.getValue("GET /legacy/{id}")
            assertEquals("fake", legacyEndpoint.framework)
            assertTrue(legacyEndpoint.endpointId !in deltasById, "an owned-but-never-hit endpoint must not appear in the delta batch")

            assertTrue(registry.disabledModules().isEmpty())
        } finally {
            endpointInstrumentation.uninstall(instrumentation, transformer)
        }
    }
}
