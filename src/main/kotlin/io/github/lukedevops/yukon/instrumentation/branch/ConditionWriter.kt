package io.github.lukedevops.yukon.instrumentation.branch

import io.github.lukedevops.yukon.export.ConditionPart
import io.github.lukedevops.yukon.export.ConditionPartKind
import io.github.lukedevops.yukon.instrumentation.branch.ConditionFingerprinter.Insn
import net.bytebuddy.jar.asm.Label
import net.bytebuddy.jar.asm.Opcodes
import net.bytebuddy.jar.asm.Type

/** The language a class was compiled from, as far as [ConditionWriter] needs to know it. */
enum class SourceLanguage {
    KOTLIN,
    JAVA,
    SCALA,
}

/** One `LocalVariableTable` entry's name and descriptor. */
internal class LocalVariable(
    val name: String,
    val descriptor: String,
)

/**
 * One method's instructions as [ConditionFingerprinter] recorded them, with what [ConditionWriter]
 * needs besides: the labels placed before each instruction, the exception handler labels, the
 * local variable table, and the start of each tracked site's window.
 */
internal class MethodInstructionsView(
    val insns: List<Insn>,
    val labelsAt: Map<Int, List<Label>>,
    val handlerLabels: Set<Label>,
    val local: (varIndex: Int, instructionIndex: Int) -> LocalVariable?,
    val windowStartOf: (siteInstructionIndex: Int) -> Int?,
) {
    /** The instruction index each label is placed before. */
    val indexOfLabel: Map<Label, Int> by lazy {
        val result = HashMap<Label, Int>()
        for ((index, labels) in labelsAt) for (label in labels) result.putIfAbsent(label, index)
        result
    }

    /** The index of every jump and switch that can go to each label. */
    val jumpSources: Map<Label, List<Int>> by lazy {
        val result = HashMap<Label, MutableList<Int>>()
        for ((index, insn) in insns.withIndex()) {
            for (target in targetsOf(insn)) result.getOrPut(target) { mutableListOf() } += index
        }
        result
    }

    companion object {
        fun targetsOf(insn: Insn): List<Label> =
            when (insn) {
                is Insn.Jump -> listOf(insn.target)
                is Insn.TableSwitch -> listOf(insn.dflt) + insn.labels
                is Insn.LookupSwitch -> listOf(insn.dflt) + insn.labels
                else -> emptyList()
            }
    }
}

/**
 * Writes a branch site's condition in the class's source language, as a list of parts. See ADR
 * 0037.
 *
 * The writer walks the site's fingerprint window with a stack of expression trees and reads the
 * site's jump the way its fall-through side sees it: the jump's own test, negated. A switch's
 * condition is its subject. An instruction or call it has no rule for becomes a placeholder. When
 * the whole condition would be one placeholder, the site has no condition and the writer returns
 * an empty list.
 *
 * Compiler idioms are read back to source only in shapes confirmed against `javap` output.
 * Kotlin runtime owners are matched by suffix, since the shaded jar rewrites `kotlin/` literals in
 * this agent's own code.
 */
internal object ConditionWriter {
    /**
     * The condition of the site at [siteIndex] in [method], from the window that starts at
     * [windowStart]. [ownerInternalName] is the class that declares the method. [isEnum] tells
     * whether a class, by internal name, is an enum, and answers false when it cannot tell.
     */
    fun write(
        method: MethodInstructionsView,
        windowStart: Int,
        siteIndex: Int,
        language: SourceLanguage,
        ownerInternalName: String,
        isEnum: (internalName: String) -> Boolean = { false },
    ): List<ConditionPart> {
        val expression =
            try {
                val names = Names(language)
                val scalaEquality =
                    if (language == SourceLanguage.SCALA) ScalaEquality.match(method, siteIndex, names, ownerInternalName) else null
                scalaEquality ?: readSite(method, windowStart, siteIndex, names, ownerInternalName, isEnum)
            } catch (_: Unwritable) {
                null
            } ?: return emptyList()
        if (isOnlyPlaceholder(expression)) return emptyList()
        return Renderer(language).render(expression)
    }

    /**
     * The value on top of the operand stack just before instruction [end], walked from
     * [windowStart], as parts. It is empty when the walk fails or the value is only a
     * placeholder. [SwitchLowering] writes a lowered switch's subject with it. See ADR 0038.
     */
    fun writeValue(
        method: MethodInstructionsView,
        windowStart: Int,
        end: Int,
        language: SourceLanguage,
        ownerInternalName: String,
    ): List<ConditionPart> {
        val value = valueBefore(method, windowStart, end, language, ownerInternalName) ?: return emptyList()
        if (isOnlyPlaceholder(value)) return emptyList()
        return Renderer(language).render(value)
    }

    /**
     * `subject == "literal"` when [equal] is true, and `subject != "literal"` when it is false.
     * The subject is the value [writeValue] reads, or a placeholder when it cannot be read.
     * [SwitchLowering] writes each string case check it keeps as a plain site with this. See ADR
     * 0038.
     */
    fun writeLiteralEquality(
        method: MethodInstructionsView,
        windowStart: Int,
        end: Int,
        literal: String,
        equal: Boolean,
        language: SourceLanguage,
        ownerInternalName: String,
    ): List<ConditionPart> {
        val value = valueBefore(method, windowStart, end, language, ownerInternalName) ?: Expr.Hole(STRING)
        return Renderer(language).render(Expr.Binary(if (equal) Op.EQ else Op.NE, value, Expr.Str(literal), "Z"))
    }

    private fun valueBefore(
        method: MethodInstructionsView,
        windowStart: Int,
        end: Int,
        language: SourceLanguage,
        ownerInternalName: String,
    ): Expr? =
        try {
            Evaluator(method, windowStart, Names(language), ownerInternalName).stackBefore(end)?.lastOrNull()
        } catch (_: Unwritable) {
            null
        }

    private fun readSite(
        method: MethodInstructionsView,
        windowStart: Int,
        siteIndex: Int,
        names: Names,
        ownerInternalName: String,
        isEnum: (internalName: String) -> Boolean,
    ): Expr? {
        val stack = Evaluator(method, windowStart, names, ownerInternalName).stackBefore(siteIndex) ?: return null

        fun isEnumValue(value: Expr): Boolean {
            val desc = value.desc ?: return false
            return desc.startsWith("L") && desc.endsWith(";") && isEnum(desc.substring(1, desc.length - 1))
        }

        // kotlinc compiles `==` between enum values to if_acmp. Identity is equality for an enum,
        // so `==` is exact there, while `===` is the only true reading for any other class.
        val identityIsEquality = { left: Expr, right: Expr ->
            names.language == SourceLanguage.KOTLIN && (isEnumValue(left) || isEnumValue(right))
        }
        return FallThrough.read(method.insns[siteIndex], stack, identityIsEquality)
    }

    private fun isOnlyPlaceholder(expression: Expr): Boolean =
        when (expression) {
            is Expr.Hole -> true
            is Expr.Not -> isOnlyPlaceholder(expression.operand)
            is Expr.Retyped -> isOnlyPlaceholder(expression.inner)
            else -> false
        }

    /** Thrown inside the writer when the window cannot be read, such as a stack underflow. */
    private class Unwritable : RuntimeException(null, null, false, false)

    private fun unwritable(): Nothing = throw Unwritable()

    /**
     * An expression tree node. [desc] is the JVM descriptor of the value when it is known, and
     * [size] is its width in stack words.
     */
    private sealed class Expr(
        val desc: String?,
        val size: Int = if (desc == "J" || desc == "D") 2 else 1,
    ) {
        class Hole(
            desc: String?,
            size: Int = if (desc == "J" || desc == "D") 2 else 1,
        ) : Expr(desc, size)

        /** Source text that needs no parentheses in any position, such as a name or a literal. */
        class Atom(
            val text: String,
            desc: String?,
            val negativeNumber: Boolean = false,
        ) : Expr(desc)

        class IntConst(
            val value: Int,
        ) : Expr("I")

        class Str(
            val value: String,
        ) : Expr("Ljava/lang/String;")

        class ClassLiteral(
            val internalName: String,
        ) : Expr("Ljava/lang/Class;")

        /** A `NEW` whose constructor has not run yet. */
        class Uninit(
            val internalName: String,
        ) : Expr("L$internalName;")

        class Member(
            val receiver: Expr?,
            val ownerName: String?,
            val name: String,
            desc: String?,
        ) : Expr(desc)

        class Call(
            val receiver: Expr?,
            val ownerName: String?,
            val name: String,
            val args: List<Expr>,
            desc: String?,
        ) : Expr(desc)

        class New(
            val internalName: String,
            val args: List<Expr>,
        ) : Expr("L$internalName;")

        class Binary(
            val op: Op,
            val left: Expr,
            val right: Expr,
            desc: String?,
        ) : Expr(desc)

        class Not(
            val operand: Expr,
        ) : Expr("Z")

        class Neg(
            val operand: Expr,
        ) : Expr(operand.desc, operand.size)

        class InstanceOf(
            val operand: Expr,
            val internalName: String,
            val negated: Boolean = false,
        ) : Expr("Z")

        class Index(
            val array: Expr,
            val index: Expr,
            desc: String?,
        ) : Expr(desc)

        class Length(
            val array: Expr,
        ) : Expr("I")

        /**
         * The `-1`, `0` or `1` an `LCMP`, `FCMPx`, `DCMPx` or Kotlin's `Intrinsics.compare` leaves.
         * [nanResult] is what the instruction gives when either operand is NaN, or null for a
         * compare of integers.
         */
        class Compare(
            val left: Expr,
            val right: Expr,
            val nanResult: Int?,
        ) : Expr("I")

        class Concat(
            val elements: List<Expr>,
        ) : Expr("Ljava/lang/String;")

        /**
         * A narrowing conversion of [operand] to the primitive [desc]. Each language writes it its
         * own way: Kotlin `x.toInt()`, Java `(int) x`, Scala `x.toInt`.
         */
        class Convert(
            val operand: Expr,
            desc: String,
        ) : Expr(desc)

        /**
         * [inner] seen through a cast, a box, an unbox or a widening conversion: it renders as
         * [inner] with a new type.
         */
        class Retyped(
            val inner: Expr,
            desc: String?,
        ) : Expr(desc)
    }

    /** A binary operator. Each language spells and ranks some of them its own way. */
    private enum class Op {
        MUL,
        DIV,
        REM,
        ADD,
        SUB,
        SHL,
        SHR,
        USHR,
        AND,
        OR,
        XOR,
        LT,
        LE,
        GT,
        GE,
        EQ,
        NE,
        REF_EQ,
        REF_NE,
        ;

        val isRelation: Boolean get() = this in LT..REF_NE
    }

    /** How the relation each jump tests against zero reads when it does not jump. */
    private fun fallThroughRelation(opcode: Int): Op =
        when (opcode) {
            Opcodes.IFEQ, Opcodes.IF_ICMPEQ -> Op.NE
            Opcodes.IFNE, Opcodes.IF_ICMPNE -> Op.EQ
            Opcodes.IFLT, Opcodes.IF_ICMPLT -> Op.GE
            Opcodes.IFGE, Opcodes.IF_ICMPGE -> Op.LT
            Opcodes.IFGT, Opcodes.IF_ICMPGT -> Op.LE
            Opcodes.IFLE, Opcodes.IF_ICMPLE -> Op.GT
            else -> unwritable()
        }

    private fun complement(op: Op): Op =
        when (op) {
            Op.LT -> Op.GE
            Op.GE -> Op.LT
            Op.GT -> Op.LE
            Op.LE -> Op.GT
            Op.EQ -> Op.NE
            Op.NE -> Op.EQ
            Op.REF_EQ -> Op.REF_NE
            Op.REF_NE -> Op.REF_EQ
            else -> unwritable()
        }

    private fun holds(
        op: Op,
        sign: Int,
    ): Boolean =
        when (op) {
            Op.LT -> sign < 0
            Op.LE -> sign <= 0
            Op.GT -> sign > 0
            Op.GE -> sign >= 0
            Op.EQ -> sign == 0
            Op.NE -> sign != 0
            else -> unwritable()
        }

    /**
     * The expression that is true exactly when [expression] is false. Equality flips, which holds
     * for NaN too. An ordering relation does not flip, since `!(a > b)` and `a <= b` differ when an
     * operand is NaN.
     */
    private fun negate(expression: Expr): Expr =
        when {
            expression is Expr.Not -> {
                expression.operand
            }

            expression is Expr.InstanceOf -> {
                Expr.InstanceOf(expression.operand, expression.internalName, !expression.negated)
            }

            expression is Expr.Binary && expression.op in setOf(Op.EQ, Op.NE, Op.REF_EQ, Op.REF_NE) -> {
                Expr.Binary(complement(expression.op), expression.left, expression.right, "Z")
            }

            else -> {
                Expr.Not(expression)
            }
        }

    /** A relation between [left] and [right], with an `int` constant written as a `char` or a `boolean` to match the other side. */
    private fun relation(
        op: Op,
        left: Expr,
        right: Expr,
    ): Expr = Expr.Binary(op, typedConstant(left, right), typedConstant(right, left), "Z")

    private fun typedConstant(
        value: Expr,
        other: Expr,
    ): Expr {
        if (value !is Expr.IntConst) return value
        return when (other.desc) {
            "C" -> {
                Expr.Atom(charLiteral(value.value.toChar()), "C")
            }

            "Z" -> {
                when (value.value) {
                    0 -> Expr.Atom("false", "Z")
                    1 -> Expr.Atom("true", "Z")
                    else -> value
                }
            }

            else -> {
                value
            }
        }
    }

    private fun charLiteral(c: Char): String {
        val body =
            when (c) {
                '\'' -> "\\'"
                '\\' -> "\\\\"
                '\n' -> "\\n"
                '\r' -> "\\r"
                '\t' -> "\\t"
                '\b' -> "\\b"
                in ' '..'~' -> c.toString()
                else -> "\\u%04X".format(c.code)
            }
        return "'$body'"
    }

    /** Reads a site's jump or switch against the stack its window leaves. */
    private object FallThrough {
        /** [identityIsEquality] tells whether an `if_acmp` between two values reads as `==` rather than as identity. */
        fun read(
            site: Insn,
            stack: MutableList<Expr>,
            identityIsEquality: (Expr, Expr) -> Boolean,
        ): Expr {
            fun pop(): Expr = stack.removeLastOrNull() ?: unwritable()
            return when (site) {
                is Insn.TableSwitch, is Insn.LookupSwitch -> {
                    pop()
                }

                is Insn.Jump -> {
                    when (site.opcode) {
                        Opcodes.IFEQ, Opcodes.IFNE, Opcodes.IFLT, Opcodes.IFGE, Opcodes.IFGT, Opcodes.IFLE -> {
                            readAgainstZero(site.opcode, pop())
                        }

                        Opcodes.IF_ICMPEQ, Opcodes.IF_ICMPNE, Opcodes.IF_ICMPLT,
                        Opcodes.IF_ICMPGE, Opcodes.IF_ICMPGT, Opcodes.IF_ICMPLE,
                        -> {
                            val right = pop()
                            relation(fallThroughRelation(site.opcode), pop(), right)
                        }

                        Opcodes.IF_ACMPEQ -> {
                            val right = pop()
                            val left = pop()
                            Expr.Binary(if (identityIsEquality(left, right)) Op.NE else Op.REF_NE, left, right, "Z")
                        }

                        Opcodes.IF_ACMPNE -> {
                            val right = pop()
                            val left = pop()
                            Expr.Binary(if (identityIsEquality(left, right)) Op.EQ else Op.REF_EQ, left, right, "Z")
                        }

                        Opcodes.IFNULL -> {
                            Expr.Binary(Op.NE, pop(), NULL, "Z")
                        }

                        Opcodes.IFNONNULL -> {
                            Expr.Binary(Op.EQ, pop(), NULL, "Z")
                        }

                        else -> {
                            unwritable()
                        }
                    }
                }

                else -> {
                    unwritable()
                }
            }
        }

        private fun readAgainstZero(
            opcode: Int,
            value: Expr,
        ): Expr {
            val op = fallThroughRelation(opcode)
            val compare = unwrap(value) as? Expr.Compare
            return when {
                compare != null -> {
                    // A NaN operand makes a float or double compare give its nanResult. When that
                    // value also falls through, the source relation is false there, so the
                    // condition is written as the jump's own relation, negated.
                    val nan = compare.nanResult
                    if (nan != null && holds(op, nan)) {
                        Expr.Not(Expr.Binary(complement(op), compare.left, compare.right, "Z"))
                    } else {
                        Expr.Binary(op, compare.left, compare.right, "Z")
                    }
                }

                value.desc == "Z" && opcode == Opcodes.IFEQ -> {
                    value
                }

                value.desc == "Z" && opcode == Opcodes.IFNE -> {
                    negate(value)
                }

                value.desc in INT_LIKE -> {
                    relation(op, value, Expr.IntConst(0))
                }

                else -> {
                    Expr.Hole("Z")
                }
            }
        }

        private fun unwrap(value: Expr): Expr = if (value is Expr.Retyped) unwrap(value.inner) else value
    }

    private val NULL = Expr.Atom("null", "Ljava/lang/Object;")
    private val INT_LIKE = setOf("I", "S", "B", "C")
    private const val STRING = "Ljava/lang/String;"

    /** How names read in the class's source language. */
    private class Names(
        val language: SourceLanguage,
    ) {
        /** A local's name as the source wrote it. Kotlin marks inlined copies with `$iv` and extension receivers with `$this$`. */
        fun local(name: String): String {
            if (language != SourceLanguage.KOTLIN) return name
            var result = name
            while (result.endsWith(INLINE_SUFFIX) && result.length > INLINE_SUFFIX.length) result = result.dropLast(INLINE_SUFFIX.length)
            return if (result.startsWith(EXTENSION_RECEIVER_PREFIX)) "this" else result
        }

        /** A class's simple name, or null when it has none a source file could write, such as an anonymous class. */
        fun simpleName(internalName: String): String? {
            val simple = internalName.substringAfterLast('/').substringAfterLast('$')
            return simple.takeIf { IDENTIFIER.matches(it) }
        }

        companion object {
            private const val INLINE_SUFFIX = "\$iv"
            private const val EXTENSION_RECEIVER_PREFIX = "\$this$"
            private val IDENTIFIER = Regex("^[A-Za-z_][A-Za-z0-9_]*$")
        }
    }

    /** Walks a window, one instruction at a time, keeping a stack of expressions. */
    private class Evaluator(
        private val method: MethodInstructionsView,
        private val windowStart: Int,
        private val names: Names,
        private val ownerInternalName: String,
    ) {
        private val insns = method.insns
        private val language = names.language

        /** Null while the walk is past an instruction that never falls through. */
        private var stack: MutableList<Expr>? = mutableListOf()
        private val pending = HashMap<Label, MutableList<List<Expr>>>()
        private val walkedJumps = HashSet<Int>()

        /** The stack just before instruction [end], or null when the window cannot be read. */
        fun stackBefore(end: Int): MutableList<Expr>? {
            for (i in windowStart until end) {
                if (i > windowStart && !mergeAt(i)) return null
                val current = stack ?: continue
                step(i, insns[i], current)
            }
            if (end > windowStart && !mergeAt(end)) return null
            return stack
        }

        /**
         * Joins the stacks that reach instruction [index] through the labels placed before it.
         * A value that is the same on every path is kept, and any other becomes a placeholder. A
         * label reached from outside what this walk has seen, such as a jump from before the
         * window or back from later in the method, makes every value there a placeholder. Returns
         * false when no stack at all is known there.
         */
        private fun mergeAt(index: Int): Boolean {
            val labels = method.labelsAt[index] ?: return true
            var unknownSource = false
            val incoming = mutableListOf<List<Expr>>()
            var joins = false
            for (label in labels) {
                if (label in method.handlerLabels) return false
                val sources = method.jumpSources[label] ?: continue
                joins = true
                if (sources.any { it < windowStart || it >= index || it !in walkedJumps }) unknownSource = true
                pending[label]?.let { incoming += it }
            }
            if (!joins) return true
            stack?.let { incoming += it.toList() }
            if (incoming.isEmpty()) return false
            val shape = incoming.first()
            if (incoming.any { state -> state.size != shape.size || state.indices.any { state[it].size != shape[it].size } }) return false
            stack =
                shape.indices
                    .map { position ->
                        val candidate = shape[position]
                        if (!unknownSource && incoming.all { it[position] === candidate }) {
                            candidate
                        } else {
                            val desc = candidate.desc.takeIf { d -> incoming.all { it[position].desc == d } }
                            Expr.Hole(desc, candidate.size)
                        }
                    }.toMutableList()
            return true
        }

        private fun remember(
            target: Label,
            from: Int,
            current: List<Expr>,
        ) {
            walkedJumps += from
            val targetIndex = method.indexOfLabel[target] ?: return
            if (targetIndex > from) pending.getOrPut(target) { mutableListOf() } += current.toList()
        }

        private fun MutableList<Expr>.pop(): Expr = removeLastOrNull() ?: unwritable()

        private fun MutableList<Expr>.popArgs(count: Int): List<Expr> {
            val args = ArrayList<Expr>(count)
            repeat(count) { args += pop() }
            args.reverse()
            return args
        }

        private fun step(
            index: Int,
            insn: Insn,
            s: MutableList<Expr>,
        ) {
            when (insn) {
                is Insn.Plain -> {
                    plain(insn.opcode, s)
                }

                is Insn.IntOperand -> {
                    if (insn.opcode == Opcodes.NEWARRAY) {
                        s.pop()
                        s += Expr.Hole(null)
                    } else {
                        s += Expr.IntConst(insn.operand)
                    }
                }

                is Insn.Var -> {
                    variable(index, insn, s)
                }

                is Insn.TypeOp -> {
                    typeOp(insn, s)
                }

                is Insn.Field -> {
                    field(insn, s)
                }

                is Insn.MethodCall -> {
                    call(insn, s)
                }

                is Insn.InvokeDynamic -> {
                    invokeDynamic(insn, s)
                }

                is Insn.Jump -> {
                    when (insn.opcode) {
                        Opcodes.GOTO -> {}

                        Opcodes.JSR -> {
                            unwritable()
                        }

                        Opcodes.IFEQ, Opcodes.IFNE, Opcodes.IFLT, Opcodes.IFGE, Opcodes.IFGT, Opcodes.IFLE,
                        Opcodes.IFNULL, Opcodes.IFNONNULL,
                        -> {
                            s.pop()
                        }

                        else -> {
                            s.popArgs(2)
                        }
                    }
                    remember(insn.target, index, s)
                    if (insn.opcode == Opcodes.GOTO) stack = null
                }

                is Insn.Ldc -> {
                    s += constant(insn.value)
                }

                is Insn.Iinc -> {}

                is Insn.TableSwitch, is Insn.LookupSwitch -> {
                    s.pop()
                    for (target in MethodInstructionsView.targetsOf(insn)) remember(target, index, s)
                    stack = null
                }

                is Insn.MultiANewArray -> {
                    s.popArgs(insn.numDimensions)
                    s += Expr.Hole(insn.descriptor)
                }
            }
        }

        private fun variable(
            index: Int,
            insn: Insn.Var,
            s: MutableList<Expr>,
        ) {
            when (insn.opcode) {
                Opcodes.ILOAD, Opcodes.LLOAD, Opcodes.FLOAD, Opcodes.DLOAD, Opcodes.ALOAD -> {
                    val local = method.local(insn.varIndex, index)
                    s +=
                        if (local == null) {
                            Expr.Hole(loadDescriptor(insn.opcode))
                        } else {
                            Expr.Atom(names.local(local.name), local.descriptor)
                        }
                }

                Opcodes.RET -> {
                    unwritable()
                }

                else -> {
                    s.pop()
                }
            }
        }

        /** The type a load opcode fixes. An `ILOAD` could be a boolean, a char or an int, and an `ALOAD` anything. */
        private fun loadDescriptor(opcode: Int): String? =
            when (opcode) {
                Opcodes.LLOAD -> "J"
                Opcodes.FLOAD -> "F"
                Opcodes.DLOAD -> "D"
                else -> null
            }

        private fun typeOp(
            insn: Insn.TypeOp,
            s: MutableList<Expr>,
        ) {
            when (insn.opcode) {
                Opcodes.NEW -> {
                    s += Expr.Uninit(insn.type)
                }

                Opcodes.ANEWARRAY -> {
                    s.pop()
                    s += Expr.Hole("[" + objectDescriptor(insn.type))
                }

                Opcodes.CHECKCAST -> {
                    s += Expr.Retyped(s.pop(), objectDescriptor(insn.type))
                }

                Opcodes.INSTANCEOF -> {
                    s += Expr.InstanceOf(s.pop(), insn.type)
                }

                else -> {
                    unwritable()
                }
            }
        }

        private fun objectDescriptor(internalName: String): String = if (internalName.startsWith("[")) internalName else "L$internalName;"

        private fun field(
            insn: Insn.Field,
            s: MutableList<Expr>,
        ) {
            when (insn.opcode) {
                Opcodes.GETSTATIC -> s += staticField(insn)
                Opcodes.GETFIELD -> s += Expr.Member(s.pop(), null, insn.name, insn.descriptor)
                Opcodes.PUTSTATIC -> s.pop()
                Opcodes.PUTFIELD -> s.popArgs(2)
                else -> unwritable()
            }
        }

        private fun staticField(insn: Insn.Field): Expr {
            val ownerName = names.simpleName(insn.owner) ?: return Expr.Hole(insn.descriptor)
            val ownType = "L${insn.owner};"
            val isSingleton =
                language == SourceLanguage.KOTLIN &&
                    (
                        (insn.name == "INSTANCE" && insn.descriptor == ownType) ||
                            (insn.name == "Companion" && insn.descriptor == "L${insn.owner}\$Companion;")
                    )
            return if (isSingleton) Expr.Atom(ownerName, insn.descriptor) else Expr.Member(null, ownerName, insn.name, insn.descriptor)
        }

        private fun call(
            insn: Insn.MethodCall,
            s: MutableList<Expr>,
        ) {
            val argumentCount = Type.getArgumentCount(insn.descriptor)
            val returnDesc = Type.getReturnType(insn.descriptor).descriptor
            val args = s.popArgs(argumentCount)
            val isStatic = insn.opcode == Opcodes.INVOKESTATIC
            val receiver = if (isStatic) null else s.pop()

            if (insn.opcode == Opcodes.INVOKESPECIAL && insn.name == "<init>") {
                if (receiver is Expr.Uninit) {
                    val created = Expr.New(receiver.internalName, args)
                    for (i in s.indices) if (s[i] === receiver) s[i] = created
                }
                return
            }
            val result = idiom(insn, receiver, args, returnDesc) ?: plainCall(insn, receiver, args, returnDesc)
            if (returnDesc != "V") s += result
        }

        /** A call read back to the source that compiled to it, or null when it is an ordinary call. */
        private fun idiom(
            insn: Insn.MethodCall,
            receiver: Expr?,
            args: List<Expr>,
            returnDesc: String,
        ): Expr? {
            if (receiver == null && args.size == 1 && isBoxing(insn)) return Expr.Retyped(args[0], returnDesc)
            if (receiver != null && args.isEmpty() && isUnboxing(insn)) return Expr.Retyped(receiver, returnDesc)
            return when (language) {
                SourceLanguage.KOTLIN -> {
                    kotlinIdiom(insn, receiver, args, returnDesc)
                }

                SourceLanguage.SCALA -> {
                    if (receiver == null && insn.owner == BOXES_RUN_TIME && insn.name == "equals" && insn.descriptor == OBJECT_EQUALITY) {
                        Expr.Binary(Op.EQ, args[0], args[1], "Z")
                    } else {
                        null
                    }
                }

                SourceLanguage.JAVA -> {
                    null
                }
            }
        }

        private fun kotlinIdiom(
            insn: Insn.MethodCall,
            receiver: Expr?,
            args: List<Expr>,
            returnDesc: String,
        ): Expr? {
            if (receiver == null && insn.owner.endsWith(INTRINSICS_SUFFIX)) {
                if (insn.name == "areEqual" && insn.descriptor == OBJECT_EQUALITY) return Expr.Binary(Op.EQ, args[0], args[1], "Z")
                if (insn.name == "compare" && insn.descriptor == "(II)I") return Expr.Compare(args[0], args[1], null)
            }
            if (args.isEmpty()) {
                val property = propertyName(insn.name, insn.descriptor) ?: return null
                if (receiver != null) return Expr.Member(receiver, null, property, returnDesc)
                val ownerName = names.simpleName(insn.owner) ?: return null
                return Expr.Member(null, ownerName, property, returnDesc)
            }
            return null
        }

        private fun plainCall(
            insn: Insn.MethodCall,
            receiver: Expr?,
            args: List<Expr>,
            returnDesc: String,
        ): Expr {
            if (receiver == null) {
                val ownerName = names.simpleName(insn.owner) ?: return Expr.Hole(returnDesc)
                return Expr.Call(null, ownerName, insn.name, args, returnDesc)
            }
            val isSuperCall =
                insn.opcode == Opcodes.INVOKESPECIAL && insn.owner != ownerInternalName &&
                    receiver is Expr.Atom && receiver.text == "this"
            val target = if (isSuperCall) Expr.Atom("super", "L${insn.owner};") else receiver
            return Expr.Call(target, null, insn.name, args, returnDesc)
        }

        private fun invokeDynamic(
            insn: Insn.InvokeDynamic,
            s: MutableList<Expr>,
        ) {
            val args = s.popArgs(Type.getArgumentCount(insn.descriptor))
            val returnDesc = Type.getReturnType(insn.descriptor).descriptor
            val bootstrap = insn.bootstrapMethod
            val isConcat =
                bootstrap.owner == "java/lang/invoke/StringConcatFactory" && bootstrap.name == "makeConcatWithConstants" &&
                    insn.bootstrapMethodArguments.firstOrNull() is String
            s += if (isConcat) concat(insn.bootstrapMethodArguments, args) ?: Expr.Hole(returnDesc) else Expr.Hole(returnDesc)
        }

        /**
         * A `makeConcatWithConstants` recipe as a `+` chain. `\u0001` takes the next argument,
         * `\u0002` the next constant, and any other character is literal text. The chain starts
         * with `""` when its first element would not make it a string concatenation: in Kotlin
         * whenever the first element is not a string, and in Java when neither of the first two is.
         */
        private fun concat(
            bootstrapArguments: Array<out Any>,
            args: List<Expr>,
        ): Expr? {
            val recipe = bootstrapArguments[0] as String
            val elements = mutableListOf<Expr>()
            val text = StringBuilder()
            var nextArg = 0
            var nextConstant = 1

            fun flushText() {
                if (text.isNotEmpty()) {
                    elements += Expr.Str(text.toString())
                    text.clear()
                }
            }
            for (c in recipe) {
                when (c) {
                    '\u0001' -> {
                        flushText()
                        elements += args.getOrNull(nextArg++) ?: return null
                    }

                    '\u0002' -> {
                        flushText()
                        elements += constant(bootstrapArguments.getOrNull(nextConstant++) ?: return null)
                    }

                    else -> {
                        text.append(c)
                    }
                }
            }
            flushText()
            if (nextArg != args.size || elements.isEmpty()) return null
            val isString = { e: Expr -> e.desc == "Ljava/lang/String;" }
            val needsLeadingString =
                when (language) {
                    SourceLanguage.KOTLIN -> !isString(elements[0])
                    else -> !isString(elements[0]) && (elements.size < 2 || !isString(elements[1]))
                }
            if (needsLeadingString) elements.add(0, Expr.Str(""))
            return Expr.Concat(elements)
        }

        private fun constant(value: Any?): Expr =
            when (value) {
                is Int -> {
                    Expr.IntConst(value)
                }

                is Long -> {
                    Expr.Atom("${value}L", "J", negativeNumber = value < 0)
                }

                is Float -> {
                    floatConstant(value)
                }

                is Double -> {
                    doubleConstant(value)
                }

                is String -> {
                    Expr.Str(value)
                }

                is Type -> {
                    if (value.sort == Type.OBJECT) {
                        if (names.simpleName(value.internalName) ==
                            null
                        ) {
                            Expr.Hole("Ljava/lang/Class;")
                        } else {
                            Expr.ClassLiteral(value.internalName)
                        }
                    } else {
                        Expr.Hole("Ljava/lang/Class;")
                    }
                }

                else -> {
                    Expr.Hole(null)
                }
            }

        private fun floatConstant(value: Float): Expr =
            when {
                value.isNaN() -> Expr.Atom("Float.NaN", "F")
                value.isInfinite() -> Expr.Atom(infinity("Float", value > 0), "F")
                else -> Expr.Atom("${value}f", "F", negativeNumber = value < 0 || 1 / value < 0)
            }

        private fun doubleConstant(value: Double): Expr =
            when {
                value.isNaN() -> Expr.Atom("Double.NaN", "D")
                value.isInfinite() -> Expr.Atom(infinity("Double", value > 0), "D")
                else -> Expr.Atom(value.toString(), "D", negativeNumber = value < 0 || 1 / value < 0)
            }

        private fun infinity(
            typeName: String,
            positive: Boolean,
        ): String =
            when (language) {
                SourceLanguage.SCALA -> if (positive) "$typeName.PositiveInfinity" else "$typeName.NegativeInfinity"
                else -> if (positive) "$typeName.POSITIVE_INFINITY" else "$typeName.NEGATIVE_INFINITY"
            }

        private fun plain(
            opcode: Int,
            s: MutableList<Expr>,
        ) {
            when (opcode) {
                Opcodes.NOP -> {}

                Opcodes.ACONST_NULL -> {
                    s += NULL
                }

                in Opcodes.ICONST_M1..Opcodes.ICONST_5 -> {
                    s += Expr.IntConst(opcode - Opcodes.ICONST_0)
                }

                Opcodes.LCONST_0, Opcodes.LCONST_1 -> {
                    s += Expr.Atom("${opcode - Opcodes.LCONST_0}L", "J")
                }

                Opcodes.FCONST_0, Opcodes.FCONST_1, Opcodes.FCONST_2 -> {
                    s += floatConstant((opcode - Opcodes.FCONST_0).toFloat())
                }

                Opcodes.DCONST_0, Opcodes.DCONST_1 -> {
                    s += doubleConstant((opcode - Opcodes.DCONST_0).toDouble())
                }

                in Opcodes.IALOAD..Opcodes.SALOAD -> {
                    val index = s.pop()
                    val array = s.pop()
                    s += Expr.Index(array, index, elementDescriptor(opcode, array.desc))
                }

                in Opcodes.IASTORE..Opcodes.SASTORE -> {
                    s.popArgs(3)
                }

                Opcodes.POP -> {
                    s.pop()
                }

                Opcodes.POP2 -> {
                    if (s.pop().size == 1) s.pop()
                }

                Opcodes.DUP -> {
                    s += s.last()
                }

                Opcodes.DUP_X1 -> {
                    val v1 = s.pop()
                    val v2 = s.pop()
                    s += listOf(v1, v2, v1)
                }

                Opcodes.DUP_X2 -> {
                    val v1 = s.pop()
                    val v2 = s.pop()
                    if (v2.size == 2) {
                        s += listOf(v1, v2, v1)
                    } else {
                        val v3 = s.pop()
                        s += listOf(v1, v3, v2, v1)
                    }
                }

                Opcodes.DUP2 -> {
                    val v1 = s.pop()
                    if (v1.size == 2) {
                        s += listOf(v1, v1)
                    } else {
                        val v2 = s.pop()
                        s += listOf(v2, v1, v2, v1)
                    }
                }

                Opcodes.DUP2_X1 -> {
                    val v1 = s.pop()
                    if (v1.size == 2) {
                        val v2 = s.pop()
                        s += listOf(v1, v2, v1)
                    } else {
                        val v2 = s.pop()
                        val v3 = s.pop()
                        s += listOf(v2, v1, v3, v2, v1)
                    }
                }

                Opcodes.DUP2_X2 -> {
                    dup2x2(s)
                }

                Opcodes.SWAP -> {
                    val v1 = s.pop()
                    val v2 = s.pop()
                    s += listOf(v1, v2)
                }

                in Opcodes.IADD..Opcodes.DREM, in Opcodes.ISHL..Opcodes.LXOR -> {
                    arithmetic(opcode, s)
                }

                in Opcodes.INEG..Opcodes.DNEG -> {
                    s += Expr.Neg(s.pop())
                }

                Opcodes.I2L, Opcodes.I2F, Opcodes.I2D, Opcodes.L2F, Opcodes.L2D, Opcodes.F2D -> {
                    s += Expr.Retyped(s.pop(), conversionResult(opcode))
                }

                in Opcodes.I2L..Opcodes.I2S -> {
                    s += Expr.Convert(s.pop(), conversionResult(opcode))
                }

                Opcodes.LCMP -> {
                    compare(s, null)
                }

                Opcodes.FCMPL, Opcodes.DCMPL -> {
                    compare(s, -1)
                }

                Opcodes.FCMPG, Opcodes.DCMPG -> {
                    compare(s, 1)
                }

                in Opcodes.IRETURN..Opcodes.ARETURN, Opcodes.ATHROW -> {
                    s.pop()
                    stack = null
                }

                Opcodes.RETURN -> {
                    stack = null
                }

                Opcodes.ARRAYLENGTH -> {
                    s += Expr.Length(s.pop())
                }

                Opcodes.MONITORENTER, Opcodes.MONITOREXIT -> {
                    s.pop()
                }

                else -> {
                    unwritable()
                }
            }
        }

        private fun dup2x2(s: MutableList<Expr>) {
            val v1 = s.pop()
            if (v1.size == 2) {
                val v2 = s.pop()
                if (v2.size == 2) {
                    s += listOf(v1, v2, v1)
                } else {
                    val v3 = s.pop()
                    s += listOf(v1, v3, v2, v1)
                }
            } else {
                val v2 = s.pop()
                val v3 = s.pop()
                if (v3.size == 2) {
                    s += listOf(v2, v1, v3, v2, v1)
                } else {
                    val v4 = s.pop()
                    s += listOf(v2, v1, v4, v3, v2, v1)
                }
            }
        }

        private fun compare(
            s: MutableList<Expr>,
            nanResult: Int?,
        ) {
            val right = s.pop()
            s += Expr.Compare(s.pop(), right, nanResult)
        }

        private fun arithmetic(
            opcode: Int,
            s: MutableList<Expr>,
        ) {
            val right = s.pop()
            val left = s.pop()
            val (op, desc) = arithmeticOp(opcode)
            val bitwise = op == Op.AND || op == Op.OR || op == Op.XOR
            val resultDesc = if (bitwise && left.desc == "Z" && right.desc == "Z") "Z" else desc
            s += Expr.Binary(op, left, right, resultDesc)
        }

        private fun arithmeticOp(opcode: Int): Pair<Op, String> {
            val typeByOffset = arrayOf("I", "J", "F", "D")
            return when (opcode) {
                in Opcodes.IADD..Opcodes.DADD -> Op.ADD to typeByOffset[opcode - Opcodes.IADD]
                in Opcodes.ISUB..Opcodes.DSUB -> Op.SUB to typeByOffset[opcode - Opcodes.ISUB]
                in Opcodes.IMUL..Opcodes.DMUL -> Op.MUL to typeByOffset[opcode - Opcodes.IMUL]
                in Opcodes.IDIV..Opcodes.DDIV -> Op.DIV to typeByOffset[opcode - Opcodes.IDIV]
                in Opcodes.IREM..Opcodes.DREM -> Op.REM to typeByOffset[opcode - Opcodes.IREM]
                Opcodes.ISHL, Opcodes.LSHL -> Op.SHL to if (opcode == Opcodes.ISHL) "I" else "J"
                Opcodes.ISHR, Opcodes.LSHR -> Op.SHR to if (opcode == Opcodes.ISHR) "I" else "J"
                Opcodes.IUSHR, Opcodes.LUSHR -> Op.USHR to if (opcode == Opcodes.IUSHR) "I" else "J"
                Opcodes.IAND, Opcodes.LAND -> Op.AND to if (opcode == Opcodes.IAND) "I" else "J"
                Opcodes.IOR, Opcodes.LOR -> Op.OR to if (opcode == Opcodes.IOR) "I" else "J"
                Opcodes.IXOR, Opcodes.LXOR -> Op.XOR to if (opcode == Opcodes.IXOR) "I" else "J"
                else -> unwritable()
            }
        }

        private fun conversionResult(opcode: Int): String =
            when (opcode) {
                Opcodes.I2L, Opcodes.F2L, Opcodes.D2L -> "J"
                Opcodes.I2F, Opcodes.L2F, Opcodes.D2F -> "F"
                Opcodes.I2D, Opcodes.L2D, Opcodes.F2D -> "D"
                Opcodes.I2B -> "B"
                Opcodes.I2C -> "C"
                Opcodes.I2S -> "S"
                else -> "I"
            }

        private fun elementDescriptor(
            opcode: Int,
            arrayDesc: String?,
        ): String? {
            if (arrayDesc != null && arrayDesc.startsWith("[")) return arrayDesc.substring(1)
            return when (opcode) {
                Opcodes.IALOAD -> "I"
                Opcodes.LALOAD -> "J"
                Opcodes.FALOAD -> "F"
                Opcodes.DALOAD -> "D"
                Opcodes.CALOAD -> "C"
                Opcodes.SALOAD -> "S"
                else -> null
            }
        }

        private fun isBoxing(insn: Insn.MethodCall): Boolean {
            if (insn.opcode != Opcodes.INVOKESTATIC) return false
            if (insn.owner == BOXES_RUN_TIME) return insn.name.startsWith("boxTo") || insn.name.startsWith("unboxTo")
            return insn.name == "valueOf" && insn.owner in BOX_OWNERS &&
                insn.descriptor == "(${BOX_OWNERS.getValue(insn.owner)})L${insn.owner};"
        }

        private fun isUnboxing(insn: Insn.MethodCall): Boolean =
            insn.opcode == Opcodes.INVOKEVIRTUAL &&
                (insn.owner in BOX_OWNERS || insn.owner == "java/lang/Number") &&
                insn.name in UNBOX_NAMES &&
                insn.descriptor == "()${UNBOX_NAMES.getValue(insn.name)}"
    }

    /**
     * The name of the Kotlin property whose getter is [name], or null when [name] is not shaped
     * like one: `getX` with no parameters and a result, or `isX` returning a boolean. A name like
     * `getURL` could come from a property `URL` or `url`, so it is left as a call. `getClass` is
     * `javaClass` in Kotlin, so it is left as a call too.
     */
    private fun propertyName(
        name: String,
        descriptor: String,
    ): String? {
        if (!descriptor.startsWith("()") || descriptor == "()V") return null
        if (name.length > 3 && name.startsWith("get") && name[3] in 'A'..'Z' && name != "getClass") {
            if (name.length > 4 && name[4] in 'A'..'Z') return null
            return name[3].lowercaseChar() + name.substring(4)
        }
        if (name.length > 2 && name.startsWith("is") && name[2] in 'A'..'Z' && descriptor == "()Z") return name
        return null
    }

    /**
     * scalac's `a == b` on two references, in both Scala 2 and Scala 3:
     *
     * ```
     * <a> <b> astore t; dup; ifnonnull L1; pop; aload t; ifnull T; goto F
     * L1: aload t; invokevirtual Object.equals; ifeq F
     * T: ...
     * ```
     *
     * The `ifeq` site reads as `a == b`, and the `ifnull` site as `b != null`. The `ifnonnull`
     * site needs no rule: its own window reads as `a == null`.
     */
    private object ScalaEquality {
        fun match(
            method: MethodInstructionsView,
            siteIndex: Int,
            names: Names,
            ownerInternalName: String,
        ): Expr? {
            val insns = method.insns
            val nonNull =
                when ((insns[siteIndex] as? Insn.Jump)?.opcode) {
                    Opcodes.IFEQ -> siteIndex - 7
                    Opcodes.IFNULL -> siteIndex - 3
                    else -> return null
                }
            if (nonNull < 2 || nonNull + 8 > insns.size) return null
            val store = insns[nonNull - 2] as? Insn.Var ?: return null
            val nonNullJump = insns[nonNull] as? Insn.Jump ?: return null
            val nullJump = insns[nonNull + 3] as? Insn.Jump ?: return null
            val falseJump = insns[nonNull + 4] as? Insn.Jump ?: return null
            val equalsJump = insns[nonNull + 7] as? Insn.Jump ?: return null
            val equalsCall = insns[nonNull + 6] as? Insn.MethodCall ?: return null
            val shapeMatches =
                store.opcode == Opcodes.ASTORE &&
                    insns[nonNull - 1] == Insn.Plain(Opcodes.DUP) &&
                    nonNullJump.opcode == Opcodes.IFNONNULL &&
                    insns[nonNull + 1] == Insn.Plain(Opcodes.POP) &&
                    insns[nonNull + 2] == Insn.Var(Opcodes.ALOAD, store.varIndex) &&
                    nullJump.opcode == Opcodes.IFNULL &&
                    falseJump.opcode == Opcodes.GOTO &&
                    method.indexOfLabel[nonNullJump.target] == nonNull + 5 &&
                    insns[nonNull + 5] == Insn.Var(Opcodes.ALOAD, store.varIndex) &&
                    equalsCall.opcode == Opcodes.INVOKEVIRTUAL &&
                    equalsCall.owner == "java/lang/Object" &&
                    equalsCall.name == "equals" &&
                    equalsCall.descriptor == "(Ljava/lang/Object;)Z" &&
                    equalsJump.opcode == Opcodes.IFEQ &&
                    equalsJump.target == falseJump.target &&
                    method.indexOfLabel[nullJump.target] == nonNull + 8
            if (!shapeMatches) return null
            val windowStart = method.windowStartOf(nonNull) ?: return null
            val stack = Evaluator(method, windowStart, names, ownerInternalName).stackBefore(nonNull - 2) ?: return null
            if (stack.size < 2) return null
            val right = stack[stack.size - 1]
            val left = stack[stack.size - 2]
            return if (siteIndex == nonNull + 7) Expr.Binary(Op.EQ, left, right, "Z") else Expr.Binary(Op.NE, right, NULL, "Z")
        }
    }

    /** Turns an expression tree into condition parts, with the parentheses each language needs. */
    private class Renderer(
        private val language: SourceLanguage,
    ) {
        private val parts = mutableListOf<ConditionPart>()
        private val code = StringBuilder()

        fun render(expression: Expr): List<ConditionPart> {
            emit(expression, 0)
            flushCode()
            return parts
        }

        private fun text(value: String) {
            code.append(value)
        }

        private fun flushCode() {
            if (code.isNotEmpty()) {
                parts += ConditionPart(ConditionPartKind.CODE, code.toString())
                code.clear()
            }
        }

        private fun literal(value: String) {
            flushCode()
            parts += ConditionPart(ConditionPartKind.STRING_LITERAL, value)
        }

        private fun placeholder() {
            flushCode()
            parts += ConditionPart(ConditionPartKind.PLACEHOLDER, "")
        }

        /** Writes [expression], in parentheses when it binds less tightly than [minimum]. */
        private fun emit(
            expression: Expr,
            minimum: Int,
        ) {
            val parenthesize = precedence(expression) < minimum
            if (parenthesize) text("(")
            emitBare(expression)
            if (parenthesize) text(")")
        }

        private fun emitBare(expression: Expr) {
            when (expression) {
                is Expr.Hole, is Expr.Uninit, is Expr.Compare -> {
                    placeholder()
                }

                is Expr.Atom -> {
                    text(expression.text)
                }

                is Expr.IntConst -> {
                    text(expression.value.toString())
                }

                is Expr.Str -> {
                    literal(expression.value)
                }

                is Expr.ClassLiteral -> {
                    classLiteral(expression)
                }

                is Expr.Retyped -> {
                    emitBare(expression.inner)
                }

                is Expr.Convert -> {
                    convert(expression)
                }

                is Expr.Member -> {
                    qualifier(expression.receiver, expression.ownerName)
                    text(expression.name)
                }

                is Expr.Call -> {
                    qualifier(expression.receiver, expression.ownerName)
                    text(expression.name)
                    arguments(expression.args)
                }

                is Expr.New -> {
                    if (language != SourceLanguage.KOTLIN) text("new ")
                    text(typeName(expression.internalName))
                    arguments(expression.args)
                }

                is Expr.Binary -> {
                    val own = precedence(expression)
                    emit(expression.left, own)
                    text(" ${symbol(expression.op)} ")
                    emit(expression.right, own + 1)
                }

                is Expr.Not -> {
                    text("!")
                    emit(expression.operand, PREFIX)
                }

                is Expr.Neg -> {
                    text("-")
                    emit(expression.operand, PREFIX + 1)
                }

                is Expr.InstanceOf -> {
                    instanceOf(expression)
                }

                is Expr.Index -> {
                    emit(expression.array, PRIMARY)
                    if (language == SourceLanguage.SCALA) text("(") else text("[")
                    emit(expression.index, 0)
                    if (language == SourceLanguage.SCALA) text(")") else text("]")
                }

                is Expr.Length -> {
                    emit(expression.array, PRIMARY)
                    text(if (language == SourceLanguage.KOTLIN) ".size" else ".length")
                }

                is Expr.Concat -> {
                    expression.elements.forEachIndexed { i, element ->
                        if (i > 0) text(" + ")
                        emit(element, if (i == 0) ADDITIVE else ADDITIVE + 1)
                    }
                }
            }
        }

        private fun qualifier(
            receiver: Expr?,
            ownerName: String?,
        ) {
            if (receiver != null) emit(receiver, PRIMARY) else text(ownerName ?: "")
            text(".")
        }

        private fun arguments(args: List<Expr>) {
            text("(")
            args.forEachIndexed { i, arg ->
                if (i > 0) text(", ")
                emit(arg, 0)
            }
            text(")")
        }

        private fun instanceOf(expression: Expr.InstanceOf) {
            val type = typeName(expression.internalName)
            when (language) {
                SourceLanguage.KOTLIN -> {
                    emit(expression.operand, KOTLIN_IS + 1)
                    text(if (expression.negated) " !is $type" else " is $type")
                }

                SourceLanguage.JAVA -> {
                    if (expression.negated) text("!(")
                    emit(expression.operand, JAVA_RELATIONAL + 1)
                    text(" instanceof $type")
                    if (expression.negated) text(")")
                }

                SourceLanguage.SCALA -> {
                    if (expression.negated) text("!")
                    emit(expression.operand, PRIMARY)
                    text(".isInstanceOf[$type]")
                }
            }
        }

        private fun convert(expression: Expr.Convert) {
            val typeName = PRIMITIVE_NAMES.getValue(expression.desc ?: "I")
            when (language) {
                SourceLanguage.KOTLIN -> {
                    emit(expression.operand, PRIMARY)
                    text(".to$typeName()")
                }

                SourceLanguage.JAVA -> {
                    text("(${typeName.lowercase()}) ")
                    emit(expression.operand, PREFIX)
                }

                SourceLanguage.SCALA -> {
                    emit(expression.operand, PRIMARY)
                    text(".to$typeName")
                }
            }
        }

        private fun classLiteral(expression: Expr.ClassLiteral) {
            val type = typeName(expression.internalName)
            text(
                when (language) {
                    SourceLanguage.KOTLIN -> "$type::class.java"
                    SourceLanguage.JAVA -> "$type.class"
                    SourceLanguage.SCALA -> "classOf[$type]"
                },
            )
        }

        private fun typeName(internalName: String): String =
            Names(language).simpleName(internalName) ?: internalName.substringAfterLast('/')

        private fun symbol(op: Op): String =
            when (op) {
                Op.MUL -> {
                    "*"
                }

                Op.DIV -> {
                    "/"
                }

                Op.REM -> {
                    "%"
                }

                Op.ADD -> {
                    "+"
                }

                Op.SUB -> {
                    "-"
                }

                Op.LT -> {
                    "<"
                }

                Op.LE -> {
                    "<="
                }

                Op.GT -> {
                    ">"
                }

                Op.GE -> {
                    ">="
                }

                Op.EQ -> {
                    "=="
                }

                Op.NE -> {
                    "!="
                }

                Op.SHL -> {
                    if (language == SourceLanguage.KOTLIN) "shl" else "<<"
                }

                Op.SHR -> {
                    if (language == SourceLanguage.KOTLIN) "shr" else ">>"
                }

                Op.USHR -> {
                    if (language == SourceLanguage.KOTLIN) "ushr" else ">>>"
                }

                Op.AND -> {
                    if (language == SourceLanguage.KOTLIN) "and" else "&"
                }

                Op.OR -> {
                    if (language == SourceLanguage.KOTLIN) "or" else "|"
                }

                Op.XOR -> {
                    if (language == SourceLanguage.KOTLIN) "xor" else "^"
                }

                Op.REF_EQ -> {
                    when (language) {
                        SourceLanguage.KOTLIN -> "==="
                        SourceLanguage.JAVA -> "=="
                        SourceLanguage.SCALA -> "eq"
                    }
                }

                Op.REF_NE -> {
                    when (language) {
                        SourceLanguage.KOTLIN -> "!=="
                        SourceLanguage.JAVA -> "!="
                        SourceLanguage.SCALA -> "ne"
                    }
                }
            }

        /** How tightly [expression] binds in this language. Higher binds tighter. */
        private fun precedence(expression: Expr): Int =
            when (expression) {
                is Expr.Retyped -> {
                    precedence(expression.inner)
                }

                is Expr.Atom -> {
                    if (expression.negativeNumber) PREFIX else PRIMARY
                }

                is Expr.IntConst -> {
                    if (expression.value < 0) PREFIX else PRIMARY
                }

                is Expr.Not, is Expr.Neg -> {
                    PREFIX
                }

                is Expr.Convert -> {
                    if (language == SourceLanguage.JAVA) PREFIX else PRIMARY
                }

                is Expr.Binary -> {
                    precedence(expression.op)
                }

                is Expr.Concat -> {
                    ADDITIVE
                }

                is Expr.InstanceOf -> {
                    when (language) {
                        SourceLanguage.KOTLIN -> KOTLIN_IS
                        SourceLanguage.JAVA -> if (expression.negated) PREFIX else JAVA_RELATIONAL
                        SourceLanguage.SCALA -> if (expression.negated) PREFIX else PRIMARY
                    }
                }

                else -> {
                    PRIMARY
                }
            }

        private fun precedence(op: Op): Int =
            when (op) {
                Op.MUL, Op.DIV, Op.REM -> {
                    MULTIPLICATIVE
                }

                Op.ADD, Op.SUB -> {
                    ADDITIVE
                }

                else -> {
                    when (language) {
                        SourceLanguage.JAVA -> javaPrecedence(op)
                        SourceLanguage.KOTLIN -> kotlinPrecedence(op)
                        SourceLanguage.SCALA -> scalaPrecedence(op)
                    }
                }
            }

        private fun javaPrecedence(op: Op): Int =
            when (op) {
                Op.SHL, Op.SHR, Op.USHR -> 60
                Op.LT, Op.LE, Op.GT, Op.GE -> JAVA_RELATIONAL
                Op.EQ, Op.NE, Op.REF_EQ, Op.REF_NE -> 45
                Op.AND -> 40
                Op.XOR -> 35
                else -> 30
            }

        /** Kotlin ranks every named infix function (`and`, `shl`) above `is`, `is` above comparison, and comparison above equality. */
        private fun kotlinPrecedence(op: Op): Int =
            when {
                op.isRelation && op in setOf(Op.LT, Op.LE, Op.GT, Op.GE) -> 50
                op.isRelation -> 45
                else -> 60
            }

        /** Scala ranks an operator by its first character, and an operator made of letters (`eq`) lowest of all. */
        private fun scalaPrecedence(op: Op): Int =
            when (op) {
                Op.SHL, Op.SHR, Op.USHR, Op.LT, Op.LE, Op.GT, Op.GE -> 50
                Op.EQ, Op.NE -> 45
                Op.AND -> 40
                Op.XOR -> 35
                Op.OR -> 30
                else -> 10
            }

        private companion object {
            const val PRIMARY = 100
            const val PREFIX = 90
            const val MULTIPLICATIVE = 80
            const val ADDITIVE = 70
            const val KOTLIN_IS = 55
            const val JAVA_RELATIONAL = 50
        }
    }

    private val PRIMITIVE_NAMES =
        mapOf("I" to "Int", "J" to "Long", "F" to "Float", "D" to "Double", "B" to "Byte", "C" to "Char", "S" to "Short")

    private const val INTRINSICS_SUFFIX = "/jvm/internal/Intrinsics"
    private const val BOXES_RUN_TIME = "scala/runtime/BoxesRunTime"
    private const val OBJECT_EQUALITY = "(Ljava/lang/Object;Ljava/lang/Object;)Z"

    private val BOX_OWNERS =
        mapOf(
            "java/lang/Integer" to "I",
            "java/lang/Long" to "J",
            "java/lang/Short" to "S",
            "java/lang/Byte" to "B",
            "java/lang/Character" to "C",
            "java/lang/Boolean" to "Z",
            "java/lang/Float" to "F",
            "java/lang/Double" to "D",
        )

    private val UNBOX_NAMES =
        mapOf(
            "intValue" to "I",
            "longValue" to "J",
            "shortValue" to "S",
            "byteValue" to "B",
            "charValue" to "C",
            "booleanValue" to "Z",
            "floatValue" to "F",
            "doubleValue" to "D",
        )
}
