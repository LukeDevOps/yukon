package io.github.lukedevops.yukon.dependencies

import io.github.lukedevops.yukon.Agent
import io.github.lukedevops.yukon.config.AgentConfig
import io.github.lukedevops.yukon.dependencies.TestJars.loadableClassEntry
import io.github.lukedevops.yukon.dependencies.TestJars.pom
import io.github.lukedevops.yukon.export.DependencyDiscoverySource
import io.github.lukedevops.yukon.instrumentation.LoadedClassSweep
import io.github.lukedevops.yukon.registry.DependencyRegistry
import io.github.lukedevops.yukon.registry.ProbeRegistry
import net.bytebuddy.agent.ByteBuddyAgent
import org.junit.jupiter.api.io.TempDir
import java.lang.ref.Reference
import java.net.URLClassLoader
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Drives the dependency count end to end: real jars, classes loaded through a real
 * [URLClassLoader], and a [LoadedClassSweep] reading the JVM's own loaded-class array.
 */
class LoadedDependencySweepTest {
    @TempDir
    lateinit var dir: Path

    private val instrumentation = ByteBuddyAgent.install()

    @Test
    fun `classes loaded from a listed jar and from a jar the listing never saw are counted per dependency`() {
        val listedJar =
            TestJars.write(
                dir.resolve("listed-1.0.jar"),
                listOf(
                    pom("org.listed", "listed", "1.0"),
                    loadableClassEntry("org.listed.One"),
                    loadableClassEntry("org.listed.Two"),
                    loadableClassEntry("org.listed.Unused"),
                ),
            )
        val lateJar = TestJars.write(dir.resolve("late-2.0.jar"), listOf(pom("org.late", "late", "2.0"), loadableClassEntry("org.late.A")))
        val registry = DependencyRegistry()
        Agent.runDependencyListing(StartupClasspathLister(emptyList(), emptyList(), listedJar.toString())::list, registry)
        val loader = URLClassLoader(arrayOf(listedJar.toUri().toURL(), lateJar.toUri().toURL()), null)
        val loaded = listOf("org.listed.One", "org.listed.Two", "org.late.A").map { Class.forName(it, true, loader) }
        val sweep =
            LoadedClassSweep(
                instrumentation,
                ProbeRegistry(),
                AgentConfig.parse(null),
                LoadedDependencyCounter(registry, emptyList(), emptyList()),
            )

        sweep.run(runForwardPass = false)
        Reference.reachabilityFence(loaded)

        // The test JVM's own classpath jars are counted too, as discovered by load; only the two fixtures matter here.
        val byArtifact =
            registry
                .entries()
                .filter { it.identities.size == 1 && it.identities.single().groupId in setOf("org.listed", "org.late") }
                .associateBy { it.identities.single().artifactId }
        val totals =
            registry
                .computeDeltas(100)
                .flatMap { it.deltas }
                .associate { it.dependencyId to it.loadedClassesTotal }
        assertEquals(2, totals[byArtifact.getValue("listed").dependencyId])
        assertEquals(1, totals[byArtifact.getValue("late").dependencyId])
        assertEquals(DependencyDiscoverySource.STARTUP_CLASSPATH, byArtifact.getValue("listed").discoverySource)
        assertEquals(DependencyDiscoverySource.LOAD, byArtifact.getValue("late").discoverySource)
    }
}
