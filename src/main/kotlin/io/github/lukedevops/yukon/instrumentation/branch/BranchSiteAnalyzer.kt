package io.github.lukedevops.yukon.instrumentation.branch

import net.bytebuddy.jar.asm.ClassReader
import net.bytebuddy.jar.asm.ClassVisitor
import net.bytebuddy.jar.asm.Label
import net.bytebuddy.jar.asm.MethodVisitor
import net.bytebuddy.jar.asm.Opcodes

/**
 * Finds every [ConditionalJump] and every `TABLESWITCH`/`LOOKUPSWITCH` in a class's original
 * bytecode, and the first source line of each method. Only methods [methodFilter] accepts are
 * searched.
 *
 * This is read-only. It only sizes the probe array and builds manifest metadata, ahead of the
 * actual rewrite that [BranchProbeAsmVisitorWrapper] performs later in the same class transform.
 *
 * A switch's outcome count is one per distinct case target plus the default. A `TABLESWITCH`
 * over sparse case values carries filler entries for the gaps that jump straight to the default
 * label; those are the default outcome, not cases of their own, and counting them separately
 * would report "case 4 never hit" for a switch that has no case 4. [BranchProbeMethodVisitor]
 * applies the same rule when it rewrites the switch, so the two agree on the slot count.
 */
object BranchSiteAnalyzer {
    /** Everything one pass over a class's bytecode yields. */
    class Analysis(
        val sites: List<BranchSite>,
        private val firstLineByMethod: Map<Pair<String, String>, Int>,
    ) {
        /** First line-number-table entry of the method, or -1 when the class carries no debug info or the bytes were never read. */
        fun firstLineOf(
            name: String,
            descriptor: String,
        ): Int = firstLineByMethod[name to descriptor] ?: -1

        companion object {
            val EMPTY = Analysis(emptyList(), emptyMap())
        }
    }

    /** How many probe slots a switch with these case targets and this default owns. */
    fun switchOutcomeCount(
        dflt: Label,
        labels: Array<out Label>,
    ): Int = labels.count { it !== dflt } + 1

    fun analyze(
        classBytes: ByteArray,
        methodFilter: (name: String, descriptor: String) -> Boolean,
    ): Analysis {
        val sites = mutableListOf<BranchSite>()
        val firstLines = mutableMapOf<Pair<String, String>, Int>()
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
                            firstLines.putIfAbsent(name to descriptor, line)
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
                            sites +=
                                BranchSite(name, descriptor, currentLine, nextSiteIndex, outcomeCount = switchOutcomeCount(dflt, labels))
                            nextSiteIndex++
                        }

                        override fun visitLookupSwitchInsn(
                            dflt: Label,
                            keys: IntArray,
                            labels: Array<out Label>,
                        ) {
                            sites +=
                                BranchSite(name, descriptor, currentLine, nextSiteIndex, outcomeCount = switchOutcomeCount(dflt, labels))
                            nextSiteIndex++
                        }
                    }
                }
            }

        ClassReader(classBytes).accept(classVisitor, ClassReader.SKIP_FRAMES)
        return Analysis(sites, firstLines)
    }
}
