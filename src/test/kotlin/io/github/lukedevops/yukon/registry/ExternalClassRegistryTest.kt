package io.github.lukedevops.yukon.registry

import io.github.lukedevops.yukon.export.ExternalClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Pins the export-time resolution and send-once delivery of [ExternalClassRegistry]. See ADR 0030. */
class ExternalClassRegistryTest {
    private var listingComplete = true
    private val resolved = mutableListOf<String>()
    private val idsByLocation = mapOf("jar:file:/libs/a.jar!/" to 0, "jar:file:/libs/b.jar!/" to 1)

    private val held = mutableSetOf<Int>()

    private val registry =
        ExternalClassRegistry(
            isListingComplete = { listingComplete },
            resolveLocation = { location ->
                resolved += location
                idsByLocation[location]
            },
            isDependencySendable = { it !in held },
        )

    private fun sent(snapshots: List<ExternalClassRegistry.ManifestSnapshot>): List<ExternalClass> =
        snapshots.flatMap { it.externalClasses }

    @Test
    fun `nothing is resolved or sent before the dependency listing completes`() {
        listingComplete = false
        registry.record("org.a.Foo", "jar:file:/libs/a.jar!/")
        registry.record("org.gone.Missing", null)

        assertTrue(registry.computeManifestEntries(100).isEmpty())
        assertTrue(resolved.isEmpty())
    }

    @Test
    fun `a jar location maps to its dependency id, and an absent name to an absent entry`() {
        registry.record("org.a.Foo", "jar:file:/libs/a.jar!/")
        registry.record("org.gone.Missing", null)

        assertEquals(
            listOf(ExternalClass("org.a.Foo", 0), ExternalClass("org.gone.Missing", null, absent = true)),
            sent(registry.computeManifestEntries(100)),
        )
    }

    @Test
    fun `a location that is not a dependency sends nothing, then or later, and is resolved once`() {
        registry.record("org.own.Shaded", "jar:file:/app/own.jar!/")

        assertTrue(sent(registry.computeManifestEntries(100)).isEmpty())
        assertTrue(sent(registry.computeManifestEntries(100)).isEmpty())
        assertEquals(listOf("jar:file:/app/own.jar!/"), resolved)
    }

    @Test
    fun `an entry goes out once after a confirmed send`() {
        registry.record("org.a.Foo", "jar:file:/libs/a.jar!/")
        registry.computeManifestEntries(100).forEach(registry::advanceManifest)

        assertTrue(registry.computeManifestEntries(100).isEmpty())
    }

    @Test
    fun `an entry whose send failed goes out again`() {
        registry.record("org.a.Foo", "jar:file:/libs/a.jar!/")
        registry.computeManifestEntries(100)

        assertEquals(listOf(ExternalClass("org.a.Foo", 0)), sent(registry.computeManifestEntries(100)))
    }

    @Test
    fun `the first recording of a name wins`() {
        registry.record("org.a.Foo", "jar:file:/libs/a.jar!/")
        registry.record("org.a.Foo", "jar:file:/libs/b.jar!/")

        assertEquals(listOf(ExternalClass("org.a.Foo", 0)), sent(registry.computeManifestEntries(100)))
    }

    @Test
    fun `a name recorded after a delivery goes out on the next compute`() {
        registry.record("org.a.Foo", "jar:file:/libs/a.jar!/")
        registry.computeManifestEntries(100).forEach(registry::advanceManifest)
        registry.record("org.b.Bar", "jar:file:/libs/b.jar!/")

        assertEquals(listOf(ExternalClass("org.b.Bar", 1)), sent(registry.computeManifestEntries(100)))
    }

    @Test
    fun `entries are chunked at the cap, one entry each`() {
        for (i in 1..5) registry.record("org.a.C$i", "jar:file:/libs/a.jar!/")

        assertEquals(listOf(2, 2, 1), registry.computeManifestEntries(2).map { it.externalClasses.size })
    }

    @Test
    fun `a name recorded already resolved goes out with that id or as absent, and nothing resolves it again`() {
        registry.recordResolved("org.a.Foo", 7)
        registry.recordResolved("org.gone.Missing", null)

        assertEquals(
            listOf(ExternalClass("org.a.Foo", 7), ExternalClass("org.gone.Missing", null, absent = true)),
            sent(registry.computeManifestEntries(100)),
        )
        assertTrue(resolved.isEmpty())
    }

    @Test
    fun `a name recorded already resolved goes out once after a confirmed send`() {
        registry.recordResolved("org.a.Foo", 7)
        registry.computeManifestEntries(100).forEach(registry::advanceManifest)

        assertTrue(registry.computeManifestEntries(100).isEmpty())
    }

    @Test
    fun `a name the transform recorded first keeps that recording, and the other way round`() {
        registry.record("org.a.Foo", "jar:file:/libs/a.jar!/")
        registry.recordResolved("org.a.Foo", 7)
        registry.recordResolved("org.b.Bar", 7)
        registry.record("org.b.Bar", "jar:file:/libs/b.jar!/")

        assertEquals(listOf(ExternalClass("org.a.Foo", 0), ExternalClass("org.b.Bar", 7)), sent(registry.computeManifestEntries(100)))
    }

    @Test
    fun `a mapping to a dependency that is not sendable is held, and goes out once it is`() {
        held += 0
        registry.record("org.a.Foo", "jar:file:/libs/a.jar!/")
        registry.record("org.b.Bar", "jar:file:/libs/b.jar!/")

        assertEquals(listOf(ExternalClass("org.b.Bar", 1)), sent(registry.computeManifestEntries(100)))
        assertEquals(listOf(ExternalClass("org.b.Bar", 1)), sent(registry.computeManifestEntries(100)), "a held entry is not delivered")

        held.clear()
        assertEquals(listOf(ExternalClass("org.a.Foo", 0), ExternalClass("org.b.Bar", 1)), sent(registry.computeManifestEntries(100)))
    }

    @Test
    fun `an absent name goes out while every dependency is held`() {
        held += listOf(0, 1)
        registry.record("org.a.Foo", "jar:file:/libs/a.jar!/")
        registry.record("org.gone.Missing", null)

        assertEquals(listOf(ExternalClass("org.gone.Missing", null, absent = true)), sent(registry.computeManifestEntries(100)))
    }

    @Test
    fun `the backlog is not delivered before the listing completes`() {
        listingComplete = false
        registry.computeManifestEntries(100)

        assertFalse(registry.isBacklogDelivered)
    }

    @Test
    fun `the backlog is delivered once every name recorded before the first compute after the listing went out`() {
        listingComplete = false
        registry.record("org.a.Foo", "jar:file:/libs/a.jar!/")
        registry.record("org.gone.Missing", null)
        registry.record("org.own.Shaded", "jar:file:/app/own.jar!/")
        assertFalse(registry.isBacklogDelivered)

        listingComplete = true
        held += 0
        registry.computeManifestEntries(100).forEach(registry::advanceManifest)
        assertFalse(registry.isBacklogDelivered, "a mapping held on its dependency is still undelivered")

        registry.record("org.b.Bar", "jar:file:/libs/b.jar!/")
        held.clear()
        held += 1
        registry.computeManifestEntries(100).forEach(registry::advanceManifest)

        assertTrue(registry.isBacklogDelivered, "a name recorded later and a name that maps to nothing do not block it")
    }

    @Test
    fun `a backlog with no names is delivered on the first compute after the listing`() {
        assertFalse(registry.isBacklogDelivered)

        registry.computeManifestEntries(100)

        assertTrue(registry.isBacklogDelivered)
    }
}
