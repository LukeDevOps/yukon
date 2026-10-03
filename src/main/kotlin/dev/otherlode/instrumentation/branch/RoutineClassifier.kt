package dev.otherlode.instrumentation.branch

import dev.otherlode.export.RoutineKind
import net.bytebuddy.jar.asm.Opcodes

/**
 * Gives each kept outcome of one method its [RoutineKind]. It reads the control-flow graph and
 * dominators [GuardAnalysis] built for the method.
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
 *    copy, which no handler dominates. The copy is routine only when that normal-path twin exists:
 *    a tracked site with the same opcode, line and lead-up outside every such handler. A `try`
 *    that can only leave by an exception gets no normal-path copy, and its handler's copy is then
 *    the only one there is.
 * 2. [RoutineKind.THROW_ONLY]: the path never rejoins, never returns, and ends in a throw that
 *    leaves the method: no typed handler in the method covers it, other than one whose every path
 *    ends in a throw (Kotlin's `use`, try-with-resources, a catch that wraps and throws again). A
 *    throw the method catches, whatever type the handler names, and carries on from runs that
 *    handler's code, which is the adopter's own logic, so it stays a finding. Its only calls
 *    build an exception or its message: `new` of a `Throwable` and its constructor,
 *    `StringBuilder` and its `append` and `toString`, `String.valueOf`, `toString` on
 *    `Object` or `String`, Kotlin's `Intrinsics.stringPlus`, and a `StringConcatFactory`
 *    `invokedynamic`. A call to one of Kotlin's throwing intrinsics (a `lateinit` check, an older
 *    compiler's `!!`) ends the path as a throw does. It may not store to a field or an array, or
 *    enter a monitor.
 * 3. [RoutineKind.NULL_DEFAULT]: the site is a null check, the outcome is its null side, and the
 *    path holds only constants, local loads and stores, stack moves, `checkcast`, jumps and
 *    returns. So it calls nothing, reads no field and throws nothing.
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

    /** Each typed `try` range whose handler can carry on: some path through it does not end in a throw. */
    private val handlingCatches: List<TryCatch> by lazy {
        instructions.tryCatches.filter { it.type != null && !alwaysThrows(it.handler) }
    }

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
        if (inFinallyHandler(siteOrdinal) && hasNormalPathTwin(siteOrdinal)) return RoutineKind.FINALLY_COPY
        if (!dominance.isReachable(outcomeNode)) return RoutineKind.NONE
        val path = walk(outcomeNode, isNullSide = nullSide(siteOrdinal) == offset)
        return when {
            path.throwOnly -> RoutineKind.THROW_ONLY
            path.nullDefault -> RoutineKind.NULL_DEFAULT
            else -> RoutineKind.NONE
        }
    }

    private fun inFinallyHandler(ordinal: Int): Boolean = finallyHandlers.any { dominance.dominates(it, ordinal) }

    /**
     * Whether a tracked site outside every `finally` handler has [ordinal]'s opcode and line and
     * the same lead-up, as the normal path's copy of a `finally` body does; see [sameLeadUp]. The
     * lead-up tells two conditions written on one line apart. Without a line table, the opcode and
     * the lead-up decide.
     */
    private fun hasNormalPathTwin(ordinal: Int): Boolean {
        val opcode = instructions.opcodes[ordinal]
        val line = instructions.lines[ordinal]
        return (0 until realCount).any { other ->
            other != ordinal &&
                instructions.isTrackedSite(other) &&
                instructions.opcodes[other] == opcode &&
                (line < 0 || instructions.lines[other] == line) &&
                sameLeadUp(ordinal, other) &&
                !inFinallyHandler(other)
        }
    }

    /**
     * Whether the instructions that compute the condition at [first] and at [second] match, opcode
     * and operand, walking back from each site until either reaches the end of the statement
     * before it (a store, a jump, a return, a throw, a pop) or the start of the method, at most
     * [LEAD_UP] instructions.
     */
    private fun sameLeadUp(
        first: Int,
        second: Int,
    ): Boolean {
        val copies = copyStarts(first, second)
        val pairedSlots = HashMap<Int, Int>()
        val pairedBack = HashMap<Int, Int>()
        for (back in 1..LEAD_UP) {
            val a = first - back
            val b = second - back
            if (a < 0 || b < 0 || endsStatement(a) || endsStatement(b)) return true
            if (instructions.opcodes[a] != instructions.opcodes[b] || !sameOperand(a, b, copies, pairedSlots, pairedBack)) {
                return false
            }
        }
        return true
    }

    /**
     * Where the two copies a twin check compares begin: the exception-path copy at [exception],
     * the instruction after its handler's store of the caught exception, and the normal-path copy
     * at [normal], where the handler's protected range ends, or -1 when none ends before it.
     */
    private class CopyStarts(
        val exception: Int,
        val normal: Int,
    )

    /** The [CopyStarts] for the exception-path [site] and its candidate twin [twin]. */
    private fun copyStarts(
        site: Int,
        twin: Int,
    ): CopyStarts {
        val handler = finallyHandlers.filter { dominance.dominates(it, site) }.maxOrNull() ?: return CopyStarts(site, -1)
        val normal =
            instructions.tryCatches
                .filter { it.type == null && it.handler == handler && it.end <= twin }
                .maxOfOrNull { it.end } ?: -1
        return CopyStarts(handler + 1, normal)
    }

    /** Whether an instruction in [from] until [to] stores to local [slot]. */
    private fun storesTo(
        slot: Int,
        from: Int,
        to: Int,
    ): Boolean =
        from in 0..to &&
            (from until to).any { instructions.opcodes[it] in Opcodes.ISTORE..Opcodes.ASTORE && instructions.operands[it] == slot }

    /**
     * Whether the instructions at [first] and [second] have the same operand. The exception-path
     * copy of a `finally` body stores the caught exception first, so every local the body declares
     * sits in a different slot there than in the normal-path copy. A local therefore compares by
     * its source name where the local variable table names both. Without names, two slots pair
     * only when each copy declared its own, storing to it after the copy began ([copies]); the
     * pairing must then hold one to one across the lead-up ([pairedSlots] and [pairedBack]). Any
     * other slot, a parameter or a local the method had before the `try`, compares exactly, since
     * both copies share it.
     */
    private fun sameOperand(
        first: Int,
        second: Int,
        copies: CopyStarts,
        pairedSlots: MutableMap<Int, Int>,
        pairedBack: MutableMap<Int, Int>,
    ): Boolean {
        val a = instructions.operands[first]
        val b = instructions.operands[second]
        if (a !is Int || b !is Int) return a == b
        val nameA = instructions.localName(first, a)
        val nameB = instructions.localName(second, b)
        if (nameA != null && nameB != null) return nameA == nameB
        if (nameA != null || nameB != null) return false
        if (!storesTo(a, copies.exception, first) || !storesTo(b, copies.normal, second)) return a == b
        return pairedSlots.getOrPut(a) { b } == b && pairedBack.getOrPut(b) { a } == a
    }

    private fun endsStatement(ordinal: Int): Boolean {
        val opcode = instructions.opcodes[ordinal]
        return opcode in Opcodes.ISTORE..Opcodes.ASTORE ||
            opcode in Opcodes.IFEQ..Opcodes.LOOKUPSWITCH ||
            opcode in Opcodes.IRETURN..Opcodes.RETURN ||
            opcode == Opcodes.ATHROW ||
            opcode == Opcodes.POP ||
            opcode == Opcodes.POP2 ||
            opcode == Opcodes.IFNULL ||
            opcode == Opcodes.IFNONNULL
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
    private fun findFinallyHandlers(): List<Int> =
        instructions.tryCatches
            .filter { it.type == null }
            .map { it.handler }
            .distinct()
            .filter(::rethrows)

    /**
     * Whether the handler starting at [handler] stores what it caught in a local and, somewhere in
     * the code it dominates, loads that local right before an `ATHROW`.
     */
    private fun rethrows(handler: Int): Boolean {
        val opcodes = instructions.opcodes
        if (opcodes[handler] != Opcodes.ASTORE) return false
        val caught = instructions.operands[handler] as? Int ?: return false
        return (handler + 1 until realCount - 1).any { ordinal ->
            opcodes[ordinal] == Opcodes.ALOAD &&
                instructions.operands[ordinal] == caught &&
                opcodes[ordinal + 1] == Opcodes.ATHROW &&
                dominance.dominates(handler, ordinal) &&
                dominance.dominates(handler, ordinal + 1)
        }
    }

    /**
     * Whether every normal path from [handler] ends in a throw inside the code the handler
     * dominates: it rethrows what it caught, or wraps it and throws that. A path that returns, or
     * leaves the handler's code (falling out of the `try` statement, going round a loop again, a
     * try expression's value), carries on, and the handler deals with the exception. A `try`
     * nested inside the handler counts its own handler as a path.
     */
    private fun alwaysThrows(handler: Int): Boolean {
        val visited = HashSet<Int>()
        val pending = ArrayDeque<Int>()
        pending.addLast(handler)
        while (pending.isNotEmpty()) {
            val node = pending.removeLast()
            if (!visited.add(node)) continue
            if (!dominance.dominates(handler, node)) return false
            if (node < realCount) {
                val opcode = instructions.opcodes[node]
                if (opcode == Opcodes.ATHROW) continue
                if (opcode in Opcodes.IRETURN..Opcodes.RETURN) return false
            }
            graph.normalSuccessors[node].forEach(pending::addLast)
            for (nested in instructions.tryCatches) {
                if (node in nested.start until nested.end && dominance.dominates(handler, nested.handler)) {
                    pending.addLast(nested.handler)
                }
            }
        }
        return true
    }

    /** Whether a handler in this method that can carry on covers the instruction at [ordinal]. */
    private fun caughtHere(ordinal: Int): Boolean = handlingCatches.any { ordinal >= it.start && ordinal < it.end }

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
                        if (caughtHere(node)) throwOnly = false
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

    /**
     * Constants, local loads and stores, stack moves, `checkcast` and jumps: nothing that calls,
     * reads a field or throws. Arithmetic is left out, `IINC` included, since a null side that counts is
     * the adopter's logic rather than a default.
     */
    private fun isPlain(opcode: Int): Boolean =
        opcode == Opcodes.NOP ||
            opcode in Opcodes.ACONST_NULL..Opcodes.LDC ||
            opcode in Opcodes.ILOAD..Opcodes.ALOAD ||
            opcode in Opcodes.ISTORE..Opcodes.ASTORE ||
            opcode in Opcodes.POP..Opcodes.SWAP ||
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

        /** The most instructions before a site [sameLeadUp] compares. */
        const val LEAD_UP = 4

        /** Kotlin's intrinsics that always throw: the one a `lateinit` check calls, and the ones older kotlinc called for `!!`. */
        val THROWING_INTRINSICS =
            setOf("throwUninitializedPropertyAccessException", "throwUninitializedProperty", "throwNpe", "throwJavaNpe")

        /** Instructions that change state outside the path's own locals and stack, or enter a subroutine. */
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
