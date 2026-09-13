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
    private var installedYukon: YukonInstrumentation? = null

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
        val yukon = YukonInstrumentation(config, registry)
        installedYukon = yukon
        installedTransformer = yukon.install(instrumentation)
        return loadFixtureFresh()
    }

    @AfterTest
    fun tearDown() {
        installedTransformer?.let { installedYukon?.uninstall(ByteBuddyAgent.install(), it) }
        installedTransformer = null
        installedYukon = null
    }

    @Test
    fun `a conditional exercised only one way reports hits for one outcome and nothing for the other`() {
        val registry = ProbeRegistry()
        val config = AgentConfig.parse("includePackages=com.example.target")

        val target = install(registry, config)
        repeat(3) { target.javaClass.getMethod("classify", Int::class.java).invoke(target, 5) }

        val manifest = registry.manifest("test", null, "instance-1")
        val branchIndices = manifest.probes.filter { it.methodName == "classify" && it.kind == ProbeKind.BRANCH }.map { it.probeIndex }
        assertEquals(2, branchIndices.size)

        val deltas = registry.computeDeltaBatch(ResourceAttributes("test", null, "i-1", null)).batch.deltas
        val byIndex = deltas.associateBy { it.probeIndex }

        val hitBranches = branchIndices.filter { it in byIndex }
        assertEquals(1, hitBranches.size, "exactly one of the two outcomes should ever have fired")
        assertEquals(3L, byIndex.getValue(hitBranches.single()).hitsTotal)
    }

    @Test
    fun `exercising both outcomes of a conditional counts each independently`() {
        val registry = ProbeRegistry()
        val config = AgentConfig.parse("includePackages=com.example.target")

        val target = install(registry, config)
        repeat(2) { target.javaClass.getMethod("classify", Int::class.java).invoke(target, 5) }
        repeat(3) { target.javaClass.getMethod("classify", Int::class.java).invoke(target, -1) }

        val manifest = registry.manifest("test", null, "instance-1")
        val branchIndices = manifest.probes.filter { it.methodName == "classify" && it.kind == ProbeKind.BRANCH }.map { it.probeIndex }

        val deltas = registry.computeDeltaBatch(ResourceAttributes("test", null, "i-1", null)).batch.deltas
        val byIndex = deltas.associateBy { it.probeIndex }

        val counts = branchIndices.map { byIndex.getValue(it).hitsTotal }.sorted()
        assertEquals(listOf(2L, 3L), counts)
    }

    @Test
    fun `hitting one case of a tableswitch reports a hit for only that case and the default`() {
        val registry = ProbeRegistry()
        val config = AgentConfig.parse("includePackages=com.example.target")

        val target = install(registry, config)
        repeat(4) { target.javaClass.getMethod("classifyDense", Int::class.java).invoke(target, 1) }

        val manifest = registry.manifest("test", null, "instance-1")
        val branchIndices =
            manifest.probes.filter { it.methodName == "classifyDense" && it.kind == ProbeKind.BRANCH }.map { it.probeIndex }
        assertEquals(4, branchIndices.size, "3 cases + 1 default")

        val deltas = registry.computeDeltaBatch(ResourceAttributes("test", null, "i-1", null)).batch.deltas
        val byIndex = deltas.associateBy { it.probeIndex }

        val hitBranches = branchIndices.filter { it in byIndex }
        assertEquals(1, hitBranches.size, "only the exercised case should have fired")
        assertEquals(4L, byIndex.getValue(hitBranches.single()).hitsTotal)
    }

    @Test
    fun `exercising every case of a tableswitch, including the default, counts each independently`() {
        val registry = ProbeRegistry()
        val config = AgentConfig.parse("includePackages=com.example.target")

        val target = install(registry, config)
        val method = target.javaClass.getMethod("classifyDense", Int::class.java)
        repeat(1) { method.invoke(target, 0) }
        repeat(2) { method.invoke(target, 1) }
        repeat(3) { method.invoke(target, 2) }
        repeat(4) { method.invoke(target, 99) } // falls to default

        val manifest = registry.manifest("test", null, "instance-1")
        val branchIndices =
            manifest.probes.filter { it.methodName == "classifyDense" && it.kind == ProbeKind.BRANCH }.map { it.probeIndex }

        val deltas = registry.computeDeltaBatch(ResourceAttributes("test", null, "i-1", null)).batch.deltas
        val byIndex = deltas.associateBy { it.probeIndex }

        val counts = branchIndices.map { byIndex.getValue(it).hitsTotal }.sorted()
        assertEquals(listOf(1L, 2L, 3L, 4L), counts)
    }

    @Test
    fun `hitting one case of a lookupswitch reports a hit for only that case and the default`() {
        val registry = ProbeRegistry()
        val config = AgentConfig.parse("includePackages=com.example.target")

        val target = install(registry, config)
        repeat(5) { target.javaClass.getMethod("classifySparse", Int::class.java).invoke(target, 1000) }

        val manifest = registry.manifest("test", null, "instance-1")
        val branchIndices =
            manifest.probes.filter { it.methodName == "classifySparse" && it.kind == ProbeKind.BRANCH }.map { it.probeIndex }
        assertEquals(3, branchIndices.size, "2 cases + 1 default")

        val deltas = registry.computeDeltaBatch(ResourceAttributes("test", null, "i-1", null)).batch.deltas
        val byIndex = deltas.associateBy { it.probeIndex }

        val hitBranches = branchIndices.filter { it in byIndex }
        assertEquals(1, hitBranches.size, "only the exercised case should have fired")
        assertEquals(5L, byIndex.getValue(hitBranches.single()).hitsTotal)
    }

    @Test
    fun `a two-outcome conditional and a switch in the same class track independently`() {
        val registry = ProbeRegistry()
        val config = AgentConfig.parse("includePackages=com.example.target")

        val target = install(registry, config)
        target.javaClass.getMethod("classify", Int::class.java).invoke(target, 5)
        repeat(2) { target.javaClass.getMethod("classifyDense", Int::class.java).invoke(target, 2) }

        val manifest = registry.manifest("test", null, "instance-1")
        val classifyBranches =
            manifest.probes.filter { it.methodName == "classify" && it.kind == ProbeKind.BRANCH }.map { it.probeIndex }
        val denseBranches =
            manifest.probes.filter { it.methodName == "classifyDense" && it.kind == ProbeKind.BRANCH }.map { it.probeIndex }

        val deltas = registry.computeDeltaBatch(ResourceAttributes("test", null, "i-1", null)).batch.deltas
        val byIndex = deltas.associateBy { it.probeIndex }

        val hitClassifyBranches = classifyBranches.filter { it in byIndex }
        assertEquals(1, hitClassifyBranches.size)
        assertEquals(1L, byIndex.getValue(hitClassifyBranches.single()).hitsTotal)

        val hitDenseBranches = denseBranches.filter { it in byIndex }
        assertEquals(1, hitDenseBranches.size)
        assertEquals(2L, byIndex.getValue(hitDenseBranches.single()).hitsTotal)
    }

    @Test
    fun `the method-entry probe for the branching method still fires independently of its branch probes`() {
        val registry = ProbeRegistry()
        val config = AgentConfig.parse("includePackages=com.example.target")

        val target = install(registry, config)
        target.javaClass.getMethod("classify", Int::class.java).invoke(target, 5)

        val manifest = registry.manifest("test", null, "instance-1")
        val methodProbeIndex = manifest.probes.single { it.methodName == "classify" && it.kind == ProbeKind.METHOD }.probeIndex

        val deltas = registry.computeDeltaBatch(ResourceAttributes("test", null, "i-1", null)).batch.deltas
        val byIndex = deltas.associateBy { it.probeIndex }

        assertTrue(methodProbeIndex in byIndex)
        assertEquals(1L, byIndex.getValue(methodProbeIndex).hitsTotal)
    }
}
