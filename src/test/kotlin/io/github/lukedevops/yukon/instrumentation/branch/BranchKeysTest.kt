package io.github.lukedevops.yukon.instrumentation.branch

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class BranchKeysTest {
    private fun conditionalSite(
        methodName: String = "m",
        methodDescriptor: String = "()Z",
        siteIndex: Int,
        fingerprint: String?,
        dropReason: BranchDropReason? = null,
        inlinedFromClassName: String? = null,
    ) = BranchSite(
        methodName = methodName,
        methodDescriptor = methodDescriptor,
        line = 1,
        siteIndex = siteIndex,
        outcomeCount = 2,
        dropReason = dropReason,
        inlinedFromClassName = inlinedFromClassName,
        conditionFingerprint = fingerprint,
        caseKeys = null,
    )

    @Test
    fun `distinct fingerprints in one method both get keys and the outcomes differ`() {
        val a = conditionalSite(siteIndex = 0, fingerprint = "fp-a")
        val b = conditionalSite(siteIndex = 1, fingerprint = "fp-b")
        val keys = BranchKeys.compute(listOf(a, b), "com.example.C")

        val aTaken = keys.getValue(0 to 0)
        val aFall = keys.getValue(0 to 1)
        val bTaken = keys.getValue(1 to 0)
        val bFall = keys.getValue(1 to 1)
        assertNotEquals(aTaken, aFall)
        assertNotEquals(aTaken, bTaken)
        assertNotEquals(aFall, bFall)
    }

    @Test
    fun `two sites sharing a fingerprint in one method get no key`() {
        val a = conditionalSite(siteIndex = 0, fingerprint = "fp-same")
        val b = conditionalSite(siteIndex = 1, fingerprint = "fp-same")
        val keys = BranchKeys.compute(listOf(a, b), "com.example.C")

        assertEquals(emptyMap(), keys)
    }

    @Test
    fun `the same fingerprint in two different methods gets keys that differ`() {
        val a = conditionalSite(methodName = "m1", siteIndex = 0, fingerprint = "fp-shared")
        val b = conditionalSite(methodName = "m2", siteIndex = 1, fingerprint = "fp-shared")
        val keys = BranchKeys.compute(listOf(a, b), "com.example.C")

        assertNotEquals(keys.getValue(0 to 0), keys.getValue(1 to 0))
        assertNotEquals(keys.getValue(0 to 1), keys.getValue(1 to 1))
    }

    @Test
    fun `a kept site colliding with a dropped site's fingerprint gets no key`() {
        val kept = conditionalSite(siteIndex = 0, fingerprint = "fp-collide")
        val dropped = conditionalSite(siteIndex = 1, fingerprint = "fp-collide", dropReason = BranchDropReason.INLINED_OUT_OF_SCOPE)
        val keys = BranchKeys.compute(listOf(kept, dropped), "com.example.C")

        assertEquals(emptyMap(), keys)
    }

    @Test
    fun `a kept inlined copy and own code with the same fingerprint but different origin both get keys`() {
        val ownCode = conditionalSite(siteIndex = 0, fingerprint = "fp-shared", inlinedFromClassName = null)
        val inlined = conditionalSite(siteIndex = 1, fingerprint = "fp-shared", inlinedFromClassName = "com.example.Origin")
        val keys = BranchKeys.compute(listOf(ownCode, inlined), "com.example.C")

        assertNotEquals(keys.getValue(0 to 0), keys.getValue(1 to 0))
    }

    @Test
    fun `a null fingerprint gets no key and costs no other site its key`() {
        val nullFp = conditionalSite(siteIndex = 0, fingerprint = null)
        val other = conditionalSite(siteIndex = 1, fingerprint = "fp-other")
        val keys = BranchKeys.compute(listOf(nullFp, other), "com.example.C")

        assertNull(keys[0 to 0])
        assertNull(keys[0 to 1])
        assertEquals(2, keys.size)
    }

    private fun switchSite(
        siteIndex: Int,
        fingerprint: String?,
        caseKeys: List<Int>?,
        outcomeCount: Int,
    ) = BranchSite(
        methodName = "s",
        methodDescriptor = "()I",
        line = 1,
        siteIndex = siteIndex,
        outcomeCount = outcomeCount,
        dropReason = null,
        inlinedFromClassName = null,
        conditionFingerprint = fingerprint,
        caseKeys = caseKeys,
    )

    @Test
    fun `switch case keys stay equal when a case is added, and default stays equal`() {
        val before = switchSite(siteIndex = 0, fingerprint = "fp-switch", caseKeys = listOf(1, 2), outcomeCount = 3)
        val after = switchSite(siteIndex = 0, fingerprint = "fp-switch", caseKeys = listOf(1, 2, 3), outcomeCount = 4)

        val beforeKeys = BranchKeys.compute(listOf(before), "com.example.C")
        val afterKeys = BranchKeys.compute(listOf(after), "com.example.C")

        assertEquals(beforeKeys.getValue(0 to 0), afterKeys.getValue(0 to 0))
        assertEquals(beforeKeys.getValue(0 to 1), afterKeys.getValue(0 to 1))
        assertEquals(beforeKeys.getValue(0 to 2), afterKeys.getValue(0 to 3))
    }

    @Test
    fun `a switch whose caseKeys size mismatches outcomeCount gets no key`() {
        val mismatched = switchSite(siteIndex = 0, fingerprint = "fp-switch", caseKeys = listOf(1, 2), outcomeCount = 4)
        val keys = BranchKeys.compute(listOf(mismatched), "com.example.C")

        assertEquals(emptyMap(), keys)
    }

    @Test
    fun `the digest pins one exact key for one fixed input`() {
        val site = conditionalSite(methodName = "target", methodDescriptor = "()Z", siteIndex = 0, fingerprint = "ICONST_1;IRETURN")
        val keys = BranchKeys.compute(listOf(site), "com.example.Fixed")

        assertEquals("102421f64c17d8b2d7315cabbecf108e", keys.getValue(0 to 0))
    }
}
