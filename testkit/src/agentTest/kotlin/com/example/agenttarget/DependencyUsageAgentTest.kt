package com.example.agenttarget

import io.github.lukedevops.yukon.testkit.DependencyUsage
import io.github.lukedevops.yukon.testkit.YukonTestCollector
import io.github.lukedevops.yukon.testkit.junit5.YukonExtension
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Proves the dependency queries against the real shaded agent: the suite's runtime classpath
 * carries two fixture jars, one this test calls into through [DependencyUser] and one nothing
 * loads. Runs after the other classes in this suite; see `junit-platform.properties`.
 */
@ExtendWith(YukonExtension::class)
@Order(3)
class DependencyUsageAgentTest {
    @Test
    fun `a startup dependency nothing loads is unloaded, and one a hit method calls is used`(collector: YukonTestCollector) {
        assertEquals("hello yukon", DependencyUser().greet())

        collector.awaitDependency("com.example.fixture", "dep-used", Duration.ofSeconds(30))
        collector.awaitDependency(null, "dep-unused", Duration.ofSeconds(30))
        collector.awaitDependenciesListed(Duration.ofSeconds(30))
        collector.awaitSettled(Duration.ofSeconds(15))

        val unused = collector.dependency(null, "dep-unused")
        assertEquals(DependencyUsage.UNLOADED, unused.status)
        assertEquals(0L, unused.loadedClassesTotal)
        assertTrue(unused in collector.unloadedDependencies())

        val used = collector.dependency("com.example.fixture", "dep-used")
        assertEquals(DependencyUsage.USED, used.status, used.toString())
        assertEquals(1L, used.loadedClassesTotal)
        assertEquals(1, used.classCount)

        // The suite runs with staticBaselineEnabled=true, so once the scan's chunks arrive the split
        // queries answer instead of throwing; neither fixture jar is unreferenced or unreached.
        val split = awaitSplit(Duration.ofSeconds(30)) { collector.unreferencedDependencies() + collector.unreachedDependencies() }
        val splitKeys = split.map { it.identityKey }
        assertTrue(":dep-unused" !in splitKeys && "com.example.fixture:dep-used" !in splitKeys, splitKeys.toString())
    }

    private fun <T> awaitSplit(
        timeout: Duration,
        query: () -> T,
    ): T {
        val deadline = System.nanoTime() + timeout.toNanos()
        while (true) {
            try {
                return query()
            } catch (notYet: IllegalStateException) {
                if (System.nanoTime() > deadline) throw notYet
                Thread.sleep(200)
            }
        }
    }
}
