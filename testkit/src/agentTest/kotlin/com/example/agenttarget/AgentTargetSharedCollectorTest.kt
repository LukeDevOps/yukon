package com.example.agenttarget

import dev.otherlode.testkit.OtherlodeTestCollector
import dev.otherlode.testkit.junit5.OtherlodeExtension
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Runs after [AgentTargetExercisedTest] (see this module's `junit-platform.properties`) to prove
 * one [OtherlodeTestCollector] is shared for the life of the test JVM: [AgentTarget]'s manifest
 * entry, delivered once during the first test class, still answers a query here with no
 * `UnknownProbeException`.
 */
@ExtendWith(OtherlodeExtension::class)
@Order(2)
class AgentTargetSharedCollectorTest {
    @Test
    fun `the shared collector is still queryable, and is the same instance OtherlodeExtension hands out`(collector: OtherlodeTestCollector) {
        assertSame(OtherlodeExtension.collector(), collector)
        assertTrue(collector.wasHit("com.example.agenttarget.AgentTarget", "exercised"))
    }
}
