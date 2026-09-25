package io.github.lukedevops.yukon.instrumentation.branch

import io.github.lukedevops.yukon.export.GeneratedBy
import io.github.lukedevops.yukon.export.KotlinKind
import net.bytebuddy.jar.asm.ClassWriter
import net.bytebuddy.jar.asm.Label
import net.bytebuddy.jar.asm.MethodVisitor
import net.bytebuddy.jar.asm.Opcodes
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Proves [BranchSiteAnalyzer] reads each class's Kotlin kind from the `k` element of its
 * `kotlin.Metadata`, and marks a multi-file facade's forwarders [GeneratedBy.MULTIFILE_FACADE] by
 * their bytecode shape. See ADR 0041.
 */
class KotlinKindAnalyzerTest {
    private companion object {
        const val METADATA_DESCRIPTOR = "Lkotlin/Metadata;"
        const val ASM_FACADE = "com/example/target/AsmFacade"
        const val ASM_PART = "com/example/target/AsmFacade__AsmPartKt"
        const val ASM_DESCRIPTOR = "(Ljava/lang/String;J)Ljava/lang/String;"
    }

    private fun analyzeKotlinTarget(simpleName: String): BranchSiteAnalyzer.Analysis =
        BranchSiteAnalyzer.analyze(File("build/classes/kotlin/test/com/example/target/$simpleName.class").readBytes()) { _, _ -> true }

    private fun analyzeJavaTarget(simpleName: String): BranchSiteAnalyzer.Analysis =
        BranchSiteAnalyzer.analyze(File("build/classes/java/test/com/example/target/$simpleName.class").readBytes()) { _, _ -> true }

    @Test
    fun `a Kotlin class and an object are KOTLIN_CLASS`() {
        assertEquals(KotlinKind.KOTLIN_CLASS, analyzeKotlinTarget("KindClass").kotlinKind)
        assertEquals(KotlinKind.KOTLIN_CLASS, analyzeKotlinTarget("KindObject").kotlinKind)
    }

    @Test
    fun `a file facade is FILE_FACADE, whether kotlinc names it or JvmName does`() {
        assertEquals(KotlinKind.FILE_FACADE, analyzeKotlinTarget("KotlinKindTargetKt").kotlinKind)
        assertEquals(KotlinKind.FILE_FACADE, analyzeKotlinTarget("WeirdName").kotlinKind)
    }

    @Test
    fun `a multi-file facade and its parts carry their own kinds`() {
        assertEquals(KotlinKind.MULTIFILE_CLASS_FACADE, analyzeKotlinTarget("MultifileText").kotlinKind)
        assertEquals(KotlinKind.MULTIFILE_CLASS_PART, analyzeKotlinTarget("MultifileText__MultifileGreetingKt").kotlinKind)
        assertEquals(KotlinKind.MULTIFILE_CLASS_PART, analyzeKotlinTarget("MultifileText__MultifileFarewellKt").kotlinKind)
    }

    @Test
    fun `a DefaultImpls class is SYNTHETIC_CLASS`() {
        assertEquals(KotlinKind.SYNTHETIC_CLASS, analyzeKotlinTarget("GeneratedInterface\$DefaultImpls").kotlinKind)
    }

    @Test
    fun `a Java class has no kotlin Metadata and is NONE`() {
        assertEquals(KotlinKind.NONE, analyzeJavaTarget("SampleTarget").kotlinKind)
    }

    @Test
    fun `a Metadata annotation with no k element takes the element's default, KOTLIN_CLASS`() {
        assertEquals(KotlinKind.KOTLIN_CLASS, BranchSiteAnalyzer.analyze(classWithMetadata(k = null)) { _, _ -> true }.kotlinKind)
    }

    @Test
    fun `a k outside 1 to 5 is NONE, as kotlin Metadata reads an unlisted kind`() {
        assertEquals(KotlinKind.NONE, BranchSiteAnalyzer.analyze(classWithMetadata(k = 0)) { _, _ -> true }.kotlinKind)
        assertEquals(KotlinKind.NONE, BranchSiteAnalyzer.analyze(classWithMetadata(k = 6)) { _, _ -> true }.kotlinKind)
    }

    @Test
    fun `each k from 1 to 5 names its kind in order`() {
        assertEquals(
            listOf(
                KotlinKind.KOTLIN_CLASS,
                KotlinKind.FILE_FACADE,
                KotlinKind.SYNTHETIC_CLASS,
                KotlinKind.MULTIFILE_CLASS_FACADE,
                KotlinKind.MULTIFILE_CLASS_PART,
            ),
            (1..5).map { BranchSiteAnalyzer.analyze(classWithMetadata(it)) { _, _ -> true }.kotlinKind },
        )
    }

    @Test
    fun `reading the kind leaves the metadata's own class reference and the Kotlin language in place`() {
        val analysis = analyzeKotlinTarget("MultifileText")

        assertEquals(true, analysis.isKotlinClass)
        assertEquals(true, "kotlin.Metadata" in analysis.classReferences)
    }

    @Test
    fun `a multi-file facade's function and property getter forwarders are MULTIFILE_FACADE, its synthetic default twin is not`() {
        val analysis = analyzeKotlinTarget("MultifileText")

        assertEquals(GeneratedBy.MULTIFILE_FACADE, analysis.generatedBy("multifileGreeting", "(Ljava/lang/String;)Ljava/lang/String;"))
        assertEquals(GeneratedBy.MULTIFILE_FACADE, analysis.generatedBy("multifileFarewell", "(Ljava/lang/String;I)Ljava/lang/String;"))
        assertEquals(GeneratedBy.MULTIFILE_FACADE, analysis.generatedBy("getMultifileSuffix", "()Ljava/lang/String;"))
        assertEquals(
            GeneratedBy.NONE,
            analysis.generatedBy("multifileFarewell\$default", "(Ljava/lang/String;IILjava/lang/Object;)Ljava/lang/String;"),
        )
    }

    @Test
    fun `the parts' real functions are NONE`() {
        assertEquals(
            GeneratedBy.NONE,
            analyzeKotlinTarget("MultifileText__MultifileGreetingKt").generatedBy("multifileGreeting", "(Ljava/lang/String;)Ljava/lang/String;"),
        )
        val farewell = analyzeKotlinTarget("MultifileText__MultifileFarewellKt")
        assertEquals(GeneratedBy.NONE, farewell.generatedBy("multifileFarewell", "(Ljava/lang/String;I)Ljava/lang/String;"))
        assertEquals(GeneratedBy.NONE, farewell.generatedBy("getMultifileSuffix", "()Ljava/lang/String;"))
    }

    @Test
    fun `a function in a normal file facade that only calls another file's function is NONE`() {
        assertEquals(GeneratedBy.NONE, analyzeKotlinTarget("KotlinKindTargetKt").generatedBy("callsAnotherFile", "()I"))
    }

    @Test
    fun `a hand-built facade forwarder with kotlinc's null check before its loads is MULTIFILE_FACADE`() {
        val bytes =
            asmFacade { mv ->
                mv.visitVarInsn(Opcodes.ALOAD, 0)
                mv.visitLdcInsn("name")
                mv.visitMethodInsn(
                    Opcodes.INVOKESTATIC,
                    "kotlin/jvm/internal/Intrinsics",
                    "checkNotNullParameter",
                    "(Ljava/lang/Object;Ljava/lang/String;)V",
                    false,
                )
                forwardToPart(mv)
            }

        assertEquals(GeneratedBy.MULTIFILE_FACADE, BranchSiteAnalyzer.analyze(bytes) { _, _ -> true }.generatedBy("f", ASM_DESCRIPTOR))
    }

    @Test
    fun `a hand-built facade forwarder with no null check is MULTIFILE_FACADE`() {
        val bytes = asmFacade { forwardToPart(it) }

        assertEquals(GeneratedBy.MULTIFILE_FACADE, BranchSiteAnalyzer.analyze(bytes) { _, _ -> true }.generatedBy("f", ASM_DESCRIPTOR))
    }

    @Test
    fun `the same forwarding body in a class that is not a multi-file facade is NONE`() {
        val bytes = asmFacade(kind = 2) { forwardToPart(it) }

        assertEquals(GeneratedBy.NONE, BranchSiteAnalyzer.analyze(bytes) { _, _ -> true }.generatedBy("f", ASM_DESCRIPTOR))
    }

    @Test
    fun `a facade function calling a class in another package, another name or another descriptor is NONE`() {
        val otherPackage = asmFacade { forwardToPart(it, owner = "com/example/other/AsmFacade__AsmPartKt") }
        val otherName = asmFacade { forwardToPart(it, name = "g") }
        val otherDescriptor =
            asmFacade { mv ->
                mv.visitVarInsn(Opcodes.ALOAD, 0)
                mv.visitVarInsn(Opcodes.LLOAD, 1)
                mv.visitInsn(Opcodes.L2I)
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, ASM_PART, "f", "(Ljava/lang/String;I)Ljava/lang/String;", false)
                mv.visitInsn(Opcodes.ARETURN)
            }

        for (bytes in listOf(otherPackage, otherName, otherDescriptor)) {
            assertEquals(GeneratedBy.NONE, BranchSiteAnalyzer.analyze(bytes) { _, _ -> true }.generatedBy("f", ASM_DESCRIPTOR))
        }
    }

    @Test
    fun `a facade function with an extra instruction or a skipped argument is NONE`() {
        val extra =
            asmFacade { mv ->
                forwardToPart(mv, returns = false)
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "trim", "()Ljava/lang/String;", false)
                mv.visitInsn(Opcodes.ARETURN)
            }
        val skipped =
            asmFacade { mv ->
                mv.visitVarInsn(Opcodes.ALOAD, 0)
                mv.visitInsn(Opcodes.LCONST_0)
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, ASM_PART, "f", ASM_DESCRIPTOR, false)
                mv.visitInsn(Opcodes.ARETURN)
            }

        for (bytes in listOf(extra, skipped)) {
            assertEquals(GeneratedBy.NONE, BranchSiteAnalyzer.analyze(bytes) { _, _ -> true }.generatedBy("f", ASM_DESCRIPTOR))
        }
    }

    @Test
    fun `a null check on a parameter that is not a reference is not kotlinc's, so the method is NONE`() {
        val bytes =
            asmFacade { mv ->
                mv.visitVarInsn(Opcodes.ALOAD, 3)
                mv.visitLdcInsn("name")
                mv.visitMethodInsn(
                    Opcodes.INVOKESTATIC,
                    "kotlin/jvm/internal/Intrinsics",
                    "checkNotNullParameter",
                    "(Ljava/lang/Object;Ljava/lang/String;)V",
                    false,
                )
                forwardToPart(mv)
            }

        assertEquals(GeneratedBy.NONE, BranchSiteAnalyzer.analyze(bytes) { _, _ -> true }.generatedBy("f", ASM_DESCRIPTOR))
    }

    /** Loads `f`'s two parameters, calls [name] on [owner] with `f`'s own descriptor, and returns when [returns]. */
    private fun forwardToPart(
        mv: MethodVisitor,
        owner: String = ASM_PART,
        name: String = "f",
        returns: Boolean = true,
    ) {
        mv.visitVarInsn(Opcodes.ALOAD, 0)
        mv.visitVarInsn(Opcodes.LLOAD, 1)
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, owner, name, ASM_DESCRIPTOR, false)
        if (returns) mv.visitInsn(Opcodes.ARETURN)
    }

    /**
     * A class [ASM_FACADE] whose `kotlin.Metadata` has `k` = [kind], declaring one
     * `public static final String f(String, long)` whose body [body] writes, with a label and a
     * line number around it, as kotlinc emits.
     */
    private fun asmFacade(
        kind: Int = 4,
        body: (MethodVisitor) -> Unit,
    ): ByteArray {
        val writer = ClassWriter(ClassWriter.COMPUTE_MAXS)
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL, ASM_FACADE, null, "java/lang/Object", null)
        writer.visitAnnotation(METADATA_DESCRIPTOR, true).apply {
            visit("k", kind)
            visitEnd()
        }
        val mv = writer.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC or Opcodes.ACC_FINAL, "f", ASM_DESCRIPTOR, null, null)
        mv.visitCode()
        val start = Label()
        mv.visitLabel(start)
        mv.visitLineNumber(1, start)
        body(mv)
        mv.visitMaxs(0, 0)
        mv.visitEnd()
        writer.visitEnd()
        return writer.toByteArray()
    }

    /** A class with one no-op method and a `kotlin.Metadata` whose `k` is [k], or that has no `k` element when [k] is null. */
    private fun classWithMetadata(k: Int?): ByteArray {
        val writer = ClassWriter(ClassWriter.COMPUTE_MAXS)
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL, "com/example/target/AsmKind", null, "java/lang/Object", null)
        writer.visitAnnotation(METADATA_DESCRIPTOR, true).apply {
            visit("xi", 48)
            if (k != null) visit("k", k)
            visitEnd()
        }
        val mv = writer.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "f", "()V", null, null)
        mv.visitCode()
        mv.visitInsn(Opcodes.RETURN)
        mv.visitMaxs(0, 0)
        mv.visitEnd()
        writer.visitEnd()
        return writer.toByteArray()
    }
}
