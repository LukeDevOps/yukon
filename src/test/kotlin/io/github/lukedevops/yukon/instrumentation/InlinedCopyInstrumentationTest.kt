package io.github.lukedevops.yukon.instrumentation

import io.github.lukedevops.yukon.config.AgentConfig
import io.github.lukedevops.yukon.export.ProbeKind
import io.github.lukedevops.yukon.export.ProbeLocation
import io.github.lukedevops.yukon.export.ResourceAttributes
import io.github.lukedevops.yukon.registry.ProbeRegistry
import net.bytebuddy.agent.ByteBuddyAgent
import net.bytebuddy.agent.builder.ResettableClassFileTransformer
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Proves the branch tier's inlined-copy drop and keep rule (ADR 0025) through the real transform
 * pipeline, on the `InlinedCopyTarget`/`InlineLibraryTarget` fixtures, rather than only through
 * [io.github.lukedevops.yukon.instrumentation.branch.InlinedCopyAnalysisTest]'s direct bytecode
 * checks.
 */
class InlinedCopyInstrumentationTest {
    private var installedTransformer: ResettableClassFileTransformer? = null
    private var installedYukon: YukonInstrumentation? = null

    private val fixtureFile = File("src/test/kotlin/com/example/target/InlinedCopyTarget.kt")
    private val fixtureLineCount = fixtureFile.readLines().size

    private fun fixtureLoader() =
        FixtureClassLoader(arrayOf(File("build/classes/kotlin/test").toURI().toURL()), javaClass.classLoader, "com.example.")

    private fun install(
        registry: ProbeRegistry,
        config: AgentConfig,
    ) {
        val instrumentation = ByteBuddyAgent.install()
        val yukon = YukonInstrumentation(config, registry)
        installedYukon = yukon
        installedTransformer = yukon.install(instrumentation)
    }

    /** Removes whatever [install] last set up, so a test can install a second, differently scoped registry. */
    private fun uninstall() {
        installedTransformer?.let { installedYukon?.uninstall(ByteBuddyAgent.install(), it) }
        installedTransformer = null
        installedYukon = null
    }

    @AfterTest
    fun tearDown() = uninstall()

    private fun branchProbesOf(
        registry: ProbeRegistry,
        methodName: String,
    ): List<ProbeLocation> =
        registry
            .manifest("test", null, "instance-1")
            .probes
            .filter {
                it.className == "com.example.target.InlinedCopyTargetKt" && it.kind == ProbeKind.BRANCH && it.methodName == methodName
            }.sortedBy { it.probeIndex }

    /** Installs, loads the fixture fresh under [includePackages], calls every function once, and returns its branch probes. */
    private fun runScope(includePackages: String): List<ProbeLocation> {
        val registry = ProbeRegistry()
        install(registry, AgentConfig.parse("includePackages=$includePackages"))
        val target = Class.forName("com.example.target.InlinedCopyTargetKt", true, fixtureLoader())
        target.getMethod("useCollections", List::class.java).invoke(null, listOf(1))
        target.getMethod("callTakingTrueBranch", Int::class.java).invoke(null, 1)
        target.getMethod("callTakingFalseBranch", Int::class.java).invoke(null, 1)
        target.getMethod("useLibraryInline", Int::class.java).invoke(null, 5)
        val probes = registry.manifest("test", null, "instance-1").probes.filter { it.kind == ProbeKind.BRANCH }
        uninstall()
        return probes
    }

    @Test
    fun `everything in scope- kept sites are the adopter's own conditionals plus every in-scope copy`() {
        val registry = ProbeRegistry()
        install(registry, AgentConfig.parse("includePackages=com.example"))

        val target = Class.forName("com.example.target.InlinedCopyTargetKt", true, fixtureLoader())
        target.getMethod("useCollections", List::class.java).invoke(null, listOf(20))
        target.getMethod("callTakingTrueBranch", Int::class.java).invoke(null, 1)
        target.getMethod("callTakingFalseBranch", Int::class.java).invoke(null, 1)
        target.getMethod("useLibraryInline", Int::class.java).invoke(null, 5)

        val allBranchProbes = registry.manifest("test", null, "instance-1").probes.filter { it.kind == ProbeKind.BRANCH }
        assertTrue(allBranchProbes.none { it.line > fixtureLineCount }, "no probe's line should exceed the fixture file's own line count")

        val useCollectionsPredicate = branchProbesOf(registry, "useCollections")
        assertEquals(
            2,
            useCollectionsPredicate.size,
            "only the adopter's own predicate conditional is kept; map/firstOrNull's own are dropped",
        )
        assertTrue(useCollectionsPredicate.all { it.inlinedFromClassName == null })

        val libraryCopy = branchProbesOf(registry, "useLibraryInline")
        assertEquals(2, libraryCopy.size, "the library's inline copy is kept, since com.example.library is in scope")
        assertTrue(libraryCopy.all { it.inlinedFromClassName == "com.example.library.InlineLibraryTargetKt" })
    }

    @Test
    fun `everything in scope- the two copies of the same-file inline function take different outcomes and land on the right slots`() {
        val registry = ProbeRegistry()
        install(registry, AgentConfig.parse("includePackages=com.example"))

        val target = Class.forName("com.example.target.InlinedCopyTargetKt", true, fixtureLoader())
        // n > 0, so flag=true inside sameFileInline: takes the not-taken (fallthrough) edge.
        target.getMethod("callTakingTrueBranch", Int::class.java).invoke(null, 1)
        // n <= 0, so flag=false inside sameFileInline: takes the taken edge.
        target.getMethod("callTakingFalseBranch", Int::class.java).invoke(null, 1)

        val deltas = registry.computeDeltaBatch(ResourceAttributes("test", null, "i-1", null)).batch.deltas
        val hitsByIndex = deltas.associate { it.probeIndex to it.hitsTotal }

        val trueBranchCopy = branchProbesOf(registry, "callTakingTrueBranch").filter { it.inlinedFromClassName != null }
        val falseBranchCopy = branchProbesOf(registry, "callTakingFalseBranch").filter { it.inlinedFromClassName != null }
        assertEquals(2, trueBranchCopy.size)
        assertEquals(2, falseBranchCopy.size)

        val trueBranchHits = trueBranchCopy.map { hitsByIndex[it.probeIndex] ?: 0L }.sorted()
        val falseBranchHits = falseBranchCopy.map { hitsByIndex[it.probeIndex] ?: 0L }.sorted()
        assertEquals(listOf(0L, 1L), trueBranchHits, "called once, so exactly one outcome fired")
        assertEquals(listOf(0L, 1L), falseBranchHits, "called once, so exactly one outcome fired")

        // Whichever of the true branch copy's two slots fired must be at the opposite position
        // (first vs second, by probeIndex order) from whichever of the false branch copy's two
        // slots fired, proving the two copies took different outcomes after slot compaction.
        val trueBranchFiredFirst = (hitsByIndex[trueBranchCopy[0].probeIndex] ?: 0L) == 1L
        val falseBranchFiredFirst = (hitsByIndex[falseBranchCopy[0].probeIndex] ?: 0L) == 1L
        assertTrue(trueBranchFiredFirst != falseBranchFiredFirst)
    }

    @Test
    fun `library out of scope- the library copy is gone, and every remaining branchIndex matches the everything-in-scope case`() {
        val everythingProbes = runScope("com.example")
        val targetOnlyProbes = runScope("com.example.target")

        assertNull(
            targetOnlyProbes.firstOrNull { it.inlinedFromClassName == "com.example.library.InlineLibraryTargetKt" },
            "the library's inline copy must be gone once com.example.library is out of scope",
        )
        assertEquals(
            everythingProbes.size - 2,
            targetOnlyProbes.size,
            "the probe count must differ by exactly the dropped site's two outcomes",
        )

        val everythingBranchIndexes = everythingProbes.map { Triple(it.className, it.methodName, it.branchIndex) }.toSet()
        val targetOnlyBranchIndexes = targetOnlyProbes.map { Triple(it.className, it.methodName, it.branchIndex) }.toSet()
        assertTrue(
            targetOnlyBranchIndexes.all { it in everythingBranchIndexes },
            "every remaining probe's branchIndex must be identical to the everything-in-scope case",
        )
    }
}
