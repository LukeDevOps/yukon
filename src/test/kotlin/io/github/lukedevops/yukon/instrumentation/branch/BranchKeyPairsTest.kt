package io.github.lukedevops.yukon.instrumentation.branch

import com.example.target.keypairs.fixtureLookup
import com.example.target.keypairs.javaFixtureBytes
import com.example.target.keypairs.keyedBuildsOf
import com.example.target.keypairs.kotlinFixtureBytes
import net.bytebuddy.jar.asm.ClassReader
import net.bytebuddy.jar.asm.ClassVisitor
import net.bytebuddy.jar.asm.ClassWriter
import net.bytebuddy.jar.asm.Label
import net.bytebuddy.jar.asm.MethodVisitor
import net.bytebuddy.jar.asm.Opcodes
import org.jacoco.core.instr.Instrumenter
import org.jacoco.core.runtime.OfflineInstrumentationAccessGenerator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Proves ADR 0031's "keeps or changes" rules against real v1/v2 fixture pairs under
 * `com.example.target.keypairs`, one edit per pair. Each pair's two classes are renamed to one
 * common name before analysis, since the key includes the class name and a service's own class
 * never renames itself between releases.
 */
class BranchKeyPairsTest {
    // -- A conditional added to an earlier method leaves a later method's keys alone -------------

    @Test
    fun `a conditional added to an earlier method keeps a later method's site keys, though its branch index shifts`() {
        val (v1, v2) = keyedBuildsOf("EarlierMethodEditV1", "EarlierMethodEditV2")

        assertEquals(v1.keyOf("second", outcome = 0), v2.keyOf("second", outcome = 0))
        assertEquals(v1.keyOf("second", outcome = 1), v2.keyOf("second", outcome = 1))
        assertNotNull(v1.keyOf("second", outcome = 0))

        assertNotEquals(
            v1.branchIndexOf("second", outcome = 0),
            v2.branchIndexOf("second", outcome = 0),
            "first gained a conditional of its own, so second's slots shift down by its outcome count",
        )
    }

    // -- Statements with no conditionals added before the condition ------------------------------

    @Test
    fun `a log call, a new local, and a reassignment added before the condition keep its key`() {
        val (v1, v2) = keyedBuildsOf("NoConditionalStatementsAddedV1", "NoConditionalStatementsAddedV2")

        assertEquals(v1.keyOf("check", outcome = 0), v2.keyOf("check", outcome = 0))
        assertEquals(v1.keyOf("check", outcome = 1), v2.keyOf("check", outcome = 1))
        assertNotNull(v1.keyOf("check", outcome = 0))
    }

    // -- A different conditional added earlier in the same method --------------------------------

    @Test
    fun `a different conditional added earlier in the same method keeps the existing site's key`() {
        val (v1, v2) = keyedBuildsOf("DifferentConditionalAddedEarlierV1", "DifferentConditionalAddedEarlierV2")

        val v1Site = v1.sites.single { it.methodName == "check" && it.conditionFingerprint?.contains("IFLE") == true }
        val v2Site = v2.sites.single { it.methodName == "check" && it.conditionFingerprint == v1Site.conditionFingerprint }
        assertNotEquals(v1Site.siteIndex, v2Site.siteIndex, "the new site earlier in the method shifts this one's siteIndex")

        assertEquals(v1.keyOf("check", 0, 0), v2.keyOf("check", 1, 0))
        assertEquals(v1.keyOf("check", 0, 1), v2.keyOf("check", 1, 1))
        assertNotNull(v1.keyOf("check", 0, 0))
    }

    // -- The condition itself edited --------------------------------------------------------------

    @Test
    fun `editing x greater than 0 to x greater than or equal 0 changes the key`() {
        val (v1, v2) = keyedBuildsOf("ConditionEditedV1", "ConditionEditedV2")

        assertNotNull(v1.keyOf("check", outcome = 0))
        assertNotNull(v2.keyOf("check", outcome = 0))
        assertNotEquals(v1.keyOf("check", outcome = 0), v2.keyOf("check", outcome = 0))
    }

    // -- The variable in the condition renamed ----------------------------------------------------

    @Test
    fun `renaming the condition's own variable changes the key`() {
        val (v1, v2) = keyedBuildsOf("VariableRenamedV1", "VariableRenamedV2")

        assertNotNull(v1.keyOf("check", outcome = 0))
        assertNotNull(v2.keyOf("check", outcome = 0))
        assertNotEquals(v1.keyOf("check", outcome = 0), v2.keyOf("check", outcome = 0))
    }

    // -- The method renamed, or its descriptor changed --------------------------------------------

    @Test
    fun `renaming the method changes the key`() {
        val (v1, v2) = keyedBuildsOf("MethodRenamedV1", "MethodRenamedV2")

        val v1Key = v1.keyOf("check", outcome = 0)
        val v2Key = v2.keyOf("verify", outcome = 0)
        assertNotNull(v1Key)
        assertNotNull(v2Key)
        assertNotEquals(v1Key, v2Key)
    }

    @Test
    fun `adding a parameter, which changes the descriptor, changes the key`() {
        val (v1, v2) = keyedBuildsOf("DescriptorChangedV1", "DescriptorChangedV2")

        val v1Key = v1.keyOf("check", outcome = 0)
        val v2Key = v2.keyOf("check", outcome = 0)
        assertNotNull(v1Key)
        assertNotNull(v2Key)
        assertNotEquals(v1Key, v2Key)
    }

    // -- An identical condition added to the same method ------------------------------------------

    @Test
    fun `an identical condition added to the same method loses both copies' keys, the existing one included`() {
        val (v1, v2) = keyedBuildsOf("IdenticalConditionAddedV1", "IdenticalConditionAddedV2")

        assertNotNull(v1.keyOf("check", 0, 0), "with only one copy, the site keeps a key")

        val v2Sites = v2.sites.filter { it.methodName == "check" }
        assertEquals(2, v2Sites.size, "the original condition plus its identical duplicate")
        v2Sites.indices.forEach { ordinal ->
            assertNull(v2.keyOf("check", ordinal, 0), "both identical copies must lose their key")
            assertNull(v2.keyOf("check", ordinal, 1))
        }
    }

    // -- A switch case added -----------------------------------------------------------------------

    @Test
    fun `a case added to a Kotlin when over Int keeps every existing case and default, and the new case gets its own key`() {
        val (v1, v2) = keyedBuildsOf("SwitchCaseAddedKotlinV1", "SwitchCaseAddedKotlinV2")

        val v1Site = v1.sites.single { it.methodName == "classify" }
        val v2Site = v2.sites.single { it.methodName == "classify" }
        assertEquals(listOf(1, 2), v1Site.caseKeys)
        assertEquals(listOf(1, 2, 3), v2Site.caseKeys)

        // Outcome offsets: 0 = case 1, 1 = case 2, 2 = default (v1); 0 = case 1, 1 = case 2, 2 = case 3, 3 = default (v2).
        assertEquals(v1.keyOf("classify", outcome = 0), v2.keyOf("classify", outcome = 0), "case 1 unchanged")
        assertEquals(v1.keyOf("classify", outcome = 1), v2.keyOf("classify", outcome = 1), "case 2 unchanged")
        assertEquals(v1.keyOf("classify", outcome = 2), v2.keyOf("classify", outcome = 3), "default unchanged, moved to the last offset")
        assertNotNull(v2.keyOf("classify", outcome = 2), "the new case 3 gets a key of its own")
    }

    @Test
    fun `a Java switch over int compiles to a real TABLESWITCH, and adding a case keeps every existing key`() {
        // javap -c confirms SwitchCaseAddedJavaV1#classify compiles to a TABLESWITCH: three dense,
        // consecutive case values. javac packs a two-case dense switch into a LOOKUPSWITCH
        // instead, which is why this fixture starts from three cases.
        val (v1, v2) =
            keyedBuildsOf(
                "SwitchCaseAddedJavaV1",
                "SwitchCaseAddedJavaV2",
                bytesOf = ::javaFixtureBytes,
            )

        val v1Site = v1.sites.single { it.methodName == "classify" }
        val v2Site = v2.sites.single { it.methodName == "classify" }
        assertEquals(listOf(1, 2, 3), v1Site.caseKeys)
        assertEquals(listOf(1, 2, 3, 4), v2Site.caseKeys)

        // Outcome offsets: 0/1/2 = cases 1/2/3, 3 = default (v1); 0/1/2/3 = cases 1/2/3/4, 4 = default (v2).
        assertEquals(v1.keyOf("classify", outcome = 0), v2.keyOf("classify", outcome = 0))
        assertEquals(v1.keyOf("classify", outcome = 1), v2.keyOf("classify", outcome = 1))
        assertEquals(v1.keyOf("classify", outcome = 2), v2.keyOf("classify", outcome = 2))
        assertEquals(v1.keyOf("classify", outcome = 3), v2.keyOf("classify", outcome = 4), "default unchanged, moved to the last offset")
        assertNotNull(v2.keyOf("classify", outcome = 3), "the new case 4 gets a key of its own")
    }

    // -- A case added to a string or enum switch read back to its source cases (ADR 0038) ---------

    @Test
    fun `a case added to a javac enum switch keeps every other case's key, though the map class moved from $1 to $2`() {
        val (v1, v2) = keyedBuildsOf("SwitchLabelsJavaV1", "SwitchLabelsJavaV2", bytesOf = ::javaFixtureBytes, lookup = ::fixtureLookup)
        val before = v1.keysByLabel("enumSwitch")
        val after = v2.keysByLabel("enumSwitch")

        assertEquals(listOf("RED", "BLUE", "default"), before.keys.toList())
        assertEquals(setOf("GREEN", "RED", "BLUE", "default"), after.keys)
        for (label in before.keys) assertEquals(assertNotNull(before[label]), after[label], "$label keeps its key")
        assertNotNull(after["GREEN"])
        assertEquals(assertNotNull(v1.siteKeyOf("enumSwitch")), v2.siteKeyOf("enumSwitch"))
    }

    @Test
    fun `a case added to a javac string switch keeps every other case's key`() {
        val (v1, v2) = keyedBuildsOf("SwitchLabelsJavaV1", "SwitchLabelsJavaV2", bytesOf = ::javaFixtureBytes, lookup = ::fixtureLookup)
        val before = v1.keysByLabel("stringSwitch")
        val after = v2.keysByLabel("stringSwitch")

        assertEquals(listOf("open", "closed", "default"), before.keys.toList())
        for (label in before.keys) assertEquals(assertNotNull(before[label]), after[label], "$label keeps its key")
        assertNotNull(after["new"])
        assertEquals(assertNotNull(v1.siteKeyOf("stringSwitch")), v2.siteKeyOf("stringSwitch"))
    }

    @Test
    fun `a case added to a javac pattern switch keeps every other case's key`() {
        val (v1, v2) = keyedBuildsOf("SwitchLabelsJavaV1", "SwitchLabelsJavaV2", bytesOf = ::javaFixtureBytes, lookup = ::fixtureLookup)
        val before = v1.keysByLabel("typeSwitch")
        val after = v2.keysByLabel("typeSwitch")

        assertEquals(listOf("String", "Long", "default"), before.keys.toList())
        for (label in before.keys) assertEquals(assertNotNull(before[label]), after[label], "$label keeps its key")
        assertNotNull(after["Integer"])
    }

    @Test
    fun `a case added to a kotlinc enum when keeps every other case's key, though every map value moved`() {
        val (v1, v2) = keyedBuildsOf("SwitchLabelsKotlinV1", "SwitchLabelsKotlinV2", lookup = ::fixtureLookup)
        val before = v1.keysByLabel("enumWhen")
        val after = v2.keysByLabel("enumWhen")

        assertEquals(listOf("RED", "BLUE", "default"), before.keys.toList())
        assertEquals(listOf("GREEN", "RED", "BLUE", "default"), after.keys.toList())
        for (label in before.keys) assertEquals(assertNotNull(before[label]), after[label], "$label keeps its key")
        assertEquals(assertNotNull(v1.siteKeyOf("enumWhen")), v2.siteKeyOf("enumWhen"))
    }

    // -- A Kotlin inline function called twice in one method ---------------------------------------

    @Test
    fun `an inline function called twice in one method loses both copies' keys, but once in each of two methods keeps and differs`() {
        val bytes = kotlinFixtureBytes("InlineKeyCollision")
        val analysis = BranchSiteAnalyzer.analyze(bytes, includePackages = listOf("com.example")) { _, _ -> true }
        val keys = BranchKeys.compute(analysis.sites, "com.example.target.keypairs.InlineKeyCollision")

        val twiceSites = analysis.sites.filter { it.methodName == "calledTwiceInOneMethod" && it.inlinedFromClassName != null }
        assertEquals(2, twiceSites.size, "isPositive is inlined once per call")
        twiceSites.forEach { site ->
            assertNull(keys[site.siteIndex to 0])
            assertNull(keys[site.siteIndex to 1])
        }

        val onceInA = analysis.sites.single { it.methodName == "calledOnceInMethodA" && it.inlinedFromClassName != null }
        val onceInB = analysis.sites.single { it.methodName == "calledOnceInMethodB" && it.inlinedFromClassName != null }
        val keyA = keys.getValue(onceInA.siteIndex to 0)
        val keyB = keys.getValue(onceInB.siteIndex to 0)
        assertNotEquals(keyA, keyB, "different methods, so no collision")
    }

    // -- An && condition where the second operand is edited ------------------------------------------

    @Test
    fun `editing the second operand of an && condition keeps the first site's key and changes the second's`() {
        val (v1, v2) = keyedBuildsOf("AndConditionSecondOperandEditedV1", "AndConditionSecondOperandEditedV2")

        val v1Sites = v1.sites.filter { it.methodName == "check" }
        assertEquals(2, v1Sites.size)

        assertEquals(v1.keyOf("check", 0, 0), v2.keyOf("check", 0, 0), "the first operand, a > 0, is unchanged")
        assertEquals(v1.keyOf("check", 0, 1), v2.keyOf("check", 0, 1))
        assertNotNull(v1.keyOf("check", 0, 0))

        assertNotEquals(v1.keyOf("check", 1, 0), v2.keyOf("check", 1, 0), "the second operand's own condition changed")
    }

    // -- A ternary inside a condition, edited before the stack empties -------------------------------

    @Test
    fun `an edit inside a condition before its stack last empties keeps the key, and one after changes it`() {
        val (v1, v2) = keyedBuildsOf("TernaryEditBeforeMergeV1", "TernaryEditBeforeMergeV2")
        val (_, v3) = keyedBuildsOf("TernaryEditBeforeMergeV1", "TernaryEditBeforeMergeV3")

        val v1Sites = v1.sites.filter { it.methodName == "check" }
        assertEquals(2, v1Sites.size, "the inner if, plus the outer comparison with zero")

        // Site 1 is the outer comparison. Its window starts at the inner if's false arm, so an
        // edit to the true arm stays outside it and an edit to the false arm lands inside it.
        assertNotNull(v1.keyOf("check", 1, 0))
        assertEquals(v1.keyOf("check", 1, 0), v2.keyOf("check", 1, 0))
        assertEquals(v1.keyOf("check", 1, 1), v2.keyOf("check", 1, 1))
        assertNotNull(v3.keyOf("check", 1, 0))
        assertNotEquals(v1.keyOf("check", 1, 0), v3.keyOf("check", 1, 0))
    }

    // -- Debug info stripped -------------------------------------------------------------------------

    /** Strips every `LocalVariableTable` entry from a class's bytes, as `ConditionFingerprintTest` does. */
    private fun stripLocalVariableTable(bytes: ByteArray): ByteArray {
        val writer = ClassWriter(0)
        val stripper =
            object : ClassVisitor(Opcodes.ASM9, writer) {
                override fun visitMethod(
                    access: Int,
                    name: String,
                    descriptor: String,
                    signature: String?,
                    exceptions: Array<out String>?,
                ): MethodVisitor {
                    val delegate = super.visitMethod(access, name, descriptor, signature, exceptions)
                    return object : MethodVisitor(Opcodes.ASM9, delegate) {
                        override fun visitLocalVariable(
                            localName: String,
                            localDescriptor: String,
                            localSignature: String?,
                            start: Label,
                            end: Label,
                            index: Int,
                        ) {
                            // Dropped: this is exactly what strips the table.
                        }
                    }
                }
            }
        ClassReader(bytes).accept(stripper, 0)
        return writer.toByteArray()
    }

    @Test
    fun `two conditions on different same-typed locals keep distinct keys with debug info, and lose both once it is stripped`() {
        val bytes = kotlinFixtureBytes("TwoLocalsSameType")

        val withTable = BranchSiteAnalyzer.analyze(bytes) { _, _ -> true }
        val withTableKeys = BranchKeys.compute(withTable.sites, "com.example.target.keypairs.TwoLocalsSameType")
        val (xSite, ySite) = withTable.sites.sortedBy { it.siteIndex }
        assertNotEquals(xSite.conditionFingerprint, ySite.conditionFingerprint)
        assertNotEquals(withTableKeys.getValue(xSite.siteIndex to 0), withTableKeys.getValue(ySite.siteIndex to 0))

        val stripped = stripLocalVariableTable(bytes)
        val withoutTable = BranchSiteAnalyzer.analyze(stripped) { _, _ -> true }
        val withoutTableKeys = BranchKeys.compute(withoutTable.sites, "com.example.target.keypairs.TwoLocalsSameType")
        val (xStripped, yStripped) = withoutTable.sites.sortedBy { it.siteIndex }
        assertEquals(xStripped.conditionFingerprint, yStripped.conditionFingerprint, "both read as a bare ILOAD without names")
        assertNull(withoutTableKeys[xStripped.siteIndex to 0])
        assertNull(withoutTableKeys[yStripped.siteIndex to 0])
    }

    // -- JaCoCo-instrumented input --------------------------------------------------------------------

    private fun jacocoInstrument(
        bytes: ByteArray,
        className: String,
    ): ByteArray = Instrumenter(OfflineInstrumentationAccessGenerator()).instrument(bytes, className)

    @Test
    fun `JaCoCo-instrumented bytes keep keys unique within the class, and never reuse a plain build's key for a different site`() {
        val plainBytes = kotlinFixtureBytes("MultiSiteFixture")
        val plain = BranchSiteAnalyzer.analyze(plainBytes) { _, _ -> true }
        val plainKeys = BranchKeys.compute(plain.sites, "com.example.target.keypairs.MultiSiteFixture")

        val instrumentedBytes = jacocoInstrument(plainBytes, "com/example/target/keypairs/MultiSiteFixture")
        val instrumented = BranchSiteAnalyzer.analyze(instrumentedBytes) { _, _ -> true }
        val instrumentedKeys = BranchKeys.compute(instrumented.sites, "com.example.target.keypairs.MultiSiteFixture")

        val instrumentedValues = instrumentedKeys.values.toList()
        assertEquals(instrumentedValues.size, instrumentedValues.toSet().size, "keys stay unique within the class")

        // The Q1 guarantee: an instrumented key never equals the plain build's key for a
        // different site, even though the plain and instrumented site sets need not correspond
        // one-to-one by siteIndex. Two keys sharing a (siteIndex, offset) is unremarkable and not
        // checked here; only cross-site equality would be a broken guarantee.
        for ((site, key) in instrumentedKeys) {
            for ((otherSite, otherPlainKey) in plainKeys) {
                if (otherSite == site) continue
                assertNotEquals(key, otherPlainKey, "instrumented key for $site must not equal plain key for a different site $otherSite")
            }
        }
    }

    // -- Determinism -----------------------------------------------------------------------------------

    @Test
    fun `analysing the same bytes twice gives identical key maps`() {
        val bytes = kotlinFixtureBytes("MultiSiteFixture")

        val first = BranchSiteAnalyzer.analyze(bytes) { _, _ -> true }
        val second = BranchSiteAnalyzer.analyze(bytes) { _, _ -> true }

        val firstKeys = BranchKeys.compute(first.sites, "com.example.target.keypairs.MultiSiteFixture")
        val secondKeys = BranchKeys.compute(second.sites, "com.example.target.keypairs.MultiSiteFixture")

        assertEquals(firstKeys, secondKeys)
    }
}
