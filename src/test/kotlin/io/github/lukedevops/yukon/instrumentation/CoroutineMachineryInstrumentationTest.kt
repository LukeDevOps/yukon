package io.github.lukedevops.yukon.instrumentation

import io.github.lukedevops.yukon.config.AgentConfig
import io.github.lukedevops.yukon.export.ProbeKind
import io.github.lukedevops.yukon.export.ProbeLocation
import io.github.lukedevops.yukon.export.ResourceAttributes
import io.github.lukedevops.yukon.instrumentation.branch.BranchDropCounts
import io.github.lukedevops.yukon.instrumentation.branch.BranchDropReason
import io.github.lukedevops.yukon.registry.ProbeRegistry
import net.bytebuddy.agent.ByteBuddyAgent
import net.bytebuddy.agent.builder.ResettableClassFileTransformer
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Proves the branch tier's coroutine-machinery drop rule (ADR 0025) through the real transform
 * pipeline, on the `CoroutineTarget` fixture, rather than only through
 * [io.github.lukedevops.yukon.instrumentation.branch.CoroutineMachineryAnalysisTest]'s direct
 * bytecode checks.
 */
class CoroutineMachineryInstrumentationTest {
    private var installedTransformer: ResettableClassFileTransformer? = null
    private var installedYukon: YukonInstrumentation? = null

    private val fixtureFile = File("src/test/kotlin/com/example/target/CoroutineTarget.kt")

    private fun lineOf(marker: String): Int {
        val index = fixtureFile.readLines().indexOfFirst { it.contains("marker: $marker") }
        check(index >= 0) { "no line in $fixtureFile carries the marker $marker" }
        return index + 1
    }

    private fun fixtureLoader() =
        FixtureClassLoader(arrayOf(File("build/classes/kotlin/test").toURI().toURL()), javaClass.classLoader, "com.example.")

    private fun install(registry: ProbeRegistry) {
        val instrumentation = ByteBuddyAgent.install()
        val yukon = YukonInstrumentation(AgentConfig.parse("includePackages=com.example.target"), registry)
        installedYukon = yukon
        installedTransformer = yukon.install(instrumentation)
    }

    private fun uninstall() {
        installedTransformer?.let { installedYukon?.uninstall(ByteBuddyAgent.install(), it) }
        installedTransformer = null
        installedYukon = null
    }

    @AfterTest
    fun tearDown() = uninstall()

    private fun branchProbesOf(
        registry: ProbeRegistry,
        className: String,
        methodName: String,
    ): List<ProbeLocation> =
        registry
            .manifest(ResourceAttributes("test", null, "instance-1", null, "run-1"))
            .probes
            .filter { it.className == className && it.kind == ProbeKind.BRANCH && it.methodName == methodName }
            .sortedBy { it.probeIndex }

    @Test
    fun `a never-suspending call leaves one outcome of the adopter's own conditional hit, on the adopter's own line`() {
        val registry = ProbeRegistry()
        install(registry)
        val loader = fixtureLoader()
        val target = Class.forName("com.example.target.CoroutineTargetKt", true, loader)

        target.getMethod("runTwoPoints", Int::class.java).invoke(null, 5)

        val probes = branchProbesOf(registry, "com.example.target.CoroutineTargetKt", "twoPoints")
        assertEquals(2, probes.size, "one adopter site, two outcomes")
        assertTrue(probes.all { it.line == lineOf("twoPoints-if") })

        val deltas = registry.computeDeltaBatch(ResourceAttributes("test", null, "i-1", null, "run-1")).batch.deltas
        val hitsByIndex = deltas.associate { it.probeIndex to it.hitsTotal }
        val hits = probes.map { hitsByIndex[it.probeIndex] ?: 0L }.sorted()
        assertEquals(listOf(0L, 1L), hits, "called once, so exactly one outcome fired")
    }

    @Test
    fun `probes survive a real suspension and resumption`() {
        val registry = ProbeRegistry()
        install(registry)
        val loader = fixtureLoader()
        val target = Class.forName("com.example.target.CoroutineTargetKt", true, loader)

        target.getMethod("runTwoPointsSuspending", Int::class.java).invoke(null, 5)
        val probes = branchProbesOf(registry, "com.example.target.CoroutineTargetKt", "twoPointsSuspending")
        assertEquals(2, probes.size)

        // The adopter's conditional sits between the two pauseLater() suspension points, so it has
        // not run yet: the first pauseLater() call suspended before reaching it.
        val beforeResume = registry.computeDeltaBatch(ResourceAttributes("test", null, "i-1", null, "run-1")).batch.deltas
        val beforeHitsByIndex = beforeResume.associate { it.probeIndex to it.hitsTotal }
        assertEquals(
            listOf(0L, 0L),
            probes.map { beforeHitsByIndex[it.probeIndex] ?: 0L },
            "the adopter's if has not run yet: the first suspension point comes before it",
        )

        target.getMethod("resumePauseLater", Int::class.java).invoke(null, 7)

        val deltas = registry.computeDeltaBatch(ResourceAttributes("test", null, "i-1", null, "run-1")).batch.deltas
        val hitsByIndex = deltas.associate { it.probeIndex to it.hitsTotal }
        val hits = probes.map { hitsByIndex[it.probeIndex] ?: 0L }.sorted()
        assertEquals(listOf(0L, 1L), hits, "resuming ran the adopter's if exactly once")
    }

    @Test
    fun `a top-level suspend function's continuation class appears nowhere in the manifest`() {
        val registry = ProbeRegistry()
        install(registry)
        val loader = fixtureLoader()
        val target = Class.forName("com.example.target.CoroutineTargetKt", true, loader)

        target.getMethod("runTwoPoints", Int::class.java).invoke(null, 5)

        val manifest = registry.manifest(ResourceAttributes("test", null, "instance-1", null, "run-1"))
        val continuationName = "com.example.target.CoroutineTargetKt\$twoPoints\$1"
        assertTrue(manifest.probes.none { it.className == continuationName }, "no probes for the continuation class")
        assertTrue(manifest.skippedClasses.none { it.className == continuationName }, "not reported as skipped either")
    }

    @Test
    fun `a member suspend function's continuation class appears nowhere in the manifest`() {
        val registry = ProbeRegistry()
        install(registry)
        val loader = fixtureLoader()
        val target = Class.forName("com.example.target.CoroutineTargetKt", true, loader)

        target.getMethod("runMember", Int::class.java).invoke(null, 5)

        val manifest = registry.manifest(ResourceAttributes("test", null, "instance-1", null, "run-1"))
        val continuationName = "com.example.target.Holder\$member\$1"
        assertTrue(manifest.probes.none { it.className == continuationName })
        assertTrue(manifest.skippedClasses.none { it.className == continuationName })
    }

    @Test
    fun `a suspend lambda's own class is probed, with a METHOD probe for invokeSuspend and its one kept branch site`() {
        val registry = ProbeRegistry()
        install(registry)
        val loader = fixtureLoader()
        val target = Class.forName("com.example.target.CoroutineTargetKt", true, loader)

        target.getMethod("runLambda", Int::class.java).invoke(null, 5)

        val manifest = registry.manifest(ResourceAttributes("test", null, "instance-1", null, "run-1"))
        val lambdaClassName = "com.example.target.CoroutineTargetKt\$runLambda\$1"
        val methodProbe =
            manifest.probes.single { it.className == lambdaClassName && it.kind == ProbeKind.METHOD && it.methodName == "invokeSuspend" }
        assertEquals("invokeSuspend", methodProbe.methodName)

        val branchProbes = manifest.probes.filter { it.className == lambdaClassName && it.kind == ProbeKind.BRANCH }
        assertEquals(2, branchProbes.size, "one adopter site, two outcomes")
        assertTrue(branchProbes.all { it.line == lineOf("lambda-if") })
    }

    @Test
    fun `the branch-drop counts feeding the DEBUG and INFO summaries record coroutine machinery`() {
        val registry = ProbeRegistry()
        val branchDropCounts = BranchDropCounts()
        val instrumentation = ByteBuddyAgent.install()
        val yukon =
            YukonInstrumentation(AgentConfig.parse("includePackages=com.example.target"), registry, branchDropCounts = branchDropCounts)
        installedYukon = yukon
        installedTransformer = yukon.install(instrumentation)
        val loader = fixtureLoader()
        val target = Class.forName("com.example.target.CoroutineTargetKt", true, loader)

        target.getMethod("runTwoPoints", Int::class.java).invoke(null, 5)

        // Loading CoroutineTargetKt transforms every suspend-shaped method the file declares, not
        // only twoPoints, and calling runTwoPoints also loads its own suspend-lambda wrapper class:
        // twoPoints (5) + twoPointsSuspending (5) + compareRefs (4) + the wrapper's own two
        // machinery sites, with a zero-machinery adopter body, from CoroutineTargetKt$runTwoPoints$1.
        assertEquals(16, branchDropCounts.countOf(BranchDropReason.COROUTINE_MACHINERY))
        // CoroutineTargetKt (twoPoints, twoPointsSuspending, compareRefs, one record() call for
        // the whole class) and CoroutineTargetKt$runTwoPoints$1 (its own wrapper class): two
        // classes recorded a drop, not one per dropping method.
        assertEquals(2, branchDropCounts.classesWithDrops())
    }
}
