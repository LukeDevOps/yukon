package io.github.lukedevops.yukon.instrumentation.branch

import io.github.lukedevops.yukon.export.RoutineKind
import net.bytebuddy.jar.asm.Opcodes

/**
 * Gives each kept outcome of one method its [RoutineKind], per ADR 0046. It reads the control-flow
 * graph and dominators [GuardAnalysis] built for the method.
 *
 * An outcome's path is the code its outcome node dominates, followed from the outcome's target
 * along normal edges. Edges into a catch handler are not followed. The path ends where it
 * rejoins, at the first instruction the outcome does not dominate, or where it leaves the method.
 *
 * The kinds are tested in this order, and the first that holds wins:
 *
 * 1. [RoutineKind.FINALLY_COPY]: the site's jump or switch is dominated by the first instruction
 *    of a catch-any handler that stores the caught exception in a local, and that handler's code
 *    loads the same local and throws it. That is the exception-path copy of a `finally` body, as
 *    javac and kotlinc emit it. A typed `catch` never counts, and neither does the normal path's
 *    copy, which no handler dominates.
 * 2. [RoutineKind.THROW_ONLY]: the path never rejoins, never returns, and ends in a throw. Its only
 *    calls build an exception or its message: `new` of a `Throwable` and its constructor,
 *    `StringBuilder` and its `append` and `toString`, `String.valueOf`, `toString` on `Object` or
 *    `String`, Kotlin's `Intrinsics.stringPlus`, and a `StringConcatFactory` `invokedynamic`. A call
 *    to one of Kotlin's throwing intrinsics (a `lateinit` check, an older compiler's `!!`) ends the
 *    path as a throw does. It may not store to a field or an array, or enter a monitor.
 * 3. [RoutineKind.NULL_DEFAULT]: the site is a null check, the outcome is its null side, and the
 *    path holds only constants, local loads and stores, stack moves, casts, jumps and returns. So
 *    it calls nothing, reads no field and throws nothing.
 *
 * [isThrowable] tells whether a class, by internal name, is a `Throwable`.
 */
internal class RoutineClassifier(
    private val instructions: MethodInstructions,
    private val graph: GuardAnalysis.Graph,
    private val dominance: GuardAnalysis.Dominance,
    private val isThrowable: (internalName: String) -> Boolean,
) {
    private val realCount = instructions.size

    /** The first instruction of each catch-any handler that copies a `finally` body. */
    private val finallyHandlers: List<Int> by lazy { findFinallyHandlers() }

    /** Every instruction another instruction jumps or switches to, for [nullSide]. */
    private val jumpTargets: Set<Int> by lazy {
        instructions.targets.flatMapTo(HashSet()) { it?.asIterable() ?: emptyList() }
    }

    /**
     * The routine kind of the outcome at [offset] of the site whose jump or switch is at
     * [siteOrdinal]. [outcomeNode] is that outcome's node in the graph.
     */
    fun kindOf(
        siteOrdinal: Int,
        outcomeNode: Int,
        offset: Int,
    ): RoutineKind {
        if (finallyHandlers.any { dominance.dominates(it, siteOrdinal) }) return RoutineKind.FINALLY_COPY
        if (!dominance.isReachable(outcomeNode)) return RoutineKind.NONE
        val path = walk(outcomeNode, isNullSide = nullSide(siteOrdinal) == offset)
        return when {
            path.throwOnly -> RoutineKind.THROW_ONLY
            path.nullDefault -> RoutineKind.NULL_DEFAULT
            else -> RoutineKind.NONE
        }
    }

    /**
     * The offset of the null side of the conditional at [ordinal], or -1 when it is not a null
     * check. `IFNULL` jumps on null, so its taken side (offset 0) is the null side, and `IFNONNULL`'s
     * fall-through (offset 1) is. An `IF_ACMPEQ` or `IF_ACMPNE` is a null check when one operand is
     * the `null` constant pushed right before it: `ACONST_NULL` just before the jump, or just before
     * a local load that comes just before the jump. Neither instruction may be a jump target, or
     * another path could have pushed a different value.
     */
    private fun nullSide(ordinal: Int): Int {
        val opcodes = instructions.opcodes
        return when (opcodes[ordinal]) {
            Opcodes.IFNULL -> 0
            Opcodes.IFNONNULL -> 1
            Opcodes.IF_ACMPEQ -> if (comparesWithNull(ordinal)) 0 else -1
            Opcodes.IF_ACMPNE -> if (comparesWithNull(ordinal)) 1 else -1
            else -> -1
        }
    }

    private fun comparesWithNull(ordinal: Int): Boolean {
        val opcodes = instructions.opcodes
        if (ordinal in jumpTargets) return false
        if (ordinal >= 1 && opcodes[ordinal - 1] == Opcodes.ACONST_NULL) return true
        return ordinal >= 2 &&
            opcodes[ordinal - 2] == Opcodes.ACONST_NULL &&
            opcodes[ordinal - 1] == Opcodes.ALOAD &&
            (ordinal - 1) !in jumpTargets
    }

    /**
     * Each catch-any handler whose first instruction is an `ASTORE` and whose code, the part the
     * handler's first instruction dominates, holds an `ALOAD` of the same local right before an
     * `ATHROW`.
     */
    private fun findFinallyHandlers(): List<Int> {
        val opcodes = instructions.opcodes
        val handlers = instructions.tryCatches.filter { it.type == null }.map { it.handler }.distinct()
        return handlers.filter { handler ->
            if (opcodes[handler] != Opcodes.ASTORE) return@filter false
            val caught = instructions.operands[handler] as? Int ?: return@filter false
            (handler + 1 until realCount - 1).any { ordinal ->
                opcodes[ordinal] == Opcodes.ALOAD &&
                    instructions.operands[ordinal] == caught &&
                    opcodes[ordinal + 1] == Opcodes.ATHROW &&
                    dominance.dominates(handler, ordinal) &&
                    dominance.dominates(handler, ordinal + 1)
            }
        }
    }

    /** What one outcome's path does, from [walk]. */
    private class Path(
        val throwOnly: Boolean,
        val nullDefault: Boolean,
    )

    /**
     * Follows the path of [outcomeNode] and tests it for [RoutineKind.THROW_ONLY] and, when
     * [isNullSide], for [RoutineKind.NULL_DEFAULT]. It stops early once neither can hold.
     */
    private fun walk(
        outcomeNode: Int,
        isNullSide: Boolean,
    ): Path {
        var throwOnly = true
        var nullDefault = isNullSide
        var throws = false
        val visited = HashSet<Int>()
        val pending = ArrayDeque<Int>()
        graph.normalSuccessors[outcomeNode].forEach(pending::addLast)
        while (pending.isNotEmpty() && (throwOnly || nullDefault)) {
            val node = pending.removeLast()
            if (!visited.add(node)) continue
            if (!dominance.dominates(outcomeNode, node)) {
                throwOnly = false
                continue
            }
            if (node < realCount) {
                when (val step = step(node)) {
                    Step.RETURN -> {
                        throwOnly = false
                        continue
                    }

                    Step.THROW -> {
                        throws = true
                        nullDefault = false
                        continue
                    }

                    else -> {
                        if (step != Step.PLAIN) nullDefault = false
                        if (step == Step.OTHER) throwOnly = false
                    }
                }
            }
            graph.normalSuccessors[node].forEach(pending::addLast)
        }
        return Path(throwOnly && throws, nullDefault)
    }

    /** What one instruction on a path means for [walk]. */
    private enum class Step {
        /** Allowed on a null side's path and on a throw path. */
        PLAIN,

        /** Leaves the method normally. */
        RETURN,

        /** Throws, or calls something that always throws. Ends the path. */
        THROW,

        /**
         * Allowed on a throw path only: a call that builds an exception or its message, or an
         * instruction such as a field read that calls nothing but does more than a null side may.
         */
        THROW_PATH,

        /** Allowed on neither path. */
        OTHER,
    }

    private fun step(ordinal: Int): Step {
        val opcode = instructions.opcodes[ordinal]
        val operand = instructions.operands[ordinal]
        return when {
            opcode in Opcodes.IRETURN..Opcodes.RETURN -> Step.RETURN
            opcode == Opcodes.ATHROW -> Step.THROW
            opcode == Opcodes.INVOKEDYNAMIC -> if (isStringConcat(operand as? CalledMethod)) Step.THROW_PATH else Step.OTHER
            opcode in Opcodes.INVOKEVIRTUAL..Opcodes.INVOKEINTERFACE -> callStep(operand as? CalledMethod)
            opcode == Opcodes.NEW -> newStep(operand as? String)
            isPlain(opcode) -> Step.PLAIN
            opcode in THROW_PATH_FORBIDDEN -> Step.OTHER
            else -> Step.THROW_PATH
        }
    }

    private fun callStep(called: CalledMethod?): Step {
        if (called == null) return Step.OTHER
        val owner = called.owner
        val name = called.name
        return when {
            owner.endsWith(INTRINSICS_SUFFIX) && name in THROWING_INTRINSICS -> Step.THROW
            owner.endsWith(INTRINSICS_SUFFIX) && name == "stringPlus" -> Step.THROW_PATH
            owner == STRING_BUILDER && (name == "<init>" || name == "append" || name == "toString") -> Step.THROW_PATH
            owner == STRING && name == "valueOf" -> Step.THROW_PATH
            (owner == OBJECT || owner == STRING) && name == "toString" && called.descriptor == "()Ljava/lang/String;" -> Step.THROW_PATH
            name == "<init>" && isThrowable(owner) -> Step.THROW_PATH
            else -> Step.OTHER
        }
    }

    private fun newStep(type: String?): Step =
        when {
            type == null -> Step.OTHER
            type == STRING_BUILDER || isThrowable(type) -> Step.THROW_PATH
            else -> Step.OTHER
        }

    private fun isStringConcat(called: CalledMethod?): Boolean =
        called != null &&
            called.owner == "java/lang/invoke/StringConcatFactory" &&
            (called.name == "makeConcatWithConstants" || called.name == "makeConcat")

    /** Constants, local loads and stores, stack moves, casts and jumps: nothing that calls, reads a field or throws. */
    private fun isPlain(opcode: Int): Boolean =
        opcode == Opcodes.NOP ||
            opcode in Opcodes.ACONST_NULL..Opcodes.LDC ||
            opcode in Opcodes.ILOAD..Opcodes.ALOAD ||
            opcode in Opcodes.ISTORE..Opcodes.ASTORE ||
            opcode in Opcodes.POP..Opcodes.SWAP ||
            opcode == Opcodes.IINC ||
            opcode in Opcodes.IFEQ..Opcodes.GOTO ||
            opcode == Opcodes.TABLESWITCH ||
            opcode == Opcodes.LOOKUPSWITCH ||
            opcode == Opcodes.IFNULL ||
            opcode == Opcodes.IFNONNULL ||
            opcode == Opcodes.CHECKCAST

    private companion object {
        // A suffix, not the whole name: shadowJar rewrites any literal in this agent's own code
        // that starts with the Kotlin package.
        const val INTRINSICS_SUFFIX = "/jvm/internal/Intrinsics"
        const val STRING_BUILDER = "java/lang/StringBuilder"
        const val STRING = "java/lang/String"
        const val OBJECT = "java/lang/Object"

        /** Kotlin's intrinsics that always throw: a `lateinit` check's, and `!!`'s before `checkNotNull`. */
        val THROWING_INTRINSICS =
            setOf("throwUninitializedPropertyAccessException", "throwUninitializedProperty", "throwNpe", "throwJavaNpe")

        /** Instructions that change state outside the path's own locals and stack. */
        val THROW_PATH_FORBIDDEN =
            setOf(
                Opcodes.PUTFIELD,
                Opcodes.PUTSTATIC,
                Opcodes.IASTORE,
                Opcodes.LASTORE,
                Opcodes.FASTORE,
                Opcodes.DASTORE,
                Opcodes.AASTORE,
                Opcodes.BASTORE,
                Opcodes.CASTORE,
                Opcodes.SASTORE,
                Opcodes.MONITORENTER,
                Opcodes.MONITOREXIT,
                Opcodes.JSR,
                Opcodes.RET,
            )
    }
}
