package io.github.lukedevops.yukon.instrumentation

import io.github.lukedevops.yukon.config.AgentConfig
import io.github.lukedevops.yukon.export.ProbeKind
import io.github.lukedevops.yukon.export.ResourceAttributes
import io.github.lukedevops.yukon.instrumentation.branch.BranchSiteAnalyzer
import io.github.lukedevops.yukon.registry.ProbeRegistry
import net.bytebuddy.agent.ByteBuddyAgent
import net.bytebuddy.agent.builder.ResettableClassFileTransformer
import net.bytebuddy.dynamic.ClassFileLocator
import net.bytebuddy.pool.TypePool
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

        val manifest = registry.manifest(ResourceAttributes("test", null, "instance-1", null, "run-1"))
        val branchIndices = manifest.probes.filter { it.methodName == "classify" && it.kind == ProbeKind.BRANCH }.map { it.probeIndex }
        assertEquals(2, branchIndices.size)

        val deltas = registry.computeDeltaBatch(ResourceAttributes("test", null, "i-1", null, "run-1")).batch.deltas
        val byIndex = deltas.associateBy { it.probeIndex }

        val hitBranches = branchIndices.filter { it in byIndex }
        assertEquals(1, hitBranches.size, "exactly one of the two outcomes should ever have fired")
        assertEquals(3L, byIndex.getValue(hitBranches.single()).hitsTotal)
    }

    @Test
    fun `with no class-bytes capture, the branch analysis falls back to the class's own resource and still finds every site`() {
        val registry = ProbeRegistry()
        val config = AgentConfig.parse("includePackages=com.example.target")
        val yukon = YukonInstrumentation(config, registry, captureClassBytes = false)
        installedYukon = yukon
        installedTransformer = yukon.install(ByteBuddyAgent.install())

        val target = loadFixtureFresh()
        repeat(3) { target.javaClass.getMethod("classify", Int::class.java).invoke(target, 5) }

        val manifest = registry.manifest(ResourceAttributes("test", null, "instance-1", null, "run-1"))
        val branchIndices = manifest.probes.filter { it.methodName == "classify" && it.kind == ProbeKind.BRANCH }.map { it.probeIndex }
        assertEquals(2, branchIndices.size, "the resource read off the loader must yield the same two-outcome site")
        val deltas = registry.computeDeltaBatch(ResourceAttributes("test", null, "i-1", null, "run-1")).batch.deltas
        assertEquals(3L, deltas.single { it.probeIndex in branchIndices }.hitsTotal)
    }

    @Test
    fun `exercising both outcomes of a conditional counts each independently`() {
        val registry = ProbeRegistry()
        val config = AgentConfig.parse("includePackages=com.example.target")

        val target = install(registry, config)
        repeat(2) { target.javaClass.getMethod("classify", Int::class.java).invoke(target, 5) }
        repeat(3) { target.javaClass.getMethod("classify", Int::class.java).invoke(target, -1) }

        val manifest = registry.manifest(ResourceAttributes("test", null, "instance-1", null, "run-1"))
        val branchIndices = manifest.probes.filter { it.methodName == "classify" && it.kind == ProbeKind.BRANCH }.map { it.probeIndex }

        val deltas = registry.computeDeltaBatch(ResourceAttributes("test", null, "i-1", null, "run-1")).batch.deltas
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

        val manifest = registry.manifest(ResourceAttributes("test", null, "instance-1", null, "run-1"))
        val branchIndices =
            manifest.probes.filter { it.methodName == "classifyDense" && it.kind == ProbeKind.BRANCH }.map { it.probeIndex }
        assertEquals(4, branchIndices.size, "3 cases + 1 default")

        val deltas = registry.computeDeltaBatch(ResourceAttributes("test", null, "i-1", null, "run-1")).batch.deltas
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

        val manifest = registry.manifest(ResourceAttributes("test", null, "instance-1", null, "run-1"))
        val branchIndices =
            manifest.probes.filter { it.methodName == "classifyDense" && it.kind == ProbeKind.BRANCH }.map { it.probeIndex }

        val deltas = registry.computeDeltaBatch(ResourceAttributes("test", null, "i-1", null, "run-1")).batch.deltas
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

        val manifest = registry.manifest(ResourceAttributes("test", null, "instance-1", null, "run-1"))
        val branchIndices =
            manifest.probes.filter { it.methodName == "classifySparse" && it.kind == ProbeKind.BRANCH }.map { it.probeIndex }
        assertEquals(3, branchIndices.size, "2 cases + 1 default")

        val deltas = registry.computeDeltaBatch(ResourceAttributes("test", null, "i-1", null, "run-1")).batch.deltas
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

        val manifest = registry.manifest(ResourceAttributes("test", null, "instance-1", null, "run-1"))
        val classifyBranches =
            manifest.probes.filter { it.methodName == "classify" && it.kind == ProbeKind.BRANCH }.map { it.probeIndex }
        val denseBranches =
            manifest.probes.filter { it.methodName == "classifyDense" && it.kind == ProbeKind.BRANCH }.map { it.probeIndex }

        val deltas = registry.computeDeltaBatch(ResourceAttributes("test", null, "i-1", null, "run-1")).batch.deltas
        val byIndex = deltas.associateBy { it.probeIndex }

        val hitClassifyBranches = classifyBranches.filter { it in byIndex }
        assertEquals(1, hitClassifyBranches.size)
        assertEquals(1L, byIndex.getValue(hitClassifyBranches.single()).hitsTotal)

        val hitDenseBranches = denseBranches.filter { it in byIndex }
        assertEquals(1, hitDenseBranches.size)
        assertEquals(2L, byIndex.getValue(hitDenseBranches.single()).hitsTotal)
    }

    @Test
    fun `a value that lands on a tableswitch filler entry counts toward the default outcome`() {
        val registry = ProbeRegistry()
        val config = AgentConfig.parse("includePackages=com.example.target")
        install(registry, config)
        val loader = FixtureClassLoader(arrayOf(File("build/classes/java/test").toURI().toURL()), javaClass.classLoader)
        val targetClass = Class.forName("com.example.target.SwitchFillerTarget", true, loader)
        val target = targetClass.getDeclaredConstructor().newInstance()
        val method = targetClass.getMethod("classifyGappy", Int::class.java)

        assertEquals(-1, method.invoke(target, 4), "4 is a gap, so it takes the default")
        assertEquals(-1, method.invoke(target, 99), "99 is out of range, so it takes the default too")
        assertEquals(3, method.invoke(target, 3))

        val manifest = registry.manifest(ResourceAttributes("test", null, "instance-1", null, "run-1"))
        val branches = manifest.probes.filter { it.methodName == "classifyGappy" && it.kind == ProbeKind.BRANCH }
        assertEquals(5, branches.size, "four real cases plus one default; no probe for the filler")

        val classId = branches.first().classId
        val byIndex =
            registry
                .computeDeltaBatch(ResourceAttributes("test", null, "i-1", null, "run-1"))
                .batch.deltas
                .filter { it.classId == classId }
                .associateBy { it.probeIndex }
        val hits = branches.map { byIndex[it.probeIndex]?.hitsTotal ?: 0L }.sorted()
        assertEquals(listOf(0L, 0L, 0L, 1L, 2L), hits, "the filler (4) and the out-of-range value (99) both count as the default")
    }

    @Test
    fun `the method-entry probe for the branching method still fires independently of its branch probes`() {
        val registry = ProbeRegistry()
        val config = AgentConfig.parse("includePackages=com.example.target")

        val target = install(registry, config)
        target.javaClass.getMethod("classify", Int::class.java).invoke(target, 5)

        val manifest = registry.manifest(ResourceAttributes("test", null, "instance-1", null, "run-1"))
        val methodProbeIndex = manifest.probes.single { it.methodName == "classify" && it.kind == ProbeKind.METHOD }.probeIndex

        val deltas = registry.computeDeltaBatch(ResourceAttributes("test", null, "i-1", null, "run-1")).batch.deltas
        val byIndex = deltas.associateBy { it.probeIndex }

        assertTrue(methodProbeIndex in byIndex)
        assertEquals(1L, byIndex.getValue(methodProbeIndex).hitsTotal)
    }

    @Test
    fun `every kept BRANCH probe carries a branch key or null, unique within the class, and a METHOD probe carries none`() {
        val registry = ProbeRegistry()
        val config = AgentConfig.parse("includePackages=com.example.target")

        install(registry, config)

        val manifest = registry.manifest(ResourceAttributes("test", null, "instance-1", null, "run-1"))
        val classProbes = manifest.probes.filter { it.className == "com.example.target.BranchTarget" }
        val branchProbes = classProbes.filter { it.kind == ProbeKind.BRANCH }
        val methodProbes = classProbes.filter { it.kind == ProbeKind.METHOD }

        assertTrue(branchProbes.isNotEmpty())
        for (probe in branchProbes) {
            probe.branchKey?.let { key ->
                assertEquals(32, key.length, "$key should be 32 hex characters")
                assertTrue(key.all { it in "0123456789abcdef" }, "$key should be lowercase hex")
            }
        }
        val presentKeys = branchProbes.mapNotNull { it.branchKey }
        assertEquals(presentKeys.size, presentKeys.toSet().size, "every branch key present should be unique within the class")

        assertTrue(methodProbes.isNotEmpty())
        assertTrue(methodProbes.all { it.branchKey == null })
    }

    @Test
    fun `the layout hash of an existing fixture is unchanged by adding branch keys`() {
        // Recomputes ProbeLayoutHash.of() with the same inputs YukonInstrumentation feeds it for
        // BranchTarget, from a fresh read of its bytecode, and pins the result: adding branch_key
        // must not perturb branch_index, probe_index or slot allocation.
        val bytes = File("build/classes/java/test/com/example/target/BranchTarget.class").readBytes()
        val pool =
            TypePool.Default.of(
                ClassFileLocator.Compound(
                    ClassFileLocator.ForFolder(File("build/classes/java/test")),
                    ClassFileLocator.ForClassLoader.ofSystemLoader(),
                ),
            )
        val typeDescription = pool.describe("com.example.target.BranchTarget").resolve()
        val methods = typeDescription.declaredMethods.filter(TypeMatchPolicy.methodMatcher(isScalaClass = false))
        val analysis =
            BranchSiteAnalyzer.analyze(bytes) { name, descriptor ->
                methods.any { it.internalName == name && it.descriptor == descriptor }
            }

        val layoutHash =
            ProbeLayoutHash.of(
                methods.map { it.internalName + it.descriptor } +
                    analysis.sites
                        .filter { it.dropReason == null }
                        .map { "${it.methodName}${it.methodDescriptor}#branch${it.siteIndex}x${it.outcomeCount}" },
            )

        assertEquals(-6840610906673495688L, layoutHash)

        val registry = ProbeRegistry()
        val config = AgentConfig.parse("includePackages=com.example.target")
        val target = install(registry, config)
        assertEquals(
            methods.size + analysis.sites.filter { it.dropReason == null }.sumOf { it.outcomeCount },
            registry.lookup("com.example.target.BranchTarget", layoutHash, target.javaClass.classLoader)?.size,
            "the pinned hash must be the exact one the real transform registers BranchTarget's probe array under",
        )
    }
}
