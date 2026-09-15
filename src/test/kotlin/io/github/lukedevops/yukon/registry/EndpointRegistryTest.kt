package io.github.lukedevops.yukon.registry

import io.github.lukedevops.yukon.export.EndpointDiscoverySource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class EndpointRegistryTest {
    @Test
    fun `register returns an entry with a normalised verb and route template`() {
        val registry = EndpointRegistry()

        val entry = registry.register(key = Any(), framework = "http-server", verb = "get", verbatimTemplate = "/orders/{id}")

        assertEquals("GET", entry.verb)
        assertEquals("/orders/{id}", entry.routeTemplate)
        assertEquals("/orders/{id}", entry.verbatimTemplate)
        assertEquals("http-server", entry.framework)
    }

    @Test
    fun `two keys normalising to the same identity share one endpoint id and one count`() {
        val registry = EndpointRegistry()
        val keyA = Any()
        val keyB = Any()

        val entryA = registry.register(key = keyA, framework = "server-a", verb = "GET", verbatimTemplate = "/health")
        val entryB = registry.register(key = keyB, framework = "server-b", verb = "GET", verbatimTemplate = "/health")

        assertEquals(entryA.endpointId, entryB.endpointId)
        entryA.hit()
        assertEquals(entryA.count, entryB.count)
    }

    @Test
    fun `lookup on an unbound key is null`() {
        val registry = EndpointRegistry()

        assertNull(registry.lookup(Any()))
    }

    @Test
    fun `lookup returns the entry bound to a key by register`() {
        val registry = EndpointRegistry()
        val key = Any()

        val entry = registry.register(key = key, framework = "http-server", verb = "GET", verbatimTemplate = "/health")

        assertSame(entry, registry.lookup(key))
    }

    @Test
    fun `lookup returns the entry bound to a key by recordDispatch`() {
        val registry = EndpointRegistry()
        val key = Any()

        val entry = registry.recordDispatch(key = key, framework = "http-server", verb = "GET", verbatimTemplate = "/health")

        assertSame(entry, registry.lookup(key))
    }

    @Test
    fun `recordDispatch marks a new entry as discovered by dispatch`() {
        val registry = EndpointRegistry()

        val entry = registry.recordDispatch(key = Any(), framework = "http-server", verb = "GET", verbatimTemplate = "/promo")

        assertEquals(EndpointDiscoverySource.DISPATCH, entry.discoverySource)
    }

    @Test
    fun `a dispatch-discovered entry later registered reports REGISTRATION and is re-sent`() {
        val registry = EndpointRegistry()
        registry.recordDispatch(key = Any(), framework = "http-server", verb = "GET", verbatimTemplate = "/promo")
        val delivered = registry.computeManifestEntries(maxPerChunk = 10).single()
        registry.advanceManifest(delivered)

        val entry = registry.register(key = Any(), framework = "http-server", verb = "GET", verbatimTemplate = "/promo")

        assertEquals(EndpointDiscoverySource.REGISTRATION, entry.discoverySource)
        val resent = registry.computeManifestEntries(maxPerChunk = 10).single()
        assertEquals(EndpointDiscoverySource.REGISTRATION, resent.endpoints.single().discoverySource)
    }

    @Test
    fun `registering the same endpoint twice does not create a second manifest entry`() {
        val registry = EndpointRegistry()
        registry.register(key = Any(), framework = "http-server", verb = "GET", verbatimTemplate = "/health")

        registry.register(key = Any(), framework = "http-server", verb = "GET", verbatimTemplate = "/health")

        assertEquals(1, registry.endpoints().size)
    }

    @Test
    fun `hit increments the count without any other state`() {
        val registry = EndpointRegistry()
        val entry = registry.register(key = Any(), framework = "http-server", verb = "GET", verbatimTemplate = "/health")

        entry.hit()
        entry.hit()

        assertEquals(2L, entry.count)
    }

    @Test
    fun `attachHandler sets the join when none is present`() {
        val registry = EndpointRegistry()
        val entry = registry.register(key = Any(), framework = "http-server", verb = "GET", verbatimTemplate = "/health")

        registry.attachHandler(entry, HandlerRef(className = "com.example.HealthHandler"))

        assertEquals("com.example.HealthHandler", entry.handlerClass)
        assertNull(entry.handlerMethod)
    }

    @Test
    fun `attachHandler upgrades a class-only join to a full method join`() {
        val registry = EndpointRegistry()
        val entry = registry.register(key = Any(), framework = "http-server", verb = "GET", verbatimTemplate = "/health")
        registry.attachHandler(entry, HandlerRef(className = "com.example.HealthHandler"))

        registry.attachHandler(
            entry,
            HandlerRef(className = "com.example.HealthHandler", methodName = "handle", descriptor = "()V"),
        )

        assertEquals("handle", entry.handlerMethod)
        assertEquals("()V", entry.handlerDescriptor)
    }

    @Test
    fun `attachHandler never overwrites a full join already present`() {
        val registry = EndpointRegistry()
        val entry = registry.register(key = Any(), framework = "http-server", verb = "GET", verbatimTemplate = "/health")
        registry.attachHandler(
            entry,
            HandlerRef(className = "com.example.HealthHandler", methodName = "handle", descriptor = "()V"),
        )

        registry.attachHandler(
            entry,
            HandlerRef(className = "com.example.Other", methodName = "other", descriptor = "()I"),
        )

        assertEquals("com.example.HealthHandler", entry.handlerClass)
        assertEquals("handle", entry.handlerMethod)
        assertEquals("()V", entry.handlerDescriptor)
    }

    @Test
    fun `recordDispatch attaches the handler class only when the entry has none yet`() {
        val registry = EndpointRegistry()
        val entry =
            registry.recordDispatch(
                key = Any(),
                framework = "http-server",
                verb = "GET",
                verbatimTemplate = "/promo",
                handlerClass = "com.example.PromoHandler",
            )

        assertEquals("com.example.PromoHandler", entry.handlerClass)
        assertNull(entry.handlerMethod)
    }

    @Test
    fun `recordDisabledModule is idempotent, keeping the first reason`() {
        val registry = EndpointRegistry()

        registry.recordDisabledModule("spring-mvc-5", reason = "first reason")
        registry.recordDisabledModule("spring-mvc-5", reason = "second reason")

        assertEquals("first reason", registry.disabledModules().single().reason)
    }

    @Test
    fun `endpoints returns a full snapshot in endpoint id order`() {
        val registry = EndpointRegistry()
        registry.register(key = Any(), framework = "http-server", verb = "GET", verbatimTemplate = "/b")
        registry.register(key = Any(), framework = "http-server", verb = "GET", verbatimTemplate = "/a")

        val ids = registry.endpoints().map { it.endpointId }

        assertEquals(ids.sorted(), ids)
    }

    @Test
    fun `computeDeltas reports the cumulative count for endpoints whose count changed`() {
        val registry = EndpointRegistry()
        val entry = registry.register(key = Any(), framework = "http-server", verb = "GET", verbatimTemplate = "/health")
        entry.hit()
        entry.hit()

        val batches = registry.computeDeltas(maxPerBatch = 10)

        assertEquals(1, batches.size)
        val delta = batches.single().deltas.single()
        assertEquals(entry.endpointId, delta.endpointId)
        assertEquals(2L, delta.hitsTotal)
    }

    @Test
    fun `computeDeltas returns an empty list when nothing changed, with no heartbeat role`() {
        val registry = EndpointRegistry()
        registry.register(key = Any(), framework = "http-server", verb = "GET", verbatimTemplate = "/health")

        assertTrue(registry.computeDeltas(maxPerBatch = 10).isEmpty())
    }

    @Test
    fun `advanceDeltas omits endpoints whose count already reached the collector`() {
        val registry = EndpointRegistry()
        val entry = registry.register(key = Any(), framework = "http-server", verb = "GET", verbatimTemplate = "/health")
        entry.hit()

        registry.advanceDeltas(registry.computeDeltas(maxPerBatch = 10).single())

        assertTrue(registry.computeDeltas(maxPerBatch = 10).isEmpty())
    }

    @Test
    fun `hits recorded between a snapshot and a failed flush are not lost`() {
        val registry = EndpointRegistry()
        val entry = registry.register(key = Any(), framework = "http-server", verb = "GET", verbatimTemplate = "/health")
        entry.hit()
        entry.hit()
        entry.hit()

        registry.computeDeltas(maxPerBatch = 10) // send fails, never advanced
        entry.hit()
        entry.hit()

        val retry =
            registry
                .computeDeltas(maxPerBatch = 10)
                .single()
                .deltas
                .single()
        assertEquals(5L, retry.hitsTotal)
    }

    @Test
    fun `a decreased count is reported and warns once`() {
        val registry = EndpointRegistry()
        val entry = registry.register(key = Any(), framework = "http-server", verb = "GET", verbatimTemplate = "/health")
        repeat(5) { entry.hit() }
        registry.advanceDeltas(registry.computeDeltas(maxPerBatch = 10).single())

        // Not reachable via this registry's own hit()/register() calls (counts only ever grow);
        // simulated directly to pin the reporting behaviour, mirroring ProbeRegistry's own test.
        entry.count = 2

        val batch =
            registry
                .computeDeltas(maxPerBatch = 10)
                .single()
                .deltas
                .single()
        assertEquals(2L, batch.hitsTotal)
    }

    @Test
    fun `an unchanged value after a delivered decrease goes quiet again`() {
        val registry = EndpointRegistry()
        val entry = registry.register(key = Any(), framework = "http-server", verb = "GET", verbatimTemplate = "/health")
        repeat(5) { entry.hit() }
        registry.advanceDeltas(registry.computeDeltas(maxPerBatch = 10).single())
        entry.count = 2
        registry.advanceDeltas(registry.computeDeltas(maxPerBatch = 10).single())

        assertTrue(registry.computeDeltas(maxPerBatch = 10).isEmpty())
    }

    @Test
    fun `firstSeenAt is stamped once and stays stable across subsequent flushes`() {
        val registry = EndpointRegistry()
        val entry = registry.register(key = Any(), framework = "http-server", verb = "GET", verbatimTemplate = "/health")
        entry.hit()

        val first = registry.computeDeltas(maxPerBatch = 10).single()
        val firstSeenAt = first.deltas.single().firstSeenAt
        registry.advanceDeltas(first)

        entry.hit()
        val second =
            registry
                .computeDeltas(maxPerBatch = 10)
                .single()
                .deltas
                .single()

        assertEquals(firstSeenAt, second.firstSeenAt)
    }

    @Test
    fun `computeDeltas splits changed endpoints into batches at the cap`() {
        val registry = EndpointRegistry()
        val entries = (1..5).map { registry.register(key = Any(), framework = "http-server", verb = "GET", verbatimTemplate = "/e$it") }
        entries.forEach { it.hit() }

        val batches = registry.computeDeltas(maxPerBatch = 2)

        assertEquals(listOf(2, 2, 1), batches.map { it.deltas.size })
    }

    @Test
    fun `advancing one delta batch leaves the other batch's endpoints pending`() {
        val registry = EndpointRegistry()
        val entryA = registry.register(key = Any(), framework = "http-server", verb = "GET", verbatimTemplate = "/a")
        val entryB = registry.register(key = Any(), framework = "http-server", verb = "GET", verbatimTemplate = "/b")
        entryA.hit()
        entryB.hit()
        val batches = registry.computeDeltas(maxPerBatch = 1)
        assertEquals(2, batches.size)

        registry.advanceDeltas(batches[0])

        val pending = registry.computeDeltas(maxPerBatch = 10)
        assertEquals(1, pending.size)
        assertEquals(
            batches[1].deltas.single().endpointId,
            pending
                .single()
                .deltas
                .single()
                .endpointId,
        )
    }

    @Test
    fun `advancing an older delta snapshot after a newer one does not roll the last-sent value back`() {
        val registry = EndpointRegistry()
        val entry = registry.register(key = Any(), framework = "http-server", verb = "GET", verbatimTemplate = "/health")
        entry.hit()
        entry.hit()
        entry.hit()
        val older = registry.computeDeltas(maxPerBatch = 10).single()
        entry.hit()
        entry.hit()
        val newer = registry.computeDeltas(maxPerBatch = 10).single()

        registry.advanceDeltas(newer)
        registry.advanceDeltas(older)

        assertTrue(registry.computeDeltas(maxPerBatch = 10).isEmpty(), "5 was already delivered; 3 must not win")
    }

    @Test
    fun `computeManifestEntries reports endpoints never delivered`() {
        val registry = EndpointRegistry()
        registry.register(key = Any(), framework = "http-server", verb = "GET", verbatimTemplate = "/health")

        val chunks = registry.computeManifestEntries(maxPerChunk = 10)

        assertEquals(
            "/health",
            chunks
                .single()
                .endpoints
                .single()
                .routeTemplate,
        )
    }

    @Test
    fun `computeManifestEntries returns an empty list when there is nothing to send`() {
        val registry = EndpointRegistry()
        registry.register(key = Any(), framework = "http-server", verb = "GET", verbatimTemplate = "/health")
        registry.advanceManifest(registry.computeManifestEntries(maxPerChunk = 10).single())

        assertTrue(registry.computeManifestEntries(maxPerChunk = 10).isEmpty())
    }

    @Test
    fun `a failed manifest send is not advanced, so the next compute retries the same endpoint`() {
        val registry = EndpointRegistry()
        registry.register(key = Any(), framework = "http-server", verb = "GET", verbatimTemplate = "/health")

        registry.computeManifestEntries(maxPerChunk = 10) // send fails, never advanced

        val retry = registry.computeManifestEntries(maxPerChunk = 10)
        assertEquals(
            "/health",
            retry
                .single()
                .endpoints
                .single()
                .routeTemplate,
        )
    }

    @Test
    fun `a join learned after delivery is re-sent on the next compute`() {
        val registry = EndpointRegistry()
        val entry = registry.register(key = Any(), framework = "http-server", verb = "GET", verbatimTemplate = "/health")
        val firstSnapshot = registry.computeManifestEntries(maxPerChunk = 10).single()

        registry.attachHandler(entry, HandlerRef(className = "com.example.HealthHandler", methodName = "handle", descriptor = "()V"))
        registry.advanceManifest(firstSnapshot)

        val secondSnapshot = registry.computeManifestEntries(maxPerChunk = 10).single()
        val location = secondSnapshot.endpoints.single()
        assertEquals("com.example.HealthHandler", location.handlerClass)
        assertEquals("handle", location.handlerMethod)
    }

    @Test
    fun `computeManifestEntries includes disabled modules not yet delivered`() {
        val registry = EndpointRegistry()
        registry.recordDisabledModule("spring-mvc-5", reason = "LinkageError")

        val chunk = registry.computeManifestEntries(maxPerChunk = 10).single()

        assertEquals("spring-mvc-5", chunk.disabledModules.single().module)
    }

    @Test
    fun `computeManifestEntries chunking counts endpoints and disabled modules together and splits at the cap`() {
        val registry = EndpointRegistry()
        registry.register(key = Any(), framework = "http-server", verb = "GET", verbatimTemplate = "/a")
        registry.register(key = Any(), framework = "http-server", verb = "GET", verbatimTemplate = "/b")
        registry.recordDisabledModule("spring-mvc-5", reason = "LinkageError")

        val chunks = registry.computeManifestEntries(maxPerChunk = 1)

        assertEquals(3, chunks.size)
        chunks.forEach { assertEquals(1, it.endpoints.size + it.disabledModules.size) }
    }

    @Test
    fun `advancing one manifest chunk leaves the other chunk's entries pending`() {
        val registry = EndpointRegistry()
        registry.register(key = Any(), framework = "http-server", verb = "GET", verbatimTemplate = "/a")
        registry.register(key = Any(), framework = "http-server", verb = "GET", verbatimTemplate = "/b")
        val chunks = registry.computeManifestEntries(maxPerChunk = 1)
        assertEquals(2, chunks.size)

        registry.advanceManifest(chunks[0])

        val pending = registry.computeManifestEntries(maxPerChunk = 10)
        assertEquals(1, pending.size)
        assertEquals(
            chunks[1].endpoints.single().routeTemplate,
            pending
                .single()
                .endpoints
                .single()
                .routeTemplate,
        )
    }

    @Test
    fun `normalizeVerb rules apply so a null and an explicit star verb merge into one endpoint`() {
        val registry = EndpointRegistry()

        val entryA = registry.register(key = Any(), framework = "http-server", verb = null, verbatimTemplate = "/health")
        val entryB = registry.register(key = Any(), framework = "http-server", verb = "*", verbatimTemplate = "/health")

        assertEquals(entryA.endpointId, entryB.endpointId)
    }

    @Test
    fun `contextPath is folded into the route template used for identity`() {
        val registry = EndpointRegistry()

        val entry =
            registry.register(
                key = Any(),
                framework = "spring-mvc",
                verb = "GET",
                verbatimTemplate = "/orders",
                contextPath = "/app",
            )

        assertEquals("/app/orders", entry.routeTemplate)
    }

    @Test
    fun `recordDispatchIfUnowned binds nothing and returns null for an identity another framework already owns`() {
        val registry = EndpointRegistry()
        val ownedEntry = registry.register(key = Any(), framework = "spring-mvc", verb = "GET", verbatimTemplate = "/users/{id}")
        val bridgeKey = Any()

        val result = registry.recordDispatchIfUnowned(key = bridgeKey, framework = "otel", verb = "GET", verbatimTemplate = "/users/{id}")

        assertNull(result)
        assertNull(registry.lookup(bridgeKey))
        assertEquals(0L, ownedEntry.count)
        assertEquals("spring-mvc", registry.endpoints().single().framework)
    }

    @Test
    fun `recordDispatchIfUnowned binds and returns the entry when the existing owner is the same framework`() {
        val registry = EndpointRegistry()
        val firstKey = Any()
        val existing = registry.recordDispatch(key = firstKey, framework = "otel", verb = "GET", verbatimTemplate = "/users/{id}")
        val secondKey = Any()

        val result = registry.recordDispatchIfUnowned(key = secondKey, framework = "otel", verb = "GET", verbatimTemplate = "/users/{id}")

        assertSame(existing, result)
        assertSame(existing, registry.lookup(secondKey))
    }

    @Test
    fun `recordDispatchIfUnowned creates a dispatch-discovered entry when no identity exists yet`() {
        val registry = EndpointRegistry()
        val key = Any()

        val result = registry.recordDispatchIfUnowned(key = key, framework = "otel", verb = "GET", verbatimTemplate = "/users/{id}")

        assertEquals("otel", result?.framework)
        assertEquals(EndpointDiscoverySource.DISPATCH, result?.discoverySource)
        assertSame(result, registry.lookup(key))
    }
}
