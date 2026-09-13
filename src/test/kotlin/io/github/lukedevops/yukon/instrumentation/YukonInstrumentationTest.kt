package io.github.lukedevops.yukon.instrumentation

import io.github.lukedevops.yukon.config.AgentConfig
import io.github.lukedevops.yukon.export.ResourceAttributes
import io.github.lukedevops.yukon.instrumentation.staticscan.StaticBaselineMismatchDetector
import io.github.lukedevops.yukon.registry.ProbeRegistry
import net.bytebuddy.agent.ByteBuddyAgent
import net.bytebuddy.agent.builder.AgentBuilder
import net.bytebuddy.agent.builder.ResettableClassFileTransformer
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
     * Each test loads its own fresh definition of the fixture class. But the underlying
     * [java.lang.instrument.Instrumentation] instance is process-wide. A transformer left
     * registered from a previous test would also fire on the next test's fixture class load,
     * and both would fight over the same synthetic field name. [tearDown] deregisters the
     * transformer afterwards, to prevent that.
     */
    private fun install(
        registry: ProbeRegistry,
        config: AgentConfig,
        staticBaselineMismatchDetector: StaticBaselineMismatchDetector = StaticBaselineMismatchDetector(),
    ): Any {
        val instrumentation = ByteBuddyAgent.install()
        installedTransformer = YukonInstrumentation(config, registry, staticBaselineMismatchDetector).install(instrumentation)
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

        val manifest = registry.manifest("test", null, "instance-1")
        val neverCalledProbeIndex = manifest.probes.single { it.methodName == "neverCalled" }.probeIndex
        val pingProbeIndex = manifest.probes.single { it.methodName == "ping" }.probeIndex

        val deltas = registry.computeDeltaBatch(ResourceAttributes("test", null, "i-1", null)).deltas
        val byIndex = deltas.associateBy { it.probeIndex }

        assertEquals(1L, byIndex.getValue(pingProbeIndex).hitsTotal)
        assertTrue(neverCalledProbeIndex !in byIndex)
    }

    @Test
    fun `hits to two different methods on the same instance land in independent counters`() {
        val registry = ProbeRegistry()
        val config = AgentConfig.parse("includePackages=com.example.target")

        val target = install(registry, config)
        repeat(3) { target.javaClass.getMethod("ping").invoke(target) }
        repeat(2) { target.javaClass.getMethod("neverCalled").invoke(target) }

        val manifest = registry.manifest("test", null, "instance-1")
        val neverCalledProbeIndex = manifest.probes.single { it.methodName == "neverCalled" }.probeIndex
        val pingProbeIndex = manifest.probes.single { it.methodName == "ping" }.probeIndex

        val deltas = registry.computeDeltaBatch(ResourceAttributes("test", null, "i-1", null)).deltas
        val byIndex = deltas.associateBy { it.probeIndex }

        assertEquals(3L, byIndex.getValue(pingProbeIndex).hitsTotal)
        assertEquals(2L, byIndex.getValue(neverCalledProbeIndex).hitsTotal)
    }

    @Test
    fun `flags a class that registers dynamically but was absent from the static baseline`() {
        val registry = ProbeRegistry()
        val config = AgentConfig.parse("includePackages=com.example.target")
        val detector = StaticBaselineMismatchDetector()
        detector.knownDeclaredClassNames = emptySet()

        install(registry, config, detector)

        // shouldWarnAbout only returns true the first time a given class name is found missing.
        // If install() already consumed that for this class, this call must now return false.
        assertFalse(detector.shouldWarnAbout("com.example.target.SampleTarget"))
    }
}
