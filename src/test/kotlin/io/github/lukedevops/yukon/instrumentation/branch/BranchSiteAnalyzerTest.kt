package io.github.lukedevops.yukon.instrumentation.branch

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BranchSiteAnalyzerTest {
    private fun readFixtureBytes(): ByteArray = File("build/classes/java/test/com/example/target/BranchTarget.class").readBytes()

    @Test
    fun `finds the one conditional jump in a method with a single if`() {
        val sites = BranchSiteAnalyzer.analyze(readFixtureBytes()) { name, _ -> name == "classify" }.sites

        assertEquals(1, sites.size)
        assertEquals("classify", sites.single().methodName)
        assertEquals(0, sites.single().siteIndex)
        assertTrue(sites.single().line > 0)
    }

    @Test
    fun `methods the filter rejects contribute no sites`() {
        val sites = BranchSiteAnalyzer.analyze(readFixtureBytes()) { _, _ -> false }.sites

        assertEquals(emptyList(), sites)
    }

    @Test
    fun `site indices are assigned once per class, not reset per method`() {
        val sites = BranchSiteAnalyzer.analyze(readFixtureBytes()) { name, _ -> name == "classify" }.sites

        assertEquals(listOf(0), sites.map { it.siteIndex })
    }

    @Test
    fun `a two-outcome conditional has an outcome count of two`() {
        val sites = BranchSiteAnalyzer.analyze(readFixtureBytes()) { name, _ -> name == "classify" }.sites

        assertEquals(2, sites.single().outcomeCount)
    }

    @Test
    fun `a tableswitch is found with one outcome per case plus the default`() {
        val sites = BranchSiteAnalyzer.analyze(readFixtureBytes()) { name, _ -> name == "classifyDense" }.sites

        assertEquals(1, sites.size)
        assertEquals("classifyDense", sites.single().methodName)
        assertEquals(4, sites.single().outcomeCount, "3 cases + 1 default")
    }

    @Test
    fun `a lookupswitch is found with one outcome per case plus the default`() {
        val sites = BranchSiteAnalyzer.analyze(readFixtureBytes()) { name, _ -> name == "classifySparse" }.sites

        assertEquals(1, sites.size)
        assertEquals("classifySparse", sites.single().methodName)
        assertEquals(3, sites.single().outcomeCount, "2 cases + 1 default")
    }

    @Test
    fun `site indices accumulate across a conditional and a switch in the same class`() {
        val sites = BranchSiteAnalyzer.analyze(readFixtureBytes()) { name, _ -> name == "classify" || name == "classifyDense" }.sites

        assertEquals(listOf(0, 1), sites.map { it.siteIndex })
    }

    @Test
    fun `a tableswitch filler entry that jumps to the default is counted as the default, not as a case`() {
        val bytes = File("build/classes/java/test/com/example/target/SwitchFillerTarget.class").readBytes()

        val sites = BranchSiteAnalyzer.analyze(bytes) { name, _ -> name == "classifyGappy" }.sites

        // Cases 1, 2, 3, 5 over a 1..5 table: four real cases plus the default, not five plus one.
        assertEquals(5, sites.single().outcomeCount)
    }

    @Test
    fun `records each analysed method's first source line`() {
        val analysis = BranchSiteAnalyzer.analyze(readFixtureBytes()) { _, _ -> true }

        val classify = analysis.firstLineOf("classify", "(I)Ljava/lang/String;")
        val dense = analysis.firstLineOf("classifyDense", "(I)I")
        assertTrue(classify > 0)
        assertTrue(dense > classify, "classifyDense is declared after classify")
        assertEquals(-1, analysis.firstLineOf("noSuchMethod", "()V"))
    }
}
