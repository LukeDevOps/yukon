package io.github.lukedevops.yukon.instrumentation.branch

import io.github.lukedevops.yukon.export.CallEdge
import io.github.lukedevops.yukon.instrumentation.TypeMatchPolicy
import net.bytebuddy.jar.asm.ClassReader
import net.bytebuddy.jar.asm.ClassVisitor
import net.bytebuddy.jar.asm.Handle
import net.bytebuddy.jar.asm.Label
import net.bytebuddy.jar.asm.MethodVisitor
import net.bytebuddy.jar.asm.Opcodes

/**
 * Finds every [ConditionalJump], every `TABLESWITCH`/`LOOKUPSWITCH`, and every Kotlin `$default`
 * method in a class's original bytecode, plus each method's first source line.
 *
 * Only methods [methodFilter] accepts contribute branch sites, first lines, and inline marks. A
 * `$default` method is found and resolved regardless of [methodFilter], since it is synthetic and
 * so never accepted by the filter the method and branch tiers share.
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
        private val inlineMethods: Set<Pair<String, String>> = emptySet(),
        val defaultSites: List<DefaultSite> = emptyList(),
        val unresolvedDefaultSites: List<Pair<String, String>> = emptyList(),
        val scalaGetterSites: List<ScalaGetterSite> = emptyList(),
        val unresolvedScalaGetterSites: List<Pair<String, String>> = emptyList(),
        /**
         * Whether the class's own original bytecode declares a `<clinit>`. `<clinit>` is excluded
         * from [io.github.lukedevops.yukon.instrumentation.TypeMatchPolicy.methodMatcher], so it
         * never contributes a [sites] entry or an ordinary method probe; this flag is what lets
         * [io.github.lukedevops.yukon.instrumentation.YukonInstrumentation] give such a class one
         * METHOD probe anyway, counted by the woven `<clinit>` prelude instead of by advice. A
         * marker interface, or a class with only instance methods, has none.
         */
        val hasTypeInitializer: Boolean = false,
        private val callEdgesByMethod: Map<Pair<String, String>, List<CallEdge>> = emptyMap(),
        /** Dotted, as the class file's own super_class entry names it. Null only for `java.lang.Object`. See ADR 0024. */
        val superClassName: String? = null,
        /** Dotted, as the class file's own interfaces entries name them. See ADR 0024. */
        val interfaceNames: List<String> = emptyList(),
    ) {
        /** First line-number-table entry of the method, or -1 when the class carries no debug info or the bytes were never read. */
        fun firstLineOf(
            name: String,
            descriptor: String,
        ): Int = firstLineByMethod[name to descriptor] ?: -1

        /**
         * Whether the method is a Kotlin inline function, detected from its own
         * `$i$f$<name>` marker local. See ADR 0022. Always false on [EMPTY], and false when the
         * class carries no debug info, since the marker lives only in the LocalVariableTable.
         */
        fun isInline(
            name: String,
            descriptor: String,
        ): Boolean = (name to descriptor) in inlineMethods

        /**
         * The in-scope call edges read from this method's own bytecode, empty for any method that
         * does not get a METHOD probe. See ADR 0024.
         */
        fun callsOf(
            name: String,
            descriptor: String,
        ): List<CallEdge> = callEdgesByMethod[name to descriptor] ?: emptyList()

        companion object {
            val EMPTY = Analysis(emptyList(), emptyMap())
        }
    }

    /** One `$default`-shaped method found before its target is resolved. */
    private data class DefaultCandidate(
        val defaultName: String,
        val defaultDescriptor: String,
        val optionalBits: Int,
        val higherMaskTested: Boolean,
    )

    /**
     * A callee named exactly as one method's bytecode names it, before any pass-through or
     * cross-class `$default` resolution. [virtualRaw] is true for `invokevirtual`/
     * `invokeinterface`, or for an `invokedynamic` whose `LambdaMetafactory` implementation handle
     * has an `H_INVOKEVIRTUAL`/`H_INVOKEINTERFACE` tag. See ADR 0024.
     */
    private data class RawCandidate(
        val owner: String,
        val name: String,
        val descriptor: String,
        val virtualRaw: Boolean,
    )

    /** One resolved cross-class `$default` target: see [resolveCrossClassDefaultTarget]. */
    private data class CrossClassDefaultTarget(
        val name: String,
        val descriptor: String,
        val virtual: Boolean,
    )

    /**
     * The `LambdaMetafactory` implementation method an `invokedynamic` instruction names, as a
     * [RawCandidate], or null for any other bootstrap (`StringConcatFactory`, Kotlin's own,
     * records). The implementation method is bootstrap argument index 1, verified against javac
     * 21 and Kotlin 2.2.21 output. See ADR 0024.
     */
    private fun lambdaCandidateOrNull(
        bootstrapMethodHandle: Handle,
        bootstrapMethodArguments: Array<out Any>,
    ): RawCandidate? {
        if (bootstrapMethodHandle.owner != "java/lang/invoke/LambdaMetafactory") return null
        if (bootstrapMethodHandle.name != "metafactory" && bootstrapMethodHandle.name != "altMetafactory") return null
        val implementationHandle = bootstrapMethodArguments.getOrNull(1) as? Handle ?: return null
        val virtualRaw = implementationHandle.tag == Opcodes.H_INVOKEVIRTUAL || implementationHandle.tag == Opcodes.H_INVOKEINTERFACE
        return RawCandidate(implementationHandle.owner, implementationHandle.name, implementationHandle.desc, virtualRaw)
    }

    /** How many probe slots a switch with these case targets and this default owns. */
    fun switchOutcomeCount(
        dflt: Label,
        labels: Array<out Label>,
    ): Int = labels.count { it !== dflt } + 1

    /**
     * A synthetic default-filling method: named `<name>$default`, or the synthetic constructor a
     * class with a defaulted constructor gets. The constructor is recognised by its descriptor's
     * suffix alone, never by the marker type's fully qualified name: that name starts with
     * `kotlin.`, a literal `shadowJar` would relocate if it appeared in this agent's own code.
     */
    private fun isDefaultShaped(
        name: String,
        descriptor: String,
    ): Boolean = name.endsWith("\$default") || (name == "<init>" && descriptor.endsWith("DefaultConstructorMarker;)V"))

    /**
     * [lookup] resolves another class's bytes by internal name, for a constructor default getter
     * whose target lives on a different class from the getter itself (see
     * [resolveScalaGetterSites]). It defaults to always returning null, which leaves such a getter
     * unresolved instead of failing analysis. A caller must catch and swallow its own lookup
     * failures; this function treats a thrown exception the same as a null result.
     */
    fun analyze(
        classBytes: ByteArray,
        lookup: (internalName: String) -> ByteArray? = { null },
        includePackages: List<String> = emptyList(),
        excludePackages: List<String> = emptyList(),
        methodFilter: (name: String, descriptor: String) -> Boolean,
    ): Analysis {
        val sites = mutableListOf<BranchSite>()
        val firstLines = mutableMapOf<Pair<String, String>, Int>()
        val inlineMethods = mutableSetOf<Pair<String, String>>()
        var nextSiteIndex = 0

        var internalClassName = ""
        var classAccess = 0
        var superInternalName: String? = null
        var interfaceInternalNames: List<String> = emptyList()
        val methodAccess = mutableMapOf<Pair<String, String>, Int>()
        val localNames = mutableMapOf<Pair<String, String>, MutableMap<Int, String>>()
        val defaultCandidates = mutableListOf<DefaultCandidate>()
        val defaultShapedNames = mutableListOf<Pair<String, String>>()
        val rawCandidatesByMethod = mutableMapOf<Pair<String, String>, MutableList<RawCandidate>>()
        val eligibleMethodKeys = mutableSetOf<Pair<String, String>>()

        val classVisitor =
            object : ClassVisitor(Opcodes.ASM9) {
                override fun visit(
                    version: Int,
                    access: Int,
                    name: String,
                    signature: String?,
                    superName: String?,
                    interfaces: Array<out String>?,
                ) {
                    internalClassName = name
                    classAccess = access
                    superInternalName = superName
                    interfaceInternalNames = interfaces?.toList() ?: emptyList()
                }

                override fun visitMethod(
                    access: Int,
                    name: String,
                    descriptor: String,
                    signature: String?,
                    exceptions: Array<out String>?,
                ): MethodVisitor {
                    methodAccess[name to descriptor] = access
                    val localNamesForMethod = localNames.getOrPut(name to descriptor) { mutableMapOf() }
                    val defaultShaped = isDefaultShaped(name, descriptor)
                    if (defaultShaped) defaultShapedNames += name to descriptor
                    val eligible = methodFilter(name, descriptor)
                    val isTypeInitializer = name == "<clinit>" && descriptor == "()V"
                    if (eligible) eligibleMethodKeys += name to descriptor
                    val candidatesForMethod = rawCandidatesByMethod.getOrPut(name to descriptor) { mutableListOf() }

                    if (!eligible && !defaultShaped) {
                        // Out of scope for the method, branch, and inline tiers, but this method
                        // may still be somebody else's $default target, so its parameter names
                        // are worth capturing. <clinit> is always out of scope here too
                        // (methodFilter excludes it), but its own first line is still worth
                        // recording: YukonInstrumentation gives a class with a type initializer of
                        // its own one METHOD probe, counted by the woven prelude rather than advice.
                        // Its call candidates are still worth capturing too: this method may be a
                        // same-class pass-through (a bridge, an access$ accessor) referenced by a
                        // probed method elsewhere in the class. See ADR 0024.
                        return object : MethodVisitor(Opcodes.ASM9) {
                            override fun visitLocalVariable(
                                localName: String,
                                localDescriptor: String,
                                localSignature: String?,
                                start: Label,
                                end: Label,
                                index: Int,
                            ) {
                                localNamesForMethod.putIfAbsent(index, localName)
                            }

                            override fun visitLineNumber(
                                line: Int,
                                start: Label,
                            ) {
                                if (isTypeInitializer) firstLines.putIfAbsent(name to descriptor, line)
                            }

                            override fun visitMethodInsn(
                                opcode: Int,
                                owner: String,
                                calleeName: String,
                                calleeDescriptor: String,
                                isInterface: Boolean,
                            ) {
                                val virtualRaw = opcode == Opcodes.INVOKEVIRTUAL || opcode == Opcodes.INVOKEINTERFACE
                                candidatesForMethod += RawCandidate(owner, calleeName, calleeDescriptor, virtualRaw)
                            }

                            override fun visitInvokeDynamicInsn(
                                invokedName: String,
                                invokedDescriptor: String,
                                bootstrapMethodHandle: Handle,
                                vararg bootstrapMethodArguments: Any,
                            ) {
                                lambdaCandidateOrNull(bootstrapMethodHandle, bootstrapMethodArguments)?.let { candidatesForMethod += it }
                            }
                        }
                    }

                    return DefaultSiteAwareMethodVisitor(
                        name = name,
                        descriptor = descriptor,
                        eligible = eligible,
                        defaultShaped = defaultShaped,
                        localNamesForMethod = localNamesForMethod,
                        sites = sites,
                        firstLines = firstLines,
                        inlineMethods = inlineMethods,
                        onSiteIndexUsed = { nextSiteIndex++ },
                        nextSiteIndex = { nextSiteIndex },
                        onDefaultCandidate = { defaultCandidates += it },
                        candidatesForMethod = candidatesForMethod,
                    )
                }
            }

        ClassReader(classBytes).accept(classVisitor, ClassReader.SKIP_FRAMES)

        val defaultSites = resolveDefaultSites(internalClassName, classAccess, methodAccess, localNames, defaultCandidates)
        val resolved = defaultSites.mapTo(mutableSetOf()) { it.defaultName to it.defaultDescriptor }
        val unresolvedDefaultSites = defaultShapedNames.distinct().filterNot { it in resolved }

        val getterCandidateNames =
            methodAccess.entries
                .filter { (_, access) -> access and (Opcodes.ACC_SYNTHETIC or Opcodes.ACC_BRIDGE) == 0 }
                .map { it.key }
                .filter { (name, _) -> scalaGetterPattern.matches(name) }
        val scalaGetterSites =
            resolveScalaGetterSites(internalClassName, classAccess, methodAccess, localNames, firstLines, getterCandidateNames, lookup)
        val resolvedGetters = scalaGetterSites.mapTo(mutableSetOf()) { it.getterName to it.getterDescriptor }
        val unresolvedScalaGetterSites = getterCandidateNames.filterNot { it in resolvedGetters }
        val hasTypeInitializer = ("<clinit>" to "()V") in methodAccess

        val callEdgeEntryPoints = if (hasTypeInitializer) eligibleMethodKeys + ("<clinit>" to "()V") else eligibleMethodKeys
        val callEdgesByMethod =
            resolveCallEdges(
                internalClassName = internalClassName,
                methodAccess = methodAccess,
                rawCandidatesByMethod = rawCandidatesByMethod,
                eligibleMethodKeys = callEdgeEntryPoints,
                lookup = lookup,
                includePackages = includePackages,
                excludePackages = excludePackages,
            )

        return Analysis(
            sites,
            firstLines,
            inlineMethods,
            defaultSites,
            unresolvedDefaultSites,
            scalaGetterSites,
            unresolvedScalaGetterSites,
            hasTypeInitializer,
            callEdgesByMethod,
            superInternalName?.replace('/', '.'),
            interfaceInternalNames.map { it.replace('/', '.') },
        )
    }

    /**
     * Resolves every [eligibleMethodKeys] method's raw candidates (collected by [analyze]) into
     * its final [CallEdge] list. See ADR 0024.
     *
     * A same-class candidate is a pass-through only when this class declares it with a body and
     * it is not in [eligibleMethodKeys]: its own raw candidates are substituted in its place,
     * transitively, guarded by a per-entry-point visited set so a cycle among pass-through methods
     * terminates instead of looping. An abstract or native method has no body to pass through, and
     * a method this class only inherits (javac names the receiver's static type as owner, so
     * `this.inherited()` arrives with this class as owner) is not in the method table at all; both
     * stay verbatim edges, since the abstract case is exactly the template-method edge a collector
     * widens to the implementers. A cross-class
     * candidate shaped like a Kotlin `$default` method is resolved one step further, against the
     * target class's own bytecode fetched through [lookup], the same mechanism
     * [resolveScalaGetterSites] already uses for a Scala constructor getter's cross-class target.
     * Any other cross-class pass-through (a bridge, an `access$` accessor, a synthetic adapter
     * class the type matcher never instruments) is left as-is: it becomes an edge to that
     * synthetic method itself, an unknown type to the collector. See ADR 0024's "known gap" for
     * why this is an accepted v1 boundary, not a bug.
     *
     * Self-edges (the entry-point method calling itself, directly or through a pass-through
     * chain) are dropped. Edges are deduplicated per entry point by (owner, name, descriptor,
     * virtual).
     */
    private fun resolveCallEdges(
        internalClassName: String,
        methodAccess: Map<Pair<String, String>, Int>,
        rawCandidatesByMethod: Map<Pair<String, String>, List<RawCandidate>>,
        eligibleMethodKeys: Set<Pair<String, String>>,
        lookup: (String) -> ByteArray?,
        includePackages: List<String>,
        excludePackages: List<String>,
    ): Map<Pair<String, String>, List<CallEdge>> {
        val crossClassMethodTables = mutableMapOf<String, MethodTable?>()

        fun methodTableFor(ownerInternalName: String): MethodTable? =
            crossClassMethodTables.getOrPut(ownerInternalName) {
                val bytes =
                    try {
                        lookup(ownerInternalName)
                    } catch (_: Exception) {
                        null
                    } ?: return@getOrPut null
                try {
                    readMethodTable(bytes)
                } catch (_: Exception) {
                    null
                }
            }

        val dottedClassName = internalClassName.replace('/', '.')

        fun resolveOne(methodKey: Pair<String, String>): List<CallEdge> {
            val edges = LinkedHashSet<CallEdge>()
            val visited = mutableSetOf<Triple<String, String, String>>()

            fun visit(
                owner: String,
                name: String,
                descriptor: String,
                virtualRaw: Boolean,
            ) {
                if (!visited.add(Triple(owner, name, descriptor))) return

                if (owner == internalClassName) {
                    val access = methodAccess[name to descriptor]
                    val nonVirtual = access != null && access and NON_VIRTUAL_FLAGS != 0
                    val virtual = virtualRaw && !nonVirtual
                    val declaredWithBody = access != null && access and BODYLESS_FLAGS == 0
                    if ((name to descriptor) in eligibleMethodKeys || !declaredWithBody) {
                        edges += CallEdge(dottedClassName, name, descriptor, virtual)
                    } else {
                        for (candidate in rawCandidatesByMethod[name to descriptor].orEmpty()) {
                            visit(candidate.owner, candidate.name, candidate.descriptor, candidate.virtualRaw)
                        }
                    }
                    return
                }

                val dottedOwner = owner.replace('/', '.')
                if (!TypeMatchPolicy.isIncluded(dottedOwner, includePackages, excludePackages)) return

                if (isDefaultShaped(name, descriptor)) {
                    val target = methodTableFor(owner)?.let { resolveCrossClassDefaultTarget(owner, name, descriptor, it) }
                    if (target != null) {
                        edges += CallEdge(dottedOwner, target.name, target.descriptor, target.virtual)
                        return
                    }
                }
                edges += CallEdge(dottedOwner, name, descriptor, virtualRaw)
            }

            for (candidate in rawCandidatesByMethod[methodKey].orEmpty()) {
                visit(candidate.owner, candidate.name, candidate.descriptor, candidate.virtualRaw)
            }
            val (selfName, selfDescriptor) = methodKey
            return edges.filterNot { it.className == dottedClassName && it.methodName == selfName && it.methodDescriptor == selfDescriptor }
        }

        return eligibleMethodKeys.associateWith(::resolveOne)
    }

    /**
     * Finds the one method [defaultName]/[defaultDescriptor] fills defaults for, on a different
     * class from the one declaring it, by descriptor shape alone: the same matching rule
     * [resolveDefaultSites] applies in-class, minus the parts that need the `$default` method's
     * own bytecode (the mask test, its optional-parameter bits), since a call edge only needs the
     * target's identity and whether it can be overridden. No match, or more than one, returns
     * null, the same as an unresolved same-class default site.
     */
    private fun resolveCrossClassDefaultTarget(
        ownerInternalName: String,
        defaultName: String,
        defaultDescriptor: String,
        table: MethodTable,
    ): CrossClassDefaultTarget? {
        val isConstructor = defaultName == "<init>"
        val targetName = if (isConstructor) "<init>" else defaultName.removeSuffix("\$default")
        val defaultParams = parseParameterDescriptors(defaultDescriptor)
        val maskIntCount = resolveMaskIntCount(defaultParams.size - 1)
        val maskStartParamIndex = defaultParams.size - 1 - maskIntCount
        if (maskStartParamIndex < 0) return null
        val valueParams = defaultParams.subList(0, maskStartParamIndex)
        val defaultReturn = returnTypeOf(defaultDescriptor)
        val ownerDescriptor = "L$ownerInternalName;"

        val matches =
            table.methodAccess.entries.filter { (key, access) ->
                val (candidateName, candidateDescriptor) = key
                if (candidateName != targetName || candidateDescriptor == defaultDescriptor) return@filter false
                val candidateIsStatic = access and Opcodes.ACC_STATIC != 0
                val candidateParams = parseParameterDescriptors(candidateDescriptor)
                if (isConstructor) {
                    returnTypeOf(candidateDescriptor) == "V" && candidateParams == valueParams
                } else if (returnTypeOf(candidateDescriptor) != defaultReturn) {
                    false
                } else if (candidateIsStatic) {
                    candidateParams == valueParams
                } else {
                    valueParams.size == candidateParams.size + 1 &&
                        valueParams[0] == ownerDescriptor &&
                        valueParams.drop(1) == candidateParams
                }
            }

        if (matches.size != 1) return null
        val (targetKey, targetAccess) = matches.single()
        val nonVirtual = targetAccess and NON_VIRTUAL_FLAGS != 0
        return CrossClassDefaultTarget(targetKey.first, targetKey.second, virtual = !nonVirtual)
    }

    /** A same-class target with any of these flags can never be overridden, so a call to it is never virtual. See ADR 0024. */
    private const val NON_VIRTUAL_FLAGS = Opcodes.ACC_PRIVATE or Opcodes.ACC_STATIC or Opcodes.ACC_FINAL

    /** A same-class target with either flag has no body to pass through, so a call to it stays an edge. */
    private const val BODYLESS_FLAGS = Opcodes.ACC_ABSTRACT or Opcodes.ACC_NATIVE

    /** Matches a Scala default getter such as `f$default$2`, capturing the target's name and the one-based parameter number. */
    private val scalaGetterPattern = Regex("^(.+)\\\$default\\\$(\\d+)$")

    /**
     * The mangled name scalac gives a constructor default getter's target group. `<init>` itself
     * is not a legal method name segment, so the compiler spells it out instead: a getter named
     * `$lessinit$greater$default$1` fills a default for the primary constructor, not for a method
     * literally named `$lessinit$greater`.
     */
    private const val CONSTRUCTOR_GETTER_TARGET_NAME = "\$lessinit\$greater"

    /**
     * Visits one method's instructions. Branch/switch sites, the first line, and the inline
     * marker are only recorded when [eligible]. A `$default`-shaped method is scanned for its
     * mask-test pattern regardless of [eligible], since it is synthetic and so never eligible
     * itself.
     */
    private class DefaultSiteAwareMethodVisitor(
        private val name: String,
        private val descriptor: String,
        private val eligible: Boolean,
        private val defaultShaped: Boolean,
        private val localNamesForMethod: MutableMap<Int, String>,
        private val sites: MutableList<BranchSite>,
        private val firstLines: MutableMap<Pair<String, String>, Int>,
        private val inlineMethods: MutableSet<Pair<String, String>>,
        private val onSiteIndexUsed: () -> Unit,
        private val nextSiteIndex: () -> Int,
        private val onDefaultCandidate: (DefaultCandidate) -> Unit,
        private val candidatesForMethod: MutableList<RawCandidate>,
    ) : MethodVisitor(Opcodes.ASM9) {
        private var currentLine = -1
        private var lastLabel: Label? = null
        private val inlineMarkerName = "\$i\$f\$$name"

        private val maskLocalIndex: Int
        private val secondaryMaskRange: IntRange

        private var phase = 0
        private var pendingConstant = 0
        private var optionalBits = 0
        private var higherMaskTested = false

        init {
            if (defaultShaped) {
                val (localIndex, maskIntCount) = maskLocalInfo(name, descriptor)
                maskLocalIndex = localIndex
                secondaryMaskRange = (localIndex + 1) until (localIndex + maskIntCount)
            } else {
                maskLocalIndex = -1
                secondaryMaskRange = IntRange.EMPTY
            }
        }

        override fun visitLabel(label: Label) {
            lastLabel = label
        }

        override fun visitLineNumber(
            line: Int,
            start: Label,
        ) {
            currentLine = line
            if (eligible) firstLines.putIfAbsent(name to descriptor, line)
        }

        override fun visitJumpInsn(
            opcode: Int,
            label: Label,
        ) {
            if (defaultShaped) {
                // kotlinc writes the test as IFEQ. A coverage agent registered ahead of this one
                // (JaCoCo) hands over its own output, where every conditional jump is inverted
                // around an inserted probe, so the same test arrives as IFNE. Either direction
                // means "mask bit tested"; the advice reads the mask itself, not the branch.
                if (phase == 3 && (opcode == Opcodes.IFEQ || opcode == Opcodes.IFNE)) optionalBits = optionalBits or pendingConstant
                resetMaskPhase()
            }
            if (eligible && ConditionalJump.isTracked(opcode)) {
                sites += BranchSite(name, descriptor, currentLine, nextSiteIndex())
                onSiteIndexUsed()
            }
        }

        override fun visitTableSwitchInsn(
            min: Int,
            max: Int,
            dflt: Label,
            vararg labels: Label,
        ) {
            if (defaultShaped) resetMaskPhase()
            if (eligible) {
                sites += BranchSite(name, descriptor, currentLine, nextSiteIndex(), outcomeCount = switchOutcomeCount(dflt, labels))
                onSiteIndexUsed()
            }
        }

        override fun visitLookupSwitchInsn(
            dflt: Label,
            keys: IntArray,
            labels: Array<out Label>,
        ) {
            if (defaultShaped) resetMaskPhase()
            if (eligible) {
                sites += BranchSite(name, descriptor, currentLine, nextSiteIndex(), outcomeCount = switchOutcomeCount(dflt, labels))
                onSiteIndexUsed()
            }
        }

        override fun visitVarInsn(
            opcode: Int,
            varIndex: Int,
        ) {
            if (!defaultShaped) return
            if (opcode == Opcodes.ILOAD && varIndex == maskLocalIndex) {
                phase = 1
                pendingConstant = 0
                return
            }
            if (opcode == Opcodes.ILOAD && varIndex in secondaryMaskRange) higherMaskTested = true
            resetMaskPhase()
        }

        override fun visitIntInsn(
            opcode: Int,
            operand: Int,
        ) {
            if (!defaultShaped) return
            if (phase == 1 && (opcode == Opcodes.BIPUSH || opcode == Opcodes.SIPUSH) && isPowerOfTwo(operand)) {
                pendingConstant = operand
                phase = 2
            } else {
                resetMaskPhase()
            }
        }

        override fun visitLdcInsn(value: Any?) {
            if (!defaultShaped) return
            if (phase == 1 && value is Int && isPowerOfTwo(value)) {
                pendingConstant = value
                phase = 2
            } else {
                resetMaskPhase()
            }
        }

        override fun visitInsn(opcode: Int) {
            if (!defaultShaped) return
            when {
                phase == 1 && opcode in Opcodes.ICONST_0..Opcodes.ICONST_5 -> {
                    val value = opcode - Opcodes.ICONST_0
                    if (isPowerOfTwo(value)) {
                        pendingConstant = value
                        phase = 2
                    } else {
                        resetMaskPhase()
                    }
                }

                phase == 2 && opcode == Opcodes.IAND -> {
                    phase = 3
                }

                else -> {
                    resetMaskPhase()
                }
            }
        }

        override fun visitFieldInsn(
            opcode: Int,
            owner: String,
            fieldName: String,
            fieldDescriptor: String,
        ) {
            if (defaultShaped) resetMaskPhase()
        }

        override fun visitMethodInsn(
            opcode: Int,
            owner: String,
            methodName: String,
            methodDescriptor: String,
            isInterface: Boolean,
        ) {
            if (defaultShaped) resetMaskPhase()
            val virtualRaw = opcode == Opcodes.INVOKEVIRTUAL || opcode == Opcodes.INVOKEINTERFACE
            candidatesForMethod += RawCandidate(owner, methodName, methodDescriptor, virtualRaw)
        }

        override fun visitTypeInsn(
            opcode: Int,
            type: String,
        ) {
            if (defaultShaped) resetMaskPhase()
        }

        override fun visitIincInsn(
            varIndex: Int,
            increment: Int,
        ) {
            if (defaultShaped) resetMaskPhase()
        }

        override fun visitInvokeDynamicInsn(
            invokedName: String,
            invokedDescriptor: String,
            bootstrapMethodHandle: Handle,
            vararg bootstrapMethodArguments: Any,
        ) {
            if (defaultShaped) resetMaskPhase()
            lambdaCandidateOrNull(bootstrapMethodHandle, bootstrapMethodArguments)?.let { candidatesForMethod += it }
        }

        override fun visitMultiANewArrayInsn(
            arrayDescriptor: String,
            numDimensions: Int,
        ) {
            if (defaultShaped) resetMaskPhase()
        }

        private fun resetMaskPhase() {
            phase = 0
            pendingConstant = 0
        }

        override fun visitLocalVariable(
            localName: String,
            localDescriptor: String,
            signature: String?,
            start: Label,
            end: Label,
            index: Int,
        ) {
            localNamesForMethod.putIfAbsent(index, localName)
            if (eligible && localName == inlineMarkerName && end === lastLabel) inlineMethods += name to descriptor
        }

        override fun visitEnd() {
            if (defaultShaped && optionalBits != 0) {
                onDefaultCandidate(DefaultCandidate(name, descriptor, optionalBits, higherMaskTested))
            }
        }
    }

    private fun isPowerOfTwo(value: Int): Boolean = value > 0 && (value and (value - 1)) == 0

    /**
     * Splits a method descriptor's parameter section into its individual type descriptors, in
     * declaration order. Handles primitives, arrays, and object types.
     */
    private fun parseParameterDescriptors(descriptor: String): List<String> {
        val params = descriptor.substring(descriptor.indexOf('(') + 1, descriptor.lastIndexOf(')'))
        val result = mutableListOf<String>()
        var i = 0
        while (i < params.length) {
            val start = i
            while (params[i] == '[') i++
            i = if (params[i] == 'L') params.indexOf(';', i) + 1 else i + 1
            result += params.substring(start, i)
        }
        return result
    }

    private fun returnTypeOf(descriptor: String): String = descriptor.substring(descriptor.lastIndexOf(')') + 1)

    /** `long` and `double` take two local variable slots; everything else, one. */
    private fun slotWidth(type: String): Int = if (type == "J" || type == "D") 2 else 1

    private fun ceilDiv(
        a: Int,
        b: Int,
    ): Int = (a + b - 1) / b

    /**
     * How many mask `int`s a `$default` method with this many non-marker, non-mask parameters
     * carries: `ceil(n / 32)` where `n` is the number of original value parameters. Solved by
     * search rather than a closed form, since `n` and the mask count are mutually dependent.
     */
    private fun resolveMaskIntCount(paramsExcludingTrailing: Int): Int {
        var maskIntCount = 1
        while (maskIntCount <= paramsExcludingTrailing) {
            val valueParams = paramsExcludingTrailing - maskIntCount
            if (valueParams >= 0 && maskIntCount == ceilDiv(maxOf(valueParams, 1), 32)) return maskIntCount
            maskIntCount++
        }
        return 1
    }

    /** The local variable slot of a `$default` method's first mask `int`, and how many mask ints it has. */
    private fun maskLocalInfo(
        name: String,
        descriptor: String,
    ): Pair<Int, Int> {
        val params = parseParameterDescriptors(descriptor)
        val paramsExcludingTrailing = params.size - 1
        val maskIntCount = resolveMaskIntCount(paramsExcludingTrailing)
        val maskStartParamIndex = paramsExcludingTrailing - maskIntCount
        val startSlot = if (name == "<init>") 1 else 0
        var slot = startSlot
        for (i in 0 until maskStartParamIndex) slot += slotWidth(params[i])
        return slot to maskIntCount
    }

    /**
     * Matches each [DefaultCandidate] to the one declared method it fills defaults for, by
     * comparing the candidate's own parameter prefix (everything before its mask ints and
     * trailing marker) against every same-named declared method's descriptor. No match, or more
     * than one, drops the candidate: it gets no probes.
     */
    private fun resolveDefaultSites(
        internalClassName: String,
        classAccess: Int,
        methodAccess: Map<Pair<String, String>, Int>,
        localNames: Map<Pair<String, String>, Map<Int, String>>,
        candidates: List<DefaultCandidate>,
    ): List<DefaultSite> {
        val classIsFinal = classAccess and Opcodes.ACC_FINAL != 0
        val ownerDescriptor = "L$internalClassName;"

        return candidates.mapNotNull { candidate ->
            val isConstructor = candidate.defaultName == "<init>"
            val targetName = if (isConstructor) "<init>" else candidate.defaultName.removeSuffix("\$default")
            val defaultParams = parseParameterDescriptors(candidate.defaultDescriptor)
            val maskIntCount = resolveMaskIntCount(defaultParams.size - 1)
            val maskStartParamIndex = defaultParams.size - 1 - maskIntCount
            val valueParams = defaultParams.subList(0, maskStartParamIndex)
            val defaultReturn = returnTypeOf(candidate.defaultDescriptor)

            val matches =
                methodAccess.entries.filter { (key, access) ->
                    val (candidateName, candidateDescriptor) = key
                    if (candidateName != targetName || candidateDescriptor == candidate.defaultDescriptor) return@filter false
                    val candidateIsStatic = access and Opcodes.ACC_STATIC != 0
                    val candidateParams = parseParameterDescriptors(candidateDescriptor)
                    if (isConstructor) {
                        returnTypeOf(candidateDescriptor) == "V" && candidateParams == valueParams
                    } else if (returnTypeOf(candidateDescriptor) != defaultReturn) {
                        false
                    } else if (candidateIsStatic) {
                        candidateParams == valueParams
                    } else {
                        valueParams.size == candidateParams.size + 1 &&
                            valueParams[0] == ownerDescriptor &&
                            valueParams.drop(1) == candidateParams
                    }
                }

            if (matches.size != 1) return@mapNotNull null
            val (targetKey, targetAccess) = matches.single()
            val targetDescriptor = targetKey.second
            val targetIsStatic = targetAccess and Opcodes.ACC_STATIC != 0
            val targetIsPrivate = targetAccess and Opcodes.ACC_PRIVATE != 0
            val targetIsFinal = targetAccess and Opcodes.ACC_FINAL != 0
            val overridable = !isConstructor && !targetIsStatic && !targetIsPrivate && !targetIsFinal && !classIsFinal

            DefaultSite(
                defaultName = candidate.defaultName,
                defaultDescriptor = candidate.defaultDescriptor,
                targetName = targetName,
                targetDescriptor = targetDescriptor,
                optionalBits = candidate.optionalBits,
                higherMaskTested = candidate.higherMaskTested,
                overridable = overridable,
                maskParameterIndex = maskStartParamIndex,
                parameterNames = parameterNamesOf(candidate, targetDescriptor, targetIsStatic, localNames[targetKey] ?: emptyMap()),
            )
        }
    }

    /**
     * The target's own parameter names, keyed by [DefaultSite.optionalBits] bit, read from its
     * `LocalVariableTable`. Slot 0 is skipped for an instance method (`this`) and for a static
     * method whose own slot 0 is named `$this$...` (an extension receiver); neither is a value
     * parameter, so neither owns a mask bit.
     */
    private fun parameterNamesOf(
        candidate: DefaultCandidate,
        targetDescriptor: String,
        targetIsStatic: Boolean,
        targetLocalNames: Map<Int, String>,
    ): Map<Int, String> {
        val targetParams = parseParameterDescriptors(targetDescriptor)
        val firstSlotName = if (targetIsStatic) targetLocalNames[0] else null
        val skipFirst = targetIsStatic && firstSlotName != null && firstSlotName.startsWith("\$this\$")
        val remainingParams = if (skipFirst) targetParams.drop(1) else targetParams
        val startSlot = if (targetIsStatic) 0 else 1
        var slot = if (skipFirst) startSlot + slotWidth(targetParams[0]) else startSlot

        val names = mutableMapOf<Int, String>()
        for (bit in remainingParams.indices) {
            if ((candidate.optionalBits shr bit) and 1 == 1) names[bit] = targetLocalNames[slot] ?: ""
            slot += slotWidth(remainingParams[bit])
        }
        return names
    }

    /**
     * Matches each Scala default getter (`f$default$N`, one of [candidateNames]) to the one
     * declared, non-getter method whose default it fills. A getter's own `N` is one-based across
     * every parameter list and counts an extension receiver, unlike Kotlin's mask bits.
     *
     * A constructor default getter (`$lessinit$greater$default$N`) is the one case whose target can
     * live outside the getter's own class. On a companion module class ([ownInternalClassName] ends
     * in `$`), the getter is an instance method and its target `<init>` lives on the sibling class
     * named without the trailing `$`, whose bytes [lookupCompanionBytes] reads. The same class also
     * carries a public static forwarder under the same getter name, whose own target `<init>` is in
     * that same class, resolved the ordinary in-class way. Either way, a constructor target is
     * never overridable.
     *
     * A non-constructor getter always resolves in its own class: a method with at least `N` JVM
     * parameters whose parameter `N` erases to the getter's own return type, or is
     * `scala.Function0` (a by-name parameter's getter returns the value type, not a thunk of it),
     * and whose leading parameters match the getter's own parameter list exactly, since a getter for
     * a later parameter list carries every earlier list's parameters whether or not its own default
     * expression reads them.
     *
     * No survivor, or more than one, leaves the getter unresolved. scalac forbids two overloads of
     * one name both declaring defaults, so ambiguity here can only come from an overload with no
     * defaults of its own.
     */
    private fun resolveScalaGetterSites(
        ownInternalClassName: String,
        classAccess: Int,
        methodAccess: Map<Pair<String, String>, Int>,
        localNames: Map<Pair<String, String>, Map<Int, String>>,
        firstLines: Map<Pair<String, String>, Int>,
        candidateNames: List<Pair<String, String>>,
        lookupCompanionBytes: (String) -> ByteArray?,
    ): List<ScalaGetterSite> {
        val ownNamespace = GetterTargetNamespace(methodAccess, localNames, firstLines, classAccess, targetClassName = null)
        val companionNamespaces = mutableMapOf<String, GetterTargetNamespace?>()

        fun companionNamespace(companionInternalName: String): GetterTargetNamespace? =
            companionNamespaces.getOrPut(companionInternalName) {
                val bytes =
                    try {
                        lookupCompanionBytes(companionInternalName)
                    } catch (_: Exception) {
                        null
                    } ?: return@getOrPut null
                val table =
                    try {
                        readMethodTable(bytes)
                    } catch (_: Exception) {
                        null
                    } ?: return@getOrPut null
                GetterTargetNamespace(
                    table.methodAccess,
                    table.localNames,
                    table.firstLines,
                    table.classAccess,
                    targetClassName = companionInternalName.replace('/', '.'),
                )
            }

        return candidateNames.mapNotNull { (getterName, getterDescriptor) ->
            val match = scalaGetterPattern.matchEntire(getterName) ?: return@mapNotNull null
            val rawTargetName = match.groupValues[1]
            val parameterIndex = match.groupValues[2].toInt() - 1
            val isConstructorGetter = rawTargetName == CONSTRUCTOR_GETTER_TARGET_NAME
            val targetName = if (isConstructorGetter) "<init>" else rawTargetName

            val namespace =
                if (isConstructorGetter && ownInternalClassName.endsWith("$")) {
                    companionNamespace(ownInternalClassName.removeSuffix("$")) ?: return@mapNotNull null
                } else {
                    ownNamespace
                }

            val getterParams = parseParameterDescriptors(getterDescriptor)
            val getterReturn = returnTypeOf(getterDescriptor)

            val matches =
                namespace.methodAccess.entries.filter { (key, _) ->
                    val (candidateName, candidateDescriptor) = key
                    if (candidateName != targetName || scalaGetterPattern.matches(candidateName)) return@filter false
                    val candidateParams = parseParameterDescriptors(candidateDescriptor)
                    if (candidateParams.size <= parameterIndex) return@filter false
                    val nthParameterMatches =
                        candidateParams[parameterIndex] == getterReturn || candidateParams[parameterIndex] == "Lscala/Function0;"
                    nthParameterMatches && candidateParams.take(getterParams.size) == getterParams
                }

            if (matches.size != 1) return@mapNotNull null
            val (targetKey, targetAccess) = matches.single()
            val targetDescriptor = targetKey.second
            val targetIsStatic = targetAccess and Opcodes.ACC_STATIC != 0
            val targetIsPrivate = targetAccess and Opcodes.ACC_PRIVATE != 0
            val targetIsFinal = targetAccess and Opcodes.ACC_FINAL != 0
            val namespaceClassIsFinal = namespace.classAccess and Opcodes.ACC_FINAL != 0
            val overridable = !isConstructorGetter && !targetIsStatic && !targetIsPrivate && !targetIsFinal && !namespaceClassIsFinal

            val targetParams = parseParameterDescriptors(targetDescriptor)
            var slot = if (targetIsStatic) 0 else 1
            for (i in 0 until parameterIndex) slot += slotWidth(targetParams[i])
            val parameterName = namespace.localNames[targetKey]?.get(slot) ?: ""
            val line = namespace.firstLines[targetKey] ?: -1

            ScalaGetterSite(
                getterName = getterName,
                getterDescriptor = getterDescriptor,
                targetName = targetName,
                targetDescriptor = targetDescriptor,
                parameterIndex = parameterIndex,
                parameterName = parameterName,
                overridable = overridable,
                targetClassName = namespace.targetClassName,
                line = line,
            )
        }
    }

    /**
     * The method table a resolved getter's target is searched in, plus the target's own class
     * name when it differs from the getter's own ([targetClassName], null for an in-class target).
     */
    private class GetterTargetNamespace(
        val methodAccess: Map<Pair<String, String>, Int>,
        val localNames: Map<Pair<String, String>, Map<Int, String>>,
        val firstLines: Map<Pair<String, String>, Int>,
        val classAccess: Int,
        val targetClassName: String?,
    )

    /** A class's method access flags, local variable names, and first line numbers, read once from its bytes. */
    private class MethodTable(
        val classAccess: Int,
        val methodAccess: Map<Pair<String, String>, Int>,
        val localNames: Map<Pair<String, String>, Map<Int, String>>,
        val firstLines: Map<Pair<String, String>, Int>,
    )

    /**
     * A minimal reader for a class this agent is not instrumenting: only what
     * [resolveScalaGetterSites] needs to resolve a constructor default getter against a companion
     * module class's own `<init>`. Unlike [analyze], every method's first line is recorded
     * unconditionally, since no method or branch tier runs against this class to gate it by
     * eligibility.
     */
    private fun readMethodTable(classBytes: ByteArray): MethodTable {
        var classAccess = 0
        val methodAccess = mutableMapOf<Pair<String, String>, Int>()
        val localNames = mutableMapOf<Pair<String, String>, MutableMap<Int, String>>()
        val firstLines = mutableMapOf<Pair<String, String>, Int>()

        val classVisitor =
            object : ClassVisitor(Opcodes.ASM9) {
                override fun visit(
                    version: Int,
                    access: Int,
                    name: String,
                    signature: String?,
                    superName: String?,
                    interfaces: Array<out String>?,
                ) {
                    classAccess = access
                }

                override fun visitMethod(
                    access: Int,
                    name: String,
                    descriptor: String,
                    signature: String?,
                    exceptions: Array<out String>?,
                ): MethodVisitor {
                    methodAccess[name to descriptor] = access
                    val localNamesForMethod = localNames.getOrPut(name to descriptor) { mutableMapOf() }
                    return object : MethodVisitor(Opcodes.ASM9) {
                        override fun visitLineNumber(
                            line: Int,
                            start: Label,
                        ) {
                            firstLines.putIfAbsent(name to descriptor, line)
                        }

                        override fun visitLocalVariable(
                            localName: String,
                            localDescriptor: String,
                            localSignature: String?,
                            start: Label,
                            end: Label,
                            index: Int,
                        ) {
                            localNamesForMethod.putIfAbsent(index, localName)
                        }
                    }
                }
            }

        ClassReader(classBytes).accept(classVisitor, ClassReader.SKIP_FRAMES)
        return MethodTable(classAccess, methodAccess, localNames, firstLines)
    }
}
