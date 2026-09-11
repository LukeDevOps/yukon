package io.github.lukedevops.yukon.instrumentation.branch

import io.github.lukedevops.yukon.advice.MethodEntryAdvice
import net.bytebuddy.jar.asm.Label
import net.bytebuddy.jar.asm.MethodVisitor
import net.bytebuddy.jar.asm.Opcodes

/**
 * Splits each [ConditionalJump] into two private edges, one per outcome, and each
 * `TABLESWITCH`/`LOOKUPSWITCH` into one private edge per case plus the default, every edge
 * incrementing its own slot in the class's shared `$yukonProbeCounts` array:
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
 * elsewhere (a loop back-edge, an if/else merge point, two switch cases falling into the same
 * code, and so on) - inserting a counter directly at an original target would conflate every
 * outcome that shares it.
 *
 * [allocateSlots] hands out this site's slots, relative to [probeIndexBase]: given the number
 * of outcomes the site needs, it returns the first slot and advances its own running total by
 * that many, so sites of different arity (a two-outcome jump next to an N-way switch) still
 * pack into contiguous slots in the same order
 * [io.github.lukedevops.yukon.instrumentation.YukonInstrumentation] lays out from
 * [BranchSiteAnalyzer]'s output.
 */
class BranchProbeMethodVisitor(
    methodVisitor: MethodVisitor,
    private val ownerInternalName: String,
    private val probeIndexBase: Int,
    private val allocateSlots: (outcomeCount: Int) -> Int,
) : MethodVisitor(Opcodes.ASM9, methodVisitor) {
    override fun visitJumpInsn(
        opcode: Int,
        label: Label,
    ) {
        if (!ConditionalJump.isTracked(opcode)) {
            super.visitJumpInsn(opcode, label)
            return
        }

        val base = probeIndexBase + allocateSlots(2)
        val taken = Label()
        val continuation = Label()

        super.visitJumpInsn(opcode, taken)
        emitProbeIncrement(base + 1)
        super.visitJumpInsn(Opcodes.GOTO, continuation)
        super.visitLabel(taken)
        emitProbeIncrement(base)
        super.visitJumpInsn(Opcodes.GOTO, label)
        super.visitLabel(continuation)
    }

    override fun visitTableSwitchInsn(
        min: Int,
        max: Int,
        dflt: Label,
        vararg labels: Label,
    ) {
        val newDefault = Label()
        val newLabels = Array(labels.size) { Label() }
        super.visitTableSwitchInsn(min, max, newDefault, *newLabels)
        emitSwitchEdges(labels.asList(), dflt, newDefault, newLabels.asList())
    }

    override fun visitLookupSwitchInsn(
        dflt: Label,
        keys: IntArray,
        labels: Array<out Label>,
    ) {
        val newDefault = Label()
        val newLabels = Array(labels.size) { Label() }
        super.visitLookupSwitchInsn(newDefault, keys, newLabels)
        emitSwitchEdges(labels.asList(), dflt, newDefault, newLabels.asList())
    }

    private fun emitSwitchEdges(
        originalLabels: List<Label>,
        originalDefault: Label,
        newDefault: Label,
        newLabels: List<Label>,
    ) {
        val base = probeIndexBase + allocateSlots(originalLabels.size + 1)
        newLabels.forEachIndexed { i, block ->
            super.visitLabel(block)
            emitProbeIncrement(base + i)
            super.visitJumpInsn(Opcodes.GOTO, originalLabels[i])
        }
        super.visitLabel(newDefault)
        emitProbeIncrement(base + originalLabels.size)
        super.visitJumpInsn(Opcodes.GOTO, originalDefault)
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
