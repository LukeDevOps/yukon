package io.github.lukedevops.yukon.instrumentation.branch

import net.bytebuddy.jar.asm.ClassReader
import net.bytebuddy.jar.asm.ClassVisitor
import net.bytebuddy.jar.asm.ClassWriter
import net.bytebuddy.jar.asm.Label
import net.bytebuddy.jar.asm.MethodVisitor
import net.bytebuddy.jar.asm.Opcodes
import org.jacoco.core.instr.Instrumenter
import org.jacoco.core.runtime.OfflineInstrumentationAccessGenerator
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConditionFingerprintTest {
    private fun readKotlinTargetBytes(simpleName: String): ByteArray =
        File("build/classes/kotlin/test/com/example/target/$simpleName.class").readBytes()

    private fun readJavaTargetBytes(simpleName: String): ByteArray =
        File("build/classes/java/test/com/example/target/$simpleName.class").readBytes()

    private fun sitesOf(
        bytes: ByteArray,
        methodName: String,
    ): List<BranchSite> = BranchSiteAnalyzer.analyze(bytes) { name, _ -> name == methodName }.sites

    // -- Stack-effect table --------------------------------------------------------------------

    @Test
    fun `LDC of a long is plus two`() {
        assertEquals(2, ConditionFingerprinter.stackEffect(ConditionFingerprinter.Insn.Ldc(5L)))
    }

    @Test
    fun `LDC of a double is plus two`() {
        assertEquals(2, ConditionFingerprinter.stackEffect(ConditionFingerprinter.Insn.Ldc(1.5)))
    }

    @Test
    fun `LDC of an int is plus one`() {
        assertEquals(1, ConditionFingerprinter.stackEffect(ConditionFingerprinter.Insn.Ldc(5)))
    }

    @Test
    fun `invokevirtual JI V on an object pops four`() {
        val insn = ConditionFingerprinter.Insn.MethodCall(Opcodes.INVOKEVIRTUAL, "com/example/Owner", "m", "(JI)V")
        assertEquals(-4, ConditionFingerprinter.stackEffect(insn))
    }

    @Test
    fun `invokestatic has no implicit receiver to pop`() {
        val insn = ConditionFingerprinter.Insn.MethodCall(Opcodes.INVOKESTATIC, "com/example/Owner", "m", "(I)V")
        assertEquals(-1, ConditionFingerprinter.stackEffect(insn))
    }

    @Test
    fun `dup2 is plus two`() {
        assertEquals(2, ConditionFingerprinter.stackEffect(ConditionFingerprinter.Insn.Plain(Opcodes.DUP2)))
    }

    @Test
    fun `getfield of a double is plus one, popping the object ref and pushing two`() {
        val insn = ConditionFingerprinter.Insn.Field(Opcodes.GETFIELD, "com/example/Owner", "d", "D")
        assertEquals(1, ConditionFingerprinter.stackEffect(insn))
    }

    @Test
    fun `putfield of a long pops the object ref and the two-word value`() {
        val insn = ConditionFingerprinter.Insn.Field(Opcodes.PUTFIELD, "com/example/Owner", "l", "J")
        assertEquals(-3, ConditionFingerprinter.stackEffect(insn))
    }

    @Test
    fun `getstatic of an int is plus one`() {
        val insn = ConditionFingerprinter.Insn.Field(Opcodes.GETSTATIC, "com/example/Owner", "i", "I")
        assertEquals(1, ConditionFingerprinter.stackEffect(insn))
    }

    @Test
    fun `iand pops two ints and pushes one`() {
        assertEquals(-1, ConditionFingerprinter.stackEffect(ConditionFingerprinter.Insn.Plain(Opcodes.IAND)))
    }

    @Test
    fun `land pops two longs and pushes one`() {
        assertEquals(-2, ConditionFingerprinter.stackEffect(ConditionFingerprinter.Insn.Plain(Opcodes.LAND)))
    }

    @Test
    fun `lcmp pops two longs and pushes one int`() {
        assertEquals(-3, ConditionFingerprinter.stackEffect(ConditionFingerprinter.Insn.Plain(Opcodes.LCMP)))
    }

    @Test
    fun `tableswitch and lookupswitch each pop the key`() {
        assertEquals(
            -1,
            ConditionFingerprinter.stackEffect(ConditionFingerprinter.Insn.TableSwitch(0, 1, Label(), arrayOf(Label(), Label()))),
        )
        assertEquals(
            -1,
            ConditionFingerprinter.stackEffect(ConditionFingerprinter.Insn.LookupSwitch(Label(), intArrayOf(1), arrayOf(Label()))),
        )
    }

    @Test
    fun `multianewarray pops one word per dimension and pushes the array ref`() {
        val insn = ConditionFingerprinter.Insn.MultiANewArray("[[I", 2)
        assertEquals(-1, ConditionFingerprinter.stackEffect(insn))
    }

    // -- Condition fingerprints over real fixtures ----------------------------------------------

    @Test
    fun `a simple condition on a parameter fingerprints to its exact text with no slot number`() {
        val site = sitesOf(readKotlinTargetBytes("FingerprintTargetKt"), "conditionOnX").single()

        assertEquals("ILOAD x;IFLE", site.conditionFingerprint)
    }

    @Test
    fun `an extra unrelated local before the condition does not change its fingerprint`() {
        val withExtra = sitesOf(readKotlinTargetBytes("FingerprintTargetKt"), "withExtraLocal").single()
        val without = sitesOf(readKotlinTargetBytes("FingerprintTargetKt"), "withoutExtraLocal").single()

        assertEquals(without.conditionFingerprint, withExtra.conditionFingerprint)
        assertNotNull(withExtra.conditionFingerprint)
    }

    @Test
    fun `renaming the condition's variable changes the fingerprint`() {
        val x = sitesOf(readKotlinTargetBytes("FingerprintTargetKt"), "conditionOnX").single()
        val y = sitesOf(readKotlinTargetBytes("FingerprintTargetKt"), "conditionOnY").single()

        assertNotEquals(x.conditionFingerprint, y.conditionFingerprint)
    }

    @Test
    fun `if (a and b) gives two different fingerprints, one per site`() {
        val sites = sitesOf(readKotlinTargetBytes("FingerprintTargetKt"), "andCondition")

        assertEquals(2, sites.size)
        assertEquals("ILOAD a;IFEQ", sites[0].conditionFingerprint)
        assertEquals("ILOAD b;IFEQ", sites[1].conditionFingerprint)
    }

    @Test
    fun `a ternary merge point inside the condition fingerprints non-null and deterministically`() {
        val first = sitesOf(readKotlinTargetBytes("FingerprintTargetKt"), "ternaryCondition")
        val second = sitesOf(readKotlinTargetBytes("FingerprintTargetKt"), "ternaryCondition")

        assertEquals(2, first.size)
        first.forEach { assertNotNull(it.conditionFingerprint) }
        assertEquals(first.map { it.conditionFingerprint }, second.map { it.conditionFingerprint })
    }

    @Test
    fun `a site right after an unconditional jump still fingerprints`() {
        val sites = sitesOf(readKotlinTargetBytes("FingerprintTargetKt"), "conditionAfterJump")

        assertEquals(2, sites.size)
        assertEquals("ILOAD a;IFEQ", sites[0].conditionFingerprint)
        assertEquals("ILOAD x;IFLE", sites[1].conditionFingerprint)
    }

    @Test
    fun `a site at a loop head reached only by fall-through still fingerprints`() {
        val sites = sitesOf(readKotlinTargetBytes("FingerprintTargetKt"), "conditionAtLoopHead")

        assertEquals(2, sites.size)
        sites.forEach { assertNotNull(it.conditionFingerprint) }
    }

    @Test
    fun `a condition inside a catch block fingerprints with depth one at the handler`() {
        val site = sitesOf(readKotlinTargetBytes("FingerprintTargetKt"), "conditionInCatch").single()

        assertEquals("ILOAD x;IFLE", site.conditionFingerprint)
    }

    @Test
    fun `a lambda's bootstrap handle name is excluded, so a differently numbered lambda still matches`() {
        val one = sitesOf(readKotlinTargetBytes("FingerprintTargetKt"), "lambdaConditionOne").single()
        val two = sitesOf(readKotlinTargetBytes("FingerprintTargetKt"), "lambdaConditionTwo").single()

        assertNotNull(one.conditionFingerprint)
        assertEquals(one.conditionFingerprint, two.conditionFingerprint)
    }

    @Test
    fun `a java condition mixing a field read and a parameter fingerprints non-null`() {
        val site = sitesOf(readJavaTargetBytes("FingerprintJavaTarget"), "overThreshold").single()

        assertNotNull(site.conditionFingerprint)
        assertTrue(site.conditionFingerprint!!.contains("GETFIELD"))
    }

    @Test
    fun `a java condition with two operands gives two distinguishable fingerprints`() {
        val sites = sitesOf(readJavaTargetBytes("FingerprintJavaTarget"), "bothSidesPositive")

        assertEquals(2, sites.size)
        assertNotEquals(sites[0].conditionFingerprint, sites[1].conditionFingerprint)
    }

    // -- Switches: case keys and fingerprints excluding case data -------------------------------

    @Test
    fun `a dense tableswitch's case keys match min plus index`() {
        val site = sitesOf(readJavaTargetBytes("BranchTarget"), "classifyDense").single()

        assertEquals(listOf(0, 1, 2), site.caseKeys)
        assertEquals(site.caseKeys!!.size + 1, site.outcomeCount)
    }

    @Test
    fun `a lookupswitch's case keys are its own key array`() {
        val site = sitesOf(readJavaTargetBytes("BranchTarget"), "classifySparse").single()

        assertEquals(listOf(1, 1000), site.caseKeys)
    }

    @Test
    fun `a sparse tableswitch with a filler entry excludes the filler from case keys`() {
        val site = sitesOf(readJavaTargetBytes("SwitchFillerTarget"), "classifyGappy").single()

        assertEquals(listOf(1, 2, 3, 5), site.caseKeys)
        assertEquals(site.caseKeys!!.size + 1, site.outcomeCount)
    }

    @Test
    fun `a conditional site carries no case keys`() {
        val site = sitesOf(readJavaTargetBytes("BranchTarget"), "classify").single()

        assertNull(site.caseKeys)
    }

    @Test
    fun `a switch's fingerprint excludes case keys, so adding a case leaves it unchanged`() {
        val dense = sitesOf(readJavaTargetBytes("BranchTarget"), "classifyDense").single()
        val gappy = sitesOf(readJavaTargetBytes("SwitchFillerTarget"), "classifyGappy").single()

        assertNotEquals(dense.caseKeys, gappy.caseKeys)
        assertEquals(dense.conditionFingerprint, gappy.conditionFingerprint)
    }

    // -- No LocalVariableTable -------------------------------------------------------------------

    /** Strips every `LocalVariableTable` entry from a class's bytes by dropping `visitLocalVariable` calls. */
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
    fun `without a LocalVariableTable, two same-typed locals fingerprint equal`() {
        val stripped = stripLocalVariableTable(readKotlinTargetBytes("FingerprintTargetKt"))

        val x = sitesOf(stripped, "conditionOnX").single()
        val y = sitesOf(stripped, "conditionOnY").single()

        assertNotNull(x.conditionFingerprint)
        assertEquals(x.conditionFingerprint, y.conditionFingerprint)
        assertEquals("ILOAD;IFLE", x.conditionFingerprint)
    }

    // -- Unknown depth --------------------------------------------------------------------------

    /**
     * Builds a method by hand whose only conditional jump sits right after dead code following a
     * `RETURN`, with no label recording a depth for it: the site's window would start at unknown
     * depth, so it must fingerprint null.
     */
    private fun classWithUnreachableConditionBytes(): ByteArray {
        val writer = ClassWriter(ClassWriter.COMPUTE_MAXS)
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "com/example/target/UnreachableConditionTarget", null, "java/lang/Object", null)
        val mv = writer.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "unreachable", "(I)I", null, null)
        mv.visitCode()
        mv.visitInsn(Opcodes.ICONST_0)
        mv.visitInsn(Opcodes.IRETURN)
        val deadLabel = Label()
        mv.visitLabel(deadLabel)
        mv.visitVarInsn(Opcodes.ILOAD, 0)
        val taken = Label()
        mv.visitJumpInsn(Opcodes.IFGT, taken)
        mv.visitInsn(Opcodes.ICONST_0)
        mv.visitInsn(Opcodes.IRETURN)
        mv.visitLabel(taken)
        mv.visitInsn(Opcodes.ICONST_1)
        mv.visitInsn(Opcodes.IRETURN)
        mv.visitMaxs(0, 0)
        mv.visitEnd()
        writer.visitEnd()
        return writer.toByteArray()
    }

    @Test
    fun `a site whose window would start at unknown depth fingerprints null`() {
        val site = sitesOf(classWithUnreachableConditionBytes(), "unreachable").single()

        assertNull(site.conditionFingerprint)
    }

    // -- JaCoCo-instrumented bytes ----------------------------------------------------------------

    private fun jacocoInstrument(bytes: ByteArray): ByteArray =
        Instrumenter(OfflineInstrumentationAccessGenerator()).instrument(bytes, "FingerprintTargetKt")

    @Test
    fun `JaCoCo-instrumented bytes still fingerprint every tracked site non-null`() {
        val plain = readKotlinTargetBytes("FingerprintTargetKt")
        val instrumented = jacocoInstrument(plain)

        val methods =
            listOf("conditionOnX", "andCondition", "ternaryCondition", "conditionAfterJump", "conditionAtLoopHead", "conditionInCatch")
        for (method in methods) {
            val sites = sitesOf(instrumented, method)
            assertTrue(sites.isNotEmpty(), "$method has sites")
            sites.forEach { site -> assertNotNull(site.conditionFingerprint, "$method site should fingerprint non-null under JaCoCo") }
        }
    }

    // -- Existing analyser behaviour is unchanged -------------------------------------------------

    @Test
    fun `site counts and indices are unaffected by fingerprinting`() {
        val analysis = BranchSiteAnalyzer.analyze(readJavaTargetBytes("BranchTarget")) { _, _ -> true }

        assertEquals(listOf(0, 1, 2), analysis.sites.map { it.siteIndex })
    }

    @Test
    fun `a coroutine-machinery or inlined-out-of-scope site still carries no fingerprint requirement change`() {
        // A dropped site (coroutine machinery, or an inlined copy out of scope) is still eligible
        // for a fingerprint: dropping and fingerprinting are independent. This class has neither
        // shape, so this simply confirms an ordinary dropped-free class keeps working end to end.
        val sites = sitesOf(readJavaTargetBytes("BranchTarget"), "classify")
        assertTrue(sites.none { it.dropReason != null })
    }
}
