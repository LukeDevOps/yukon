package com.example.agenttarget

import io.github.lukedevops.yukon.testkit.YukonTestCollector
import io.github.lukedevops.yukon.testkit.junit5.YukonExtension
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Runs after [AgentTargetExercisedTest] (see this module's `junit-platform.properties`) to prove
 * one [YukonTestCollector] is shared for the life of the test JVM: [AgentTarget]'s manifest
 * entry, delivered once during the first test class, still answers a query here with no
 * `UnknownProbeException`.
 */
@ExtendWith(YukonExtension::class)
@Order(2)
class AgentTargetSharedCollectorTest {
    @Test
    fun `the shared collector is still queryable, and is the same instance YukonExtension hands out`(collector: YukonTestCollector) {
        assertSame(YukonExtension.collector(), collector)
        assertTrue(collector.wasHit("com.example.agenttarget.AgentTarget", "exercised"))
    }
}
