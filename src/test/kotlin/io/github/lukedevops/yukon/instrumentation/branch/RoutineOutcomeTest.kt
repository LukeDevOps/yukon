package io.github.lukedevops.yukon.instrumentation.branch

import io.github.lukedevops.yukon.export.RoutineKind
import io.github.lukedevops.yukon.export.RoutineKind.FINALLY_COPY
import io.github.lukedevops.yukon.export.RoutineKind.NONE
import io.github.lukedevops.yukon.export.RoutineKind.NULL_DEFAULT
import io.github.lukedevops.yukon.export.RoutineKind.THROW_ONLY
import net.bytebuddy.jar.asm.ClassWriter
import net.bytebuddy.jar.asm.Label
import net.bytebuddy.jar.asm.MethodVisitor
import net.bytebuddy.jar.asm.Opcodes
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Proves each routine kind ADR 0046 names, and each shape it leaves as a finding, against real
 * compiled fixtures: `RoutineTarget.kt` and `RoutineJavaTarget.java`. Every shape was confirmed
 * with `javap -c -p` on these fixtures first.
 *
 * Each site reads as its outcomes' kinds in outcome order: a conditional's taken side, then its
 * fall-through.
 */
class RoutineOutcomeTest {
    /** Reads another test fixture's bytes by internal name, the way the agent reads a class through its loader. */
    private val fixtureLookup: (String) -> ByteArray? = { internalName ->
        listOf("kotlin", "java")
            .map { File("build/classes/$it/test/$internalName.class") }
            .firstOrNull { it.isFile }
            ?.readBytes()
    }

    private fun analysis(bytes: ByteArray) =
        BranchSiteAnalyzer.analyze(bytes, fixtureLookup, includePackages = listOf("com.example")) { name, _ -> name != "<init>" }

    private val kotlin by lazy {
        analysis(File("build/classes/kotlin/test/com/example/target/RoutineTarget.class").readBytes())
    }

    private val java by lazy {
        analysis(File("build/classes/java/test/com/example/target/RoutineJavaTarget.class").readBytes())
    }

    /** Each kept site of [method], in site order, as its outcomes' routine kinds. */
    private fun kinds(
        analysis: BranchSiteAnalyzer.Analysis,
        method: String,
    ): List<List<RoutineKind>> = analysis.keptSites.filter { it.site.methodName == method }.map { site -> site.outcomes.map { it.routine } }

    @Test
    fun `the null side of a safe call is a null default`() {
        assertEquals(listOf(listOf(NULL_DEFAULT, NONE)), kinds(kotlin, "safeCall"))
    }

    @Test
    fun `the null side of an elvis with a constant or a return is a null default`() {
        // kotlinc tests `ifnonnull`, so the fall-through is the null side.
        assertEquals(listOf(listOf(NONE, NULL_DEFAULT)), kinds(kotlin, "elvisConstant"))
        assertEquals(listOf(listOf(NONE, NULL_DEFAULT)), kinds(kotlin, "elvisReturn"))
    }

    @Test
    fun `an elvis whose null side calls a function is not routine on either side`() {
        assertEquals(listOf(listOf(NONE, NONE)), kinds(kotlin, "elvisCall"))
    }

    @Test
    fun `an elvis whose null side only throws is throw only`() {
        assertEquals(listOf(listOf(NONE, THROW_ONLY)), kinds(kotlin, "elvisThrow"))
    }

    @Test
    fun `an elvis with error and a lateinit read in its message is throw only, and so is the lateinit check`() {
        assertEquals(listOf(listOf(NONE, THROW_ONLY), listOf(NONE, THROW_ONLY)), kinds(kotlin, "elvisError"))
    }

    @Test
    fun `a lateinit check's throwing side is throw only`() {
        // The intrinsic throws inside, and kotlinc follows it with `aconst_null` into the return.
        assertEquals(listOf(listOf(NONE, THROW_ONLY)), kinds(kotlin, "lateinitName"))
    }

    @Test
    fun `the throwing else of an exhaustive when is throw only`() {
        assertEquals(listOf(listOf(NONE, NONE), listOf(THROW_ONLY, NONE)), kinds(kotlin, "sealedWhen"))
        assertEquals(listOf(listOf(NONE, NONE), listOf(THROW_ONLY, NONE)), kinds(kotlin, "instanceWhen"))
    }

    @Test
    fun `a hand-written guard that only throws is throw only, and one that logs first is not`() {
        assertEquals(listOf(listOf(NONE, THROW_ONLY)), kinds(kotlin, "guardThrow"))
        assertEquals(listOf(listOf(NONE, THROW_ONLY)), kinds(java, "guardThrow"))
        assertEquals(listOf(listOf(NONE, NONE)), kinds(kotlin, "guardLogThenThrow"))
    }

    @Test
    fun `a check that calls nothing is not routine when it is not a null check`() {
        assertEquals(listOf(listOf(NONE, NONE)), kinds(kotlin, "capTotal"))
    }

    @Test
    fun `the null side of a Java early return is a null default`() {
        assertEquals(listOf(listOf(NONE, NULL_DEFAULT)), kinds(java, "earlyReturn"))
    }

    @Test
    fun `a finally body's exception-path copy is a finally copy and its normal copy is not`() {
        assertEquals(listOf(listOf(NONE, NONE), listOf(FINALLY_COPY, FINALLY_COPY)), kinds(kotlin, "tryFinally"))
        assertEquals(listOf(listOf(NONE, NONE), listOf(FINALLY_COPY, FINALLY_COPY)), kinds(java, "tryFinally"))
    }

    @Test
    fun `a condition inside a use block is not routine`() {
        // use's own finally calls closeFinally and holds no site, so only the adopter's check is left.
        assertEquals(listOf(listOf(NONE, NONE)), kinds(kotlin, "useBlock"))
    }

    @Test
    fun `try-with-resources keeps its null checks as null defaults and nothing else as routine`() {
        // javac 21 catches Throwable, not any, so the handler is a typed catch and no site is a
        // finally copy. The resource's null check on each path skips close() on its null side.
        assertEquals(
            listOf(listOf(NONE, NONE), listOf(NULL_DEFAULT, NONE), listOf(NULL_DEFAULT, NONE)),
            kinds(java, "tryWithResources"),
        )
        assertEquals(listOf(listOf(NONE, NONE)), kinds(java, "tryWithResourcesNew"))
    }

    @Test
    fun `a condition in a typed catch is not routine`() {
        assertEquals(listOf(listOf(NONE, NONE)), kinds(kotlin, "typedCatch"))
        assertEquals(listOf(listOf(NONE, NONE)), kinds(java, "typedCatch"))
    }

    @Test
    fun `a reference compare against the null constant is a null check on either operand order`() {
        val analysis =
            analysis(
                referenceCompareClass(
                    CompareMethod("nullSecond", Opcodes.IF_ACMPNE) {
                        it.visitVarInsn(Opcodes.ALOAD, 0)
                        it.visitInsn(Opcodes.ACONST_NULL)
                    },
                    CompareMethod("nullFirst", Opcodes.IF_ACMPEQ) {
                        it.visitInsn(Opcodes.ACONST_NULL)
                        it.visitVarInsn(Opcodes.ALOAD, 0)
                    },
                    CompareMethod("sameLocal", Opcodes.IF_ACMPEQ) {
                        it.visitVarInsn(Opcodes.ALOAD, 0)
                        it.visitVarInsn(Opcodes.ALOAD, 0)
                    },
                ),
            )

        assertEquals(listOf(listOf(NONE, NULL_DEFAULT)), kinds(analysis, "nullSecond"))
        assertEquals(listOf(listOf(NULL_DEFAULT, NONE)), kinds(analysis, "nullFirst"))
        assertEquals(listOf(listOf(NONE, NONE)), kinds(analysis, "sameLocal"))
    }

    /** One static `(Object)I` method: [operands] pushes two references, and [compare] tests them. */
    private class CompareMethod(
        val name: String,
        val compare: Int,
        val operands: (MethodVisitor) -> Unit,
    )

    /**
     * A class of [methods], none of which any compiler here emits. Each returns 1 when its compare
     * jumps and 0 when it falls through, so neither side calls anything.
     */
    private fun referenceCompareClass(vararg methods: CompareMethod): ByteArray {
        val writer = ClassWriter(ClassWriter.COMPUTE_FRAMES or ClassWriter.COMPUTE_MAXS)
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "com/example/target/RoutineCompareTarget", null, "java/lang/Object", null)
        for (compareMethod in methods) {
            val method = writer.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, compareMethod.name, "(Ljava/lang/Object;)I", null, null)
            method.visitCode()
            compareMethod.operands(method)
            val taken = Label()
            method.visitJumpInsn(compareMethod.compare, taken)
            method.visitInsn(Opcodes.ICONST_0)
            method.visitInsn(Opcodes.IRETURN)
            method.visitLabel(taken)
            method.visitInsn(Opcodes.ICONST_1)
            method.visitInsn(Opcodes.IRETURN)
            method.visitMaxs(0, 0)
            method.visitEnd()
        }
        writer.visitEnd()
        return writer.toByteArray()
    }
}
