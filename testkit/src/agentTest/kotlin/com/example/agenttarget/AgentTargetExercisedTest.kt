package com.example.agenttarget

import dev.otherlode.testkit.OtherlodeTestCollector
import dev.otherlode.testkit.junit5.OtherlodeExtension
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.time.Duration
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Proves [OtherlodeExtension] against the real, `-javaagent`-attached shaded agent jar rather than
 * hand-built payloads. Runs first, before [AgentTargetSharedCollectorTest]: see this module's
 * own `junit-platform.properties`.
 */
@ExtendWith(OtherlodeExtension::class)
@Order(1)
class AgentTargetExercisedTest {
    @Test
    fun `wasHit reflects which method actually ran`(collector: OtherlodeTestCollector) {
        AgentTarget().exercised()

        collector.awaitSettled(Duration.ofSeconds(15))

        assertTrue(collector.wasHit("com.example.agenttarget.AgentTarget", "exercised"))
        assertFalse(collector.wasHit("com.example.agenttarget.AgentTarget", "neverCalled"))
    }
}
