package io.github.lukedevops.yukon.dependencies

import io.github.lukedevops.yukon.dependencies.TestJars.loadableClassEntry
import io.github.lukedevops.yukon.export.DependencyDiscoverySource
import io.github.lukedevops.yukon.export.DependencyIdentity
import io.github.lukedevops.yukon.export.DependencyIdentitySource
import io.github.lukedevops.yukon.registry.DependencyRegistry
import net.bytebuddy.ByteBuddy
import org.junit.jupiter.api.io.TempDir
import java.lang.invoke.MethodHandles
import java.net.URLClassLoader
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LoadedDependencyCounterTest {
    @TempDir
    lateinit var dir: Path

    private val registry = DependencyRegistry()
    private val dependencyId =
        registry.register(
            listOf(DependencyIdentity("g", "a", "1")),
            DependencyIdentitySource.POM_PROPERTIES,
            "/libs/a.jar",
            DependencyDiscoverySource.STARTUP_CLASSPATH,
            classCount = 10,
        )

    /** Maps every protection domain to the one dependency, so only the counter's own filters decide what counts. */
    private val counter = LoadedDependencyCounter(registry) { dependencyId }

    private fun total(): Long =
        registry
            .computeDeltas(10)
            .singleOrNull()
            ?.deltas
            ?.single()
            ?.loadedClassesTotal ?: 0

    @Test
    fun `nothing is counted before the listing completes`() {
        counter.count(arrayOf(LoadedDependencyCounterTest::class.java))

        assertEquals(0, total())
    }

    @Test
    fun `the same name from two loaders counts once`() {
        registry.markListingComplete()
        val jar = TestJars.write(dir.resolve("fixture.jar"), listOf(loadableClassEntry("org.fixture.Twice")))
        val first = URLClassLoader(arrayOf(jar.toUri().toURL()), null).loadClass("org.fixture.Twice")
        val second = URLClassLoader(arrayOf(jar.toUri().toURL()), null).loadClass("org.fixture.Twice")
        assertTrue(first !== second)

        counter.count(arrayOf(first, second))

        assertEquals(1, total())
    }

    @Test
    fun `hidden classes, arrays, primitives and classes with no code source are skipped`() {
        registry.markListingComplete()
        val hiddenBytes =
            ByteBuddy()
                .subclass(Any::class.java)
                .name("io.github.lukedevops.yukon.dependencies.HiddenFixture")
                .make()
                .bytes
        val hidden = MethodHandles.lookup().defineHiddenClass(hiddenBytes, false).lookupClass()
        assertTrue(hidden.isHidden)

        counter.count(
            arrayOf(
                hidden,
                Array<LoadedDependencyCounterTest>::class.java,
                Int::class.javaPrimitiveType!!,
                String::class.java,
                LoadedDependencyCounterTest::class.java,
            ),
        )

        assertEquals(1, total(), "only the ordinary class with a code source counts")
    }

    @Test
    fun `a name counted once stays counted on later calls`() {
        registry.markListingComplete()
        counter.count(arrayOf(LoadedDependencyCounterTest::class.java))
        registry.advanceDeltas(registry.computeDeltas(10).single())

        counter.count(emptyArray())

        assertTrue(registry.computeDeltas(10).isEmpty(), "an unloaded class keeps its count, so nothing changes")
    }

    @Test
    fun `a count before the listing completes marks no generation`() {
        counter.count(arrayOf(LoadedDependencyCounterTest::class.java))

        assertEquals(0, registry.countGeneration)
    }

    @Test
    fun `a count after the listing completes marks a generation, which releases the dependency once delivered`() {
        registry.markListingComplete()

        counter.count(arrayOf(LoadedDependencyCounterTest::class.java))

        assertEquals(1, registry.countGeneration)
        assertFalse(registry.isSendable(dependencyId), "counted, but the counts are not delivered")
        registry.markCountsDelivered(registry.countGeneration)
        assertTrue(registry.isSendable(dependencyId))
    }
}
