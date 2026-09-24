package io.github.lukedevops.yukon.instrumentation.branch

import io.github.lukedevops.yukon.export.ConditionPart
import io.github.lukedevops.yukon.export.ConditionPartKind
import io.github.lukedevops.yukon.instrumentation.branch.ConditionFingerprinter.Insn
import net.bytebuddy.jar.asm.ClassReader
import net.bytebuddy.jar.asm.ClassVisitor
import net.bytebuddy.jar.asm.Handle
import net.bytebuddy.jar.asm.Label
import net.bytebuddy.jar.asm.MethodVisitor
import net.bytebuddy.jar.asm.Opcodes
import net.bytebuddy.jar.asm.Type

/**
 * Reads a switch over a string or an enum, and javac's pattern switch, back to the cases the
 * source names. See ADR 0038.
 *
 * Each shape below was confirmed with `javap -c -l -p` on javac 17 and 21, kotlinc 2.2.21, scalac
 * 2.13.15 and scalac 3.3.4 output. A switch that differs from every shape in any detail is not
 * read, and its sites stay as they are.
 *
 * - javac, string: `astore t; iconst_m1; istore i; aload t; hashCode; switch`, then per hash case a
 *   chain of `aload t; ldc "s"; String.equals; ifeq next; <k>; istore i; goto D`, then
 *   `D: iload i; switch`. The switch at `D` is rebuilt with the literal whose check stores each
 *   case key. The hash switch and every `equals` check are lowering.
 * - kotlinc and scalac, string: a hash switch on a temporary whose every case starts a chain of
 *   `equals` checks against a literal with that hash. There is no second switch. The hash switch
 *   and its null check are lowering. Each `equals` check stays a plain site, whose condition is
 *   written as `subject == "literal"` or `subject != "literal"`.
 * - javac and kotlinc, enum: a switch on `map[subject.ordinal()]`, where `map` is javac's
 *   `$SwitchMap$` or kotlinc's `$EnumSwitchMapping$` array. The switch is rebuilt with the
 *   constant each map value stands for, read from the map class's static initialiser. kotlinc's
 *   null check on a nullable subject is lowering, and case key `-1` is `null`.
 * - javac, pattern switch: a switch on the index `SwitchBootstraps.typeSwitch` or `enumSwitch`
 *   returns. The switch is rebuilt with the bootstrap argument each index names, and `-1` is
 *   `null`. A `when` guard's own conditional and its restart jump are left alone.
 *
 * A rebuilt switch whose default only constructs and throws `MatchException`,
 * `IncompatibleClassChangeError` or kotlinc's `NoWhenBranchMatchedException` has a throwing
 * default. The source has no default there.
 */
internal object SwitchLowering {
    /**
     * One lowered switch in a method, by instruction index.
     *
     * [lowering] holds the tracked jumps and switches the compiler added. [rebuilt] is the switch
     * whose cases are the source's cases, with [caseLabels] one per case outcome in the order
     * [BranchProbeMethodVisitor] numbers them, and [throwingDefault] true when its default only
     * throws. It is null when the lowering has no such switch. [caseChecks] holds each `equals`
     * check that stays a plain site. The subject is the value on top of the stack just before
     * [subjectEnd]. [kindToken] names the lowering, so the rebuilt site's fingerprint never
     * names the temporary. [enumClass] is the enum a map switch maps, whose name stands in for
     * the map class in that fingerprint, and null for any other switch.
     */
    class Reading(
        val lowering: List<Int>,
        val rebuilt: Int?,
        val caseLabels: List<ConditionPart>,
        val throwingDefault: Boolean,
        val caseChecks: List<CaseCheck>,
        val subjectEnd: Int,
        val kindToken: String,
        val enumClass: String? = null,
    )

    /** One `equals` check of a string case, at instruction [jump], that stays a plain site. */
    class CaseCheck(
        val jump: Int,
        val literal: String,
        val fallsThroughWhenEqual: Boolean,
    )

    /**
     * The lowered switches in [insns]. [indexOfLabel] places each label before an instruction.
     * [depthAt] gives the operand stack depth before each instruction, or -1 when it is unknown.
     * [enumMappings] reads the map arrays a class declares.
     */
    fun read(
        insns: List<Insn>,
        indexOfLabel: Map<Label, Int>,
        depthAt: IntArray,
        enumMappings: EnumSwitchMappings,
    ): List<Reading> {
        val code = Code(insns, indexOfLabel, depthAt)
        val readings = mutableListOf<Reading>()
        for (index in insns.indices) {
            if (insns[index] !is Insn.TableSwitch && insns[index] !is Insn.LookupSwitch) continue
            val reading =
                code.javacString(index)
                    ?: code.hashMatch(index)
                    ?: code.enumMapping(index, enumMappings)
                    ?: code.bootstrapSwitch(index)
                    ?: continue
            readings += reading
        }
        return readings
    }

    /** Whether [insn] reads javac's or kotlinc's enum map array. */
    fun isEnumMapRead(insn: Insn): Boolean =
        insn is Insn.Field &&
            insn.opcode == Opcodes.GETSTATIC &&
            insn.descriptor == "[I" &&
            (insn.name.startsWith(JAVAC_MAP_PREFIX) || insn.name.startsWith(KOTLIN_MAP_PREFIX))

    /** One case entry of a switch that is not a filler for a gap: its key and its target. */
    private class CaseEntry(
        val key: Int,
        val target: Label,
    )

    private class Code(
        val insns: List<Insn>,
        val indexOfLabel: Map<Label, Int>,
        val depthAt: IntArray,
    ) {
        private fun at(label: Label): Int? = indexOfLabel[label]

        private fun varAt(
            index: Int,
            opcode: Int,
        ): Int? = (insns.getOrNull(index) as? Insn.Var)?.takeIf { it.opcode == opcode }?.varIndex

        private fun jumpAt(
            index: Int,
            vararg opcodes: Int,
        ): Insn.Jump? = (insns.getOrNull(index) as? Insn.Jump)?.takeIf { it.opcode in opcodes }

        private fun plainAt(
            index: Int,
            opcode: Int,
        ): Boolean = insns.getOrNull(index) == Insn.Plain(opcode)

        private fun intAt(index: Int): Int? =
            when (val insn = insns.getOrNull(index)) {
                is Insn.Plain -> if (insn.opcode in Opcodes.ICONST_M1..Opcodes.ICONST_5) insn.opcode - Opcodes.ICONST_0 else null
                is Insn.IntOperand -> if (insn.opcode == Opcodes.BIPUSH || insn.opcode == Opcodes.SIPUSH) insn.operand else null
                is Insn.Ldc -> insn.value as? Int
                else -> null
            }

        private fun isSwitch(index: Int): Boolean = insns.getOrNull(index).let { it is Insn.TableSwitch || it is Insn.LookupSwitch }

        private fun defaultOf(switch: Int): Label =
            when (val insn = insns[switch]) {
                is Insn.TableSwitch -> insn.dflt
                is Insn.LookupSwitch -> insn.dflt
                else -> error("not a switch")
            }

        /** The switch's case entries in label-array order, leaving out a filler that jumps to the default. */
        private fun casesOf(switch: Int): List<CaseEntry> =
            when (val insn = insns[switch]) {
                is Insn.TableSwitch -> {
                    insn.labels.indices.filter { insn.labels[it] !== insn.dflt }.map {
                        CaseEntry(
                            insn.min + it,
                            insn.labels[it],
                        )
                    }
                }

                is Insn.LookupSwitch -> {
                    insn.labels.indices.filter { insn.labels[it] !== insn.dflt }.map {
                        CaseEntry(
                            insn.keys[it],
                            insn.labels[it],
                        )
                    }
                }

                else -> {
                    error("not a switch")
                }
            }

        private fun isStringHashCode(index: Int): Boolean =
            insns.getOrNull(index) == Insn.MethodCall(Opcodes.INVOKEVIRTUAL, STRING_OWNER, "hashCode", "()I")

        /**
         * An `equals` check against a literal on local [temp] at [index]: `aload t; ldc "s"` as
         * javac and kotlinc write it, or `ldc "s"; aload t` as scalac writes it, then
         * `String.equals` (or `Object.equals` from scalac) and `ifeq` or `ifne`.
         */
        private fun equalsCheckAt(
            index: Int,
            temp: Int,
        ): Pair<String, Insn.Jump>? {
            val literal =
                when {
                    varAt(index, Opcodes.ALOAD) == temp -> (insns.getOrNull(index + 1) as? Insn.Ldc)?.value as? String
                    varAt(index + 1, Opcodes.ALOAD) == temp -> (insns.getOrNull(index) as? Insn.Ldc)?.value as? String
                    else -> null
                } ?: return null
            val call = insns.getOrNull(index + 2) as? Insn.MethodCall ?: return null
            val isEquals =
                call.opcode == Opcodes.INVOKEVIRTUAL &&
                    (call.owner == STRING_OWNER || call.owner == OBJECT_OWNER) &&
                    call.name == "equals" &&
                    call.descriptor == "(Ljava/lang/Object;)Z"
            if (!isEquals) return null
            val jump = jumpAt(index + 3, Opcodes.IFEQ, Opcodes.IFNE) ?: return null
            return literal to jump
        }

        /** javac's string switch. See [SwitchLowering]. */
        fun javacString(hash: Int): Reading? {
            if (!isStringHashCode(hash - 1)) return null
            val temp = varAt(hash - 2, Opcodes.ALOAD) ?: return null
            val indexVar = varAt(hash - 3, Opcodes.ISTORE) ?: return null
            if (intAt(hash - 4) != -1 || varAt(hash - 5, Opcodes.ASTORE) != temp) return null
            val join = at(defaultOf(hash)) ?: return null
            if (varAt(join, Opcodes.ILOAD) != indexVar || !isSwitch(join + 1)) return null
            val indexSwitch = join + 1

            val lowering = mutableListOf(hash)
            val literalByIndex = HashMap<Int, String>()
            for (entry in casesOf(hash)) {
                var position = at(entry.target) ?: return null
                while (true) {
                    val (literal, jump) = equalsCheckAt(position, temp) ?: return null
                    if (varAt(position, Opcodes.ALOAD) != temp || jump.opcode != Opcodes.IFEQ) return null
                    if (literal.hashCode() != entry.key) return null
                    val caseIndex = intAt(position + 4) ?: return null
                    if (varAt(position + 5, Opcodes.ISTORE) != indexVar) return null
                    val after = position + 6
                    val joins = after == join || (jumpAt(after, Opcodes.GOTO)?.let { at(it.target) } == join)
                    if (!joins || literalByIndex.put(caseIndex, literal) != null) return null
                    lowering += position + 3
                    val next = at(jump.target) ?: return null
                    if (next == join) break
                    position = next
                }
            }
            val labels = casesOf(indexSwitch).map { ConditionPart(ConditionPartKind.STRING_LITERAL, literalByIndex[it.key] ?: return null) }
            return Reading(lowering, indexSwitch, labels, throwsOnly(indexSwitch), emptyList(), hash - 5, "STRING-SWITCH")
        }

        /** kotlinc's and scalac's string match, with no second switch. See [SwitchLowering]. */
        fun hashMatch(hash: Int): Reading? {
            if (!isStringHashCode(hash - 1)) return null
            val temp = varAt(hash - 2, Opcodes.ALOAD) ?: return null
            val defaultIndex = at(defaultOf(hash)) ?: return null
            val lowering = mutableListOf<Int>()
            val store =
                when {
                    varAt(hash - 3, Opcodes.ASTORE) == temp -> {
                        hash - 3
                    }

                    // kotlinc on a nullable subject: `aload t; ifnull <default>; aload t; hashCode`.
                    jumpAt(hash - 3, Opcodes.IFNULL)?.let { at(it.target) } == defaultIndex &&
                        varAt(hash - 4, Opcodes.ALOAD) == temp &&
                        varAt(hash - 5, Opcodes.ASTORE) == temp -> {
                        lowering += hash - 3
                        hash - 5
                    }

                    // scalac: `aload t; ifnonnull L; iconst_0; goto S; L: aload t; hashCode; S: switch`.
                    jumpAt(hash - 3, Opcodes.GOTO)?.let { at(it.target) } == hash &&
                        intAt(hash - 4) == 0 &&
                        jumpAt(hash - 5, Opcodes.IFNONNULL)?.let { at(it.target) } == hash - 2 &&
                        varAt(hash - 6, Opcodes.ALOAD) == temp &&
                        varAt(hash - 7, Opcodes.ASTORE) == temp -> {
                        lowering += hash - 5
                        hash - 7
                    }

                    else -> {
                        return null
                    }
                }
            lowering += hash

            val checks = mutableListOf<CaseCheck>()
            val literals = HashSet<String>()
            for (entry in casesOf(hash)) {
                var position = at(entry.target) ?: return null
                var check = equalsCheckAt(position, temp) ?: return null
                while (true) {
                    val (literal, jump) = check
                    if (literal.hashCode() != entry.key || !literals.add(literal)) return null
                    checks += CaseCheck(position + 3, literal, fallsThroughWhenEqual = jump.opcode == Opcodes.IFEQ)
                    val next = if (jump.opcode == Opcodes.IFNE) position + 4 else at(jump.target) ?: return null
                    check = equalsCheckAt(next, temp) ?: break
                    position = next
                }
            }
            return Reading(lowering, null, emptyList(), false, checks, store, "STRING-MATCH")
        }

        /** A switch on javac's or kotlinc's enum map. See [SwitchLowering]. */
        fun enumMapping(
            switch: Int,
            enumMappings: EnumSwitchMappings,
        ): Reading? {
            if (!plainAt(switch - 1, Opcodes.IALOAD)) return null
            val ordinal = insns.getOrNull(switch - 2) as? Insn.MethodCall ?: return null
            if (ordinal.opcode != Opcodes.INVOKEVIRTUAL || ordinal.name != "ordinal" || ordinal.descriptor != "()I") return null
            val enumClass = ordinal.owner

            val lowering = mutableListOf<Int>()
            var nullable = false
            val mapIndex: Int
            val subjectEnd: Int
            if (plainAt(switch - 3, Opcodes.SWAP)) {
                // kotlinc: `<subject>; getstatic map; swap; ordinal; iaload`, with an optional
                // `dup; ifnonnull L; pop; iconst_m1; goto S` null check before `L: getstatic`.
                mapIndex = switch - 4
                if (!isMapField(mapIndex, KOTLIN_MAP_OWNER_SUFFIX, KOTLIN_MAP_PREFIX)) return null
                val nullCheck = mapIndex - 4
                val hasNullCheck =
                    jumpAt(mapIndex - 1, Opcodes.GOTO)?.let { at(it.target) } == switch &&
                        intAt(mapIndex - 2) == -1 &&
                        plainAt(mapIndex - 3, Opcodes.POP) &&
                        jumpAt(nullCheck, Opcodes.IFNONNULL)?.let { at(it.target) } == mapIndex &&
                        plainAt(nullCheck - 1, Opcodes.DUP)
                if (hasNullCheck) {
                    lowering += nullCheck
                    nullable = true
                    subjectEnd = nullCheck - 1
                } else {
                    subjectEnd = mapIndex
                }
            } else {
                // javac: `getstatic map; <subject>; ordinal; iaload`, where the subject leaves
                // exactly one value above the map.
                val depth = depthAt.getOrNull(switch - 2) ?: return null
                if (depth < 2) return null
                mapIndex = (switch - 3 downTo 0).firstOrNull { depthAt[it] == depth - 2 && depthAt[it + 1] == depth - 1 } ?: return null
                if (!isMapField(mapIndex, "", JAVAC_MAP_PREFIX)) return null
                if ((mapIndex + 1 until switch - 2).any { depthAt[it] < depth - 1 }) return null
                subjectEnd = switch - 2
            }

            val map = insns[mapIndex] as Insn.Field
            val mapping = enumMappings.of(map.owner)[map.name] ?: return null
            if (mapping.enumInternalName != enumClass) return null
            val labels =
                casesOf(switch).map {
                    val name = if (it.key == -1 && nullable) NULL_LABEL else mapping.constantsByValue[it.key] ?: return null
                    ConditionPart(ConditionPartKind.CODE, name)
                }
            return Reading(lowering, switch, labels, throwsOnly(switch), emptyList(), subjectEnd, "ENUM-SWITCH $enumClass", enumClass)
        }

        private fun isMapField(
            index: Int,
            ownerSuffix: String,
            namePrefix: String,
        ): Boolean {
            val field = insns.getOrNull(index) as? Insn.Field ?: return false
            return field.opcode == Opcodes.GETSTATIC && field.descriptor == "[I" && field.name.startsWith(namePrefix) &&
                field.owner.endsWith(ownerSuffix)
        }

        /** javac's switch on the index `SwitchBootstraps.typeSwitch` or `enumSwitch` returns. See [SwitchLowering]. */
        fun bootstrapSwitch(switch: Int): Reading? {
            val call = insns.getOrNull(switch - 1) as? Insn.InvokeDynamic ?: return null
            val bootstrap = call.bootstrapMethod
            if (bootstrap.owner != SWITCH_BOOTSTRAPS || call.name != bootstrap.name) return null
            val isEnumSwitch =
                when (bootstrap.name) {
                    "typeSwitch" -> false
                    "enumSwitch" -> true
                    else -> return null
                }
            if (Type.getArgumentTypes(call.descriptor).size != 2 || !call.descriptor.endsWith("I)I")) return null
            val restart = varAt(switch - 2, Opcodes.ILOAD) ?: return null
            val temp = varAt(switch - 3, Opcodes.ALOAD) ?: return null
            if (varAt(switch - 4, Opcodes.ISTORE) != restart || intAt(switch - 5) != 0) return null
            if (varAt(switch - 6, Opcodes.ASTORE) != temp) return null

            val arguments = call.bootstrapMethodArguments
            val labels =
                casesOf(switch).map { entry ->
                    if (entry.key == -1) return@map ConditionPart(ConditionPartKind.CODE, NULL_LABEL)
                    when (val argument = arguments.getOrNull(entry.key)) {
                        is Type -> ConditionPart(ConditionPartKind.CODE, simpleName(argument) ?: return null)
                        is String -> ConditionPart(if (isEnumSwitch) ConditionPartKind.CODE else ConditionPartKind.STRING_LITERAL, argument)
                        is Int -> if (isEnumSwitch) return null else ConditionPart(ConditionPartKind.CODE, argument.toString())
                        else -> return null
                    }
                }
            return Reading(emptyList(), switch, labels, throwsOnly(switch), emptyList(), switch - 6, "PATTERN-SWITCH ${bootstrap.name}")
        }

        /**
         * Whether [switch]'s default only throws: `new X; dup; invokespecial X.<init>()V; athrow`
         * for `IncompatibleClassChangeError` (javac 17) and `NoWhenBranchMatchedException`
         * (kotlinc), or with `aconst_null; aconst_null` and `(String, Throwable)` for
         * `MatchException` (javac 21). kotlinc's class is matched by suffix, since the shaded jar
         * rewrites `kotlin/` literals.
         */
        private fun throwsOnly(switch: Int): Boolean {
            val start = at(defaultOf(switch)) ?: return false
            val created = (insns.getOrNull(start) as? Insn.TypeOp)?.takeIf { it.opcode == Opcodes.NEW }?.type ?: return false
            if (!plainAt(start + 1, Opcodes.DUP)) return false
            val (constructor, descriptor) =
                when {
                    created == MATCH_EXCEPTION -> {
                        if (!plainAt(start + 2, Opcodes.ACONST_NULL) || !plainAt(start + 3, Opcodes.ACONST_NULL)) return false
                        start + 4 to "(Ljava/lang/String;Ljava/lang/Throwable;)V"
                    }

                    created == INCOMPATIBLE_CLASS_CHANGE || created.endsWith(NO_WHEN_BRANCH_SUFFIX) -> {
                        start + 2 to "()V"
                    }

                    else -> {
                        return false
                    }
                }
            return insns.getOrNull(constructor) == Insn.MethodCall(Opcodes.INVOKESPECIAL, created, "<init>", descriptor) &&
                plainAt(constructor + 1, Opcodes.ATHROW)
        }

        /** A class pattern's type as the source names it, such as `Circle` for `Shapes$Circle`, or `String[]`. */
        private fun simpleName(type: Type): String? =
            when (type.sort) {
                Type.OBJECT -> {
                    type.internalName
                        .substringAfterLast('/')
                        .substringAfterLast('$')
                        .takeIf { it.isNotEmpty() }
                }

                Type.ARRAY -> {
                    simpleName(type.elementType)?.let { it + "[]".repeat(type.dimensions) }
                }

                else -> {
                    null
                }
            }
    }

    private const val STRING_OWNER = "java/lang/String"
    private const val OBJECT_OWNER = "java/lang/Object"
    private const val SWITCH_BOOTSTRAPS = "java/lang/runtime/SwitchBootstraps"
    private const val MATCH_EXCEPTION = "java/lang/MatchException"
    private const val INCOMPATIBLE_CLASS_CHANGE = "java/lang/IncompatibleClassChangeError"
    private const val NO_WHEN_BRANCH_SUFFIX = "/NoWhenBranchMatchedException"
    private const val JAVAC_MAP_PREFIX = "\$SwitchMap\$"
    private const val KOTLIN_MAP_PREFIX = "\$EnumSwitchMapping\$"
    private const val KOTLIN_MAP_OWNER_SUFFIX = "\$WhenMappings"
    private const val NULL_LABEL = "null"
}

/**
 * The enum map arrays a class declares, read from its static initialiser: javac's `$SwitchMap$`
 * arrays on a synthetic class, and kotlinc's `$EnumSwitchMapping$` arrays on `$WhenMappings`. A
 * class's bytes come from [lookup], through the class loader's resources, and the class is never
 * loaded. Each class is read once. A class the lookup cannot read, or throws on, has no maps. See
 * ADR 0038.
 */
class EnumSwitchMappings(
    private val lookup: (internalName: String) -> ByteArray?,
) {
    /** One map array: the enum it maps and the constant each map value stands for. */
    class Mapping(
        val enumInternalName: String,
        val constantsByValue: Map<Int, String>,
    )

    private val byOwner = HashMap<String, Map<String, Mapping>>()

    /** The map arrays [ownerInternalName] declares, by field name. */
    fun of(ownerInternalName: String): Map<String, Mapping> =
        byOwner.getOrPut(ownerInternalName) {
            try {
                lookup(ownerInternalName)?.let(::read) ?: emptyMap()
            } catch (_: Exception) {
                emptyMap()
            }
        }

    /**
     * Reads each `<array>[E.C.ordinal()] = n` store in the static initialiser. The array is a
     * `getstatic` of the map field, as javac writes it, or a local that a later `aload; putstatic`
     * stores into the field, as kotlinc writes it. A map with two stores of one value, or with
     * constants of two enums, is left out.
     */
    private fun read(bytes: ByteArray): Map<String, Mapping> {
        val entries = HashMap<String, MutableList<Triple<String, String, Int>>>()
        ClassReader(bytes).accept(
            object : ClassVisitor(Opcodes.ASM9) {
                override fun visitMethod(
                    access: Int,
                    name: String,
                    descriptor: String,
                    signature: String?,
                    exceptions: Array<out String>?,
                ): MethodVisitor? = if (name == "<clinit>") InitializerVisitor(entries) else null
            },
            ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES,
        )
        return entries
            .mapNotNull { (field, stores) ->
                val enumClass = stores.first().first
                if (stores.any { it.first != enumClass }) return@mapNotNull null
                val byValue = stores.associate { it.third to it.second }
                if (byValue.size != stores.size) return@mapNotNull null
                field to Mapping(enumClass, byValue)
            }.toMap()
    }

    /**
     * Follows the static initialiser's instructions for the store pattern [read] describes.
     * [pendingByLocal] holds a kotlinc local's stores until its `putstatic` names the field.
     */
    private class InitializerVisitor(
        private val entries: MutableMap<String, MutableList<Triple<String, String, Int>>>,
    ) : MethodVisitor(Opcodes.ASM9) {
        /** Where a store's array came from: a `getstatic` of a map field, or a local. */
        private sealed interface Target {
            data class Field(
                val name: String,
            ) : Target

            data class Local(
                val index: Int,
            ) : Target
        }

        private val pendingByLocal = HashMap<Int, MutableList<Triple<String, String, Int>>>()
        private var array: Target? = null
        private var constant: Pair<String, String>? = null
        private var ordinalRead = false
        private var value: Int? = null
        private var loadedLocal: Int? = null

        private fun reset() {
            array = null
            constant = null
            ordinalRead = false
            value = null
        }

        private fun other() {
            reset()
            loadedLocal = null
        }

        override fun visitFieldInsn(
            opcode: Int,
            owner: String,
            name: String,
            descriptor: String,
        ) {
            val local = loadedLocal
            when {
                opcode == Opcodes.GETSTATIC && descriptor == "[I" -> {
                    other()
                    array = Target.Field(name)
                }

                opcode == Opcodes.GETSTATIC && array != null && constant == null && descriptor == "L$owner;" -> {
                    constant = owner to name
                }

                opcode == Opcodes.PUTSTATIC && descriptor == "[I" && local != null && array == Target.Local(local) -> {
                    pendingByLocal.remove(local)?.let { entries.getOrPut(name) { mutableListOf() } += it }
                    other()
                }

                else -> {
                    other()
                }
            }
        }

        override fun visitVarInsn(
            opcode: Int,
            varIndex: Int,
        ) {
            other()
            when (opcode) {
                Opcodes.ALOAD -> {
                    array = Target.Local(varIndex)
                    loadedLocal = varIndex
                }

                Opcodes.ASTORE -> {
                    pendingByLocal[varIndex] = mutableListOf()
                }
            }
        }

        override fun visitMethodInsn(
            opcode: Int,
            owner: String,
            name: String,
            descriptor: String,
            isInterface: Boolean,
        ) {
            val current = constant
            if (opcode == Opcodes.INVOKEVIRTUAL && name == "ordinal" && descriptor == "()I" && current?.first == owner && !ordinalRead) {
                ordinalRead = true
            } else {
                other()
            }
        }

        override fun visitInsn(opcode: Int) {
            when {
                opcode == Opcodes.NOP -> {}

                ordinalRead && value == null && opcode in Opcodes.ICONST_M1..Opcodes.ICONST_5 -> {
                    value = opcode - Opcodes.ICONST_0
                }

                opcode == Opcodes.IASTORE && ordinalRead && value != null -> {
                    store()
                    other()
                }

                else -> {
                    other()
                }
            }
        }

        override fun visitIntInsn(
            opcode: Int,
            operand: Int,
        ) {
            if (ordinalRead && value == null && (opcode == Opcodes.BIPUSH || opcode == Opcodes.SIPUSH)) value = operand else other()
        }

        override fun visitLdcInsn(value: Any?) {
            if (ordinalRead && this.value == null && value is Int) this.value = value else other()
        }

        private fun store() {
            val (enumClass, constantName) = constant ?: return
            val entry = Triple(enumClass, constantName, value ?: return)
            when (val target = array) {
                is Target.Field -> {
                    entries.getOrPut(target.name) { mutableListOf() } += entry
                }

                is Target.Local -> {
                    pendingByLocal[target.index]?.add(entry)
                }

                null -> {}
            }
        }

        override fun visitTypeInsn(
            opcode: Int,
            type: String,
        ) = other()

        override fun visitIincInsn(
            varIndex: Int,
            increment: Int,
        ) = other()

        override fun visitInvokeDynamicInsn(
            name: String,
            descriptor: String,
            bootstrapMethodHandle: Handle,
            vararg bootstrapMethodArguments: Any,
        ) = other()

        override fun visitJumpInsn(
            opcode: Int,
            label: Label,
        ) = other()

        override fun visitTableSwitchInsn(
            min: Int,
            max: Int,
            dflt: Label,
            vararg labels: Label,
        ) = other()

        override fun visitLookupSwitchInsn(
            dflt: Label,
            keys: IntArray,
            labels: Array<out Label>,
        ) = other()
    }
}
