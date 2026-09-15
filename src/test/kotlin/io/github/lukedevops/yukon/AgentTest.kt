package io.github.lukedevops.yukon

import io.github.lukedevops.yukon.instrumentation.endpoints.api.AdviceBinder
import io.github.lukedevops.yukon.instrumentation.endpoints.api.EndpointModule
import net.bytebuddy.agent.ByteBuddyAgent
import net.bytebuddy.description.type.TypeDescription
import net.bytebuddy.dynamic.DynamicType
import net.bytebuddy.matcher.ElementMatcher
import net.bytebuddy.matcher.ElementMatchers
import kotlin.test.Test
import kotlin.test.assertEquals
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

    @Test
    fun `endpointsEnabled=false leaves Running's endpoint transformer null, and stop() still works`() {
        val instrumentation = ByteBuddyAgent.install()

        val running =
            Agent.start(
                "includePackages=io.github.lukedevops.yukon.neverloaded.fixture,flushIntervalSeconds=3600,endpointsEnabled=false",
                instrumentation,
            )

        try {
            assertNotNull(running)
            assertNull(running.endpointTransformer)
        } finally {
            running?.stop()
        }
    }

    @Test
    fun `endpointsEnabled defaults to true, leaving Running's endpoint transformer non-null, and stop() still works`() {
        val instrumentation = ByteBuddyAgent.install()

        val running =
            Agent.start(
                "includePackages=io.github.lukedevops.yukon.neverloaded.fixture,flushIntervalSeconds=3600",
                instrumentation,
            )

        try {
            assertNotNull(running)
            assertNotNull(running.endpointTransformer)
        } finally {
            running?.stop()
        }
    }

    @Test
    fun `filterEndpointModules drops the otel module unless otelBridgeEnabled, and keeps every other module either way`() {
        val otelModule = fakeEndpointModule("otel")
        val jdkModule = fakeEndpointModule("jdk-httpserver")
        val modules = listOf(jdkModule, otelModule)

        assertEquals(listOf(jdkModule), Agent.filterEndpointModules(modules, otelBridgeEnabled = false))
        assertEquals(listOf(jdkModule, otelModule), Agent.filterEndpointModules(modules, otelBridgeEnabled = true))
    }

    @Test
    fun `filterEndpointModules is a no-op when no module is named otel`() {
        val modules = listOf(fakeEndpointModule("jdk-httpserver"), fakeEndpointModule("spring-mvc"))

        assertEquals(modules, Agent.filterEndpointModules(modules, otelBridgeEnabled = false))
    }
}

/** A minimal [EndpointModule] whose only meaningful behaviour is its [EndpointModule.name]. */
private fun fakeEndpointModule(moduleName: String): EndpointModule =
    object : EndpointModule {
        override val name: String = moduleName

        override fun typeMatcher(): ElementMatcher<in TypeDescription> = ElementMatchers.any()

        override fun transform(
            builder: DynamicType.Builder<*>,
            typeDescription: TypeDescription,
            advice: AdviceBinder,
            classLoader: ClassLoader?,
        ): DynamicType.Builder<*> = builder
    }
