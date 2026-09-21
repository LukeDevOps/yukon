package io.github.lukedevops.yukon.registry

import io.github.lukedevops.yukon.export.DependencyDiscoverySource
import io.github.lukedevops.yukon.export.DependencyIdentity
import io.github.lukedevops.yukon.export.DependencyIdentitySource
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
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

    @Test
    fun `a changed loaded-class total produces one delta and an unchanged one none`() {
        val registry = DependencyRegistry()
        val a = registry.add(DependencyIdentity("g", "a", "1"))
        registry.add(DependencyIdentity("g", "b", "1"))
        registry.recordLoaded(a, "org.a.One")
        registry.recordLoaded(a, "org.a.Two")

        val snapshots = registry.computeDeltas(10)

        val delta = snapshots.single().deltas.single()
        assertEquals(a, delta.dependencyId)
        assertEquals(2, delta.loadedClassesTotal)
        registry.advanceDeltas(snapshots.single())
        assertTrue(registry.computeDeltas(10).isEmpty(), "nothing changed since the delivered total")
    }

    @Test
    fun `the total counts distinct names, so a name recorded twice counts once`() {
        val registry = DependencyRegistry()
        val a = registry.add(DependencyIdentity("g", "a", "1"))

        registry.recordLoaded(a, "org.a.One")
        registry.recordLoaded(a, "org.a.One")

        assertEquals(
            1,
            registry
                .computeDeltas(10)
                .single()
                .deltas
                .single()
                .loadedClassesTotal,
        )
    }

    @Test
    fun `firstLoadedAt is stamped by the first compute that sees a class and never moves`() {
        val registry = DependencyRegistry()
        val a = registry.add(DependencyIdentity("g", "a", "1"))
        assertTrue(registry.computeDeltas(10).isEmpty(), "a total of zero is not a change")

        val before = System.currentTimeMillis()
        registry.recordLoaded(a, "org.a.One")
        val first = registry.computeDeltas(10).single()
        val stamped = first.deltas.single().firstLoadedAt
        assertTrue(stamped >= before, "stamped at compute time, got $stamped")
        registry.advanceDeltas(first)

        Thread.sleep(5)
        registry.recordLoaded(a, "org.a.Two")
        assertEquals(
            stamped,
            registry
                .computeDeltas(10)
                .single()
                .deltas
                .single()
                .firstLoadedAt,
        )
    }

    @Test
    fun `a delta snapshot never advanced is computed again`() {
        val registry = DependencyRegistry()
        val a = registry.add(DependencyIdentity("g", "a", "1"))
        registry.recordLoaded(a, "org.a.One")

        registry.computeDeltas(10)

        assertEquals(
            1,
            registry
                .computeDeltas(10)
                .single()
                .deltas
                .single()
                .loadedClassesTotal,
        )
    }

    @Test
    fun `advancing records only the totals the snapshot staged`() {
        val registry = DependencyRegistry()
        val a = registry.add(DependencyIdentity("g", "a", "1"))
        registry.recordLoaded(a, "org.a.One")
        val staged = registry.computeDeltas(10).single()
        registry.recordLoaded(a, "org.a.Two")

        registry.advanceDeltas(staged)

        assertEquals(
            2,
            registry
                .computeDeltas(10)
                .single()
                .deltas
                .single()
                .loadedClassesTotal,
        )
    }

    @Test
    fun `an older snapshot confirmed after a newer one does not roll the delivered total back`() {
        val registry = DependencyRegistry()
        val a = registry.add(DependencyIdentity("g", "a", "1"))
        registry.recordLoaded(a, "org.a.One")
        val older = registry.computeDeltas(10).single()
        registry.recordLoaded(a, "org.a.Two")
        val newer = registry.computeDeltas(10).single()

        registry.advanceDeltas(newer)
        registry.advanceDeltas(older)

        assertTrue(registry.computeDeltas(10).isEmpty())
    }

    @Test
    fun `deltas are chunked at the cap, each weighing one`() {
        val registry = DependencyRegistry()
        repeat(5) {
            val id = registry.add(DependencyIdentity("g", "a$it", "1"))
            registry.recordLoaded(id, "org.a$it.One")
        }

        val snapshots = registry.computeDeltas(2)

        assertEquals(listOf(2, 2, 1), snapshots.map { it.deltas.size })
        assertEquals((0..4).toList(), snapshots.flatMap { s -> s.deltas.map { it.dependencyId } })
    }

    @Test
    fun `an origin is found by canonical path, and an identity by its key`(
        @TempDir dir: Path,
    ) {
        val real = Files.createDirectories(dir.resolve("real")).resolve("a-1.jar")
        Files.write(real, ByteArray(0))
        val link = Files.createSymbolicLink(dir.resolve("link"), dir.resolve("real"))
        val registry = DependencyRegistry()
        val id =
            registry.register(
                listOf(DependencyIdentity("g", "a", "1")),
                DependencyIdentitySource.POM_PROPERTIES,
                real.toString(),
                DependencyDiscoverySource.STARTUP_CLASSPATH,
                classCount = 1,
                origin = DependencyOrigin.FlatJar(link.resolve("a-1.jar")),
            )

        assertEquals(id, registry.idForOrigin(DependencyOrigin.FlatJar(real)))
        assertEquals(null, registry.idForOrigin(DependencyOrigin.FlatJar(dir.resolve("other.jar"))))
        assertEquals(id, registry.idForKey(listOf("g:a")))
        assertEquals(null, registry.idForKey(listOf("g:b")))
    }
}
