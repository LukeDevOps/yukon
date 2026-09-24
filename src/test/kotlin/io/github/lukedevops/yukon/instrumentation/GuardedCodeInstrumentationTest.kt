package io.github.lukedevops.yukon.instrumentation

import io.github.lukedevops.yukon.config.AgentConfig
import io.github.lukedevops.yukon.export.BranchOutcome
import io.github.lukedevops.yukon.export.BranchRole
import io.github.lukedevops.yukon.export.BranchSite
import io.github.lukedevops.yukon.export.CallEdge
import io.github.lukedevops.yukon.export.CallEdgeKind
import io.github.lukedevops.yukon.export.LineRange
import io.github.lukedevops.yukon.export.ProbeKind
import io.github.lukedevops.yukon.export.ProbeLocation
import io.github.lukedevops.yukon.export.ProbeManifest
import io.github.lukedevops.yukon.export.ResourceAttributes
import io.github.lukedevops.yukon.registry.ProbeRegistry
import net.bytebuddy.agent.ByteBuddyAgent
import net.bytebuddy.agent.builder.ResettableClassFileTransformer
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Proves through the real transform that each kept outcome lists the lines it guards, and that
 * each site and call edge names its guard. See ADR 0037.
 */
class GuardedCodeInstrumentationTest {
    private var installedTransformer: ResettableClassFileTransformer? = null
    private var installedYukon: YukonInstrumentation? = null
    private lateinit var manifest: ProbeManifest

    private val resource = ResourceAttributes("test", null, "instance-1", null, "run-1")
    private val kotlinSource = File("src/test/kotlin/com/example/target/GuardTarget.kt")
    private val inlineSource = File("src/test/kotlin/com/example/target/GuardInline.kt")
    private val javaSource = File("src/test/java/com/example/target/GuardSwitchTarget.java")

    @BeforeTest
    fun loadFixtures() {
        val registry = ProbeRegistry()
        val yukon = YukonInstrumentation(AgentConfig.parse("includePackages=com.example.target"), registry)
        installedYukon = yukon
        installedTransformer = yukon.install(ByteBuddyAgent.install())
        val kotlinLoader =
            FixtureClassLoader(arrayOf(File("build/classes/kotlin/test").toURI().toURL()), javaClass.classLoader, "com.example.")
        Class.forName("com.example.target.GuardTarget", true, kotlinLoader)
        Class.forName("com.example.target.GuardTargetKt", true, kotlinLoader)
        val javaLoader = FixtureClassLoader(arrayOf(File("build/classes/java/test").toURI().toURL()), javaClass.classLoader)
        Class.forName("com.example.target.GuardSwitchTarget", true, javaLoader)
        manifest = registry.manifest(resource)
    }

    @AfterTest
    fun tearDown() {
        installedTransformer?.let { installedYukon?.uninstall(ByteBuddyAgent.install(), it) }
        installedTransformer = null
        installedYukon = null
    }

    private fun methodProbe(
        methodName: String,
        className: String = "com.example.target.GuardTarget",
    ): ProbeLocation = manifest.probes.single { it.className == className && it.kind == ProbeKind.METHOD && it.methodName == methodName }

    private fun sites(
        methodName: String,
        className: String = "com.example.target.GuardTarget",
    ): List<BranchSite> = methodProbe(methodName, className).branchSites

    private fun calls(
        methodName: String,
        className: String = "com.example.target.GuardTarget",
    ): List<CallEdge> = methodProbe(methodName, className).calls

    private fun BranchSite.outcome(role: BranchRole): BranchOutcome = outcomes.single { it.role == role }

    /** The 1-based line of [source] that ends with `// marker: <marker>`. */
    private fun line(
        marker: String,
        source: File = kotlinSource,
    ): Int {
        val index = source.readLines().indexOfFirst { it.trimEnd().endsWith("// marker: $marker") }
        assertTrue(index >= 0, "no line in ${source.name} carries marker $marker")
        return index + 1
    }

    /** Every line [ranges] cover in [sourceFile]. */
    private fun linesIn(
        ranges: List<LineRange>,
        sourceFile: String = "GuardTarget.kt",
    ): Set<Int> = ranges.filter { it.sourceFile == sourceFile }.flatMap { it.firstLine..it.lastLine }.toSet()

    private fun BranchOutcome.guards(
        line: Int,
        sourceFile: String = "GuardTarget.kt",
    ): Boolean = line in linesIn(guardedLines, sourceFile)

    private fun BranchOutcome.touches(line: Int): Boolean = line in linesIn(guardedLines) || line in linesIn(partlyGuardedLines)

    @Test
    fun `an if-else's fall-through guards the then lines, its taken outcome the else lines, and neither the lines after the merge`() {
        val site = sites("ifElse").single()
        val taken = site.outcome(BranchRole.TAKEN)
        val fallThrough = site.outcome(BranchRole.FALL_THROUGH)

        assertTrue(fallThrough.guards(line("ifElse-then")))
        assertTrue(taken.guards(line("ifElse-else")))
        assertFalse(fallThrough.touches(line("ifElse-else")))
        assertFalse(taken.touches(line("ifElse-then")))
        assertFalse(fallThrough.touches(line("ifElse-after")))
        assertFalse(taken.touches(line("ifElse-after")))
        assertTrue((fallThrough.guardedLines + taken.guardedLines).all { it.sourceFile == "GuardTarget.kt" })
    }

    @Test
    fun `a blank line and a comment inside an arm do not split its guarded range`() {
        val fallThrough = sites("blankInArm").single().outcome(BranchRole.FALL_THROUGH)
        val first = line("blankInArm-first")
        val last = line("blankInArm-last")

        assertEquals(3, last - first, "the fixture keeps a blank line and a comment between the two calls")
        assertTrue(fallThrough.guardedLines.any { it.firstLine == first && it.lastLine == last })
    }

    @Test
    fun `the skip side of an if with no else guards nothing`() {
        val site = sites("ifOnly").single()

        assertEquals(emptyList(), site.outcome(BranchRole.TAKEN).guardedLines)
        assertEquals(emptyList(), site.outcome(BranchRole.TAKEN).partlyGuardedLines)
        assertTrue(site.outcome(BranchRole.FALL_THROUGH).guards(line("ifOnly-then")))
        assertFalse(site.outcome(BranchRole.FALL_THROUGH).touches(line("ifOnly-after")))
    }

    @Test
    fun `an elvis with return on one line leaves that line partly guarded by the null outcome`() {
        // kotlinc tests `ifnonnull`, so the fall-through is the null side that returns.
        val site = sites("elvis").single()
        val nullSide = site.outcome(BranchRole.FALL_THROUGH)
        val nonNullSide = site.outcome(BranchRole.TAKEN)

        assertTrue(line("elvis") in linesIn(nullSide.partlyGuardedLines))
        assertFalse(nullSide.guards(line("elvis")))
        assertTrue(line("elvis") in linesIn(nonNullSide.partlyGuardedLines))
        assertTrue(nonNullSide.guards(line("elvis-after")))
    }

    @Test
    fun `a nested site is guarded by the outer outcome, its call by the inner one, and the outer outcome guards the inner block`() {
        val (outer, inner) = sites("nested")
        val outerThen = outer.outcome(BranchRole.FALL_THROUGH)
        val innerThen = inner.outcome(BranchRole.FALL_THROUGH)

        assertNull(outer.guard)
        assertEquals(outerThen.branchIndex, inner.guard)
        assertEquals(innerThen.branchIndex, calls("nested").single { it.methodName == "sink" }.guard)
        assertTrue(outerThen.guards(line("nested-outer-then")))
        assertTrue(outerThen.guards(line("nested-inner-then")))
        assertTrue(innerThen.guards(line("nested-inner-then")))
        assertFalse(innerThen.touches(line("nested-outer-then")))
    }

    @Test
    fun `a call before the first branch and a call after a merge have no guard`() {
        val edges = calls("beforeAndAfter")

        assertNull(edges.single { it.methodName == "before" }.guard)
        assertNull(edges.single { it.methodName == "after" }.guard)
    }

    @Test
    fun `one callee called in both arms gives two edges with different guards, and twice in one arm gives one`() {
        val site = sites("bothArms").single()
        val shared = calls("bothArms").filter { it.methodName == "shared" }

        assertEquals(2, shared.size)
        assertEquals(
            setOf(site.outcome(BranchRole.TAKEN).branchIndex, site.outcome(BranchRole.FALL_THROUGH).branchIndex),
            shared.map { it.guard }.toSet(),
        )
    }

    @Test
    fun `a catch whose try straddles a branch has no guard, and a whole try-catch inside one arm is guarded by that arm`() {
        val straddling = sites("catchAcrossBranch").single()
        val straddlingCalls = calls("catchAcrossBranch")
        assertNull(straddlingCalls.single { it.methodName == "recover" }.guard)
        assertEquals(straddling.outcome(BranchRole.FALL_THROUGH).branchIndex, straddlingCalls.single { it.methodName == "risky" }.guard)

        val armThen = sites("catchInsideArm").single().outcome(BranchRole.FALL_THROUGH).branchIndex
        val armCalls = calls("catchInsideArm")
        assertEquals(armThen, armCalls.single { it.methodName == "risky" }.guard)
        assertEquals(armThen, armCalls.single { it.methodName == "recover" }.guard)
    }

    @Test
    fun `the outcome that enters a while loop's body guards the body lines`() {
        // kotlinc tests the loop condition at the top and jumps past the body when it is false.
        val site = sites("loop").single()
        val entersBody = site.outcome(BranchRole.FALL_THROUGH)

        assertTrue(entersBody.guards(line("loop-body")))
        assertTrue(entersBody.guards(line("loop-step")))
        assertFalse(site.outcome(BranchRole.TAKEN).touches(line("loop-body")))
        assertEquals(entersBody.branchIndex, calls("loop").single { it.methodName == "sink" }.guard)
    }

    @Test
    fun `each switch case guards its own body and the default guards the default body`() {
        val site = sites("select", "com.example.target.GuardSwitchTarget").single()
        val bodies = mapOf(1 to "select-one", 2 to "select-two", 5 to "select-five")

        for ((caseKey, marker) in bodies) {
            val outcome = site.outcomes.single { it.role == BranchRole.CASE && it.caseKey == caseKey }
            assertEquals(
                setOf(line(marker, javaSource)),
                linesIn(outcome.guardedLines, "GuardSwitchTarget.java") - linesOfBreaks(),
                "case $caseKey",
            )
        }
        val default = site.outcome(BranchRole.DEFAULT)
        assertTrue(line("select-default", javaSource) in linesIn(default.guardedLines, "GuardSwitchTarget.java"))
        assertTrue(
            site.outcomes.none {
                line("select-after", javaSource) in
                    linesIn(it.guardedLines + it.partlyGuardedLines, "GuardSwitchTarget.java")
            },
        )
    }

    /** The lines of [javaSource] that hold only `break;`, which each case also guards. */
    private fun linesOfBreaks(): Set<Int> =
        javaSource
            .readLines()
            .withIndex()
            .filter { it.value.trim() == "break;" }
            .map { it.index + 1 }
            .toSet()

    @Test
    fun `an in-scope inline function inside an arm adds its own lines in its own file, and a stdlib one adds none`() {
        val inlineThen = sites("inlineInArm").single().outcome(BranchRole.FALL_THROUGH)
        val helperLines = setOf(line("guardInlineHelper-body", inlineSource), line("guardInlineHelper-return", inlineSource))

        assertEquals(helperLines, linesIn(inlineThen.guardedLines, "GuardInline.kt"))
        assertTrue(inlineThen.guards(line("inlineInArm-then")))

        val lineCount = kotlinSource.readLines().size
        val stdlibSites = sites("stdlibInArm")
        val stdlibRanges = stdlibSites.flatMap { site -> site.outcomes.flatMap { it.guardedLines + it.partlyGuardedLines } }
        assertTrue(stdlibRanges.isNotEmpty())
        assertTrue(
            stdlibRanges.all { it.sourceFile == "GuardTarget.kt" && it.lastLine <= lineCount },
            "no range names stdlib code: $stdlibRanges",
        )
    }

    @Test
    fun `code after a suspension point inside an arm is guarded by the real outcome, never by machinery`() {
        val site = sites("suspendInArm", "com.example.target.GuardTargetKt").single()
        val then = site.outcome(BranchRole.FALL_THROUGH)
        val edges = calls("suspendInArm", "com.example.target.GuardTargetKt")

        assertNull(site.guard)
        assertTrue(then.guards(line("suspendInArm-after")))
        assertEquals(then.branchIndex, edges.single { it.methodName == "afterSuspension" }.guard)
        assertEquals(then.branchIndex, edges.single { it.methodName == "pauseNow" }.guard)
    }

    @Test
    fun `a lambda and an object expression made inside an arm carry that arm's guard`() {
        val lambdaSite = sites("lambdaInArm").single()
        val creations = calls("lambdaInArm").filter { it.kind == CallEdgeKind.CREATES }
        assertEquals(
            lambdaSite.outcome(BranchRole.FALL_THROUGH).branchIndex,
            creations.single { it.methodName == "lambdaInArm\$lambda\$0" }.guard,
        )
        assertEquals(lambdaSite.outcome(BranchRole.TAKEN).branchIndex, creations.single { it.methodName == "lambdaInArm\$lambda\$1" }.guard)

        val objectThen = sites("objectInArm").single().outcome(BranchRole.FALL_THROUGH).branchIndex
        val objectEdges = calls("objectInArm").filter { it.className == "com.example.target.GuardTarget\$objectInArm\$1" }
        assertEquals(setOf("<init>", "run"), objectEdges.map { it.methodName }.toSet())
        assertTrue(objectEdges.all { it.guard == objectThen }, "$objectEdges")
    }

    @Test
    fun `an edge that takes a pass-through's place keeps the guard of the call to the pass-through`() {
        val then = sites("defaultInArm").single().outcome(BranchRole.FALL_THROUGH)
        val target = calls("defaultInArm").single { it.methodName == "withDefault" }

        assertEquals("(I)I", target.methodDescriptor, "the call to withDefault\$default resolves to withDefault itself")
        assertEquals(then.branchIndex, target.guard)
    }

    @Test
    fun `a static field read of another in-scope class inside an arm gives its initializer edge that arm's guard`() {
        val then = sites("staticInArm").single().outcome(BranchRole.FALL_THROUGH)
        val initializer = calls("staticInArm").single { it.className == "com.example.target.GuardConfig" && it.methodName == "<clinit>" }

        assertEquals(then.branchIndex, assertNotNull(initializer.guard))
    }
}
