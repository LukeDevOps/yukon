package io.github.lukedevops.yukon.instrumentation

import io.github.lukedevops.yukon.config.AgentConfig
import io.github.lukedevops.yukon.export.ProbeKind
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

class BranchInstrumentationTest {
    private var installedTransformer: ResettableClassFileTransformer? = null

    private fun loadFixtureFresh(): Any {
        val classesDir = File("build/classes/java/test")
        val loader = FixtureClassLoader(arrayOf(classesDir.toURI().toURL()), javaClass.classLoader)
        val targetClass = Class.forName("com.example.target.BranchTarget", true, loader)
        return targetClass.getDeclaredConstructor().newInstance()
    }

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
    fun `a conditional exercised only one way reports hits for one outcome and nothing for the other`() {
        val registry = ProbeRegistry()
        val config = AgentConfig.parse("includePackages=com.example.target")

        val target = install(registry, config)
        repeat(3) { target.javaClass.getMethod("classify", Int::class.java).invoke(target, 5) }

        val manifest = registry.manifest("test", null)
        val branchIndices = manifest.probes.filter { it.methodName == "classify" && it.kind == ProbeKind.BRANCH }.map { it.probeIndex }
        assertEquals(2, branchIndices.size)

        val deltas = registry.computeDeltaBatch(ResourceAttributes("test", null, "i-1", null)).deltas
        val byIndex = deltas.associateBy { it.probeIndex }

        val hitBranches = branchIndices.filter { it in byIndex }
        assertEquals(1, hitBranches.size, "exactly one of the two outcomes should ever have fired")
        assertEquals(3L, byIndex.getValue(hitBranches.single()).hitsSinceLastFlush)
    }

    @Test
    fun `exercising both outcomes of a conditional counts each independently`() {
        val registry = ProbeRegistry()
        val config = AgentConfig.parse("includePackages=com.example.target")

        val target = install(registry, config)
        repeat(2) { target.javaClass.getMethod("classify", Int::class.java).invoke(target, 5) }
        repeat(3) { target.javaClass.getMethod("classify", Int::class.java).invoke(target, -1) }

        val manifest = registry.manifest("test", null)
        val branchIndices = manifest.probes.filter { it.methodName == "classify" && it.kind == ProbeKind.BRANCH }.map { it.probeIndex }

        val deltas = registry.computeDeltaBatch(ResourceAttributes("test", null, "i-1", null)).deltas
        val byIndex = deltas.associateBy { it.probeIndex }

        val counts = branchIndices.map { byIndex.getValue(it).hitsSinceLastFlush }.sorted()
        assertEquals(listOf(2L, 3L), counts)
    }

    @Test
    fun `the method-entry probe for the branching method still fires independently of its branch probes`() {
        val registry = ProbeRegistry()
        val config = AgentConfig.parse("includePackages=com.example.target")

        val target = install(registry, config)
        target.javaClass.getMethod("classify", Int::class.java).invoke(target, 5)

        val manifest = registry.manifest("test", null)
        val methodProbeIndex = manifest.probes.single { it.methodName == "classify" && it.kind == ProbeKind.METHOD }.probeIndex

        val deltas = registry.computeDeltaBatch(ResourceAttributes("test", null, "i-1", null)).deltas
        val byIndex = deltas.associateBy { it.probeIndex }

        assertTrue(methodProbeIndex in byIndex)
        assertEquals(1L, byIndex.getValue(methodProbeIndex).hitsSinceLastFlush)
    }
}
