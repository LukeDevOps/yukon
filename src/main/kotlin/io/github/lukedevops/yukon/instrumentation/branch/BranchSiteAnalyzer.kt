package io.github.lukedevops.yukon.instrumentation.branch

import net.bytebuddy.jar.asm.ClassReader
import net.bytebuddy.jar.asm.ClassVisitor
import net.bytebuddy.jar.asm.Label
import net.bytebuddy.jar.asm.MethodVisitor
import net.bytebuddy.jar.asm.Opcodes

/**
 * Finds every [ConditionalJump] and every `TABLESWITCH`/`LOOKUPSWITCH` in a class's original
 * bytecode. Only methods [methodFilter] accepts are searched.
 *
 * This is read-only. It only sizes the probe array and builds manifest metadata, ahead of the
 * actual rewrite that [BranchProbeAsmVisitorWrapper] performs later in the same class transform.
 */
object BranchSiteAnalyzer {
    fun analyze(
        classBytes: ByteArray,
        methodFilter: (name: String, descriptor: String) -> Boolean,
    ): List<BranchSite> {
        val sites = mutableListOf<BranchSite>()
        var nextSiteIndex = 0

        val classVisitor =
            object : ClassVisitor(Opcodes.ASM9) {
                override fun visitMethod(
                    access: Int,
                    name: String,
                    descriptor: String,
                    signature: String?,
                    exceptions: Array<out String>?,
                ): MethodVisitor? {
                    if (!methodFilter(name, descriptor)) return null

                    return object : MethodVisitor(Opcodes.ASM9) {
                        var currentLine = -1

                        override fun visitLineNumber(
                            line: Int,
                            start: Label,
                        ) {
                            currentLine = line
                        }

                        override fun visitJumpInsn(
                            opcode: Int,
                            label: Label,
                        ) {
                            if (!ConditionalJump.isTracked(opcode)) return
                            sites += BranchSite(name, descriptor, currentLine, nextSiteIndex)
                            nextSiteIndex++
                        }

                        override fun visitTableSwitchInsn(
                            min: Int,
                            max: Int,
                            dflt: Label,
                            vararg labels: Label,
                        ) {
                            sites += BranchSite(name, descriptor, currentLine, nextSiteIndex, outcomeCount = labels.size + 1)
                            nextSiteIndex++
                        }

                        override fun visitLookupSwitchInsn(
                            dflt: Label,
                            keys: IntArray,
                            labels: Array<out Label>,
                        ) {
                            sites += BranchSite(name, descriptor, currentLine, nextSiteIndex, outcomeCount = labels.size + 1)
                            nextSiteIndex++
                        }
                    }
                }
            }

        ClassReader(classBytes).accept(classVisitor, ClassReader.SKIP_FRAMES)
        return sites
    }
}
