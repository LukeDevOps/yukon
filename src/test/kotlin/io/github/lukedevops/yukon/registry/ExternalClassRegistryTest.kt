package io.github.lukedevops.yukon.registry

import io.github.lukedevops.yukon.export.ExternalClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Pins the export-time resolution and send-once delivery of [ExternalClassRegistry]. See ADR 0030. */
class ExternalClassRegistryTest {
    private var listingComplete = true
    private val resolved = mutableListOf<String>()
    private val idsByLocation = mapOf("jar:file:/libs/a.jar!/" to 0, "jar:file:/libs/b.jar!/" to 1)

    private val registry =
        ExternalClassRegistry({ listingComplete }) { location ->
            resolved += location
            idsByLocation[location]
        }

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
}
