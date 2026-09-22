package io.github.lukedevops.yukon.instrumentation.branch

import net.bytebuddy.jar.asm.ClassReader
import net.bytebuddy.jar.asm.ClassVisitor
import net.bytebuddy.jar.asm.ConstantDynamic
import net.bytebuddy.jar.asm.Handle
import net.bytebuddy.jar.asm.Label
import net.bytebuddy.jar.asm.MethodVisitor
import net.bytebuddy.jar.asm.Opcodes
import net.bytebuddy.jar.asm.Type

/**
 * Builds each tracked branch site's condition fingerprint, as a second, independent `ClassReader`
 * pass over a class's original bytecode. See ADR 0031.
 *
 * The fingerprint is the canonical text of the instructions from the last point in the method
 * where the operand stack was empty up to the site's own jump or switch. [BranchSiteAnalyzer]
 * attaches fingerprint `i` of a method to that method's `i`-th tracked site, in the same encounter
 * order it numbers sites itself, dropped sites included.
 *
 * This visits every method regardless of any method filter, since [BranchSiteAnalyzer] decides
 * afterwards which methods it kept sites for.
 */
object ConditionFingerprinter {
    /** One method's ordered fingerprints and, for a switch site, its case keys. Both lists are indexed by encounter ordinal. */
    class MethodResult(
        val fingerprints: List<String?>,
        val caseKeys: List<List<Int>?>,
    )

    fun analyze(classBytes: ByteArray): Map<Pair<String, String>, MethodResult> {
        val results = mutableMapOf<Pair<String, String>, MethodResult>()
        val classVisitor =
            object : ClassVisitor(Opcodes.ASM9) {
                override fun visitMethod(
                    access: Int,
                    name: String,
                    descriptor: String,
                    signature: String?,
                    exceptions: Array<out String>?,
                ): MethodVisitor =
                    ConditionFingerprintMethodVisitor { fingerprints, caseKeys ->
                        results[name to descriptor] = MethodResult(fingerprints, caseKeys)
                    }
            }
        ClassReader(classBytes).accept(classVisitor, ClassReader.SKIP_FRAMES)
        return results
    }

    /**
     * One raw bytecode instruction, recorded with just enough data to compute its stack effect
     * and its fingerprint token. Internal, not private, so [stackEffect]'s opcode families are
     * each directly testable without building bytecode by hand for every case.
     */
    internal sealed interface Insn {
        data class Plain(
            val opcode: Int,
        ) : Insn

        data class IntOperand(
            val opcode: Int,
            val operand: Int,
        ) : Insn

        data class Var(
            val opcode: Int,
            val varIndex: Int,
        ) : Insn

        data class TypeOp(
            val opcode: Int,
            val type: String,
        ) : Insn

        data class Field(
            val opcode: Int,
            val owner: String,
            val name: String,
            val descriptor: String,
        ) : Insn

        data class MethodCall(
            val opcode: Int,
            val owner: String,
            val name: String,
            val descriptor: String,
        ) : Insn

        data class InvokeDynamic(
            val name: String,
            val descriptor: String,
            val bootstrapMethod: Handle,
            val bootstrapMethodArguments: Array<out Any>,
        ) : Insn

        data class Jump(
            val opcode: Int,
            val target: Label,
        ) : Insn

        data class Ldc(
            val value: Any?,
        ) : Insn

        data class Iinc(
            val varIndex: Int,
            val increment: Int,
        ) : Insn

        data class TableSwitch(
            val min: Int,
            val max: Int,
            val dflt: Label,
            val labels: Array<out Label>,
        ) : Insn

        data class LookupSwitch(
            val dflt: Label,
            val keys: IntArray,
            val labels: Array<out Label>,
        ) : Insn

        data class MultiANewArray(
            val descriptor: String,
            val numDimensions: Int,
        ) : Insn
    }

    /** A `visitLabel` event recorded in encounter order alongside the instruction list. */
    private class LabelMark(
        val label: Label,
        val instructionIndex: Int,
    )

    /** One `LocalVariableTable` entry, resolved after the whole method body has been visited. */
    private class LocalVarEntry(
        val name: String,
        val index: Int,
        val start: Label,
        val end: Label,
    )

    /**
     * Visits one method's instructions, and in [visitEnd] resolves stack depth, local variable
     * names and the fingerprint window for each tracked site. [onResult] receives the method's
     * fingerprints and case keys, one entry per tracked site in encounter order.
     */
    private class ConditionFingerprintMethodVisitor(
        private val onResult: (List<String?>, List<List<Int>?>) -> Unit,
    ) : MethodVisitor(Opcodes.ASM9) {
        private val insns = mutableListOf<Insn>()
        private val labelMarks = mutableListOf<LabelMark>()
        private val handlerLabels = mutableSetOf<Label>()
        private val localVars = mutableListOf<LocalVarEntry>()

        override fun visitInsn(opcode: Int) {
            insns += Insn.Plain(opcode)
        }

        override fun visitIntInsn(
            opcode: Int,
            operand: Int,
        ) {
            insns += Insn.IntOperand(opcode, operand)
        }

        override fun visitVarInsn(
            opcode: Int,
            varIndex: Int,
        ) {
            insns += Insn.Var(opcode, varIndex)
        }

        override fun visitTypeInsn(
            opcode: Int,
            type: String,
        ) {
            insns += Insn.TypeOp(opcode, type)
        }

        override fun visitFieldInsn(
            opcode: Int,
            owner: String,
            name: String,
            descriptor: String,
        ) {
            insns += Insn.Field(opcode, owner, name, descriptor)
        }

        override fun visitMethodInsn(
            opcode: Int,
            owner: String,
            name: String,
            descriptor: String,
            isInterface: Boolean,
        ) {
            insns += Insn.MethodCall(opcode, owner, name, descriptor)
        }

        override fun visitInvokeDynamicInsn(
            name: String,
            descriptor: String,
            bootstrapMethodHandle: Handle,
            vararg bootstrapMethodArguments: Any,
        ) {
            insns += Insn.InvokeDynamic(name, descriptor, bootstrapMethodHandle, bootstrapMethodArguments)
        }

        override fun visitJumpInsn(
            opcode: Int,
            label: Label,
        ) {
            insns += Insn.Jump(opcode, label)
        }

        override fun visitLdcInsn(value: Any?) {
            insns += Insn.Ldc(value)
        }

        override fun visitIincInsn(
            varIndex: Int,
            increment: Int,
        ) {
            insns += Insn.Iinc(varIndex, increment)
        }

        override fun visitTableSwitchInsn(
            min: Int,
            max: Int,
            dflt: Label,
            vararg labels: Label,
        ) {
            insns += Insn.TableSwitch(min, max, dflt, labels)
        }

        override fun visitLookupSwitchInsn(
            dflt: Label,
            keys: IntArray,
            labels: Array<out Label>,
        ) {
            insns += Insn.LookupSwitch(dflt, keys, labels)
        }

        override fun visitMultiANewArrayInsn(
            descriptor: String,
            numDimensions: Int,
        ) {
            insns += Insn.MultiANewArray(descriptor, numDimensions)
        }

        override fun visitLabel(label: Label) {
            labelMarks += LabelMark(label, insns.size)
        }

        override fun visitTryCatchBlock(
            start: Label,
            end: Label,
            handler: Label,
            type: String?,
        ) {
            handlerLabels += handler
        }

        override fun visitLocalVariable(
            name: String,
            descriptor: String,
            signature: String?,
            start: Label,
            end: Label,
            index: Int,
        ) {
            localVars += LocalVarEntry(name, index, start, end)
        }

        override fun visitEnd() {
            val instructionIndexOfLabel = mutableMapOf<Label, Int>()
            for (mark in labelMarks) instructionIndexOfLabel.putIfAbsent(mark.label, mark.instructionIndex)
            val labelsAt = labelMarks.groupBy { it.instructionIndex }

            val fingerprints = mutableListOf<String?>()
            val caseKeys = mutableListOf<List<Int>?>()

            // A forward jump or switch records the depth its target enters with, before that
            // label is reached. A backward target is never looked up here, since this method has
            // already moved past its position by the time the jump is visited.
            val forwardDepthOfLabel = mutableMapOf<Label, Int>()

            var depth: Int? = 0
            var zeroPoint: Int? = 0

            fun localNameAt(
                varIndex: Int,
                instructionIndex: Int,
            ): String? =
                localVars
                    .firstOrNull { entry ->
                        entry.index == varIndex &&
                            instructionIndexOfLabel[entry.start]?.let { it <= instructionIndex } == true &&
                            instructionIndexOfLabel[entry.end]?.let { instructionIndex < it } == true
                    }?.name

            for (i in insns.indices) {
                val marksHere = labelsAt[i]
                if (marksHere != null) {
                    val handlerMark = marksHere.firstOrNull { it.label in handlerLabels }
                    val forwardMark = marksHere.firstOrNull { it.label in forwardDepthOfLabel }
                    depth =
                        when {
                            handlerMark != null -> 1
                            forwardMark != null -> forwardDepthOfLabel.getValue(forwardMark.label)
                            else -> depth
                        }
                }
                if (depth == null) {
                    zeroPoint = null
                } else if (depth == 0) {
                    zeroPoint = i
                }

                val insn = insns[i]
                if (isTrackedSite(insn)) {
                    fingerprints += windowFingerprint(zeroPoint, i, ::localNameAt)
                    caseKeys += caseKeysOf(insn)
                }

                val effect = stackEffect(insn)
                depth = depth?.plus(effect)

                for (target in jumpTargets(insn)) {
                    val recorded = depth
                    if (recorded != null) forwardDepthOfLabel.putIfAbsent(target, recorded)
                }
                if (hasNoFallThrough(insn)) depth = null
            }

            onResult(fingerprints, caseKeys)
        }

        /** Whether [insn] is a site [BranchSiteAnalyzer] tracks: a [ConditionalJump] or any switch. */
        private fun isTrackedSite(insn: Insn): Boolean =
            when (insn) {
                is Insn.Jump -> ConditionalJump.isTracked(insn.opcode)
                is Insn.TableSwitch, is Insn.LookupSwitch -> true
                else -> false
            }

        /** [BranchProbeMethodVisitor]'s case order for a switch: label-array order, skipping entries whose label is the default. */
        private fun caseKeysOf(insn: Insn): List<Int>? =
            when (insn) {
                is Insn.TableSwitch ->
                    insn.labels.indices
                        .filter { insn.labels[it] !== insn.dflt }
                        .map { insn.min + it }

                is Insn.LookupSwitch ->
                    insn.labels.indices
                        .filter { insn.labels[it] !== insn.dflt }
                        .map { insn.keys[it] }

                else -> null
            }

        /**
         * The fingerprint text for the window `[zeroPoint, siteIndex]`, or null when [zeroPoint]
         * is null: the site was reached while the operand stack depth was unknown, or no earlier
         * point in the method is known to have left the stack empty.
         */
        private fun windowFingerprint(
            zeroPoint: Int?,
            siteIndex: Int,
            localNameAt: (varIndex: Int, instructionIndex: Int) -> String?,
        ): String? {
            if (zeroPoint == null) return null
            return (zeroPoint..siteIndex).joinToString(";") { i -> tokenFor(insns[i], i, localNameAt) }
        }

        /** The forward jump or switch targets an instruction hands the stack depth on to. */
        private fun jumpTargets(insn: Insn): List<Label> =
            when (insn) {
                is Insn.Jump -> listOf(insn.target)
                is Insn.TableSwitch -> listOf(insn.dflt) + insn.labels
                is Insn.LookupSwitch -> listOf(insn.dflt) + insn.labels
                else -> emptyList()
            }

        /**
         * Opcodes after which control never falls through to the next instruction: `GOTO`,
         * `ATHROW`, every `xRETURN`, a switch, and `RET`. `RET` returns to its `JSR` caller rather
         * than falling through, the same as a method return; `JSR`/`RET` are obsolete bytecode
         * this agent does not expect to instrument.
         */
        private fun hasNoFallThrough(insn: Insn): Boolean =
            when (insn) {
                is Insn.Jump -> insn.opcode == Opcodes.GOTO
                is Insn.Plain -> insn.opcode == Opcodes.ATHROW || insn.opcode in Opcodes.IRETURN..Opcodes.RETURN
                is Insn.Var -> insn.opcode == Opcodes.RET
                is Insn.TableSwitch, is Insn.LookupSwitch -> true
                else -> false
            }
    }

    /**
     * The stack effect of one instruction, in JVM stack words (a `long` or `double` counts as
     * two). `ASM`'s `Frame`/`AnalyzerAdapter` are not part of ByteBuddy's shaded copy, so this is
     * a hand-written table over each opcode family.
     */
    internal fun stackEffect(insn: Insn): Int =
        when (insn) {
            is Insn.Plain -> plainStackEffect(insn.opcode)
            is Insn.IntOperand -> if (insn.opcode == Opcodes.NEWARRAY) 0 else 1
            is Insn.Var -> varStackEffect(insn.opcode)
            is Insn.TypeOp -> typeStackEffect(insn.opcode)
            is Insn.Field -> fieldStackEffect(insn.opcode, insn.descriptor)
            is Insn.MethodCall -> methodCallStackEffect(insn.opcode, insn.descriptor)
            is Insn.InvokeDynamic -> invokeDynamicStackEffect(insn.descriptor)
            is Insn.Jump -> jumpStackEffect(insn.opcode)
            is Insn.Ldc -> ldcSize(insn.value)
            is Insn.Iinc -> 0
            is Insn.TableSwitch -> -1
            is Insn.LookupSwitch -> -1
            is Insn.MultiANewArray -> 1 - insn.numDimensions
        }

    private fun plainStackEffect(opcode: Int): Int =
        when (opcode) {
            Opcodes.NOP -> 0
            Opcodes.ACONST_NULL -> 1
            in Opcodes.ICONST_M1..Opcodes.ICONST_5 -> 1
            Opcodes.LCONST_0, Opcodes.LCONST_1 -> 2
            Opcodes.FCONST_0, Opcodes.FCONST_1, Opcodes.FCONST_2 -> 1
            Opcodes.DCONST_0, Opcodes.DCONST_1 -> 2
            Opcodes.IALOAD, Opcodes.FALOAD, Opcodes.AALOAD, Opcodes.BALOAD, Opcodes.CALOAD, Opcodes.SALOAD -> -1
            Opcodes.LALOAD, Opcodes.DALOAD -> 0
            Opcodes.IASTORE, Opcodes.FASTORE, Opcodes.AASTORE, Opcodes.BASTORE, Opcodes.CASTORE, Opcodes.SASTORE -> -3
            Opcodes.LASTORE, Opcodes.DASTORE -> -4
            Opcodes.POP -> -1
            Opcodes.POP2 -> -2
            Opcodes.DUP, Opcodes.DUP_X1, Opcodes.DUP_X2 -> 1
            Opcodes.DUP2, Opcodes.DUP2_X1, Opcodes.DUP2_X2 -> 2
            Opcodes.SWAP -> 0
            Opcodes.IADD, Opcodes.ISUB, Opcodes.IMUL, Opcodes.IDIV, Opcodes.IREM,
            Opcodes.IAND, Opcodes.IOR, Opcodes.IXOR, Opcodes.ISHL, Opcodes.ISHR, Opcodes.IUSHR,
            Opcodes.FADD, Opcodes.FSUB, Opcodes.FMUL, Opcodes.FDIV, Opcodes.FREM,
            -> -1
            Opcodes.LADD, Opcodes.LSUB, Opcodes.LMUL, Opcodes.LDIV, Opcodes.LREM,
            Opcodes.LAND, Opcodes.LOR, Opcodes.LXOR,
            Opcodes.DADD, Opcodes.DSUB, Opcodes.DMUL, Opcodes.DDIV, Opcodes.DREM,
            -> -2
            Opcodes.LSHL, Opcodes.LSHR, Opcodes.LUSHR -> -1
            Opcodes.INEG, Opcodes.FNEG -> 0
            Opcodes.LNEG, Opcodes.DNEG -> 0
            Opcodes.I2L, Opcodes.I2D -> 1
            Opcodes.I2F, Opcodes.I2B, Opcodes.I2C, Opcodes.I2S -> 0
            Opcodes.L2I, Opcodes.L2F -> -1
            Opcodes.L2D -> 0
            Opcodes.F2I -> 0
            Opcodes.F2L, Opcodes.F2D -> 1
            Opcodes.D2I, Opcodes.D2F -> -1
            Opcodes.D2L -> 0
            Opcodes.LCMP -> -3
            Opcodes.FCMPL, Opcodes.FCMPG -> -1
            Opcodes.DCMPL, Opcodes.DCMPG -> -3
            Opcodes.IRETURN, Opcodes.FRETURN, Opcodes.ARETURN -> -1
            Opcodes.LRETURN, Opcodes.DRETURN -> -2
            Opcodes.RETURN -> 0
            Opcodes.ARRAYLENGTH -> 0
            Opcodes.ATHROW -> -1
            Opcodes.MONITORENTER, Opcodes.MONITOREXIT -> -1
            else -> 0
        }

    private fun varStackEffect(opcode: Int): Int =
        when (opcode) {
            Opcodes.ILOAD, Opcodes.FLOAD, Opcodes.ALOAD -> 1
            Opcodes.LLOAD, Opcodes.DLOAD -> 2
            Opcodes.ISTORE, Opcodes.FSTORE, Opcodes.ASTORE -> -1
            Opcodes.LSTORE, Opcodes.DSTORE -> -2
            Opcodes.RET -> 0
            else -> 0
        }

    private fun typeStackEffect(opcode: Int): Int =
        when (opcode) {
            Opcodes.NEW -> 1
            Opcodes.ANEWARRAY -> 0
            Opcodes.CHECKCAST -> 0
            Opcodes.INSTANCEOF -> 0
            else -> 0
        }

    private fun fieldStackEffect(
        opcode: Int,
        descriptor: String,
    ): Int {
        val size = Type.getType(descriptor).size
        return when (opcode) {
            Opcodes.GETSTATIC -> size
            Opcodes.PUTSTATIC -> -size
            Opcodes.GETFIELD -> size - 1
            Opcodes.PUTFIELD -> -(1 + size)
            else -> 0
        }
    }

    private fun methodCallStackEffect(
        opcode: Int,
        descriptor: String,
    ): Int {
        val packed = Type.getArgumentsAndReturnSizes(descriptor)
        // ASM's own packing bakes in an implicit receiver word, which INVOKESTATIC never pushes.
        val argumentsSize = if (opcode == Opcodes.INVOKESTATIC) (packed ushr 2) - 1 else packed ushr 2
        val returnSize = packed and 0x03
        return returnSize - argumentsSize
    }

    private fun invokeDynamicStackEffect(descriptor: String): Int {
        val packed = Type.getArgumentsAndReturnSizes(descriptor)
        val argumentsSize = (packed ushr 2) - 1
        val returnSize = packed and 0x03
        return returnSize - argumentsSize
    }

    private fun jumpStackEffect(opcode: Int): Int =
        when (opcode) {
            Opcodes.IFEQ, Opcodes.IFNE, Opcodes.IFLT, Opcodes.IFGE, Opcodes.IFGT, Opcodes.IFLE,
            Opcodes.IFNULL, Opcodes.IFNONNULL,
            -> -1
            Opcodes.IF_ICMPEQ, Opcodes.IF_ICMPNE, Opcodes.IF_ICMPLT, Opcodes.IF_ICMPGE,
            Opcodes.IF_ICMPGT, Opcodes.IF_ICMPLE, Opcodes.IF_ACMPEQ, Opcodes.IF_ACMPNE,
            -> -2
            Opcodes.GOTO -> 0
            Opcodes.JSR -> 1
            else -> 0
        }

    private fun ldcSize(value: Any?): Int =
        when (value) {
            is Long, is Double -> 2
            is ConstantDynamic -> value.size
            else -> 1
        }

    /** The canonical text for one instruction in a fingerprint window. See ADR 0031, point 4 of the brief that landed it. */
    private fun tokenFor(
        insn: Insn,
        instructionIndex: Int,
        localNameAt: (varIndex: Int, instructionIndex: Int) -> String?,
    ): String =
        when (insn) {
            is Insn.Plain -> opcodeName(insn.opcode)
            is Insn.IntOperand -> "${opcodeName(insn.opcode)} ${insn.operand}"
            is Insn.Var -> {
                val varName = localNameAt(insn.varIndex, instructionIndex)
                if (varName == null) opcodeName(insn.opcode) else "${opcodeName(insn.opcode)} $varName"
            }
            is Insn.TypeOp -> "${opcodeName(insn.opcode)} ${insn.type}"
            is Insn.Field -> "${opcodeName(insn.opcode)} ${insn.owner}.${insn.name}:${insn.descriptor}"
            is Insn.MethodCall -> "${opcodeName(insn.opcode)} ${insn.owner}.${insn.name} ${insn.descriptor}"
            is Insn.InvokeDynamic -> invokeDynamicToken(insn)
            is Insn.Jump -> opcodeName(insn.opcode)
            is Insn.Ldc -> "LDC ${ldcToken(insn.value)}"
            is Insn.Iinc -> {
                val varName = localNameAt(insn.varIndex, instructionIndex)
                if (varName == null) "IINC" else "IINC $varName ${insn.increment}"
            }
            is Insn.TableSwitch -> opcodeName(Opcodes.TABLESWITCH)
            is Insn.LookupSwitch -> opcodeName(Opcodes.LOOKUPSWITCH)
            is Insn.MultiANewArray -> "${opcodeName(Opcodes.MULTIANEWARRAY)} ${insn.descriptor} ${insn.numDimensions}"
        }

    /**
     * `INVOKEDYNAMIC`'s call site name and descriptor, the bootstrap handle's owner and name, and
     * each bootstrap `Handle` argument's owner and descriptor, never its name: kotlinc and javac
     * name a lambda body `lambda$foo$0` and similar, and that number shifts when another lambda is
     * added elsewhere in the class.
     */
    private fun invokeDynamicToken(insn: Insn.InvokeDynamic): String {
        val handleArgs =
            insn.bootstrapMethodArguments
                .filterIsInstance<Handle>()
                .joinToString(",") { "${it.owner}:${it.desc}" }
        return "INVOKEDYNAMIC ${insn.name} ${insn.descriptor} ${insn.bootstrapMethod.owner}.${insn.bootstrapMethod.name} [$handleArgs]"
    }

    /** An `LDC` constant's tag and value. A string escapes `;` and `\` so it cannot be mistaken for token structure. */
    private fun ldcToken(value: Any?): String =
        when (value) {
            is Int -> "I:$value"
            is Long -> "J:$value"
            is Float -> "F:$value"
            is Double -> "D:$value"
            is String -> "S:${escapeString(value)}"
            is Type -> "T:${value.descriptor}"
            is Handle -> "H:${value.owner}.${value.name} ${value.desc}"
            is ConstantDynamic -> "CD:${value.name} ${value.descriptor}"
            else -> "?:$value"
        }

    private fun escapeString(value: String): String = value.replace("\\", "\\\\").replace(";", "\\;")

    /** Every JVM opcode this fingerprinter's instructions can carry, by its bytecode mnemonic. */
    private fun opcodeName(opcode: Int): String =
        when (opcode) {
            Opcodes.NOP -> "NOP"
            Opcodes.ACONST_NULL -> "ACONST_NULL"
            Opcodes.ICONST_M1 -> "ICONST_M1"
            Opcodes.ICONST_0 -> "ICONST_0"
            Opcodes.ICONST_1 -> "ICONST_1"
            Opcodes.ICONST_2 -> "ICONST_2"
            Opcodes.ICONST_3 -> "ICONST_3"
            Opcodes.ICONST_4 -> "ICONST_4"
            Opcodes.ICONST_5 -> "ICONST_5"
            Opcodes.LCONST_0 -> "LCONST_0"
            Opcodes.LCONST_1 -> "LCONST_1"
            Opcodes.FCONST_0 -> "FCONST_0"
            Opcodes.FCONST_1 -> "FCONST_1"
            Opcodes.FCONST_2 -> "FCONST_2"
            Opcodes.DCONST_0 -> "DCONST_0"
            Opcodes.DCONST_1 -> "DCONST_1"
            Opcodes.BIPUSH -> "BIPUSH"
            Opcodes.SIPUSH -> "SIPUSH"
            Opcodes.LDC -> "LDC"
            Opcodes.ILOAD -> "ILOAD"
            Opcodes.LLOAD -> "LLOAD"
            Opcodes.FLOAD -> "FLOAD"
            Opcodes.DLOAD -> "DLOAD"
            Opcodes.ALOAD -> "ALOAD"
            Opcodes.IALOAD -> "IALOAD"
            Opcodes.LALOAD -> "LALOAD"
            Opcodes.FALOAD -> "FALOAD"
            Opcodes.DALOAD -> "DALOAD"
            Opcodes.AALOAD -> "AALOAD"
            Opcodes.BALOAD -> "BALOAD"
            Opcodes.CALOAD -> "CALOAD"
            Opcodes.SALOAD -> "SALOAD"
            Opcodes.ISTORE -> "ISTORE"
            Opcodes.LSTORE -> "LSTORE"
            Opcodes.FSTORE -> "FSTORE"
            Opcodes.DSTORE -> "DSTORE"
            Opcodes.ASTORE -> "ASTORE"
            Opcodes.IASTORE -> "IASTORE"
            Opcodes.LASTORE -> "LASTORE"
            Opcodes.FASTORE -> "FASTORE"
            Opcodes.DASTORE -> "DASTORE"
            Opcodes.AASTORE -> "AASTORE"
            Opcodes.BASTORE -> "BASTORE"
            Opcodes.CASTORE -> "CASTORE"
            Opcodes.SASTORE -> "SASTORE"
            Opcodes.POP -> "POP"
            Opcodes.POP2 -> "POP2"
            Opcodes.DUP -> "DUP"
            Opcodes.DUP_X1 -> "DUP_X1"
            Opcodes.DUP_X2 -> "DUP_X2"
            Opcodes.DUP2 -> "DUP2"
            Opcodes.DUP2_X1 -> "DUP2_X1"
            Opcodes.DUP2_X2 -> "DUP2_X2"
            Opcodes.SWAP -> "SWAP"
            Opcodes.IADD -> "IADD"
            Opcodes.LADD -> "LADD"
            Opcodes.FADD -> "FADD"
            Opcodes.DADD -> "DADD"
            Opcodes.ISUB -> "ISUB"
            Opcodes.LSUB -> "LSUB"
            Opcodes.FSUB -> "FSUB"
            Opcodes.DSUB -> "DSUB"
            Opcodes.IMUL -> "IMUL"
            Opcodes.LMUL -> "LMUL"
            Opcodes.FMUL -> "FMUL"
            Opcodes.DMUL -> "DMUL"
            Opcodes.IDIV -> "IDIV"
            Opcodes.LDIV -> "LDIV"
            Opcodes.FDIV -> "FDIV"
            Opcodes.DDIV -> "DDIV"
            Opcodes.IREM -> "IREM"
            Opcodes.LREM -> "LREM"
            Opcodes.FREM -> "FREM"
            Opcodes.DREM -> "DREM"
            Opcodes.INEG -> "INEG"
            Opcodes.LNEG -> "LNEG"
            Opcodes.FNEG -> "FNEG"
            Opcodes.DNEG -> "DNEG"
            Opcodes.ISHL -> "ISHL"
            Opcodes.LSHL -> "LSHL"
            Opcodes.ISHR -> "ISHR"
            Opcodes.LSHR -> "LSHR"
            Opcodes.IUSHR -> "IUSHR"
            Opcodes.LUSHR -> "LUSHR"
            Opcodes.IAND -> "IAND"
            Opcodes.LAND -> "LAND"
            Opcodes.IOR -> "IOR"
            Opcodes.LOR -> "LOR"
            Opcodes.IXOR -> "IXOR"
            Opcodes.LXOR -> "LXOR"
            Opcodes.I2L -> "I2L"
            Opcodes.I2F -> "I2F"
            Opcodes.I2D -> "I2D"
            Opcodes.L2I -> "L2I"
            Opcodes.L2F -> "L2F"
            Opcodes.L2D -> "L2D"
            Opcodes.F2I -> "F2I"
            Opcodes.F2L -> "F2L"
            Opcodes.F2D -> "F2D"
            Opcodes.D2I -> "D2I"
            Opcodes.D2L -> "D2L"
            Opcodes.D2F -> "D2F"
            Opcodes.I2B -> "I2B"
            Opcodes.I2C -> "I2C"
            Opcodes.I2S -> "I2S"
            Opcodes.LCMP -> "LCMP"
            Opcodes.FCMPL -> "FCMPL"
            Opcodes.FCMPG -> "FCMPG"
            Opcodes.DCMPL -> "DCMPL"
            Opcodes.DCMPG -> "DCMPG"
            Opcodes.IFEQ -> "IFEQ"
            Opcodes.IFNE -> "IFNE"
            Opcodes.IFLT -> "IFLT"
            Opcodes.IFGE -> "IFGE"
            Opcodes.IFGT -> "IFGT"
            Opcodes.IFLE -> "IFLE"
            Opcodes.IF_ICMPEQ -> "IF_ICMPEQ"
            Opcodes.IF_ICMPNE -> "IF_ICMPNE"
            Opcodes.IF_ICMPLT -> "IF_ICMPLT"
            Opcodes.IF_ICMPGE -> "IF_ICMPGE"
            Opcodes.IF_ICMPGT -> "IF_ICMPGT"
            Opcodes.IF_ICMPLE -> "IF_ICMPLE"
            Opcodes.IF_ACMPEQ -> "IF_ACMPEQ"
            Opcodes.IF_ACMPNE -> "IF_ACMPNE"
            Opcodes.GOTO -> "GOTO"
            Opcodes.JSR -> "JSR"
            Opcodes.RET -> "RET"
            Opcodes.TABLESWITCH -> "TABLESWITCH"
            Opcodes.LOOKUPSWITCH -> "LOOKUPSWITCH"
            Opcodes.IRETURN -> "IRETURN"
            Opcodes.LRETURN -> "LRETURN"
            Opcodes.FRETURN -> "FRETURN"
            Opcodes.DRETURN -> "DRETURN"
            Opcodes.ARETURN -> "ARETURN"
            Opcodes.RETURN -> "RETURN"
            Opcodes.GETSTATIC -> "GETSTATIC"
            Opcodes.PUTSTATIC -> "PUTSTATIC"
            Opcodes.GETFIELD -> "GETFIELD"
            Opcodes.PUTFIELD -> "PUTFIELD"
            Opcodes.INVOKEVIRTUAL -> "INVOKEVIRTUAL"
            Opcodes.INVOKESPECIAL -> "INVOKESPECIAL"
            Opcodes.INVOKESTATIC -> "INVOKESTATIC"
            Opcodes.INVOKEINTERFACE -> "INVOKEINTERFACE"
            Opcodes.INVOKEDYNAMIC -> "INVOKEDYNAMIC"
            Opcodes.NEW -> "NEW"
            Opcodes.NEWARRAY -> "NEWARRAY"
            Opcodes.ANEWARRAY -> "ANEWARRAY"
            Opcodes.ARRAYLENGTH -> "ARRAYLENGTH"
            Opcodes.ATHROW -> "ATHROW"
            Opcodes.CHECKCAST -> "CHECKCAST"
            Opcodes.INSTANCEOF -> "INSTANCEOF"
            Opcodes.MONITORENTER -> "MONITORENTER"
            Opcodes.MONITOREXIT -> "MONITOREXIT"
            Opcodes.MULTIANEWARRAY -> "MULTIANEWARRAY"
            Opcodes.IFNULL -> "IFNULL"
            Opcodes.IFNONNULL -> "IFNONNULL"
            else -> "OP_$opcode"
        }
}
