package io.github.lukedevops.yukon.instrumentation.branch

import net.bytebuddy.jar.asm.ClassReader
import net.bytebuddy.jar.asm.ClassVisitor
import net.bytebuddy.jar.asm.Handle
import net.bytebuddy.jar.asm.Label
import net.bytebuddy.jar.asm.MethodVisitor
import net.bytebuddy.jar.asm.Opcodes
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the join between the analyser's streaming visitor and the control-flow graph (ADR 0037):
 * the ordinal a downstream visitor reads from [InstructionRecorder.lastOrdinal] while it visits an
 * instruction is that instruction's index in the recorded [MethodInstructions], and a method's
 * tracked instructions are its analysed sites one for one, in order.
 */
class InstructionRecorderTest {
    private val fixtureClassFiles: List<File> =
        listOf("build/classes/kotlin/test/com/example/target", "build/classes/java/test/com/example/target")
            .flatMap { root -> File(root).walkTopDown().filter { it.isFile && it.name.endsWith(".class") }.toList() }

    /** Each instruction event a downstream visitor saw, as the ordinal it read and the opcode it was given. */
    private class SeenInstructions(
        private val recorder: () -> InstructionRecorder,
    ) : MethodVisitor(Opcodes.ASM9) {
        val seen = mutableListOf<Pair<Int, Int>>()

        private fun see(opcode: Int) {
            seen += recorder().lastOrdinal to opcode
        }

        override fun visitInsn(opcode: Int) = see(opcode)

        override fun visitIntInsn(
            opcode: Int,
            operand: Int,
        ) = see(opcode)

        override fun visitVarInsn(
            opcode: Int,
            varIndex: Int,
        ) = see(opcode)

        override fun visitTypeInsn(
            opcode: Int,
            type: String,
        ) = see(opcode)

        override fun visitFieldInsn(
            opcode: Int,
            owner: String,
            name: String,
            descriptor: String,
        ) = see(opcode)

        override fun visitMethodInsn(
            opcode: Int,
            owner: String,
            name: String,
            descriptor: String,
            isInterface: Boolean,
        ) = see(opcode)

        override fun visitInvokeDynamicInsn(
            name: String,
            descriptor: String,
            bootstrapMethodHandle: Handle,
            vararg bootstrapMethodArguments: Any,
        ) = see(Opcodes.INVOKEDYNAMIC)

        override fun visitJumpInsn(
            opcode: Int,
            label: Label,
        ) = see(opcode)

        override fun visitLdcInsn(value: Any?) = see(Opcodes.LDC)

        override fun visitIincInsn(
            varIndex: Int,
            increment: Int,
        ) = see(Opcodes.IINC)

        override fun visitTableSwitchInsn(
            min: Int,
            max: Int,
            dflt: Label,
            vararg labels: Label,
        ) = see(Opcodes.TABLESWITCH)

        override fun visitLookupSwitchInsn(
            dflt: Label,
            keys: IntArray,
            labels: Array<out Label>,
        ) = see(Opcodes.LOOKUPSWITCH)

        override fun visitMultiANewArrayInsn(
            descriptor: String,
            numDimensions: Int,
        ) = see(Opcodes.MULTIANEWARRAY)
    }

    /** Every method of [bytes], recorded, with what its downstream visitor saw. */
    private fun record(bytes: ByteArray): Map<Pair<String, String>, Pair<MethodInstructions, List<Pair<Int, Int>>>> {
        val result = mutableMapOf<Pair<String, String>, Pair<MethodInstructions, List<Pair<Int, Int>>>>()
        val visitor =
            object : ClassVisitor(Opcodes.ASM9) {
                override fun visitMethod(
                    access: Int,
                    name: String,
                    descriptor: String,
                    signature: String?,
                    exceptions: Array<out String>?,
                ): MethodVisitor {
                    lateinit var downstream: SeenInstructions
                    lateinit var recorder: InstructionRecorder
                    recorder =
                        InstructionRecorder({ SeenInstructions { recorder }.also { downstream = it } }) { instructions ->
                            result[name to descriptor] = instructions() to downstream.seen
                        }
                    return recorder
                }
            }
        ClassReader(bytes).accept(visitor, ClassReader.SKIP_FRAMES)
        return result
    }

    @Test
    fun `a downstream visitor reads each instruction's own ordinal, and the recorded list holds that instruction there`() {
        assertTrue(fixtureClassFiles.size > 50, "the fixture classes are compiled")
        for (file in fixtureClassFiles) {
            for ((method, recorded) in record(file.readBytes())) {
                val (instructions, seen) = recorded
                assertEquals(seen.indices.toList(), seen.map { it.first }, "${file.name} ${method.first}: ordinals in visit order")
                assertEquals(instructions.opcodes.toList(), seen.map { it.second }, "${file.name} ${method.first}: opcodes at each ordinal")
            }
        }
    }

    @Test
    fun `a method's tracked instructions are its analysed sites one for one`() {
        var methodsWithSites = 0
        for (file in fixtureClassFiles) {
            val bytes = file.readBytes()
            val sitesByMethod =
                BranchSiteAnalyzer
                    .analyze(bytes) { name, _ -> name != "<clinit>" }
                    .sites
                    .groupingBy { it.methodName to it.methodDescriptor }
                    .eachCount()
            for ((method, recorded) in record(bytes)) {
                if (method.first == "<clinit>") continue
                val instructions = recorded.first
                val tracked = (0 until instructions.size).count { instructions.isTrackedSite(it) }
                assertEquals(sitesByMethod[method] ?: 0, tracked, "${file.name} ${method.first}${method.second}")
                if (tracked > 0) methodsWithSites++
            }
        }
        assertTrue(methodsWithSites > 20, "enough methods with sites to mean something: $methodsWithSites")
    }
}
