package io.github.lukedevops.yukon.instrumentation.branch

import io.github.lukedevops.yukon.export.GeneratedBy
import io.github.lukedevops.yukon.instrumentation.JvmDefaultDisableFixtures
import net.bytebuddy.jar.asm.ClassReader
import net.bytebuddy.jar.asm.ClassWriter
import net.bytebuddy.jar.asm.Label
import net.bytebuddy.jar.asm.MethodVisitor
import net.bytebuddy.jar.asm.Opcodes
import org.jacoco.core.instr.Instrumenter
import org.jacoco.core.runtime.OfflineInstrumentationAccessGenerator
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BranchSiteAnalyzerTest {
    private companion object {
        const val DISABLED_INTERFACE_DESCRIPTOR = "Lcom/example/target/jvmdefaultdisable/DisabledDefaultInterface;"
        const val ASM_INTERFACE = "com/example/target/AsmShape"
        const val ASM_METHOD_DESCRIPTOR = "(L$ASM_INTERFACE;J)J"
    }

    private fun readFixtureBytes(): ByteArray = File("build/classes/java/test/com/example/target/BranchTarget.class").readBytes()

    private fun readInlineTargetBytes(simpleName: String): ByteArray =
        File("build/classes/kotlin/test/com/example/target/$simpleName.class").readBytes()

    private fun readJavaTargetBytes(simpleName: String): ByteArray =
        File("build/classes/java/test/com/example/target/$simpleName.class").readBytes()

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

    @Test
    fun `an inline member function is marked inline`() {
        val analysis = BranchSiteAnalyzer.analyze(readInlineTargetBytes("InlineTarget")) { _, _ -> true }

        assertTrue(analysis.isInline("member", "(II)I"))
    }

    @Test
    fun `an inline top-level function is marked inline`() {
        val analysis = BranchSiteAnalyzer.analyze(readInlineTargetBytes("InlineTargetKt")) { _, _ -> true }

        assertTrue(analysis.isInline("topLevelInline", "(I)I"))
    }

    @Test
    fun `a plain function is not marked inline`() {
        val member = BranchSiteAnalyzer.analyze(readInlineTargetBytes("InlineTarget")) { _, _ -> true }
        val topLevel = BranchSiteAnalyzer.analyze(readInlineTargetBytes("InlineTargetKt")) { _, _ -> true }

        assertFalse(member.isInline("plain", "(I)I"))
        assertFalse(topLevel.isInline("topLevelPlain", "(I)I"))
    }

    @Test
    fun `a non-inline overload that inlines a same-named sibling is not itself marked inline`() {
        val analysis = BranchSiteAnalyzer.analyze(readInlineTargetBytes("InlineTarget")) { _, _ -> true }

        // same(int) is not inline; it merely calls the inline same(int, int), whose inlined copy
        // plants the same "$i$f$same" local name, but only over a sub-range of same(int)'s code.
        assertFalse(analysis.isInline("same", "(I)I"))
        assertTrue(analysis.isInline("same", "(II)I"))
    }

    @Test
    fun `isInline is false for every method on an empty analysis`() {
        assertFalse(BranchSiteAnalyzer.Analysis.EMPTY.isInline("member", "(II)I"))
    }

    @Test
    fun `a method the filter rejects is not marked inline`() {
        val analysis = BranchSiteAnalyzer.analyze(readInlineTargetBytes("InlineTarget")) { name, _ -> name != "member" }

        assertFalse(analysis.isInline("member", "(II)I"))
    }

    /** A default site is found regardless of the filter, since `$default` methods are synthetic and so never eligible. */
    private fun analyzeDefaultSites(simpleName: String) = BranchSiteAnalyzer.analyze(readInlineTargetBytes(simpleName)) { _, _ -> false }

    @Test
    fun `a final class's multi-bit default method resolves its target with the right bits, names, and overridability`() {
        val analysis = analyzeDefaultSites("DefaultArgumentTarget")

        val site = analysis.defaultSites.single { it.defaultName == "f\$default" }
        assertEquals("f", site.targetName)
        assertEquals("(IILjava/lang/String;J)I", site.targetDescriptor)
        assertEquals(0b1110, site.optionalBits, "bits 1, 2, 3 for b, c, d; bit 0 (a) is required")
        assertFalse(site.overridable, "f is final, on a final class")
        assertFalse(site.higherMaskTested)
        assertEquals(mapOf(1 to "b", 2 to "c", 3 to "d"), site.parameterNames)
    }

    @Test
    fun `a default method already instrumented by JaCoCo still resolves its mask bits`() {
        // A coverage agent registered ahead of this one hands the transformer chain its own
        // output, in which every conditional jump is inverted around an inserted probe, so the
        // mask test ends in IFNE rather than the IFEQ kotlinc wrote. This is what an adopter's
        // test suite looks like with coverage switched on.
        val plain = analyzeDefaultSites("DefaultArgumentTarget").defaultSites.single { it.defaultName == "f\$default" }
        val instrumented =
            Instrumenter(OfflineInstrumentationAccessGenerator())
                .instrument(readInlineTargetBytes("DefaultArgumentTarget"), "DefaultArgumentTarget")

        val analysis = BranchSiteAnalyzer.analyze(instrumented) { _, _ -> false }

        val site = analysis.defaultSites.singleOrNull { it.defaultName == "f\$default" }
        assertEquals(plain.optionalBits, site?.optionalBits, "the same optional parameters must be found in JaCoCo's output")
        assertEquals(plain.parameterNames, site?.parameterNames)
    }

    @Test
    fun `a top-level function's default method resolves against its static target`() {
        val analysis = analyzeDefaultSites("DefaultArgumentTargetKt")

        val site = analysis.defaultSites.single { it.defaultName == "topLevelWithDefault\$default" }
        assertEquals("topLevelWithDefault", site.targetName)
        assertEquals(0b10, site.optionalBits)
        assertFalse(site.overridable, "a static top-level function is never overridable")
        assertEquals(mapOf(1 to "b"), site.parameterNames)
    }

    @Test
    fun `an extension function's receiver is excluded from the mask bit and the parameter name`() {
        val analysis = analyzeDefaultSites("DefaultArgumentTargetKt")

        val site = analysis.defaultSites.single { it.defaultName == "extWithDefault\$default" }
        assertEquals("extWithDefault", site.targetName)
        assertEquals(0b1, site.optionalBits, "n is value-parameter index 0; the receiver owns no bit")
        assertEquals(mapOf(0 to "n"), site.parameterNames)
    }

    @Test
    fun `a member extension function's receiver is excluded from the parameter name, the same as a top-level one`() {
        // Confirmed with javap on Kotlin 2.2.21 output: decorate$default tests `iload_3; iconst_1;
        // iand`, so mask bit 0 is `suffix`, while the target's LocalVariableTable has the receiver
        // `$this$decorate` at slot 1 and `suffix` at slot 2.
        val analysis = analyzeDefaultSites("MemberExtensionTarget")

        val site = analysis.defaultSites.single { it.defaultName == "decorate\$default" }
        assertEquals("decorate", site.targetName)
        assertEquals("(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;", site.targetDescriptor)
        assertEquals(0b1, site.optionalBits, "suffix is value-parameter index 0; the receiver owns no bit")
        assertEquals(mapOf(0 to "suffix"), site.parameterNames)
    }

    @Test
    fun `a mask bit whose constant only fits an LDC is found like the BIPUSH and SIPUSH ones`() {
        val analysis = analyzeDefaultSites("DefaultArgumentTargetKt")

        val site = analysis.defaultSites.single { it.defaultName == "sixteenDefaults\$default" }
        assertEquals(0xFFFF, site.optionalBits, "every one of the sixteen parameters is optional")
        assertEquals("p15", site.parameterNames[15])
        assertFalse(site.higherMaskTested, "sixteen parameters fit in one mask int")
    }

    @Test
    fun `a synthetic constructor resolves against the plain constructor it calls`() {
        val analysis = analyzeDefaultSites("ConstructedWithDefault")

        val site = analysis.defaultSites.single()
        assertEquals("<init>", site.defaultName)
        assertEquals("<init>", site.targetName)
        assertEquals("(II)V", site.targetDescriptor)
        assertEquals(0b10, site.optionalBits)
        assertFalse(site.overridable, "a constructor is never overridable")
        assertEquals(mapOf(1 to "b"), site.parameterNames)
    }

    @Test
    fun `an open method's default site is overridable`() {
        val analysis = analyzeDefaultSites("OpenBase")

        val site = analysis.defaultSites.single()
        assertEquals("greet", site.targetName)
        assertTrue(site.overridable, "greet is open, on a non-final class")
        assertEquals(mapOf(0 to "name"), site.parameterNames)
    }

    @Test
    fun `an interface method's default site lives on the interface and is overridable`() {
        val analysis = analyzeDefaultSites("Greeter")

        val site = analysis.defaultSites.single()
        assertEquals("greet", site.targetName)
        assertTrue(site.overridable, "an interface target is overridable")
        // greet itself is abstract, so it has no Code attribute and so no LocalVariableTable.
        assertEquals(mapOf(0 to ""), site.parameterNames)
    }

    @Test
    fun `an interface's DefaultImpls forwarding stub has no mask test and yields no default site`() {
        val analysis = analyzeDefaultSites("Greeter\$DefaultImpls")

        assertEquals(emptyList(), analysis.defaultSites)
    }

    @Test
    fun `a non-mask bitwise and on another local is not mistaken for a mask test`() {
        val analysis = analyzeDefaultSites("DefaultArgumentTargetKt")

        val site = analysis.defaultSites.single { it.defaultName == "withNonMaskAnd\$default" }
        assertEquals(0b10, site.optionalBits, "only b's real mask test counts; a's default-value \"and 4\" does not")
    }

    @Test
    fun `a JvmOverloads-generated overload does not confuse target resolution`() {
        val analysis = analyzeDefaultSites("OverloadsTarget")

        val site = analysis.defaultSites.single()
        assertEquals("withOverloads", site.targetName)
        assertEquals("(II)I", site.targetDescriptor, "the two-parameter method, not the one-parameter JvmOverloads overload")
    }

    @Test
    fun `a data class's copy$default is resolved like any other default site`() {
        val analysis = analyzeDefaultSites("DataTarget")

        val site = analysis.defaultSites.single { it.defaultName == "copy\$default" }
        assertEquals("copy", site.targetName)
        assertEquals(0b11, site.optionalBits)
    }

    @Test
    fun `a data class's component, copy, equals, hashCode, and toString are DATA_CLASS, its constructor and getters are not`() {
        val analysis = BranchSiteAnalyzer.analyze(readInlineTargetBytes("GeneratedPoint")) { _, _ -> true }

        assertEquals(GeneratedBy.DATA_CLASS, analysis.generatedBy("component1", "()I"))
        assertEquals(GeneratedBy.DATA_CLASS, analysis.generatedBy("component2", "()Ljava/lang/String;"))
        assertEquals(GeneratedBy.DATA_CLASS, analysis.generatedBy("copy", "(ILjava/lang/String;)Lcom/example/target/GeneratedPoint;"))
        assertEquals(GeneratedBy.DATA_CLASS, analysis.generatedBy("equals", "(Ljava/lang/Object;)Z"))
        assertEquals(GeneratedBy.DATA_CLASS, analysis.generatedBy("hashCode", "()I"))
        assertEquals(GeneratedBy.DATA_CLASS, analysis.generatedBy("toString", "()Ljava/lang/String;"))
        assertEquals(GeneratedBy.NONE, analysis.generatedBy("<init>", "(ILjava/lang/String;)V"))
        assertEquals(GeneratedBy.NONE, analysis.generatedBy("getX", "()I"))
        assertEquals(GeneratedBy.NONE, analysis.generatedBy("getY", "()Ljava/lang/String;"))
        assertEquals(GeneratedBy.NONE, analysis.generatedBy("custom", "()I"))
    }

    @Test
    fun `a hand-written toString on a data class is still marked DATA_CLASS`() {
        val analysis = BranchSiteAnalyzer.analyze(readInlineTargetBytes("GeneratedPointCustomToString")) { _, _ -> true }

        assertEquals(GeneratedBy.DATA_CLASS, analysis.generatedBy("toString", "()Ljava/lang/String;"))
    }

    @Test
    fun `a class that hand-writes copy and component1 but no equals, hashCode, or toString marks nothing`() {
        val analysis = BranchSiteAnalyzer.analyze(readInlineTargetBytes("HandWrittenCopy")) { _, _ -> true }

        assertEquals(GeneratedBy.NONE, analysis.generatedBy("copy", "(I)Lcom/example/target/HandWrittenCopy;"))
        assertEquals(GeneratedBy.NONE, analysis.generatedBy("component1", "()I"))
        assertEquals(GeneratedBy.NONE, analysis.generatedBy("<init>", "(I)V"))
        assertEquals(GeneratedBy.NONE, analysis.generatedBy("getV", "()I"))
    }

    @Test
    fun `an enum's values, valueOf, and getEntries are marked ENUM, its constructor and type initializer are not`() {
        val analysis = BranchSiteAnalyzer.analyze(readInlineTargetBytes("GeneratedColour")) { _, _ -> true }

        assertEquals(GeneratedBy.ENUM, analysis.generatedBy("values", "()[Lcom/example/target/GeneratedColour;"))
        assertEquals(GeneratedBy.ENUM, analysis.generatedBy("valueOf", "(Ljava/lang/String;)Lcom/example/target/GeneratedColour;"))
        assertEquals(GeneratedBy.ENUM, analysis.generatedBy("getEntries", "()Lkotlin/enums/EnumEntries;"))
        assertEquals(GeneratedBy.NONE, analysis.generatedBy("<init>", "()V"))
        assertEquals(GeneratedBy.NONE, analysis.generatedBy("<clinit>", "()V"))
    }

    @Test
    fun `a Java enum's values and valueOf are marked ENUM, with no getEntries to mark`() {
        val analysis = BranchSiteAnalyzer.analyze(readJavaTargetBytes("JavaColour")) { _, _ -> true }

        assertEquals(GeneratedBy.ENUM, analysis.generatedBy("values", "()[Lcom/example/target/JavaColour;"))
        assertEquals(GeneratedBy.ENUM, analysis.generatedBy("valueOf", "(Ljava/lang/String;)Lcom/example/target/JavaColour;"))
        assertEquals(GeneratedBy.NONE, analysis.generatedBy("getEntries", "()Lkotlin/enums/EnumEntries;"))
    }

    @Test
    fun `a default-mode DefaultImpls method that only forwards to the interface is marked DEFAULT_IMPLS`() {
        val analysis = BranchSiteAnalyzer.analyze(readInlineTargetBytes("GeneratedInterface\$DefaultImpls")) { _, _ -> true }

        assertEquals(GeneratedBy.DEFAULT_IMPLS, analysis.generatedBy("withBody", "(Lcom/example/target/GeneratedInterface;)I"))
        assertEquals(
            GeneratedBy.DEFAULT_IMPLS,
            analysis.generatedBy("getLabel", "(Lcom/example/target/GeneratedInterface;)Ljava/lang/String;"),
        )
    }

    @Test
    fun `a disable-mode DefaultImpls method holding the interface method's real body is NONE`() {
        val analysis =
            BranchSiteAnalyzer.analyze(JvmDefaultDisableFixtures.classBytes("DisabledDefaultInterface\$DefaultImpls")) { _, _ -> true }

        assertEquals(GeneratedBy.NONE, analysis.generatedBy("withBranch", "($DISABLED_INTERFACE_DESCRIPTOR" + "I)I"))
        assertEquals(GeneratedBy.NONE, analysis.generatedBy("withBody", "($DISABLED_INTERFACE_DESCRIPTOR)I"))
        assertEquals(GeneratedBy.NONE, analysis.generatedBy("getLabel", "($DISABLED_INTERFACE_DESCRIPTOR)Ljava/lang/String;"))
        assertEquals(GeneratedBy.NONE, analysis.generatedBy("priv", "($DISABLED_INTERFACE_DESCRIPTOR" + "I)I"))
    }

    @Test
    fun `a disable-mode DefaultImpls method whose one invokestatic targets its own class, not the interface, is NONE`() {
        val analysis =
            BranchSiteAnalyzer.analyze(JvmDefaultDisableFixtures.classBytes("DisabledDefaultInterface\$DefaultImpls")) { _, _ -> true }

        assertEquals(GeneratedBy.NONE, analysis.generatedBy("callsPrivate", "($DISABLED_INTERFACE_DESCRIPTOR" + "I)I"))
    }

    @Test
    fun `a hand-built DefaultImpls method loading every argument, calling the interface once, and returning is DEFAULT_IMPLS`() {
        val bytes =
            asmDefaultImplsClass { mv ->
                mv.visitVarInsn(Opcodes.ALOAD, 0)
                mv.visitVarInsn(Opcodes.LLOAD, 1)
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, ASM_INTERFACE, "access\$f\$jd", "(L$ASM_INTERFACE;J)J", true)
                mv.visitInsn(Opcodes.LRETURN)
            }

        assertEquals(GeneratedBy.DEFAULT_IMPLS, BranchSiteAnalyzer.analyze(bytes) { _, _ -> true }.generatedBy("f", ASM_METHOD_DESCRIPTOR))
    }

    @Test
    fun `a DefaultImpls method whose one invokestatic targets an owner other than the interface is NONE`() {
        val bytes =
            asmDefaultImplsClass { mv ->
                mv.visitVarInsn(Opcodes.ALOAD, 0)
                mv.visitVarInsn(Opcodes.LLOAD, 1)
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, "com/example/target/SomewhereElse", "f", "(L$ASM_INTERFACE;J)J", false)
                mv.visitInsn(Opcodes.LRETURN)
            }

        assertEquals(GeneratedBy.NONE, BranchSiteAnalyzer.analyze(bytes) { _, _ -> true }.generatedBy("f", ASM_METHOD_DESCRIPTOR))
    }

    @Test
    fun `a DefaultImpls forwarder with one extra instruction before its return is NONE`() {
        val bytes =
            asmDefaultImplsClass { mv ->
                mv.visitVarInsn(Opcodes.ALOAD, 0)
                mv.visitVarInsn(Opcodes.LLOAD, 1)
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, ASM_INTERFACE, "access\$f\$jd", "(L$ASM_INTERFACE;J)J", true)
                mv.visitInsn(Opcodes.LNEG)
                mv.visitInsn(Opcodes.LRETURN)
            }

        assertEquals(GeneratedBy.NONE, BranchSiteAnalyzer.analyze(bytes) { _, _ -> true }.generatedBy("f", ASM_METHOD_DESCRIPTOR))
    }

    @Test
    fun `a DefaultImpls forwarder that calls the interface twice is NONE`() {
        val bytes =
            asmDefaultImplsClass { mv ->
                mv.visitVarInsn(Opcodes.ALOAD, 0)
                mv.visitVarInsn(Opcodes.LLOAD, 1)
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, ASM_INTERFACE, "access\$f\$jd", "(L$ASM_INTERFACE;J)J", true)
                mv.visitInsn(Opcodes.POP2)
                mv.visitVarInsn(Opcodes.ALOAD, 0)
                mv.visitVarInsn(Opcodes.LLOAD, 1)
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, ASM_INTERFACE, "access\$f\$jd", "(L$ASM_INTERFACE;J)J", true)
                mv.visitInsn(Opcodes.LRETURN)
            }

        assertEquals(GeneratedBy.NONE, BranchSiteAnalyzer.analyze(bytes) { _, _ -> true }.generatedBy("f", ASM_METHOD_DESCRIPTOR))
    }

    @Test
    fun `a DefaultImpls forwarder that skips an argument is NONE`() {
        val bytes =
            asmDefaultImplsClass { mv ->
                mv.visitVarInsn(Opcodes.ALOAD, 0)
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, ASM_INTERFACE, "access\$g\$jd", "(L$ASM_INTERFACE;)J", true)
                mv.visitInsn(Opcodes.LRETURN)
            }

        assertEquals(GeneratedBy.NONE, BranchSiteAnalyzer.analyze(bytes) { _, _ -> true }.generatedBy("f", ASM_METHOD_DESCRIPTOR))
    }

    /**
     * A `$DefaultImpls` class for [ASM_INTERFACE] declaring one `public static long f(AsmShape, long)`
     * whose body [body] writes, with a label and a line number around it, as kotlinc emits.
     */
    private fun asmDefaultImplsClass(body: (MethodVisitor) -> Unit): ByteArray {
        val writer = ClassWriter(ClassWriter.COMPUTE_MAXS)
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL, "$ASM_INTERFACE\$DefaultImpls", null, "java/lang/Object", null)
        val mv = writer.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "f", ASM_METHOD_DESCRIPTOR, null, null)
        mv.visitCode()
        val start = Label()
        mv.visitLabel(start)
        mv.visitLineNumber(7, start)
        body(mv)
        mv.visitMaxs(0, 0)
        mv.visitEnd()
        writer.visitEnd()
        return writer.toByteArray()
    }

    @Test
    fun `a method on a non-DefaultImpls class named the same as a default is not marked`() {
        val analysis = BranchSiteAnalyzer.analyze(readInlineTargetBytes("NotDefaultImpls")) { _, _ -> true }

        assertEquals(GeneratedBy.NONE, analysis.generatedBy("withBody", "()I"))
    }

    @Test
    fun `a record's equals, hashCode, and toString are marked RECORD, its accessors and extra method are not`() {
        val analysis = BranchSiteAnalyzer.analyze(readJavaTargetBytes("RecordTarget")) { _, _ -> true }

        assertEquals(GeneratedBy.RECORD, analysis.generatedBy("equals", "(Ljava/lang/Object;)Z"))
        assertEquals(GeneratedBy.RECORD, analysis.generatedBy("hashCode", "()I"))
        assertEquals(GeneratedBy.RECORD, analysis.generatedBy("toString", "()Ljava/lang/String;"))
        assertEquals(GeneratedBy.NONE, analysis.generatedBy("x", "()I"))
        assertEquals(GeneratedBy.NONE, analysis.generatedBy("y", "()Ljava/lang/String;"))
        assertEquals(GeneratedBy.NONE, analysis.generatedBy("extra", "()I"))
    }

    @Test
    fun `generatedBy is NONE for every method on an empty analysis`() {
        assertEquals(GeneratedBy.NONE, BranchSiteAnalyzer.Analysis.EMPTY.generatedBy("component1", "()I"))
    }

    @Test
    fun `a Kotlin fixture read as compiled has line numbers and is marked Kotlin`() {
        val analysis = BranchSiteAnalyzer.analyze(readInlineTargetBytes("InlineTarget")) { _, _ -> true }

        assertTrue(analysis.hasLineNumbers)
        assertTrue(analysis.isKotlinClass)
    }

    @Test
    fun `the same bytes with debug info stripped have no line numbers but are still marked Kotlin`() {
        val writer = ClassWriter(0)
        ClassReader(readInlineTargetBytes("InlineTarget")).accept(writer, ClassReader.SKIP_DEBUG)
        val stripped = writer.toByteArray()

        val analysis = BranchSiteAnalyzer.analyze(stripped) { _, _ -> true }

        assertFalse(analysis.hasLineNumbers)
        assertTrue(analysis.isKotlinClass)
    }

    @Test
    fun `a Java fixture is not marked Kotlin`() {
        val analysis = BranchSiteAnalyzer.analyze(readFixtureBytes()) { _, _ -> true }

        assertTrue(analysis.hasLineNumbers, "the Java fixture still has ordinary debug info")
        assertFalse(analysis.isKotlinClass)
    }

    @Test
    fun `hasLineNumbers and isKotlinClass are both false on an empty analysis`() {
        assertFalse(BranchSiteAnalyzer.Analysis.EMPTY.hasLineNumbers)
        assertFalse(BranchSiteAnalyzer.Analysis.EMPTY.isKotlinClass)
    }
}
