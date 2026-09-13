package io.github.lukedevops.yukon

import net.bytebuddy.agent.ByteBuddyAgent
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AgentTest {
    /**
     * Compares the thread names present before and after a call, rather than asserting an
     * absolute count. The test JVM can already be carrying `yukon-*` threads left by other test
     * classes in the same Gradle test worker, so an absolute "no such thread exists" assertion
     * would be flaky for reasons unrelated to this test.
     */
    private fun currentThreadNames(): Set<String> = Thread.getAllStackTraces().keys.mapTo(mutableSetOf()) { it.name }

    @Test
    fun `enabled=false starts no yukon threads at all`() {
        val instrumentation = ByteBuddyAgent.install()
        val before = currentThreadNames()

        val running = Agent.start("enabled=false", instrumentation)

        assertNull(running)
        val newThreadNames = currentThreadNames() - before
        assertTrue(newThreadNames.none { it.startsWith("yukon-") }, "unexpected new yukon- threads: $newThreadNames")
    }

    @Test
    fun `enabled (the default) starts the export scheduler`() {
        val instrumentation = ByteBuddyAgent.install()
        val before = currentThreadNames()

        // Scoped to a package nothing in this JVM ever loads, so the transformer never matches a
        // real class while it is installed. The interval keeps the scheduler from attempting a
        // flush before stop() removes it along with the shutdown hook, so the test JVM never
        // sends anything to a collector, at exit included.
        val running =
            Agent.start(
                "includePackages=io.github.lukedevops.yukon.neverloaded.fixture,flushIntervalSeconds=3600",
                instrumentation,
            )

        try {
            assertNotNull(running, "enabled must start the agent")
            val newThreadNames = currentThreadNames() - before
            assertTrue(newThreadNames.any { it.startsWith("yukon-") }, "expected a new yukon- thread, found: $newThreadNames")
        } finally {
            running?.stop()
        }
    }
}
