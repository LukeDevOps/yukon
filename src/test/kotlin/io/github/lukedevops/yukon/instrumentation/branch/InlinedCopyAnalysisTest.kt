package io.github.lukedevops.yukon.instrumentation.branch

import net.bytebuddy.jar.asm.ClassReader
import net.bytebuddy.jar.asm.ClassWriter
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Proves the branch tier's drop-and-keep rule for an inlined copy (ADR 0025) against the
 * `InlinedCopyTarget`/`InlineLibraryTarget` fixtures. Every expected line and descriptor here was
 * checked against real `javap -v` output before this test was written, not guessed.
 */
class InlinedCopyAnalysisTest {
    private fun readTargetBytes(): ByteArray = File("build/classes/kotlin/test/com/example/target/InlinedCopyTargetKt.class").readBytes()

    private val fixtureFile = File("src/test/kotlin/com/example/target/InlinedCopyTarget.kt")
    private val libraryFile = File("src/test/kotlin/com/example/library/InlineLibraryTarget.kt")

    private val ownClassName = "com.example.target.InlinedCopyTargetKt"
    private val libraryClassName = "com.example.library.InlineLibraryTargetKt"

    /** The 1-based line of the first line in [file] carrying `// marker: [marker]`. */
    private fun lineOf(
        file: File,
        marker: String,
    ): Int {
        val index = file.readLines().indexOfFirst { it.contains("marker: $marker") }
        check(index >= 0) { "no line in $file carries the marker $marker" }
        return index + 1
    }

    private fun analyze(includePackages: List<String> = listOf("com.example")) =
        BranchSiteAnalyzer.analyze(readTargetBytes(), includePackages = includePackages) { _, _ -> true }

    @Test
    fun `site indices are contiguous across every tracked site, dropped or kept`() {
        val sites = analyze().sites

        assertEquals((0 until sites.size).toList(), sites.map { it.siteIndex })
    }

    @Test
    fun `map and firstOrNull's own internal conditionals are dropped as out-of-scope copies`() {
        val sites = analyze().sites.filter { it.methodName == "useCollections" }

        val dropped = sites.filter { it.dropReason != null }
        assertEquals(3, dropped.size, "map's hasNext loop, firstOrNull's hasNext loop, and firstOrNull's own predicate check")
        assertTrue(dropped.all { it.dropReason == BranchDropReason.INLINED_OUT_OF_SCOPE })
        assertTrue(dropped.all { it.inlinedFromClassName == null }, "a dropped site carries no origin")
    }

    @Test
    fun `the adopter's own predicate inside firstOrNull keeps its real line and is not a copy`() {
        val sites = analyze().sites.filter { it.methodName == "useCollections" }

        val own = sites.single { it.dropReason == null }
        assertEquals(lineOf(fixtureFile, "useCollections-predicate"), own.line)
        assertNull(own.inlinedFromClassName)
    }

    @Test
    fun `a same-file inline function's own compiled method is not itself a copy`() {
        val site = analyze().sites.single { it.methodName == "sameFileInline" }

        assertEquals(lineOf(fixtureFile, "sameFileInline-if"), site.line)
        assertNull(site.dropReason)
        assertNull(site.inlinedFromClassName)
    }

    @Test
    fun `two call sites of a same-file inline function are both kept and labelled with the class's own name`() {
        val expectedLine = lineOf(fixtureFile, "sameFileInline-if")
        val trueBranchCopy = analyze().sites.single { it.methodName == "callTakingTrueBranch" && it.inlinedFromClassName != null }
        val falseBranchCopy = analyze().sites.single { it.methodName == "callTakingFalseBranch" && it.inlinedFromClassName != null }

        assertEquals(expectedLine, trueBranchCopy.line)
        assertEquals(ownClassName, trueBranchCopy.inlinedFromClassName)
        assertNull(trueBranchCopy.dropReason)
        assertEquals(expectedLine, falseBranchCopy.line)
        assertEquals(ownClassName, falseBranchCopy.inlinedFromClassName)
        assertNull(falseBranchCopy.dropReason)
    }

    @Test
    fun `each caller's own comparison is kept as its own code, not a copy`() {
        val trueBranchOwn = analyze().sites.single { it.methodName == "callTakingTrueBranch" && it.inlinedFromClassName == null }
        val falseBranchOwn = analyze().sites.single { it.methodName == "callTakingFalseBranch" && it.inlinedFromClassName == null }

        assertEquals(lineOf(fixtureFile, "callTakingTrueBranch"), trueBranchOwn.line)
        assertEquals(lineOf(fixtureFile, "callTakingFalseBranch"), falseBranchOwn.line)
    }

    @Test
    fun `an inlined copy from an in-scope library is kept and labelled with the library's own class`() {
        val site = analyze(includePackages = listOf("com.example")).sites.single { it.methodName == "useLibraryInline" }

        assertNull(site.dropReason)
        assertEquals(lineOf(libraryFile, "libraryInline-if"), site.line)
        assertEquals(libraryClassName, site.inlinedFromClassName)
    }

    @Test
    fun `the same copy is dropped once its origin package is excluded from scope`() {
        val site = analyze(includePackages = listOf("com.example.target")).sites.single { it.methodName == "useLibraryInline" }

        assertEquals(BranchDropReason.INLINED_OUT_OF_SCOPE, site.dropReason)
        assertNull(site.inlinedFromClassName)
    }

    @Test
    fun `dropped ordinals are correct per method`() {
        val analysis = analyze()

        assertEquals(setOf(0, 1, 3), analysis.droppedOrdinalsOf("useCollections", "(Ljava/util/List;)Ljava/lang/Integer;"))
        assertEquals(emptySet(), analysis.droppedOrdinalsOf("sameFileInline", "(Z)I"))
        assertEquals(emptySet(), analysis.droppedOrdinalsOf("callTakingTrueBranch", "(I)I"))
        assertEquals(emptySet(), analysis.droppedOrdinalsOf("callTakingFalseBranch", "(I)I"))
    }

    @Test
    fun `useLibraryInline's single site is its own method's first and only dropped ordinal once its origin is out of scope`() {
        val analysis = analyze(includePackages = listOf("com.example.target"))

        assertEquals(setOf(0), analysis.droppedOrdinalsOf("useLibraryInline", "(I)I"))
    }

    /** Defines arbitrary bytes as a class, the way a JVM classloader would, without touching disk. */
    private class ByteArrayClassLoader(
        parent: ClassLoader,
    ) : ClassLoader(parent) {
        fun define(
            name: String,
            bytes: ByteArray,
        ): Class<*> = defineClass(name, bytes, 0, bytes.size)
    }

    @Test
    fun `stripped debug info produces no dropped sites and no lines`() {
        val writer = ClassWriter(0)
        ClassReader(readTargetBytes()).accept(writer, ClassReader.SKIP_DEBUG)
        val stripped = writer.toByteArray()

        // Confirm the stripped bytes are still a loadable class, not structurally-similar
        // garbage, the same reassurance BranchBytesCaptureTest gets from defining swapped bytes.
        ByteArrayClassLoader(javaClass.classLoader).define("com.example.target.InlinedCopyTargetKt", stripped)

        val analysis = BranchSiteAnalyzer.analyze(stripped, includePackages = listOf("com.example")) { _, _ -> true }

        assertTrue(analysis.sites.isNotEmpty(), "the same conditionals and switches are still found")
        assertTrue(analysis.sites.all { it.dropReason == null }, "no SMAP means nothing is recognised as a copy")
        assertTrue(analysis.sites.all { it.inlinedFromClassName == null })
        assertTrue(analysis.sites.all { it.line == -1 }, "no LineNumberTable means no line is ever recorded")
    }
}
