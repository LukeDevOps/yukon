package io.github.lukedevops.yukon.instrumentation.branch

import org.jacoco.core.instr.Instrumenter
import org.jacoco.core.runtime.OfflineInstrumentationAccessGenerator
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Proves the branch tier's coroutine-machinery drop rule (ADR 0025) against the `CoroutineTarget`
 * fixture. Every tracked site and instruction shape here was checked against real `javap -c -p`
 * output on Kotlin 2.2.21 before this test was written, not assumed from the design doc alone: the
 * suspended-marker compare (shape ii) turned out to compare a `DUP` of the suspension call's own
 * result against `ALOAD` of the tracked slot, not two `ALOAD`s of two different locals.
 */
class CoroutineMachineryAnalysisTest {
    private fun classBytes(simpleName: String): ByteArray =
        File("build/classes/kotlin/test/com/example/target/$simpleName.class").readBytes()

    private val facadeBytes = classBytes("CoroutineTargetKt")
    private val holderBytes = classBytes("Holder")
    private val lambdaBytes = classBytes("CoroutineTargetKt\$runLambda\$1")

    private val fixtureFile = File("src/test/kotlin/com/example/target/CoroutineTarget.kt")

    /** The 1-based line of the first line in [fixtureFile] carrying `// marker: [marker]`. */
    private fun lineOf(marker: String): Int {
        val index = fixtureFile.readLines().indexOfFirst { it.contains("marker: $marker") }
        check(index >= 0) { "no line in $fixtureFile carries the marker $marker" }
        return index + 1
    }

    private fun analyze(
        bytes: ByteArray,
        methodName: String,
    ) = BranchSiteAnalyzer.analyze(bytes) { name, _ -> name == methodName }

    private val twoPointsDescriptor = "(ILkotlin/coroutines/Continuation;)Ljava/lang/Object;"

    @Test
    fun `a top-level suspend function with two suspension points drops five machinery sites and keeps its own conditional`() {
        val sites = analyze(facadeBytes, "twoPoints").sites

        val dropped = sites.filter { it.dropReason != null }
        val kept = sites.filter { it.dropReason == null }
        assertEquals(5, dropped.size)
        assertTrue(dropped.all { it.dropReason == BranchDropReason.COROUTINE_MACHINERY })
        assertEquals(1, kept.size)
        assertEquals(lineOf("twoPoints-if"), kept.single().line)
    }

    @Test
    fun `a member suspend function drops four machinery sites and keeps its own conditional`() {
        val sites = analyze(holderBytes, "member").sites

        val dropped = sites.filter { it.dropReason != null }
        val kept = sites.filter { it.dropReason == null }
        assertEquals(4, dropped.size)
        assertTrue(dropped.all { it.dropReason == BranchDropReason.COROUTINE_MACHINERY })
        assertEquals(1, kept.size)
        assertEquals(lineOf("member-if"), kept.single().line)
    }

    @Test
    fun `a suspend lambda's invokeSuspend drops two machinery sites and keeps its own conditional`() {
        val sites = analyze(lambdaBytes, "invokeSuspend").sites

        val dropped = sites.filter { it.dropReason != null }
        val kept = sites.filter { it.dropReason == null }
        assertEquals(2, dropped.size)
        assertTrue(dropped.all { it.dropReason == BranchDropReason.COROUTINE_MACHINERY })
        assertEquals(1, kept.size)
        assertEquals(lineOf("lambda-if"), kept.single().line)
    }

    @Test
    fun `a suspend function with no suspension point drops nothing and keeps its own conditional`() {
        val sites = analyze(facadeBytes, "noPoint").sites

        assertTrue(sites.all { it.dropReason == null })
        assertEquals(1, sites.size)
        assertEquals(lineOf("noPoint-if"), sites.single().line)
    }

    @Test
    fun `dropped ordinals are the machinery sites' encounter order, per method`() {
        val analysis = BranchSiteAnalyzer.analyze(facadeBytes) { _, _ -> true }

        assertEquals(setOf(0, 1, 2, 3, 5), analysis.droppedOrdinalsOf("twoPoints", twoPointsDescriptor))
    }

    @Test
    fun `a same-shape switch and field in a non-suspend method is not mistaken for coroutine machinery`() {
        val sites = analyze(facadeBytes, "plainSwitchOnLabel").sites

        assertEquals(1, sites.size, "the switch itself, kept")
        assertNull(sites.single().dropReason)
    }

    @Test
    fun `a same-shape reference compare in a non-suspend method is not mistaken for coroutine machinery`() {
        val sites = analyze(facadeBytes, "plainReferenceCompare").sites

        assertEquals(1, sites.size)
        assertNull(sites.single().dropReason)
        assertEquals(lineOf("plainReferenceCompare-compare"), sites.single().line)
    }

    @Test
    fun `a suspend function's own reference compare on its own locals is kept, not mistaken for the suspended-marker check`() {
        val sites = analyze(facadeBytes, "compareRefs").sites

        val kept = sites.filter { it.dropReason == null }
        assertEquals(1, kept.size)
        assertEquals(lineOf("compareRefs-compare"), kept.single().line)
        assertEquals(4, sites.count { it.dropReason == BranchDropReason.COROUTINE_MACHINERY })
    }

    @Test
    fun `JaCoCo's inverted jumps still resolve the same drop and keep counts for a top-level suspend function`() {
        val instrumented = Instrumenter(OfflineInstrumentationAccessGenerator()).instrument(facadeBytes, "CoroutineTargetKt")

        val sites = analyze(instrumented, "twoPoints").sites

        val dropped = sites.filter { it.dropReason != null }
        val kept = sites.filter { it.dropReason == null }
        assertEquals(5, dropped.size)
        assertTrue(dropped.all { it.dropReason == BranchDropReason.COROUTINE_MACHINERY })
        assertEquals(1, kept.size)
    }

    @Test
    fun `JaCoCo's inverted jumps still resolve the same drop and keep counts for a suspend lambda`() {
        val instrumented =
            Instrumenter(OfflineInstrumentationAccessGenerator()).instrument(lambdaBytes, "CoroutineTargetKt\$runLambda\$1")

        val sites = analyze(instrumented, "invokeSuspend").sites

        val dropped = sites.filter { it.dropReason != null }
        val kept = sites.filter { it.dropReason == null }
        assertEquals(2, dropped.size)
        assertEquals(1, kept.size)
    }

    @Test
    fun `JaCoCo's inverted jumps still resolve the same drop and keep counts for a member suspend function`() {
        val instrumented = Instrumenter(OfflineInstrumentationAccessGenerator()).instrument(holderBytes, "Holder")

        val sites = analyze(instrumented, "member").sites

        val dropped = sites.filter { it.dropReason != null }
        val kept = sites.filter { it.dropReason == null }
        assertEquals(4, dropped.size)
        assertEquals(1, kept.size)
    }
}
