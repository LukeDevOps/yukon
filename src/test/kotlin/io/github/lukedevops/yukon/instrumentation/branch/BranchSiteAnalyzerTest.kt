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
        val sites = BranchSiteAnalyzer.analyze(readFixtureBytes()) { _, _ -> true }

        assertEquals(listOf(0), sites.map { it.siteIndex })
    }
}
