package io.github.lukedevops.yukon.instrumentation

import io.github.lukedevops.yukon.config.AgentConfig
import io.github.lukedevops.yukon.export.ResourceAttributes
import io.github.lukedevops.yukon.registry.ProbeRegistry
import net.bytebuddy.agent.ByteBuddyAgent
import net.bytebuddy.agent.builder.AgentBuilder
import net.bytebuddy.agent.builder.ResettableClassFileTransformer
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class YukonInstrumentationTest {
    private var installedTransformer: ResettableClassFileTransformer? = null

    private fun loadFixtureFresh(): Any {
        val classesDir = File("build/classes/java/test")
        val loader = FixtureClassLoader(arrayOf(classesDir.toURI().toURL()), javaClass.classLoader)
        val targetClass = Class.forName("com.example.target.SampleTarget", true, loader)
        return targetClass.getDeclaredConstructor().newInstance()
    }

    /**
     * Each test loads its own fresh definition of the fixture class, but the underlying
     * [java.lang.instrument.Instrumentation] instance is process-wide: a transformer left
     * registered from a previous test would also fire for the next test's fixture class load,
     * fighting over the same synthetic field name. [tearDown] deregisters it afterwards.
     */
    private fun install(
        registry: ProbeRegistry,
        config: AgentConfig,
    ): Any {
        val instrumentation = ByteBuddyAgent.install()
        installedTransformer = YukonInstrumentation(config, registry).install(instrumentation)
        return loadFixtureFresh()
    }

    @AfterTest
    fun tearDown() {
        installedTransformer?.reset(ByteBuddyAgent.install(), AgentBuilder.RedefinitionStrategy.DISABLED)
        installedTransformer = null
    }

    @Test
    fun `only the methods that were actually called show up in the next delta batch`() {
        val registry = ProbeRegistry()
        val config = AgentConfig.parse("includePackages=com.example.target")

        val target = install(registry, config)
        target.javaClass.getMethod("ping").invoke(target)

        val manifest = registry.manifest("test", null)
        val neverCalledProbeIndex = manifest.probes.single { it.methodName == "neverCalled" }.probeIndex
        val pingProbeIndex = manifest.probes.single { it.methodName == "ping" }.probeIndex

        val deltas = registry.computeDeltaBatch(ResourceAttributes("test", null, "i-1", null)).deltas
        val byIndex = deltas.associateBy { it.probeIndex }

        assertEquals(1L, byIndex.getValue(pingProbeIndex).hitsSinceLastFlush)
        assertTrue(neverCalledProbeIndex !in byIndex)
    }

    @Test
    fun `hits to two different methods on the same instance land in independent counters`() {
        val registry = ProbeRegistry()
        val config = AgentConfig.parse("includePackages=com.example.target")

        val target = install(registry, config)
        repeat(3) { target.javaClass.getMethod("ping").invoke(target) }
        repeat(2) { target.javaClass.getMethod("neverCalled").invoke(target) }

        val manifest = registry.manifest("test", null)
        val neverCalledProbeIndex = manifest.probes.single { it.methodName == "neverCalled" }.probeIndex
        val pingProbeIndex = manifest.probes.single { it.methodName == "ping" }.probeIndex

        val deltas = registry.computeDeltaBatch(ResourceAttributes("test", null, "i-1", null)).deltas
        val byIndex = deltas.associateBy { it.probeIndex }

        assertEquals(3L, byIndex.getValue(pingProbeIndex).hitsSinceLastFlush)
        assertEquals(2L, byIndex.getValue(neverCalledProbeIndex).hitsSinceLastFlush)
    }
}
