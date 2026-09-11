package io.github.lukedevops.yukon.instrumentation.branch

import io.github.lukedevops.yukon.advice.MethodEntryAdvice
import net.bytebuddy.jar.asm.Label
import net.bytebuddy.jar.asm.MethodVisitor
import net.bytebuddy.jar.asm.Opcodes

/**
 * Splits each [ConditionalJump] into two private edges, one per outcome, each incrementing its
 * own slot in the class's shared `$yukonProbeCounts` array:
 *
 * ```
 * IFEQ original_target              IFEQ taken
 *                            ->        probes[notTakenIndex]++
 *                                      GOTO continue
 *                                    taken:
 *                                      probes[takenIndex]++
 *                                      GOTO original_target
 *                                    continue:
 * ```
 *
 * Neither new edge is shared with any other control flow, so a probe only increments for the
 * outcome it stands for, regardless of what the original jump target is also used for
 * elsewhere (a loop back-edge, an if/else merge point, and so on) - inserting a counter
 * directly at the original target would conflate both outcomes there.
 *
 * `probeIndexBase + siteIndex * 2` is the taken slot and `+ 1` the not-taken slot, matching
 * the pairing [io.github.lukedevops.yukon.instrumentation.YukonInstrumentation] builds from
 * [BranchSiteAnalyzer]'s output.
 */
class BranchProbeMethodVisitor(
    methodVisitor: MethodVisitor,
    private val ownerInternalName: String,
    private val probeIndexBase: Int,
    private val nextSiteIndex: () -> Int,
) : MethodVisitor(Opcodes.ASM9, methodVisitor) {
    override fun visitJumpInsn(
        opcode: Int,
        label: Label,
    ) {
        if (!ConditionalJump.isTracked(opcode)) {
            super.visitJumpInsn(opcode, label)
            return
        }

        val siteIndex = nextSiteIndex()
        val takenIndex = probeIndexBase + siteIndex * 2
        val notTakenIndex = probeIndexBase + siteIndex * 2 + 1
        val taken = Label()
        val continuation = Label()

        super.visitJumpInsn(opcode, taken)
        emitProbeIncrement(notTakenIndex)
        super.visitJumpInsn(Opcodes.GOTO, continuation)
        super.visitLabel(taken)
        emitProbeIncrement(takenIndex)
        super.visitJumpInsn(Opcodes.GOTO, label)
        super.visitLabel(continuation)
    }

    private fun emitProbeIncrement(index: Int) {
        super.visitFieldInsn(Opcodes.GETSTATIC, ownerInternalName, MethodEntryAdvice.PROBE_ARRAY_FIELD, "[J")
        pushInt(index)
        super.visitInsn(Opcodes.DUP2)
        super.visitInsn(Opcodes.LALOAD)
        super.visitInsn(Opcodes.LCONST_1)
        super.visitInsn(Opcodes.LADD)
        super.visitInsn(Opcodes.LASTORE)
    }

    private fun pushInt(value: Int) {
        when (value) {
            in -1..5 -> super.visitInsn(Opcodes.ICONST_0 + value)
            in Byte.MIN_VALUE..Byte.MAX_VALUE -> super.visitIntInsn(Opcodes.BIPUSH, value)
            in Short.MIN_VALUE..Short.MAX_VALUE -> super.visitIntInsn(Opcodes.SIPUSH, value)
            else -> super.visitLdcInsn(value)
        }
    }
}
