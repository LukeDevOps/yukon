package io.github.lukedevops.yukon.registry

import io.github.lukedevops.yukon.export.DependencyDiscoverySource
import io.github.lukedevops.yukon.export.DependencyIdentity
import io.github.lukedevops.yukon.export.DependencyIdentitySource
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DependencyRegistryTest {
    private fun DependencyRegistry.add(
        vararg identities: DependencyIdentity,
        location: String = "/libs/${identities.first().artifactId}.jar",
    ): Int =
        register(
            identities.toList(),
            DependencyIdentitySource.POM_PROPERTIES,
            location,
            DependencyDiscoverySource.STARTUP_CLASSPATH,
            classCount = 3,
            origin = DependencyOrigin.FlatJar(Path.of(location)),
        )

    @Test
    fun `ids are assigned from 0 in registration order`() {
        val registry = DependencyRegistry()

        assertEquals(0, registry.add(DependencyIdentity("g", "a", "1")))
        assertEquals(1, registry.add(DependencyIdentity("g", "b", "1")))
        assertEquals(listOf(0, 1), registry.entries().map { it.dependencyId })
    }

    @Test
    fun `registering an identity already present returns its id and keeps the first record, whatever the version`() {
        val registry = DependencyRegistry()
        val first = registry.add(DependencyIdentity("g", "a", "1"), location = "/first.jar")

        val second = registry.add(DependencyIdentity("g", "a", "2"), location = "/second.jar")

        assertEquals(first, second)
        assertEquals("/first.jar", registry.entries().single().location)
    }

    @Test
    fun `the identity key is the sorted group-artifact pairs, so order of identities does not matter`() {
        val registry = DependencyRegistry()
        val a = DependencyIdentity("g", "a", "1")
        val b = DependencyIdentity(null, "b", null)

        assertEquals(registry.add(a, b), registry.add(b, a))
        assertEquals(listOf(":b", "g:a"), DependencyRegistry.identityKey(listOf(a, b)))
    }

    @Test
    fun `a dependency is delivered once, and the location carries every field`() {
        val registry = DependencyRegistry()
        registry.add(DependencyIdentity("g", "a", "1"))

        val chunks = registry.computeManifestEntries(10)
        registry.advanceManifest(chunks.single())

        val location = chunks.single().dependencies.single()
        assertEquals(0, location.dependencyId)
        assertEquals(listOf(DependencyIdentity("g", "a", "1")), location.identities)
        assertEquals(DependencyIdentitySource.POM_PROPERTIES, location.identitySource)
        assertEquals("/libs/a.jar", location.location)
        assertEquals(DependencyDiscoverySource.STARTUP_CLASSPATH, location.discoverySource)
        assertEquals(3, location.classCount)
        assertTrue(registry.computeManifestEntries(10).isEmpty())
    }

    @Test
    fun `a snapshot never advanced is computed again on the next call`() {
        val registry = DependencyRegistry()
        registry.add(DependencyIdentity("g", "a", "1"))

        registry.computeManifestEntries(10)

        assertEquals(
            1,
            registry
                .computeManifestEntries(10)
                .single()
                .dependencies.size,
        )
    }

    @Test
    fun `advancing one snapshot marks exactly its dependencies delivered`() {
        val registry = DependencyRegistry()
        registry.add(DependencyIdentity("g", "a", "1"))
        val first = registry.computeManifestEntries(10).single()
        registry.add(DependencyIdentity("g", "b", "1"))

        registry.advanceManifest(first)

        assertEquals(
            listOf("b"),
            registry
                .computeManifestEntries(10)
                .single()
                .dependencies
                .map { it.identities.single().artifactId },
        )
    }

    @Test
    fun `manifest entries are chunked at the cap, each dependency weighing one`() {
        val registry = DependencyRegistry()
        repeat(5) { registry.add(DependencyIdentity("g", "a$it", "1")) }

        val chunks = registry.computeManifestEntries(2)

        assertEquals(listOf(2, 2, 1), chunks.map { it.dependencies.size })
        assertEquals((0..4).toList(), chunks.flatMap { chunk -> chunk.dependencies.map { it.dependencyId } })
    }

    @Test
    fun `the listing-complete flag starts false and stays set once marked`() {
        val registry = DependencyRegistry()
        assertFalse(registry.isListingComplete)

        registry.markListingComplete()

        assertTrue(registry.isListingComplete)
    }
}
