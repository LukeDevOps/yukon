package io.github.lukedevops.yukon.instrumentation.branch

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BranchSiteAnalyzerTest {
    private fun readFixtureBytes(): ByteArray = File("build/classes/java/test/com/example/target/BranchTarget.class").readBytes()

    @Test
    fun `finds the one conditional jump in a method with a single if`() {
        val sites = BranchSiteAnalyzer.analyze(readFixtureBytes()) { name, _ -> name == "classify" }

        assertEquals(1, sites.size)
        assertEquals("classify", sites.single().methodName)
        assertEquals(0, sites.single().siteIndex)
        assertTrue(sites.single().line > 0)
    }

    @Test
    fun `methods the filter rejects contribute no sites`() {
        val sites = BranchSiteAnalyzer.analyze(readFixtureBytes()) { _, _ -> false }

        assertEquals(emptyList(), sites)
    }

    @Test
    fun `site indices are assigned once per class, not reset per method`() {
        val sites = BranchSiteAnalyzer.analyze(readFixtureBytes()) { name, _ -> name == "classify" }

        assertEquals(listOf(0), sites.map { it.siteIndex })
    }

    @Test
    fun `a two-outcome conditional has an outcome count of two`() {
        val sites = BranchSiteAnalyzer.analyze(readFixtureBytes()) { name, _ -> name == "classify" }

        assertEquals(2, sites.single().outcomeCount)
    }

    @Test
    fun `a tableswitch is found with one outcome per case plus the default`() {
        val sites = BranchSiteAnalyzer.analyze(readFixtureBytes()) { name, _ -> name == "classifyDense" }

        assertEquals(1, sites.size)
        assertEquals("classifyDense", sites.single().methodName)
        assertEquals(4, sites.single().outcomeCount, "3 cases + 1 default")
    }

    @Test
    fun `a lookupswitch is found with one outcome per case plus the default`() {
        val sites = BranchSiteAnalyzer.analyze(readFixtureBytes()) { name, _ -> name == "classifySparse" }

        assertEquals(1, sites.size)
        assertEquals("classifySparse", sites.single().methodName)
        assertEquals(3, sites.single().outcomeCount, "2 cases + 1 default")
    }

    @Test
    fun `site indices accumulate across a conditional and a switch in the same class`() {
        val sites = BranchSiteAnalyzer.analyze(readFixtureBytes()) { name, _ -> name == "classify" || name == "classifyDense" }

        assertEquals(listOf(0, 1), sites.map { it.siteIndex })
    }
}
