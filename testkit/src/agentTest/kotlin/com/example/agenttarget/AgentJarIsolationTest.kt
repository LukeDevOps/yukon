package com.example.agenttarget

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The testkit brings the wire module onto a test classpath and nothing else of the agent's, so the
 * agent runs from its shaded `-javaagent` jar alone. Were the agent's own classes on the test
 * classpath, the system loader would find them ahead of the appended agent jar and the agent would
 * run unshaded, against whatever ByteBuddy and Kotlin versions the test classpath resolved.
 */
class AgentJarIsolationTest {
    @Test
    fun `the agent's entry point loads from the shaded agent jar, and its wire classes are its own`() {
        val loader = ClassLoader.getSystemClassLoader()
        val agentLocation =
            loader
                .loadClass("dev.otherlode.Agent")
                .protectionDomain.codeSource.location.path

        assertTrue(agentLocation.endsWith(".jar") && "-plain" !in agentLocation, agentLocation)
        assertEquals(
            agentLocation,
            loader
                .loadClass("dev.otherlode.shaded.export.ExportScheduler")
                .protectionDomain.codeSource.location.path,
            "the agent's exporter is relocated into its own jar",
        )
        assertNull(
            loader.getResource("dev/otherlode/export/ExportScheduler.class"),
            "the agent's unshaded exporter is on the test classpath",
        )
    }
}
