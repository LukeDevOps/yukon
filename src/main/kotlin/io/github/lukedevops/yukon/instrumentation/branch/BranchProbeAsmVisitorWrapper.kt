package io.github.lukedevops.yukon.instrumentation.branch

import net.bytebuddy.asm.AsmVisitorWrapper
import net.bytebuddy.description.field.FieldDescription
import net.bytebuddy.description.field.FieldList
import net.bytebuddy.description.method.MethodList
import net.bytebuddy.description.type.TypeDescription
import net.bytebuddy.implementation.Implementation
import net.bytebuddy.jar.asm.ClassVisitor
import net.bytebuddy.jar.asm.ClassWriter
import net.bytebuddy.jar.asm.MethodVisitor
import net.bytebuddy.jar.asm.Opcodes
import net.bytebuddy.pool.TypePool

/**
 * Hosts the branch-tracking tier's raw ASM rewrite inside ByteBuddy's own class-transform
 * pipeline. This weaves branch probes in the same pass as the method-entry
 * [net.bytebuddy.asm.Advice] tier, instead of using a second, competing transformer.
 *
 * Rewriting a conditional jump into two edges introduces new basic blocks. The class file's stack
 * map frames then need recomputing, so [mergeWriter] asks ByteBuddy's writer to do that.
 *
 * [probeIndexBase] is where branch slots start in the class's shared probe array. Method-entry
 * probes occupy `[0, probeIndexBase)`.
 *
 * [branchSlotCapacity] is how many branch slots the array actually has, as sized from
 * [BranchSiteAnalyzer]'s pass over the same bytes. The rewrite must never allocate past it: a
 * site that would is left as the original instruction, uninstrumented, rather than emitting an
 * increment that would throw `ArrayIndexOutOfBoundsException` inside the application's own
 * method. A site count that differs from the analysis in either direction is reported once per
 * class through [onSiteCountMismatch], since a shortfall means slots have shifted and the
 * manifest's branch metadata no longer lines up with what each slot counts.
 *
 * [droppedOrdinalsByMethod] names each method's dropped sites by their per-method encounter
 * ordinal (see ADR 0025); a method absent from it, or every method when the default is left in
 * place, has nothing dropped.
 */
class BranchProbeAsmVisitorWrapper(
    private val eligibleMethods: (name: String, descriptor: String) -> Boolean,
    private val probeIndexBase: Int,
    private val branchSlotCapacity: Int = Int.MAX_VALUE,
    private val onSiteCountMismatch: (expectedSlots: Int, actualSlots: Int) -> Unit = { _, _ -> },
    private val droppedOrdinalsByMethod: (name: String, descriptor: String) -> Set<Int> = { _, _ -> emptySet() },
) : AsmVisitorWrapper {
    override fun mergeWriter(flags: Int): Int = flags or ClassWriter.COMPUTE_FRAMES

    override fun mergeReader(flags: Int): Int = flags

    override fun wrap(
        instrumentedType: TypeDescription,
        classVisitor: ClassVisitor,
        implementationContext: Implementation.Context,
        typePool: TypePool,
        fields: FieldList<FieldDescription.InDefinedShape>,
        methods: MethodList<*>,
        writerFlags: Int,
        readerFlags: Int,
    ): ClassVisitor {
        val ownerInternalName = instrumentedType.internalName
        var nextSlot = 0
        var slotsWanted = 0

        return object : ClassVisitor(Opcodes.ASM9, classVisitor) {
            override fun visitMethod(
                access: Int,
                name: String,
                descriptor: String,
                signature: String?,
                exceptions: Array<out String>?,
            ): MethodVisitor {
                val delegate = super.visitMethod(access, name, descriptor, signature, exceptions)
                if (!eligibleMethods(name, descriptor)) return delegate
                return BranchProbeMethodVisitor(
                    delegate,
                    ownerInternalName,
                    probeIndexBase,
                    droppedOrdinalsByMethod(name, descriptor),
                ) { outcomeCount ->
                    slotsWanted += outcomeCount
                    if (nextSlot + outcomeCount > branchSlotCapacity) return@BranchProbeMethodVisitor BranchProbeMethodVisitor.NO_SLOT
                    val base = nextSlot
                    nextSlot += outcomeCount
                    base
                }
            }

            override fun visitEnd() {
                if (branchSlotCapacity != Int.MAX_VALUE && slotsWanted != branchSlotCapacity) {
                    onSiteCountMismatch(branchSlotCapacity, slotsWanted)
                }
                super.visitEnd()
            }
        }
    }
}
