package io.github.lukedevops.yukon.instrumentation.branch

import io.github.lukedevops.yukon.export.BodyKind
import io.github.lukedevops.yukon.export.CallEdge
import io.github.lukedevops.yukon.export.CallEdgeKind
import io.github.lukedevops.yukon.export.GeneratedBy
import io.github.lukedevops.yukon.instrumentation.ScalaClassDetector
import io.github.lukedevops.yukon.instrumentation.TypeMatchPolicy
import net.bytebuddy.jar.asm.AnnotationVisitor
import net.bytebuddy.jar.asm.ClassReader
import net.bytebuddy.jar.asm.ClassVisitor
import net.bytebuddy.jar.asm.FieldVisitor
import net.bytebuddy.jar.asm.Handle
import net.bytebuddy.jar.asm.Label
import net.bytebuddy.jar.asm.MethodVisitor
import net.bytebuddy.jar.asm.Opcodes
import net.bytebuddy.jar.asm.RecordComponentVisitor
import net.bytebuddy.jar.asm.TypePath
import io.github.lukedevops.yukon.export.BranchSite as BranchSitePayload

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
 * A switch's outcome count is one per case entry plus the default. A `TABLESWITCH` over sparse
 * case values carries filler entries for the gaps that jump straight to the default label; those
 * are the default outcome, not cases of their own, and counting them separately would report
 * "case 4 never hit" for a switch that has no case 4. [BranchProbeMethodVisitor] applies the same
 * rule when it rewrites the switch, so the two agree on the slot count.
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
        /**
         * Per method, the ordinals of its dropped sites: the site's encounter index among every
         * tracked conditional and switch in that method, dropped or kept, counted from zero.
         * [BranchProbeAsmVisitorWrapper] uses this to skip a dropped site without allocating a
         * slot for it, in step with the same encounter order [BranchProbeMethodVisitor] walks.
         * See ADR 0025.
         */
        private val droppedOrdinalsByMethod: Map<Pair<String, String>, Set<Int>> = emptyMap(),
        /**
         * What compiled each method into existence, keyed by (name, descriptor), computed once
         * per class from its own method table, superclass and method bodies. See [generatedBy]
         * and ADR 0026.
         */
        private val generatedByMethod: Map<Pair<String, String>, GeneratedBy> = emptyMap(),
        /**
         * Whether any method this analysis visited carried at least one `LineNumberTable` entry.
         * False when debug info was stripped (ProGuard, R8), or the class carries none to begin
         * with.
         */
        val hasLineNumbers: Boolean = false,
        /**
         * Whether the class carries a class-level annotation shaped like `kotlin.Metadata`: one
         * package segment, then `Metadata`. Matched by shape, not by a literal, since `shadowJar`
         * relocates any literal in this agent's own code that starts with `kotlin/`.
         */
        val isKotlinClass: Boolean = false,
        private val referencesByMethod: Map<Pair<String, String>, List<String>> = emptyMap(),
        /**
         * The out-of-scope classes the class references outside any probed method, dotted: its
         * header, fields and record components, methods with no body, re-kinded Scala default
         * getters, and pass-throughs nothing in the class reaches. See [placeReferences] and ADR 0030.
         */
        val classReferences: List<String> = emptyList(),
        private val lambdaBodies: Set<Pair<String, String>> = emptySet(),
        /**
         * The class file's `SourceFile` attribute exactly as it appears, or null when it has none
         * or the bytes were never read. See ADR 0034.
         */
        val sourceFile: String? = null,
        /** What kind of body class this is, from [BodyKindRule]. [BodyKind.NONE] on [EMPTY]. See ADR 0034. */
        val bodyKind: BodyKind = BodyKind.NONE,
        /** The source name of a [BodyKind.LOCAL_CLASS], and null for every other kind. See ADR 0034. */
        val sourceName: String? = null,
        /**
         * The forwarder table's entries this class yields (ADR 0035): each pass-through that a
         * framework can report as a handler for one of [analyze]'s handler interfaces, with the
         * one probed method it forwards to. Empty when no handler interface was given.
         */
        val handlerForwarders: List<HandlerForwarder> = emptyList(),
        /** The class's own name, dotted, as its header names it. Empty on [EMPTY]. Every branch key and site key digests it. */
        val className: String = "",
    ) {
        /**
         * Each kept site of [sites], in site index order, with its outcomes numbered, given roles
         * and keyed by [KeptBranchSite.of]. This is the one numbering both the manifest's BRANCH
         * and METHOD probes and the static baseline's declared methods use. See ADR 0037.
         */
        val keptSites: List<KeptBranchSite> by lazy { KeptBranchSite.of(sites, className) }

        private val keptSitesByMethod by lazy { keptSites.groupBy { it.site.methodName to it.site.methodDescriptor } }

        /**
         * The method's kept sites as the manifest and the static baseline send them, in site index
         * order. Empty for any method with no kept site, `<clinit>` included. See ADR 0037.
         */
        fun branchSitesOf(
            name: String,
            descriptor: String,
        ): List<BranchSitePayload> = keptSitesByMethod[name to descriptor]?.map { it.toPayload() } ?: emptyList()

        /**
         * Whether the method is a lambda body: [methodFilter][analyze] accepted it, an
         * `invokedynamic` in this class names it as the `LambdaMetafactory` implementation, and
         * its name passes [TypeMatchPolicy.isLambdaBodyName]. The `invokedynamic` may name it
         * through a pass-through, which is how scalac reaches a body through its `$adapted`
         * boxing forwarder. Always false on [EMPTY]. See ADR 0034.
         */
        fun isLambdaBody(
            name: String,
            descriptor: String,
        ): Boolean = (name to descriptor) in lambdaBodies

        /**
         * The out-of-scope classes this method references, dotted, first seen first: its own
         * bytecode, signature and annotations, plus those of every pass-through it reaches, by the
         * call-edge rules of ADR 0024. Empty for any method that does not get a METHOD probe. JDK
         * classes are still listed here; the transform drops them. See ADR 0030.
         */
        fun referencesOf(
            name: String,
            descriptor: String,
        ): List<String> = referencesByMethod[name to descriptor] ?: emptyList()

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

        /** The ordinals of [name]/[descriptor]'s dropped sites; see [droppedOrdinalsByMethod]. */
        fun droppedOrdinalsOf(
            name: String,
            descriptor: String,
        ): Set<Int> = droppedOrdinalsByMethod[name to descriptor] ?: emptySet()

        /**
         * What compiled this method into existence, from bytecode shape alone, per ADR 0026.
         * [GeneratedBy.NONE] on [EMPTY], and for any method none of the shape rules matched.
         */
        fun generatedBy(
            name: String,
            descriptor: String,
        ): GeneratedBy = generatedByMethod[name to descriptor] ?: GeneratedBy.NONE

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
     * Parsed method tables of classes referenced across a class boundary, shared between
     * analyses so a class that many others reference is read and parsed once rather than once per
     * referencing class. Bounded and least-recently-used, since the tables of a whole classpath
     * would otherwise stay live for the life of the holder. Safe to share between threads; every
     * access is synchronised. A class whose bytes cannot be read is remembered as unreadable, so a
     * miss is not retried on every analysis either.
     */
    class CrossClassTableCache(
        private val maxEntries: Int,
    ) {
        private val tables =
            object : LinkedHashMap<String, Any>(16, 0.75f, true) {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Any>?): Boolean = size > maxEntries
            }

        /** The number of tables held, unreadable entries included. */
        val size: Int
            get() = synchronized(tables) { tables.size }

        internal fun getOrRead(
            internalName: String,
            read: () -> MethodTable?,
        ): MethodTable? {
            synchronized(tables) { tables[internalName] }?.let { return it as? MethodTable }
            val table = read()
            synchronized(tables) { tables[internalName] = table ?: UNREADABLE }
            return table
        }

        private companion object {
            val UNREADABLE = Any()
        }
    }

    /**
     * A callee named exactly as one method's bytecode names it, before any pass-through or
     * cross-class `$default` resolution. [virtualRaw] is true for `invokevirtual`/
     * `invokeinterface`, or for an `invokedynamic` whose `LambdaMetafactory` implementation handle
     * has an `H_INVOKEVIRTUAL`/`H_INVOKEINTERFACE` tag. See ADR 0024.
     *
     * [kind] is [CallEdgeKind.CREATES] only for such an `invokedynamic`, and [capturedCount] is
     * then its [capturedCount]; every other candidate is a [CallEdgeKind.CALL] with nothing
     * captured. See ADR 0034.
     *
     * [functionalInterface] is the internal name of the interface such an `invokedynamic` makes a
     * lambda for, the return type of its `invokedType`, and null for every other candidate. The
     * forwarder table keys on it (ADR 0035).
     */
    internal data class RawCandidate(
        val owner: String,
        val name: String,
        val descriptor: String,
        val virtualRaw: Boolean,
        val kind: CallEdgeKind = CallEdgeKind.CALL,
        val capturedCount: Int = 0,
        val functionalInterface: String? = null,
    )

    /** One resolved cross-class `$default` target: see [resolveCrossClassDefaultTarget]. */
    private data class CrossClassDefaultTarget(
        val name: String,
        val descriptor: String,
        val virtual: Boolean,
    )

    /**
     * Instruction handling every method-body visitor in [analyze] and [readMethodTable] shares:
     * a method call or `invokedynamic` is always a [RawCandidate], and a static field use on
     * another class is a [RawCandidate] for that class's own `<clinit>`. A subclass overrides
     * these to add its own side effect (resetting the `$default` mask-test state machine) as long
     * as it calls through, or adds further callbacks such as line numbers and local variable names.
     *
     * A `GETSTATIC`/`PUTSTATIC` on the class itself is excluded: that access, folded into a
     * pass-through this class owns, must never look like a use of its own `<clinit>` from the
     * outside, which would make it eligible for substitution as a pass-through in its own right.
     * `GETFIELD`/`PUTFIELD` are excluded from every owner, since an instance field access implies
     * nothing beyond the constructor edge the object's creation already carries. See ADR 0024.
     */
    private open class CallCandidateMethodVisitor(
        private val ownerInternalName: String,
        private val candidatesForMethod: MutableList<RawCandidate>,
        referencesForMethod: MutableSet<String>,
    ) : MethodVisitor(Opcodes.ASM9) {
        private val references = ReferenceCollector(referencesForMethod)

        override fun visitMethodInsn(
            opcode: Int,
            owner: String,
            name: String,
            descriptor: String,
            isInterface: Boolean,
        ) {
            references.internalName(owner)
            references.descriptor(descriptor)
            val virtualRaw = opcode == Opcodes.INVOKEVIRTUAL || opcode == Opcodes.INVOKEINTERFACE
            candidatesForMethod += RawCandidate(owner, name, descriptor, virtualRaw)
        }

        override fun visitInvokeDynamicInsn(
            name: String,
            descriptor: String,
            bootstrapMethodHandle: Handle,
            vararg bootstrapMethodArguments: Any,
        ) {
            references.descriptor(descriptor)
            references.handle(bootstrapMethodHandle)
            bootstrapMethodArguments.forEach(references::constant)
            lambdaCandidateOrNull(descriptor, bootstrapMethodHandle, bootstrapMethodArguments)?.let { candidatesForMethod += it }
        }

        override fun visitTypeInsn(
            opcode: Int,
            type: String,
        ) {
            references.internalName(type)
        }

        override fun visitMultiANewArrayInsn(
            descriptor: String,
            numDimensions: Int,
        ) {
            references.descriptor(descriptor)
        }

        override fun visitLdcInsn(value: Any?) {
            references.constant(value)
        }

        override fun visitTryCatchBlock(
            start: Label,
            end: Label,
            handler: Label,
            type: String?,
        ) {
            references.internalName(type)
        }

        override fun visitAnnotation(
            descriptor: String,
            visible: Boolean,
        ): AnnotationVisitor? = references.annotation(descriptor, visible)

        override fun visitParameterAnnotation(
            parameter: Int,
            descriptor: String,
            visible: Boolean,
        ): AnnotationVisitor? = references.annotation(descriptor, visible)

        override fun visitTypeAnnotation(
            typeRef: Int,
            typePath: TypePath?,
            descriptor: String,
            visible: Boolean,
        ): AnnotationVisitor? = references.annotation(descriptor, visible)

        override fun visitAnnotationDefault(): AnnotationVisitor = references.annotationDefault()

        override fun visitInsnAnnotation(
            typeRef: Int,
            typePath: TypePath?,
            descriptor: String,
            visible: Boolean,
        ): AnnotationVisitor? = references.annotation(descriptor, visible)

        override fun visitTryCatchAnnotation(
            typeRef: Int,
            typePath: TypePath?,
            descriptor: String,
            visible: Boolean,
        ): AnnotationVisitor? = references.annotation(descriptor, visible)

        override fun visitLocalVariableAnnotation(
            typeRef: Int,
            typePath: TypePath?,
            start: Array<out Label>,
            end: Array<out Label>,
            index: IntArray,
            descriptor: String,
            visible: Boolean,
        ): AnnotationVisitor? = references.annotation(descriptor, visible)

        override fun visitFieldInsn(
            opcode: Int,
            owner: String,
            fieldName: String,
            fieldDescriptor: String,
        ) {
            references.internalName(owner)
            references.descriptor(fieldDescriptor)
            if (owner == ownerInternalName) return
            if (opcode != Opcodes.GETSTATIC && opcode != Opcodes.PUTSTATIC) return
            candidatesForMethod += RawCandidate(owner, "<clinit>", "()V", virtualRaw = false)
        }
    }

    /**
     * The `LambdaMetafactory` implementation method an `invokedynamic` instruction names, as a
     * [CallEdgeKind.CREATES] [RawCandidate], or null for any other bootstrap
     * (`StringConcatFactory`, Kotlin's own, records). The implementation method is bootstrap
     * argument index 1, verified against javac 21 and Kotlin 2.2.21 output. [invokedDescriptor] is
     * the instruction's own descriptor, the call site's `invokedType`. See ADRs 0024 and 0034.
     */
    private fun lambdaCandidateOrNull(
        invokedDescriptor: String,
        bootstrapMethodHandle: Handle,
        bootstrapMethodArguments: Array<out Any>,
    ): RawCandidate? {
        if (bootstrapMethodHandle.owner != "java/lang/invoke/LambdaMetafactory") return null
        if (bootstrapMethodHandle.name != "metafactory" && bootstrapMethodHandle.name != "altMetafactory") return null
        val implementationHandle = bootstrapMethodArguments.getOrNull(1) as? Handle ?: return null
        val virtualRaw = implementationHandle.tag == Opcodes.H_INVOKEVIRTUAL || implementationHandle.tag == Opcodes.H_INVOKEINTERFACE
        return RawCandidate(
            implementationHandle.owner,
            implementationHandle.name,
            implementationHandle.desc,
            virtualRaw,
            CallEdgeKind.CREATES,
            capturedCount(invokedDescriptor, implementationHandle.tag),
            returnTypeOf(invokedDescriptor).removePrefix("L").removeSuffix(";"),
        )
    }

    /**
     * How many of an implementation method's leading parameters a `LambdaMetafactory` call site
     * fills with captured values. Each parameter of [invokedDescriptor], the call site's
     * `invokedType`, is one captured value. The metafactory passes them to the implementation in
     * order, ahead of the functional interface's own arguments.
     *
     * For an instance method ([implementationTag] `H_INVOKEVIRTUAL`, `H_INVOKEINTERFACE` or
     * `H_INVOKESPECIAL`), the first captured value is the receiver, which is not in the
     * implementation's parameter list, so it is not counted. An unbound reference such as
     * `Foo::name` captures nothing and takes its receiver from the interface's first argument, so
     * the result never goes below zero. A static method and a constructor (`H_NEWINVOKESPECIAL`)
     * have no receiver to bind, so every captured value fills a parameter. See ADR 0034.
     */
    internal fun capturedCount(
        invokedDescriptor: String,
        implementationTag: Int,
    ): Int {
        val captured = parseParameterDescriptors(invokedDescriptor).size
        val receiver =
            when (implementationTag) {
                Opcodes.H_INVOKEVIRTUAL, Opcodes.H_INVOKEINTERFACE, Opcodes.H_INVOKESPECIAL -> 1
                else -> 0
            }
        return (captured - receiver).coerceAtLeast(0)
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
     * A class-level annotation descriptor shaped like `kotlin.Metadata`'s own: `L`, one package
     * segment with no further `/`, then `/Metadata;`. Shape, not a literal, so this source file
     * never spells out a string starting with `kotlin/`, which `shadowJar` would otherwise rewrite
     * in this agent's own relocated copy.
     */
    private val kotlinMetadataDescriptorShape = Regex("^L[^/;]+/Metadata;$")

    /**
     * [lookup] resolves another class's bytes by internal name, for a constructor default getter
     * whose target lives on a different class from the getter itself (see
     * [resolveScalaGetterSites]). It defaults to always returning null, which leaves such a getter
     * unresolved instead of failing analysis. A caller must catch and swallow its own lookup
     * failures; this function treats a thrown exception the same as a null result.
     *
     * [handlerInterfaces] names, by `Class.getName()`, the functional interfaces a framework takes
     * a handler as. The analysis yields [Analysis.handlerForwarders] only for those.
     */
    fun analyze(
        classBytes: ByteArray,
        lookup: (internalName: String) -> ByteArray? = { null },
        includePackages: List<String> = emptyList(),
        excludePackages: List<String> = emptyList(),
        tableCache: CrossClassTableCache? = null,
        handlerInterfaces: Set<String> = emptySet(),
        methodFilter: (name: String, descriptor: String) -> Boolean,
    ): Analysis {
        val sites = mutableListOf<BranchSite>()
        val firstLines = mutableMapOf<Pair<String, String>, Int>()
        val inlineMethods = mutableSetOf<Pair<String, String>>()
        var nextSiteIndex = 0
        var hasLineNumbers = false
        var isKotlinClass = false

        var internalClassName = ""
        var sourceFile: String? = null
        var hasEnclosingMethod = false
        var ownInnerClassEntry: BodyKindRule.OwnInnerClassEntry? = null
        var classAccess = 0
        var superInternalName: String? = null
        var interfaceInternalNames: List<String> = emptyList()
        var smap = KotlinSmap.EMPTY
        val methodAccess = mutableMapOf<Pair<String, String>, Int>()
        val methodsWithLineNumbers = mutableSetOf<Pair<String, String>>()
        val localNames = mutableMapOf<Pair<String, String>, MutableMap<Int, String>>()
        val defaultCandidates = mutableListOf<DefaultCandidate>()
        val defaultShapedNames = mutableListOf<Pair<String, String>>()
        val rawCandidatesByMethod = mutableMapOf<Pair<String, String>, MutableList<RawCandidate>>()
        val eligibleMethodKeys = mutableSetOf<Pair<String, String>>()
        val droppedOrdinalsByMethod = mutableMapOf<Pair<String, String>, MutableSet<Int>>()
        val rawReferencesByMethod = mutableMapOf<Pair<String, String>, MutableSet<String>>()
        val rawClassReferences = LinkedHashSet<String>()
        val classReferenceCollector = ReferenceCollector(rawClassReferences)

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
                    classReferenceCollector.internalName(superName)
                    interfaces?.forEach(classReferenceCollector::internalName)
                    classReferenceCollector.signature(signature)
                }

                // Called once, after visit() and before any visitMethod(), so every method
                // visitor below sees the class's fully parsed SMAP. See ADR 0025.
                override fun visitSource(
                    source: String?,
                    debug: String?,
                ) {
                    sourceFile = source
                    smap = KotlinSmapParser.parse(debug)
                }

                override fun visitOuterClass(
                    owner: String,
                    name: String?,
                    descriptor: String?,
                ) {
                    hasEnclosingMethod = true
                }

                override fun visitInnerClass(
                    name: String,
                    outerName: String?,
                    innerName: String?,
                    access: Int,
                ) {
                    if (name == internalClassName) ownInnerClassEntry = BodyKindRule.OwnInnerClassEntry(innerName)
                }

                // Delivered after visitSource() and before any visitMethod(), so this flag is
                // settled before any method visitor below could need it.
                override fun visitAnnotation(
                    descriptor: String,
                    visible: Boolean,
                ): AnnotationVisitor? {
                    if (kotlinMetadataDescriptorShape.matches(descriptor)) isKotlinClass = true
                    return classReferenceCollector.annotation(descriptor, visible)
                }

                override fun visitTypeAnnotation(
                    typeRef: Int,
                    typePath: TypePath?,
                    descriptor: String,
                    visible: Boolean,
                ): AnnotationVisitor? = classReferenceCollector.annotation(descriptor, visible)

                override fun visitField(
                    access: Int,
                    name: String,
                    descriptor: String,
                    signature: String?,
                    value: Any?,
                ): FieldVisitor = classLevelMemberReferences(classReferenceCollector, descriptor, signature)

                override fun visitRecordComponent(
                    name: String,
                    descriptor: String,
                    signature: String?,
                ): RecordComponentVisitor = recordComponentReferences(classReferenceCollector, descriptor, signature)

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
                    val referencesForMethod = rawReferencesByMethod.getOrPut(name to descriptor) { LinkedHashSet() }
                    recordSignatureReferences(referencesForMethod, descriptor, signature, exceptions)

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
                        return object : CallCandidateMethodVisitor(internalClassName, candidatesForMethod, referencesForMethod) {
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
                                hasLineNumbers = true
                                methodsWithLineNumbers += name to descriptor
                                if (isTypeInitializer) firstLines.putIfAbsent(name to descriptor, line)
                            }
                        }
                    }

                    return DefaultSiteAwareMethodVisitor(
                        name = name,
                        descriptor = descriptor,
                        isStatic = access and Opcodes.ACC_STATIC != 0,
                        eligible = eligible,
                        defaultShaped = defaultShaped,
                        ownerInternalName = internalClassName,
                        ownerSuperInternalName = superInternalName,
                        localNamesForMethod = localNamesForMethod,
                        sites = sites,
                        firstLines = firstLines,
                        inlineMethods = inlineMethods,
                        onSiteIndexUsed = { nextSiteIndex++ },
                        nextSiteIndex = { nextSiteIndex },
                        onDefaultCandidate = { defaultCandidates += it },
                        candidatesForMethod = candidatesForMethod,
                        referencesForMethod = referencesForMethod,
                        smap = { smap },
                        includePackages = includePackages,
                        excludePackages = excludePackages,
                        onSiteDropped = { ordinal -> droppedOrdinalsByMethod.getOrPut(name to descriptor) { mutableSetOf() } += ordinal },
                        onLineNumberSeen = {
                            hasLineNumbers = true
                            methodsWithLineNumbers += name to descriptor
                        },
                    )
                }
            }

        ClassReader(classBytes).accept(classVisitor, ClassReader.SKIP_FRAMES)

        attachConditionFingerprints(sites, classBytes)

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
        val generatedByMethod = computeGeneratedBy(classBytes, internalClassName, superInternalName, methodAccess, methodsWithLineNumbers)

        val callEdgeEntryPoints = if (hasTypeInitializer) eligibleMethodKeys + ("<clinit>" to "()V") else eligibleMethodKeys
        val resolvedCalls =
            resolveCallEdges(
                internalClassName = internalClassName,
                methodAccess = methodAccess,
                rawCandidatesByMethod = rawCandidatesByMethod,
                eligibleMethodKeys = callEdgeEntryPoints,
                lookup = lookup,
                includePackages = includePackages,
                excludePackages = excludePackages,
                tableCache = tableCache,
                rawReferencesByMethod = rawReferencesByMethod,
                handlerInterfaces = handlerInterfaces,
            )
        val references =
            placeReferences(
                internalClassName = internalClassName,
                methodAccess = methodAccess,
                entryPoints = callEdgeEntryPoints,
                resolvedCalls = resolvedCalls,
                rawReferencesByMethod = rawReferencesByMethod,
                rawClassReferences = rawClassReferences,
                rekindedGetters = resolvedGetters,
                includePackages = includePackages,
                excludePackages = excludePackages,
            )

        val lambdaBodies = findLambdaBodies(internalClassName, methodAccess, rawCandidatesByMethod, eligibleMethodKeys)
        val bodyClass = BodyKindRule.classify(hasEnclosingMethod, superInternalName, ownInnerClassEntry, isKotlinClass)

        return Analysis(
            sites,
            firstLines,
            inlineMethods,
            defaultSites,
            unresolvedDefaultSites,
            scalaGetterSites,
            unresolvedScalaGetterSites,
            hasTypeInitializer,
            resolvedCalls.edgesByMethod,
            superInternalName?.replace('/', '.'),
            interfaceInternalNames.map { it.replace('/', '.') },
            droppedOrdinalsByMethod,
            generatedByMethod,
            hasLineNumbers,
            isKotlinClass,
            references.byMethod,
            references.onClass,
            lambdaBodies,
            sourceFile,
            bodyClass.kind,
            bodyClass.sourceName,
            resolvedCalls.handlerForwarders,
            internalClassName.replace('/', '.'),
        )
    }

    /**
     * The probed methods of this class that are lambda bodies, per ADR 0034: an `invokedynamic` in
     * this class names the method as the `LambdaMetafactory` implementation, [eligibleMethodKeys]
     * holds it, and its name passes [TypeMatchPolicy.isLambdaBodyName].
     *
     * When the implementation is a same-class pass-through (declared with a body, not probed), the
     * same-class methods it calls are tested in its place, transitively. Scala 2 and Scala 3 both name
     * an `$adapted` boxing forwarder as the implementation whenever the body takes or returns a
     * primitive, and the forwarder calls the real `$anonfun$` body. Without this step no such
     * body would be flagged.
     */
    private fun findLambdaBodies(
        internalClassName: String,
        methodAccess: Map<Pair<String, String>, Int>,
        rawCandidatesByMethod: Map<Pair<String, String>, List<RawCandidate>>,
        eligibleMethodKeys: Set<Pair<String, String>>,
    ): Set<Pair<String, String>> {
        val pending =
            ArrayDeque(
                rawCandidatesByMethod.values
                    .flatten()
                    .filter { it.kind == CallEdgeKind.CREATES && it.owner == internalClassName }
                    .map { it.name to it.descriptor },
            )
        val named = mutableSetOf<Pair<String, String>>()
        while (pending.isNotEmpty()) {
            val key = pending.removeFirst()
            if (!named.add(key)) continue
            val access = methodAccess[key] ?: continue
            val isPassThrough = key !in eligibleMethodKeys && access and BODYLESS_FLAGS == 0
            if (!isPassThrough) continue
            for (candidate in rawCandidatesByMethod[key].orEmpty()) {
                if (candidate.owner == internalClassName) pending += candidate.name to candidate.descriptor
            }
        }
        return named.filterTo(mutableSetOf()) { it in eligibleMethodKeys && TypeMatchPolicy.isLambdaBodyName(it.first) }
    }

    /**
     * Attaches each site's condition fingerprint and, for a switch, its case keys, from a second,
     * independent [ConditionFingerprinter] pass over the same bytes. Fingerprint `i` of a method
     * goes to that method's `i`-th entry in [sites], in encounter order, dropped sites counted:
     * [ConditionFingerprinter] visits every method and every tracked site regardless of scope, the
     * same way [DefaultSiteAwareMethodVisitor.recordSite] numbers a method's sites regardless of
     * whether they get dropped. A method whose fingerprint count does not match its site count is
     * left with no fingerprints at all, and a class this pass cannot read leaves every site as it
     * was. This never throws: a fingerprinting failure costs fingerprints, not the analysis.
     */
    private fun attachConditionFingerprints(
        sites: MutableList<BranchSite>,
        classBytes: ByteArray,
    ) {
        val fingerprintsByMethod =
            try {
                ConditionFingerprinter.analyze(classBytes)
            } catch (_: Exception) {
                return
            }
        val siteIndicesByMethod = mutableMapOf<Pair<String, String>, MutableList<Int>>()
        sites.forEachIndexed { index, site ->
            siteIndicesByMethod.getOrPut(site.methodName to site.methodDescriptor) { mutableListOf() } += index
        }
        for ((methodKey, siteIndices) in siteIndicesByMethod) {
            val result = fingerprintsByMethod[methodKey] ?: continue
            if (result.fingerprints.size != siteIndices.size) continue
            siteIndices.forEachIndexed { ordinal, siteListIndex ->
                sites[siteListIndex] =
                    sites[siteListIndex].copy(
                        conditionFingerprint = result.fingerprints[ordinal],
                        caseKeys = result.caseKeys[ordinal],
                    )
            }
        }
    }

    /** Where each of a class's references ends up: on a probed method, or on the class. */
    private class PlacedReferences(
        val byMethod: Map<Pair<String, String>, List<String>>,
        val onClass: List<String>,
    )

    /**
     * Places every reference the class's bytecode holds, per ADR 0030: each entry point (a method
     * with a METHOD probe, and `<clinit>` when there is one) keeps its own references plus those of
     * every pass-through it reaches, as [resolveCallEdges] gathered them, and everything else goes
     * to the class. That is the class header, fields and record components, a method with no body
     * (abstract, native), a resolved Scala default getter (whose probe is re-kinded onto its target,
     * ADR 0023, so it carries no METHOD probe to hold them), and a pass-through no entry point in
     * this class reaches, such as an `access$` accessor only a nested class calls. Nothing is
     * dropped, so the class is the fallback for a reference no probed method can hold.
     *
     * Each list is deduplicated, first seen first, dotted, and keeps only names outside the include
     * rules, other than the class itself.
     */
    private fun placeReferences(
        internalClassName: String,
        methodAccess: Map<Pair<String, String>, Int>,
        entryPoints: Set<Pair<String, String>>,
        resolvedCalls: ResolvedCalls,
        rawReferencesByMethod: Map<Pair<String, String>, Set<String>>,
        rawClassReferences: Set<String>,
        rekindedGetters: Set<Pair<String, String>>,
        includePackages: List<String>,
        excludePackages: List<String>,
    ): PlacedReferences {
        fun outOfScope(rawNames: Collection<String>): List<String> =
            rawNames
                .asSequence()
                .filter { it != internalClassName }
                .map { it.replace('/', '.') }
                .filterNot { TypeMatchPolicy.isIncluded(it, includePackages, excludePackages) }
                .distinct()
                .toList()

        // A method with no body never gets a METHOD probe, whatever the method filter said, so its
        // references go to the class rather than to a probe that does not exist.
        fun isProbed(key: Pair<String, String>): Boolean =
            key in entryPoints && key !in rekindedGetters && (methodAccess[key] ?: 0) and BODYLESS_FLAGS == 0

        val onClass = LinkedHashSet(rawClassReferences)
        // A method without a probe hands its references to the class unless some entry point
        // reached it as a pass-through and took them. A method with no body is never reached that
        // way, since a call to one stays an edge. An entry point without a probe (a re-kinded
        // Scala getter) hands over everything it gathered, pass-throughs it reached included.
        for (key in methodAccess.keys) {
            if (!isProbed(key) && key !in resolvedCalls.reachedPassThroughs) onClass += rawReferencesByMethod[key].orEmpty()
        }
        for ((key, gathered) in resolvedCalls.referencesByMethod) {
            if (!isProbed(key)) onClass += gathered
        }
        val byMethod =
            resolvedCalls.referencesByMethod
                .filterKeys(::isProbed)
                .mapValues { (_, rawNames) -> outOfScope(rawNames) }
        return PlacedReferences(byMethod, outOfScope(onClass))
    }

    /** Records the types a field or method signature names, from its descriptor, generic signature and throws clause. */
    private fun recordSignatureReferences(
        references: MutableSet<String>,
        descriptor: String,
        signature: String?,
        exceptions: Array<out String>?,
    ) {
        val collector = ReferenceCollector(references)
        collector.descriptor(descriptor)
        collector.signature(signature)
        exceptions?.forEach(collector::internalName)
    }

    /** A field's descriptor and signature, and a visitor for its runtime-visible annotations, all recorded as class references. */
    private fun classLevelMemberReferences(
        collector: ReferenceCollector,
        descriptor: String,
        signature: String?,
    ): FieldVisitor {
        collector.descriptor(descriptor)
        collector.signature(signature)
        return object : FieldVisitor(Opcodes.ASM9) {
            override fun visitAnnotation(
                descriptor: String,
                visible: Boolean,
            ): AnnotationVisitor? = collector.annotation(descriptor, visible)

            override fun visitTypeAnnotation(
                typeRef: Int,
                typePath: TypePath?,
                descriptor: String,
                visible: Boolean,
            ): AnnotationVisitor? = collector.annotation(descriptor, visible)
        }
    }

    /** A record component's descriptor, signature and runtime-visible annotations, recorded as class references. */
    private fun recordComponentReferences(
        collector: ReferenceCollector,
        descriptor: String,
        signature: String?,
    ): RecordComponentVisitor {
        collector.descriptor(descriptor)
        collector.signature(signature)
        return object : RecordComponentVisitor(Opcodes.ASM9) {
            override fun visitAnnotation(
                descriptor: String,
                visible: Boolean,
            ): AnnotationVisitor? = collector.annotation(descriptor, visible)

            override fun visitTypeAnnotation(
                typeRef: Int,
                typePath: TypePath?,
                descriptor: String,
                visible: Boolean,
            ): AnnotationVisitor? = collector.annotation(descriptor, visible)
        }
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
     * widens to the implementers.
     *
     * A cross-class candidate shaped like a Kotlin `$default` method is resolved against the
     * target class's own bytecode fetched through [lookup], the same mechanism
     * [resolveScalaGetterSites] already uses for a Scala constructor getter's cross-class target;
     * a `$default` whose target the descriptor cannot name falls through to the general rule
     * below, since its body invokes the target anyway. Any cross-class candidate whose owner
     * declares it with a body the method tier would not probe
     * (a bridge, an `access$` accessor, or any other synthetic method that is not a probed lambda
     * body, see [wouldNotBeProbedByMethodTier]) is a pass-through the same way: its own raw
     * candidates, read from the owner's bytes via [readMethodTable], are substituted transitively.
     * Invoking a cross-class pass-through is itself a use of its owner, so it also adds an edge to
     * that owner's `<clinit>`, the same as a static field read or write on that owner (see
     * [CallCandidateMethodVisitor]); this edge is never gated on the owner actually declaring a
     * `<clinit>`, since the collector already drops an edge with no matching node. A cross-class
     * candidate that owner's bytes cannot resolve, or that the owner does not declare at all (an
     * inherited method), stays a verbatim edge with its original virtual flag; one the owner
     * declares and the method tier would probe keeps its name and descriptor but has its virtual
     * flag corrected the same way a same-class target's is.
     *
     * A candidate named `<init>` or `<clinit>` whose owner's [MethodTable.hasEnclosingMethod] is
     * true names a body class: a suspend lambda, an object expression, or an anonymous or local
     * class. A function or property reference is a body class too, but a synthetic one, handled
     * below. In addition to the edge already added for that candidate, an edge
     * is added from the entry point to every method the body class declares with a body, other than
     * `<init>` and `<clinit>`, that the method tier would probe, with the same non-virtual
     * correction a same-class target gets. The creator is the only method that can ever reach a
     * body class's methods, so without this edge every one of them would look uncalled the moment
     * its only caller is out of scope, which is the common case: a framework invokes an object
     * expression's `run`, or a coroutine library resumes a suspend lambda. A
     * named, non-local class carries no `EnclosingMethod` attribute, so `new` on one is never
     * expanded this way.
     *
     * A body class the agent never probes ([MethodTable.isUnprobedBodyClass]) is a pass-through as
     * a whole, since an edge into it would name a method with no probe. kotlinc makes such classes
     * for every function and property reference and each `$sam$` wrapper, which are synthetic, and
     * for every suspend function's own continuation. A candidate for any of its methods adds no
     * edge to the class itself. Its own raw candidates are substituted in its place instead. When the candidate is
     * its `<init>` or `<clinit>`, the raw candidates of every other method it declares with a body
     * are substituted too, as `CREATES` edges, the way the body-class edges above are. So
     * `val f = ::twice` gives its creator a `CREATES` edge to `twice`, the same edge a Java
     * `this::twice` gives. A continuation's `invokeSuspend` calls back into the suspend function that
     * created it, which is a self-edge and is dropped. See ADR 0034.
     *
     * Self-edges (the entry-point method calling itself, directly or through a pass-through
     * chain) are dropped, and so is a candidate for this class's own `<clinit>`, which a
     * substituted forwarder on another class can carry back in when it reads a static field of
     * the class being analysed: a method of this class that runs has already initialised it. Edges are deduplicated per entry point by (owner, name, descriptor,
     * virtual).
     *
     * Every edge has a kind (ADR 0034). A candidate starts with its own: [CallEdgeKind.CREATES]
     * for a `LambdaMetafactory` `invokedynamic`, [CallEdgeKind.CALL] for everything else. The
     * edges that take a pass-through's place keep the kind of the candidate that reached it, and
     * a `CREATES` candidate found inside the pass-through stays `CREATES`. So once a walk passes a
     * `CREATES` step, every edge below it is `CREATES`: such an edge only runs once the created
     * body runs, never when the creator runs. The captured count travels with the kind, capped at
     * the substituted target's own parameter count. The only pass-through a compiler names as an
     * implementation is scalac's boxing forwarder, which passes its parameters on in order. The body-class edges are `CREATES` edges with
     * nothing captured, while the constructor or initializer edge that leads to them keeps the
     * kind it arrived with. Edges are deduplicated per entry point by every field, kind and
     * captured count included, so a method that both calls and creates the same target keeps
     * both edges.
     *
     * References (ADR 0030) ride the same walk, unfiltered: an entry point starts with its own, and
     * every pass-through substituted into it, same-class or cross-class, adds its own, so a
     * reference is attributed exactly where the pass-through's callees are. A cross-class `$default`
     * resolved to its target also adds its own references, since its body evaluates the default
     * expressions on the caller's behalf. A body-class join adds none: the body class's methods hold
     * their own references in their own class's analysis. A body class the agent never probes has no
     * analysis of its own, so each of its methods the walk passes through adds its references like any other
     * pass-through.
     *
     * With [handlerInterfaces] given, the same walk also finds the forwarder table's entries. See
     * [findHandlerForwarders] and ADR 0035.
     */
    private fun resolveCallEdges(
        internalClassName: String,
        methodAccess: Map<Pair<String, String>, Int>,
        rawCandidatesByMethod: Map<Pair<String, String>, List<RawCandidate>>,
        eligibleMethodKeys: Set<Pair<String, String>>,
        lookup: (String) -> ByteArray?,
        includePackages: List<String>,
        excludePackages: List<String>,
        tableCache: CrossClassTableCache? = null,
        rawReferencesByMethod: Map<Pair<String, String>, Set<String>> = emptyMap(),
        handlerInterfaces: Set<String> = emptySet(),
    ): ResolvedCalls {
        val crossClassMethodTables = mutableMapOf<String, MethodTable?>()

        fun readTable(ownerInternalName: String): MethodTable? {
            val bytes =
                try {
                    lookup(ownerInternalName)
                } catch (_: Exception) {
                    null
                } ?: return null
            return try {
                readMethodTable(bytes)
            } catch (_: Exception) {
                null
            }
        }

        // Memoised by containsKey rather than getOrPut: an unreadable owner's table is null, and
        // getOrPut treats a null value as absent, which would read the owner again on every
        // reference to it.
        fun methodTableFor(ownerInternalName: String): MethodTable? {
            if (ownerInternalName in crossClassMethodTables) return crossClassMethodTables[ownerInternalName]
            val table =
                if (tableCache != null) {
                    tableCache.getOrRead(ownerInternalName) { readTable(ownerInternalName) }
                } else {
                    readTable(ownerInternalName)
                }
            crossClassMethodTables[ownerInternalName] = table
            return table
        }

        val dottedClassName = internalClassName.replace('/', '.')

        val reachedPassThroughs = mutableSetOf<Pair<String, String>>()
        val referencesByMethod = mutableMapOf<Pair<String, String>, Set<String>>()
        val reachedUnprobedBodyClasses = LinkedHashSet<String>()

        // Walks candidates by the rules above. The references and same-class pass-throughs the
        // walk passes through go into the two sets it is given.
        fun walk(
            candidates: List<RawCandidate>,
            references: MutableSet<String>,
            passThroughs: MutableSet<Pair<String, String>>,
        ): Set<CallEdge> {
            val edges = LinkedHashSet<CallEdge>()
            val visited = mutableSetOf<VisitKey>()

            fun edge(
                owner: String,
                name: String,
                descriptor: String,
                virtual: Boolean,
                kind: CallEdgeKind,
                capturedCount: Int,
            ): CallEdge {
                val captured = if (capturedCount == 0) 0 else capturedCount.coerceAtMost(parseParameterDescriptors(descriptor).size)
                return CallEdge(owner, name, descriptor, virtual, kind, captured)
            }

            fun visit(
                owner: String,
                name: String,
                descriptor: String,
                virtualRaw: Boolean,
                kind: CallEdgeKind,
                capturedCount: Int,
            ) {
                if (!visited.add(VisitKey(owner, name, descriptor, kind, capturedCount))) return

                // A candidate inside a pass-through keeps its own kind when it creates something,
                // and otherwise takes the kind the pass-through was reached with.
                fun visitInside(candidate: RawCandidate) {
                    if (candidate.kind == CallEdgeKind.CREATES) {
                        visit(
                            candidate.owner,
                            candidate.name,
                            candidate.descriptor,
                            candidate.virtualRaw,
                            CallEdgeKind.CREATES,
                            candidate.capturedCount,
                        )
                    } else {
                        visit(candidate.owner, candidate.name, candidate.descriptor, candidate.virtualRaw, kind, capturedCount)
                    }
                }

                if (owner == internalClassName) {
                    if (name == "<clinit>") return
                    val access = methodAccess[name to descriptor]
                    val nonVirtual = access != null && access and NON_VIRTUAL_FLAGS != 0
                    val virtual = virtualRaw && !nonVirtual
                    val declaredWithBody = access != null && access and BODYLESS_FLAGS == 0
                    if ((name to descriptor) in eligibleMethodKeys || !declaredWithBody) {
                        edges += edge(dottedClassName, name, descriptor, virtual, kind, capturedCount)
                    } else {
                        passThroughs += name to descriptor
                        references += rawReferencesByMethod[name to descriptor].orEmpty()
                        rawCandidatesByMethod[name to descriptor].orEmpty().forEach(::visitInside)
                    }
                    return
                }

                val dottedOwner = owner.replace('/', '.')
                if (!TypeMatchPolicy.isIncluded(dottedOwner, includePackages, excludePackages)) return

                if (isDefaultShaped(name, descriptor)) {
                    val target = methodTableFor(owner)?.let { resolveCrossClassDefaultTarget(owner, name, descriptor, it) }
                    if (target != null) {
                        edges += edge(dottedOwner, target.name, target.descriptor, target.virtual, kind, capturedCount)
                        references += methodTableFor(owner)?.rawReferencesByMethod?.get(name to descriptor).orEmpty()
                        return
                    }
                }

                val table = methodTableFor(owner)
                val access = table?.methodAccess?.get(name to descriptor)
                if (table == null || access == null) {
                    edges += edge(dottedOwner, name, descriptor, virtualRaw, kind, capturedCount)
                    return
                }

                val declaredWithBody = access and BODYLESS_FLAGS == 0
                val isConstructorOrInitializer = name == "<init>" || name == "<clinit>"
                if (table.isUnprobedBodyClass) {
                    references += table.rawReferencesByMethod[name to descriptor].orEmpty()
                    table.rawCandidatesByMethod[name to descriptor].orEmpty().forEach(::visitInside)
                    if (isConstructorOrInitializer) {
                        reachedUnprobedBodyClasses += owner
                        for ((bodyKey, bodyAccess) in table.methodAccess) {
                            val (bodyName, bodyDescriptor) = bodyKey
                            if (bodyName == "<init>" || bodyName == "<clinit>" || bodyAccess and BODYLESS_FLAGS != 0) continue
                            visit(owner, bodyName, bodyDescriptor, bodyAccess and NON_VIRTUAL_FLAGS == 0, CallEdgeKind.CREATES, 0)
                        }
                    }
                    return
                }
                if (declaredWithBody && !isConstructorOrInitializer && wouldNotBeProbedByMethodTier(access, name, table.isScalaClass)) {
                    references += table.rawReferencesByMethod[name to descriptor].orEmpty()
                    table.rawCandidatesByMethod[name to descriptor].orEmpty().forEach(::visitInside)
                    visit(owner, "<clinit>", "()V", false, kind, 0)
                    return
                }

                val nonVirtual = access and NON_VIRTUAL_FLAGS != 0
                edges += edge(dottedOwner, name, descriptor, virtualRaw && !nonVirtual, kind, capturedCount)

                if (isConstructorOrInitializer && table.hasEnclosingMethod) {
                    for ((bodyKey, bodyAccess) in table.methodAccess) {
                        val (bodyName, bodyDescriptor) = bodyKey
                        if (bodyName == "<init>" || bodyName == "<clinit>") continue
                        if (bodyAccess and BODYLESS_FLAGS != 0) continue
                        if (wouldNotBeProbedByMethodTier(bodyAccess, bodyName, table.isScalaClass)) continue
                        val bodyNonVirtual = bodyAccess and NON_VIRTUAL_FLAGS != 0
                        edges += CallEdge(dottedOwner, bodyName, bodyDescriptor, !bodyNonVirtual, CallEdgeKind.CREATES)
                    }
                }
            }

            for (candidate in candidates) {
                visit(candidate.owner, candidate.name, candidate.descriptor, candidate.virtualRaw, candidate.kind, candidate.capturedCount)
            }
            return edges
        }

        fun resolveOne(methodKey: Pair<String, String>): List<CallEdge> {
            val references = LinkedHashSet<String>(rawReferencesByMethod[methodKey].orEmpty())
            referencesByMethod[methodKey] = references
            val edges = walk(rawCandidatesByMethod[methodKey].orEmpty(), references, reachedPassThroughs)
            val (selfName, selfDescriptor) = methodKey
            return edges.filterNot { it.className == dottedClassName && it.methodName == selfName && it.methodDescriptor == selfDescriptor }
        }

        val edgesByMethod = eligibleMethodKeys.associateWith(::resolveOne)
        val handlerForwarders =
            if (handlerInterfaces.isEmpty()) {
                emptyList()
            } else {
                findHandlerForwarders(
                    internalClassName,
                    methodAccess,
                    rawCandidatesByMethod,
                    eligibleMethodKeys,
                    reachedUnprobedBodyClasses,
                    handlerInterfaces,
                    ::methodTableFor,
                ) { candidates -> walk(candidates, LinkedHashSet(), mutableSetOf()) }
            }
        return ResolvedCalls(edgesByMethod, referencesByMethod, reachedPassThroughs, handlerForwarders)
    }

    /**
     * The forwarder table's entries for one class (ADR 0035). Two kinds of pass-through qualify:
     * - a method of this class that an `invokedynamic` here names as the implementation of a
     *   lambda for one of [handlerInterfaces], when it is a pass-through. scalac names its
     *   `$adapted` boxing forwarder this way.
     * - a method of a body class the agent does not probe, which [reachedUnprobedBodyClasses]
     *   holds, when that class implements one of [handlerInterfaces]. kotlinc makes such a class
     *   for a reference passed as a Java functional interface under class-based SAM conversion.
     *   The type matcher never analyses it, so its entry is written here, where its creator is
     *   analysed.
     *
     * [walk] resolves a pass-through's own candidates by the same rules [resolveCallEdges] applies
     * to an entry point. An entry is written only when the `CALL` edges it yields name exactly one
     * method, not counting the pass-through itself or a `<clinit>`. A `<clinit>` edge stands for the
     * class being initialised, not for a call. No entry is written when that one method has no body
     * (abstract or native), as far as its owner's bytes show. Then the reported name stands.
     */
    private fun findHandlerForwarders(
        internalClassName: String,
        methodAccess: Map<Pair<String, String>, Int>,
        rawCandidatesByMethod: Map<Pair<String, String>, List<RawCandidate>>,
        eligibleMethodKeys: Set<Pair<String, String>>,
        reachedUnprobedBodyClasses: Set<String>,
        handlerInterfaces: Set<String>,
        methodTableFor: (String) -> MethodTable?,
        walk: (List<RawCandidate>) -> Set<CallEdge>,
    ): List<HandlerForwarder> {
        val dottedClassName = internalClassName.replace('/', '.')

        fun isHandlerInterface(internalName: String?): Boolean = internalName != null && internalName.replace('/', '.') in handlerInterfaces

        fun hasNoBody(edge: CallEdge): Boolean {
            val key = edge.methodName to edge.methodDescriptor
            val access =
                if (edge.className == dottedClassName) {
                    methodAccess[key]
                } else {
                    methodTableFor(edge.className.replace('.', '/'))?.methodAccess?.get(key)
                }
            return access != null && access and BODYLESS_FLAGS != 0
        }

        fun forwarder(
            ownerInternalName: String,
            key: Pair<String, String>,
            candidates: List<RawCandidate>,
        ): HandlerForwarder? {
            val owner = ownerInternalName.replace('/', '.')
            val (name, descriptor) = key
            val targets =
                walk(candidates)
                    .asSequence()
                    .filter { it.kind == CallEdgeKind.CALL && it.methodName != "<clinit>" }
                    .filterNot { it.className == owner && it.methodName == name && it.methodDescriptor == descriptor }
                    .distinctBy { Triple(it.className, it.methodName, it.methodDescriptor) }
                    .toList()
            val target = targets.singleOrNull() ?: return null
            if (hasNoBody(target)) return null
            return HandlerForwarder(owner, name, descriptor, target.className, target.methodName, target.methodDescriptor)
        }

        val forwarders = mutableListOf<HandlerForwarder>()
        val implementations =
            rawCandidatesByMethod.values
                .asSequence()
                .flatten()
                .filter { it.kind == CallEdgeKind.CREATES && it.owner == internalClassName && isHandlerInterface(it.functionalInterface) }
                .map { it.name to it.descriptor }
                .distinct()
        for (key in implementations) {
            val access = methodAccess[key] ?: continue
            if (key in eligibleMethodKeys || access and BODYLESS_FLAGS != 0) continue
            forwarder(internalClassName, key, rawCandidatesByMethod[key].orEmpty())?.let { forwarders += it }
        }
        // A snapshot: the walk below can reach further body classes and add them to this set.
        for (bodyClass in reachedUnprobedBodyClasses.toList()) {
            val table = methodTableFor(bodyClass) ?: continue
            if (table.interfaceInternalNames.none(::isHandlerInterface)) continue
            for ((key, access) in table.methodAccess) {
                if (key.first == "<init>" || key.first == "<clinit>" || access and BODYLESS_FLAGS != 0) continue
                forwarder(bodyClass, key, table.rawCandidatesByMethod[key].orEmpty())?.let { forwarders += it }
            }
        }
        return forwarders
    }

    /** One step of [resolveCallEdges]'s walk: a callee, with the kind and captured count it was reached with. */
    private data class VisitKey(
        val owner: String,
        val name: String,
        val descriptor: String,
        val kind: CallEdgeKind,
        val capturedCount: Int,
    )

    /**
     * What [resolveCallEdges] yields: each entry point's edges and its references (raw internal
     * names, the entry point's own and every pass-through's it reaches), the same-class
     * pass-throughs some entry point reached, and the forwarder table's entries.
     */
    private class ResolvedCalls(
        val edgesByMethod: Map<Pair<String, String>, List<CallEdge>>,
        val referencesByMethod: Map<Pair<String, String>, Set<String>>,
        val reachedPassThroughs: Set<Pair<String, String>>,
        val handlerForwarders: List<HandlerForwarder>,
    )

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

    /** A target with any of these flags can never be overridden, so a call to it is never virtual. See ADR 0024. */
    private const val NON_VIRTUAL_FLAGS = Opcodes.ACC_PRIVATE or Opcodes.ACC_STATIC or Opcodes.ACC_FINAL

    /** A target with either flag has no body to pass through, so a call to it stays an edge. */
    private const val BODYLESS_FLAGS = Opcodes.ACC_ABSTRACT or Opcodes.ACC_NATIVE

    /**
     * Whether the method tier would not probe a declared method with these [access] flags and
     * [name], owned by a Scala class when [isScalaClass] is true: a bridge, always, or a synthetic
     * method that is not a lambda body the method tier does probe. Mirrors
     * [TypeMatchPolicy.methodMatcher]'s own synthetic handling exactly, so a method resolved as a
     * cross-class pass-through here is never one the method tier also probes in its own right.
     * See ADR 0024.
     */
    private fun wouldNotBeProbedByMethodTier(
        access: Int,
        name: String,
        isScalaClass: Boolean,
    ): Boolean {
        if (access and Opcodes.ACC_BRIDGE != 0) return true
        if (access and Opcodes.ACC_SYNTHETIC != 0) return !TypeMatchPolicy.isProbedLambdaBody(name, isScalaClass)
        return false
    }

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
     * The last few real instructions [DefaultSiteAwareMethodVisitor] has walked, enough to
     * recognise one of [CoroutineShapes]'s four patterns at the moment a tracked jump or switch is
     * reached. Every instruction is pushed, jumps and switches included; `Label`, line-number and
     * frame events are not, since they are not instructions, so a pattern keyed on "immediately
     * preceding" is unaffected by debug info and stack-map frames.
     */
    private sealed interface RecentInsn {
        /** No instruction has been seen yet, or the last one was not worth remembering. */
        data object None : RecentInsn

        /** `GETFIELD owner.name:descriptor`. */
        data class GetField(
            val owner: String,
            val name: String,
            val descriptor: String,
        ) : RecentInsn

        /** `ALOAD varIndex`. */
        data class ALoad(
            val varIndex: Int,
        ) : RecentInsn

        /** `INSTANCEOF type`. */
        data class InstanceOf(
            val type: String,
        ) : RecentInsn

        /** `LDC value`. */
        data class Ldc(
            val value: Any?,
        ) : RecentInsn

        /** `IAND`. */
        data object Iand : RecentInsn

        /** Any other instruction, kept only to break a pattern that needed something else here. */
        data object Other : RecentInsn
    }

    /**
     * Recognises the four bytecode shapes kotlinc's coroutine state machine leaves in a
     * suspend-shaped method, confirmed with `javap` against Kotlin 2.2.21 output. See ADR 0025.
     *
     * Every match is keyed on the instructions immediately preceding a tracked jump or switch, so
     * a coverage agent registered ahead of this one (JaCoCo) is tolerated the same way the
     * omission tier already tolerates it: JaCoCo inverts a conditional jump around an inserted
     * probe and leaves the instructions before it untouched, so `IFEQ` and `IFNE` (and `IF_ACMPEQ`
     * and `IF_ACMPNE`) are both accepted.
     */
    private object CoroutineShapes {
        /**
         * A method is suspend-shaped when its descriptor's last parameter is a `Continuation`, or
         * when it is `invokeSuspend(Object)Object` on a class whose direct superclass is a suspend
         * lambda's. Matched by suffix: `shadowJar` rewrites a literal starting with `kotlin/` or
         * `kotlin.` in this agent's own code.
         */
        fun isSuspendShaped(
            name: String,
            descriptor: String,
            ownerSuperInternalName: String?,
        ): Boolean {
            val lastParameter = parseParameterDescriptors(descriptor).lastOrNull()
            if (lastParameter != null && lastParameter.endsWith("coroutines/Continuation;")) return true
            if (name != "invokeSuspend" || descriptor != "(Ljava/lang/Object;)Ljava/lang/Object;") return false
            return ownerSuperInternalName != null &&
                (ownerSuperInternalName.endsWith("/SuspendLambda") || ownerSuperInternalName.endsWith("/RestrictedSuspendLambda"))
        }

        /** Shape (i): a `TABLESWITCH` whose immediately preceding real instruction reads the continuation's `label` field. */
        fun isLabelSwitch(mostRecent: RecentInsn): Boolean =
            mostRecent is RecentInsn.GetField && mostRecent.name == "label" && mostRecent.descriptor == "I"

        /**
         * Shape (ii): `IF_ACMPEQ`/`IF_ACMPNE` where one of the two immediately preceding real
         * instructions is `ALOAD` of a slot this method stored right after calling
         * `IntrinsicsKt.getCOROUTINE_SUSPENDED()`. kotlinc never spends a second local on the
         * suspension point's own result: it duplicates that value with `DUP` instead of storing
         * and reloading it, so the other operand's own preceding instruction is a `DUP`, not a
         * second `ALOAD`, confirmed with `javap` against Kotlin 2.2.21 output.
         */
        fun isSuspendedCompare(
            mostRecent: RecentInsn,
            secondMostRecent: RecentInsn,
            suspendedMarkerSlots: Set<Int>,
        ): Boolean =
            isTrackedSuspendedLoad(mostRecent, suspendedMarkerSlots) || isTrackedSuspendedLoad(secondMostRecent, suspendedMarkerSlots)

        private fun isTrackedSuspendedLoad(
            insn: RecentInsn,
            suspendedMarkerSlots: Set<Int>,
        ): Boolean = insn is RecentInsn.ALoad && insn.varIndex in suspendedMarkerSlots

        /**
         * Shape (iii): `ALOAD <continuation slot>; INSTANCEOF T; IFEQ|IFNE`, where `T`'s internal
         * name starts with the owning class's own name followed by `$`, kotlinc's own nesting for
         * the continuation class it generates per suspend function.
         */
        fun isContinuationInstanceOfCheck(
            mostRecent: RecentInsn,
            secondMostRecent: RecentInsn,
            ownerInternalName: String,
            continuationSlot: Int,
        ): Boolean {
            val instanceOf = mostRecent as? RecentInsn.InstanceOf ?: return false
            val load = secondMostRecent as? RecentInsn.ALoad ?: return false
            if (load.varIndex != continuationSlot) return false
            return instanceOf.type.startsWith("$ownerInternalName\$")
        }

        /**
         * Shape (iv): `GETFIELD T.label:I; LDC Int.MIN_VALUE; IAND; IFEQ|IFNE`, the re-entry test
         * on the continuation's `label` field, with the same `T` rule as shape (iii).
         */
        fun isLabelReentryCheck(
            mostRecent: RecentInsn,
            secondMostRecent: RecentInsn,
            thirdMostRecent: RecentInsn,
            ownerInternalName: String,
        ): Boolean {
            if (mostRecent !is RecentInsn.Iand) return false
            val ldc = secondMostRecent as? RecentInsn.Ldc ?: return false
            if (ldc.value != Int.MIN_VALUE) return false
            val getField = thirdMostRecent as? RecentInsn.GetField ?: return false
            if (getField.name != "label" || getField.descriptor != "I") return false
            return getField.owner.startsWith("$ownerInternalName\$")
        }
    }

    /**
     * Visits one method's instructions. Branch/switch sites, the first line, and the inline
     * marker are only recorded when [eligible]. A `$default`-shaped method is scanned for its
     * mask-test pattern regardless of [eligible], since it is synthetic and so never eligible
     * itself.
     */
    private class DefaultSiteAwareMethodVisitor(
        private val name: String,
        private val descriptor: String,
        private val isStatic: Boolean,
        private val eligible: Boolean,
        private val defaultShaped: Boolean,
        private val ownerInternalName: String,
        /** The class's own direct superclass, dotted-to-internal form; null only for `java.lang.Object`. */
        private val ownerSuperInternalName: String?,
        private val localNamesForMethod: MutableMap<Int, String>,
        private val sites: MutableList<BranchSite>,
        private val firstLines: MutableMap<Pair<String, String>, Int>,
        private val inlineMethods: MutableSet<Pair<String, String>>,
        private val onSiteIndexUsed: () -> Unit,
        private val nextSiteIndex: () -> Int,
        private val onDefaultCandidate: (DefaultCandidate) -> Unit,
        candidatesForMethod: MutableList<RawCandidate>,
        referencesForMethod: MutableSet<String>,
        private val smap: () -> KotlinSmap,
        private val includePackages: List<String>,
        private val excludePackages: List<String>,
        /** Called with a dropped site's per-method ordinal; see [BranchSiteAnalyzer.Analysis.droppedOrdinalsOf]. */
        private val onSiteDropped: (ordinal: Int) -> Unit,
        /** Called once per `LineNumberTable` entry this method carries; see [BranchSiteAnalyzer.Analysis.hasLineNumbers]. */
        private val onLineNumberSeen: () -> Unit,
    ) : CallCandidateMethodVisitor(ownerInternalName, candidatesForMethod, referencesForMethod) {
        private var currentLine = -1
        private var lastLabel: Label? = null
        private val inlineMarkerName = "\$i\$f\$$name"
        private var nextMethodOrdinal = 0

        private val maskLocalIndex: Int
        private val secondaryMaskRange: IntRange

        private var phase = 0
        private var pendingConstant = 0
        private var optionalBits = 0
        private var higherMaskTested = false

        /**
         * Whether this method carries a coroutine state machine of its own: a trailing
         * `Continuation` parameter, or `invokeSuspend` on a class whose direct superclass is a
         * suspend lambda's. See [CoroutineShapes] and ADR 0025.
         */
        private val suspendShaped = CoroutineShapes.isSuspendShaped(name, descriptor, ownerSuperInternalName)

        /** The local-variable slot of this method's last parameter, the continuation for a suspend function. */
        private val lastParameterSlot = lastParameterLocalIndex(descriptor, isStatic)

        /** The last three real instructions visited, most recent first. See [CoroutineShapes]. */
        private var recentInsn1: RecentInsn = RecentInsn.None
        private var recentInsn2: RecentInsn = RecentInsn.None
        private var recentInsn3: RecentInsn = RecentInsn.None

        /** Local slots this method assigned with `ASTORE` to hold an `IntrinsicsKt.getCOROUTINE_SUSPENDED()` result. */
        private val suspendedMarkerSlots = mutableSetOf<Int>()

        /**
         * Set right after visiting `INVOKESTATIC IntrinsicsKt.getCOROUTINE_SUSPENDED()`, and
         * consumed by the next `ASTORE`, whichever slot that turns out to be. kotlinc's own output
         * has the `ASTORE` directly next, with nothing real in between, but a coverage agent
         * registered ahead of this one (JaCoCo) inserts its own probe-array bookkeeping
         * (`ALOAD`/`BIPUSH`/`ICONST_1`/`BASTORE`) right after the call before the `ASTORE` runs,
         * confirmed with `javap` against JaCoCo 0.8.13's offline `Instrumenter` output. None of
         * that bookkeeping is itself an `ASTORE`, so waiting for the next one rather than requiring
         * strict adjacency tolerates it the same way the rest of this analyser already tolerates
         * JaCoCo's inverted jumps.
         */
        private var pendingSuspendedMarkerCall = false

        private fun pushInsn(insn: RecentInsn) {
            recentInsn3 = recentInsn2
            recentInsn2 = recentInsn1
            recentInsn1 = insn
        }

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
            onLineNumberSeen()
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
                recordSite(coroutineMachinery = suspendShaped && isCoroutineMachineryJump(opcode))
            }
            pushInsn(RecentInsn.Other)
        }

        override fun visitTableSwitchInsn(
            min: Int,
            max: Int,
            dflt: Label,
            vararg labels: Label,
        ) {
            if (defaultShaped) resetMaskPhase()
            if (eligible) {
                recordSite(
                    switchOutcomeCount(dflt, labels),
                    isSwitch = true,
                    coroutineMachinery =
                        suspendShaped && CoroutineShapes.isLabelSwitch(recentInsn1),
                )
            }
            pushInsn(RecentInsn.Other)
        }

        override fun visitLookupSwitchInsn(
            dflt: Label,
            keys: IntArray,
            labels: Array<out Label>,
        ) {
            if (defaultShaped) resetMaskPhase()
            if (eligible) {
                recordSite(switchOutcomeCount(dflt, labels), isSwitch = true)
            }
            pushInsn(RecentInsn.Other)
        }

        /**
         * Whether an `IFEQ`/`IFNE`/`IF_ACMPEQ`/`IF_ACMPNE` about to be visited is one of
         * [CoroutineShapes]'s compare shapes (ii, iii, or iv), given the instructions
         * [recentInsn1]/[recentInsn2]/[recentInsn3] already pushed. Only called when [suspendShaped].
         */
        private fun isCoroutineMachineryJump(opcode: Int): Boolean =
            when (opcode) {
                Opcodes.IF_ACMPEQ, Opcodes.IF_ACMPNE -> {
                    CoroutineShapes.isSuspendedCompare(recentInsn1, recentInsn2, suspendedMarkerSlots)
                }

                Opcodes.IFEQ, Opcodes.IFNE -> {
                    CoroutineShapes.isContinuationInstanceOfCheck(recentInsn1, recentInsn2, ownerInternalName, lastParameterSlot) ||
                        CoroutineShapes.isLabelReentryCheck(recentInsn1, recentInsn2, recentInsn3, ownerInternalName)
                }

                else -> {
                    false
                }
            }

        /**
         * Records one tracked site at [currentLine], with [outcomeCount] outcomes. [isSwitch] is
         * true for a `TABLESWITCH` or `LOOKUPSWITCH`, and false for a conditional jump.
         *
         * [coroutineMachinery] is checked first, ahead of the SMAP lookup: a site kotlinc wove for
         * a suspend function's own state machine gets [BranchDropReason.COROUTINE_MACHINERY] and
         * never reaches the inlined-copy check below, since it carries no origin of its own to
         * resolve. Otherwise the line is resolved against the class's SMAP: a line with no origin
         * is the class's own code, an origin outside scope drops the site (see
         * [BranchDropReason.INLINED_OUT_OF_SCOPE]), and an origin inside scope keeps it labelled
         * with the origin's own line and class. Either way the site keeps its place in
         * [nextSiteIndex]'s numbering.
         */
        private fun recordSite(
            outcomeCount: Int = 2,
            isSwitch: Boolean = false,
            coroutineMachinery: Boolean = false,
        ) {
            val ordinal = nextMethodOrdinal++
            if (coroutineMachinery) {
                onSiteDropped(ordinal)
                sites +=
                    BranchSite(
                        name,
                        descriptor,
                        currentLine,
                        nextSiteIndex(),
                        outcomeCount,
                        dropReason = BranchDropReason.COROUTINE_MACHINERY,
                        isSwitch = isSwitch,
                    )
                onSiteIndexUsed()
                return
            }
            val origin = smap().originOf(currentLine)
            val site =
                when {
                    origin == null -> {
                        BranchSite(name, descriptor, currentLine, nextSiteIndex(), outcomeCount, isSwitch = isSwitch)
                    }

                    TypeMatchPolicy.isIncluded(origin.originClassName, includePackages, excludePackages) -> {
                        BranchSite(
                            name,
                            descriptor,
                            origin.inputLine,
                            nextSiteIndex(),
                            outcomeCount,
                            inlinedFromClassName = origin.originClassName,
                            isSwitch = isSwitch,
                        )
                    }

                    else -> {
                        onSiteDropped(ordinal)
                        BranchSite(
                            name,
                            descriptor,
                            currentLine,
                            nextSiteIndex(),
                            outcomeCount,
                            dropReason = BranchDropReason.INLINED_OUT_OF_SCOPE,
                            isSwitch = isSwitch,
                        )
                    }
                }
            sites += site
            onSiteIndexUsed()
        }

        override fun visitVarInsn(
            opcode: Int,
            varIndex: Int,
        ) {
            when (opcode) {
                Opcodes.ALOAD -> {
                    pushInsn(RecentInsn.ALoad(varIndex))
                }

                Opcodes.ASTORE -> {
                    if (pendingSuspendedMarkerCall) {
                        suspendedMarkerSlots += varIndex
                        pendingSuspendedMarkerCall = false
                    }
                    pushInsn(RecentInsn.Other)
                }

                else -> {
                    pushInsn(RecentInsn.Other)
                }
            }
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
            pushInsn(RecentInsn.Other)
            if (!defaultShaped) return
            if (phase == 1 && (opcode == Opcodes.BIPUSH || opcode == Opcodes.SIPUSH) && isPowerOfTwo(operand)) {
                pendingConstant = operand
                phase = 2
            } else {
                resetMaskPhase()
            }
        }

        override fun visitLdcInsn(value: Any?) {
            super.visitLdcInsn(value)
            pushInsn(RecentInsn.Ldc(value))
            if (!defaultShaped) return
            if (phase == 1 && value is Int && isPowerOfTwo(value)) {
                pendingConstant = value
                phase = 2
            } else {
                resetMaskPhase()
            }
        }

        override fun visitInsn(opcode: Int) {
            pushInsn(if (opcode == Opcodes.IAND) RecentInsn.Iand else RecentInsn.Other)
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
            pushInsn(if (opcode == Opcodes.GETFIELD) RecentInsn.GetField(owner, fieldName, fieldDescriptor) else RecentInsn.Other)
            if (defaultShaped) resetMaskPhase()
            super.visitFieldInsn(opcode, owner, fieldName, fieldDescriptor)
        }

        override fun visitMethodInsn(
            opcode: Int,
            owner: String,
            methodName: String,
            methodDescriptor: String,
            isInterface: Boolean,
        ) {
            val isCoroutineSuspendedCall =
                opcode == Opcodes.INVOKESTATIC && methodName == "getCOROUTINE_SUSPENDED" && owner.endsWith("/IntrinsicsKt")
            if (isCoroutineSuspendedCall) pendingSuspendedMarkerCall = true
            pushInsn(RecentInsn.Other)
            if (defaultShaped) resetMaskPhase()
            super.visitMethodInsn(opcode, owner, methodName, methodDescriptor, isInterface)
        }

        override fun visitTypeInsn(
            opcode: Int,
            type: String,
        ) {
            super.visitTypeInsn(opcode, type)
            pushInsn(if (opcode == Opcodes.INSTANCEOF) RecentInsn.InstanceOf(type) else RecentInsn.Other)
            if (defaultShaped) resetMaskPhase()
        }

        override fun visitIincInsn(
            varIndex: Int,
            increment: Int,
        ) {
            pushInsn(RecentInsn.Other)
            if (defaultShaped) resetMaskPhase()
        }

        override fun visitInvokeDynamicInsn(
            invokedName: String,
            invokedDescriptor: String,
            bootstrapMethodHandle: Handle,
            vararg bootstrapMethodArguments: Any,
        ) {
            pushInsn(RecentInsn.Other)
            if (defaultShaped) resetMaskPhase()
            super.visitInvokeDynamicInsn(invokedName, invokedDescriptor, bootstrapMethodHandle, *bootstrapMethodArguments)
        }

        override fun visitMultiANewArrayInsn(
            arrayDescriptor: String,
            numDimensions: Int,
        ) {
            super.visitMultiANewArrayInsn(arrayDescriptor, numDimensions)
            pushInsn(RecentInsn.Other)
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

    /** Matches a Kotlin data class component accessor's name, capturing its one-based index. */
    private val dataClassComponentPattern = Regex("^component(\\d+)$")

    /**
     * What compiled each of [internalClassName]'s own declared methods into existence, from
     * bytecode shape alone, per ADR 0026. No rule here reads an annotation or `kotlin.Metadata`.
     *
     * A class named with the `$DefaultImpls` suffix marks a method [GeneratedBy.DEFAULT_IMPLS] only
     * when its body only forwards, as [defaultImplsForwarders] checks, and marks nothing else in the
     * class. Under `-jvm-default=enable`, the default from language version 2.2, the interface
     * method holds the real body and `$DefaultImpls` keeps a forwarder for callers compiled against
     * the older layout, which nothing in the application calls and which would otherwise read as
     * never hit. Under
     * `-jvm-default=disable`, the default up to language version 2.1, the interface method is
     * abstract and `$DefaultImpls` holds the real body, conditionals included, so marking every
     * method in the class would hide code the adopter wrote. The forwarder test reads the body,
     * never the `Deprecated` attribute kotlinc gives a forwarder, since an adopter's own
     * `@Deprecated` default method carries that attribute too.
     *
     * A class whose direct superclass is `java.lang.Enum` marks `values()` returning an array of
     * the class, `valueOf(Ljava/lang/String;)` returning the class, and `getEntries()` of any
     * descriptor, [GeneratedBy.ENUM].
     *
     * A class whose direct superclass is `java.lang.Record` marks `equals(Ljava/lang/Object;)Z`,
     * `hashCode()I`, and `toString()Ljava/lang/String;` [GeneratedBy.RECORD]; its accessors are
     * the adopter's own component declarations and stay [GeneratedBy.NONE].
     *
     * A data class is recognised by the shape the compiler alone can produce: a consecutive
     * `component1` through `componentN`, each taking no parameters, whose return types in order
     * equal the parameter types of some `<init>` with exactly N parameters, plus a `copy` taking
     * those same N parameter types and returning the class itself, plus
     * `equals(Ljava/lang/Object;)Z`, `hashCode()I`, and `toString()Ljava/lang/String;`. Nothing is
     * marked unless all of them are present; a class that hand-writes some but not all, such as a
     * bare `copy` and `component1` with no `equals`, `hashCode`, or `toString`, is left
     * [GeneratedBy.NONE] throughout, since the compiler itself never produces that partial shape.
     * Once the shape matches, `componentN` and `copy` are [GeneratedBy.DATA_CLASS], and each of
     * `equals`, `hashCode` and `toString` is [GeneratedBy.DATA_CLASS] only when it is absent from
     * [methodsWithLineNumbers]; see [markDataClassMembers].
     */
    private fun computeGeneratedBy(
        classBytes: ByteArray,
        internalClassName: String,
        superInternalName: String?,
        methodAccess: Map<Pair<String, String>, Int>,
        methodsWithLineNumbers: Set<Pair<String, String>>,
    ): Map<Pair<String, String>, GeneratedBy> {
        val result = mutableMapOf<Pair<String, String>, GeneratedBy>()

        if (internalClassName.endsWith(DEFAULT_IMPLS_SUFFIX)) {
            val interfaceInternalName = internalClassName.removeSuffix(DEFAULT_IMPLS_SUFFIX)
            for (key in defaultImplsForwarders(classBytes, interfaceInternalName)) result[key] = GeneratedBy.DEFAULT_IMPLS
            return result
        }

        if (superInternalName == "java/lang/Enum") {
            val arrayDescriptor = "()[L$internalClassName;"
            val valueOfDescriptor = "(Ljava/lang/String;)L$internalClassName;"
            for (key in methodAccess.keys) {
                val (name, descriptor) = key
                when {
                    name == "values" && descriptor == arrayDescriptor -> result[key] = GeneratedBy.ENUM
                    name == "valueOf" && descriptor == valueOfDescriptor -> result[key] = GeneratedBy.ENUM
                    name == "getEntries" -> result[key] = GeneratedBy.ENUM
                }
            }
        }

        if (superInternalName == "java/lang/Record") {
            for (key in methodAccess.keys) {
                val (name, descriptor) = key
                if (isEqualsHashCodeOrToString(name, descriptor)) result[key] = GeneratedBy.RECORD
            }
        }

        markDataClassMembers(internalClassName, methodAccess, methodsWithLineNumbers, result)
        return result
    }

    private const val DEFAULT_IMPLS_SUFFIX = "\$DefaultImpls"

    /**
     * The methods of a `$DefaultImpls` class whose body only forwards to [interfaceInternalName]:
     * it loads each of its parameters once, in declaration order, with the load opcode for that
     * parameter's type, then makes exactly one `invokestatic` whose owner is the interface, then
     * returns with one xRETURN. Labels, line numbers, frames and other pseudo-instructions are
     * ignored; any other instruction, including a conditional jump, a `checkcast` or a boxing call,
     * means the method is not a forwarder.
     *
     * The rule is kept this narrow on purpose. A real forwarder shape it misses shows up as a
     * false never-hit, which someone can see and report; a wider rule that also matched a real body
     * would hide that body from never-hit with nothing to show for it. Every forwarder kotlinc
     * 2.2.21 emits under `-jvm-default=enable` matches, generic, `long`/`double`, property accessor,
     * `$default` and suspend methods included (checked with `javap`).
     */
    private fun defaultImplsForwarders(
        classBytes: ByteArray,
        interfaceInternalName: String,
    ): Set<Pair<String, String>> {
        val forwarders = mutableSetOf<Pair<String, String>>()
        val classVisitor =
            object : ClassVisitor(Opcodes.ASM9) {
                override fun visitMethod(
                    access: Int,
                    name: String,
                    descriptor: String,
                    signature: String?,
                    exceptions: Array<out String>?,
                ): MethodVisitor? {
                    if (access and BODYLESS_FLAGS != 0) return null
                    val firstSlot = if (access and Opcodes.ACC_STATIC != 0) 0 else 1
                    val expectedLoads = mutableListOf<Pair<Int, Int>>()
                    var slot = firstSlot
                    for (type in parseParameterDescriptors(descriptor)) {
                        expectedLoads += loadOpcodeFor(type) to slot
                        slot += slotWidth(type)
                    }
                    return ForwarderShapeVisitor(interfaceInternalName, expectedLoads) { forwarders += name to descriptor }
                }
            }
        ClassReader(classBytes).accept(classVisitor, ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES)
        return forwarders
    }

    /** The xLOAD opcode that pushes a local of field descriptor [type]. */
    private fun loadOpcodeFor(type: String): Int =
        when (type[0]) {
            'J' -> Opcodes.LLOAD
            'F' -> Opcodes.FLOAD
            'D' -> Opcodes.DLOAD
            'L', '[' -> Opcodes.ALOAD
            else -> Opcodes.ILOAD
        }

    /**
     * Walks one method body and calls [onForwarder] at its end when the body is exactly
     * [expectedLoads], then one `invokestatic` on [interfaceInternalName], then one xRETURN. See
     * [defaultImplsForwarders].
     */
    private class ForwarderShapeVisitor(
        private val interfaceInternalName: String,
        private val expectedLoads: List<Pair<Int, Int>>,
        private val onForwarder: () -> Unit,
    ) : MethodVisitor(Opcodes.ASM9) {
        private var loadsSeen = 0
        private var invoked = false
        private var returned = false
        private var broken = false

        private fun reject() {
            broken = true
        }

        override fun visitVarInsn(
            opcode: Int,
            varIndex: Int,
        ) {
            if (invoked || loadsSeen >= expectedLoads.size || expectedLoads[loadsSeen] != (opcode to varIndex)) {
                reject()
                return
            }
            loadsSeen++
        }

        override fun visitMethodInsn(
            opcode: Int,
            owner: String,
            name: String,
            descriptor: String,
            isInterface: Boolean,
        ) {
            if (invoked || opcode != Opcodes.INVOKESTATIC || owner != interfaceInternalName || loadsSeen != expectedLoads.size) {
                reject()
                return
            }
            invoked = true
        }

        override fun visitInsn(opcode: Int) {
            if (!invoked || returned || opcode !in Opcodes.IRETURN..Opcodes.RETURN) {
                reject()
                return
            }
            returned = true
        }

        override fun visitIntInsn(
            opcode: Int,
            operand: Int,
        ) = reject()

        override fun visitTypeInsn(
            opcode: Int,
            type: String,
        ) = reject()

        override fun visitFieldInsn(
            opcode: Int,
            owner: String,
            name: String,
            descriptor: String,
        ) = reject()

        override fun visitInvokeDynamicInsn(
            name: String,
            descriptor: String,
            bootstrapMethodHandle: Handle,
            vararg bootstrapMethodArguments: Any?,
        ) = reject()

        override fun visitJumpInsn(
            opcode: Int,
            label: Label,
        ) = reject()

        override fun visitLdcInsn(value: Any?) = reject()

        override fun visitIincInsn(
            varIndex: Int,
            increment: Int,
        ) = reject()

        override fun visitTableSwitchInsn(
            min: Int,
            max: Int,
            dflt: Label,
            vararg labels: Label,
        ) = reject()

        override fun visitLookupSwitchInsn(
            dflt: Label,
            keys: IntArray,
            labels: Array<out Label>,
        ) = reject()

        override fun visitMultiANewArrayInsn(
            descriptor: String,
            numDimensions: Int,
        ) = reject()

        override fun visitTryCatchBlock(
            start: Label,
            end: Label,
            handler: Label,
            type: String?,
        ) = reject()

        override fun visitEnd() {
            if (!broken && invoked && returned) onForwarder()
        }
    }

    private fun isEqualsHashCodeOrToString(
        name: String,
        descriptor: String,
    ): Boolean =
        (name == "equals" && descriptor == "(Ljava/lang/Object;)Z") ||
            (name == "hashCode" && descriptor == "()I") ||
            (name == "toString" && descriptor == "()Ljava/lang/String;")

    /**
     * Finds a consecutive `component1..componentN` group, a matching `<init>`, a matching `copy`,
     * and all three of `equals`/`hashCode`/`toString`, and leaves [result] untouched unless every
     * part of the shape is present. When it is, the `componentN` group and `copy` are marked
     * [GeneratedBy.DATA_CLASS], and so is each of `equals`, `hashCode` and `toString` that is not
     * in [methodsWithLineNumbers].
     *
     * kotlinc 2.2.21 emits the generated `equals`, `hashCode` and `toString` with no line-number
     * table, and an override the adopter wrote with a table pointing at its body; access flags,
     * the local variable table and parameter annotations are the same for both (checked with
     * `javap`). A method with at least one line number is therefore the adopter's and stays
     * [GeneratedBy.NONE], so a hand-written `equals` reads as ordinary code. Kotlin forbids
     * hand-writing `componentN` or `copy` on a data class, so those two need no such check. A class
     * compiled without debug info has no line numbers anywhere, so all three are marked; ADR 0026
     * accepts that, and the class already draws the stripped-debug warning.
     */
    private fun markDataClassMembers(
        internalClassName: String,
        methodAccess: Map<Pair<String, String>, Int>,
        methodsWithLineNumbers: Set<Pair<String, String>>,
        result: MutableMap<Pair<String, String>, GeneratedBy>,
    ) {
        val components =
            methodAccess.keys
                .mapNotNull { key ->
                    val (name, descriptor) = key
                    val match = dataClassComponentPattern.matchEntire(name) ?: return@mapNotNull null
                    if (parseParameterDescriptors(descriptor).isNotEmpty()) return@mapNotNull null
                    val index = match.groupValues[1].toIntOrNull() ?: return@mapNotNull null
                    index to (key to returnTypeOf(descriptor))
                }.toMap()
        if (components.isEmpty()) return
        val componentCount = components.keys.max()
        if ((1..componentCount).any { it !in components }) return
        val componentTypes = (1..componentCount).map { components.getValue(it).second }

        val hasMatchingConstructor =
            methodAccess.keys.any { (name, descriptor) ->
                name == "<init>" && parseParameterDescriptors(descriptor) == componentTypes
            }
        if (!hasMatchingConstructor) return

        val ownerDescriptor = "L$internalClassName;"
        val copyKey =
            methodAccess.keys.firstOrNull { (name, descriptor) ->
                name == "copy" && returnTypeOf(descriptor) == ownerDescriptor && parseParameterDescriptors(descriptor) == componentTypes
            } ?: return

        val objectMethodKeys =
            listOf("equals" to "(Ljava/lang/Object;)Z", "hashCode" to "()I", "toString" to "()Ljava/lang/String;")
        if (objectMethodKeys.any { it !in methodAccess }) return

        for (index in 1..componentCount) result[components.getValue(index).first] = GeneratedBy.DATA_CLASS
        result[copyKey] = GeneratedBy.DATA_CLASS
        for (key in objectMethodKeys) {
            if (key !in methodsWithLineNumbers) result[key] = GeneratedBy.DATA_CLASS
        }
    }

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
     * The local-variable slot of [descriptor]'s last parameter: for a suspend function, the
     * `Continuation` the compiler appends. Computed the same way [maskLocalInfo] locates a
     * `$default` method's mask int, from the descriptor and [isStatic] alone, since a parameter's
     * slot is fixed by the method's signature and never depends on the method body.
     */
    private fun lastParameterLocalIndex(
        descriptor: String,
        isStatic: Boolean,
    ): Int {
        val params = parseParameterDescriptors(descriptor)
        var slot = if (isStatic) 0 else 1
        for (i in 0 until params.size - 1) slot += slotWidth(params[i])
        return slot
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
     * `LocalVariableTable`. Slot 0 is skipped for an instance method (`this`), and the first
     * declared parameter is skipped when its local is named `$this$...`: kotlinc's name for an
     * extension receiver, which sits at slot 0 of a top-level extension function and at slot 1 of
     * a member extension function. Neither `this` nor a receiver is a value parameter, so neither
     * owns a mask bit. Checked against `javap` of Kotlin 2.2.21 output for both shapes.
     */
    private fun parameterNamesOf(
        candidate: DefaultCandidate,
        targetDescriptor: String,
        targetIsStatic: Boolean,
        targetLocalNames: Map<Int, String>,
    ): Map<Int, String> {
        val targetParams = parseParameterDescriptors(targetDescriptor)
        val startSlot = if (targetIsStatic) 0 else 1
        val firstParameterName = targetLocalNames[startSlot]
        val skipFirst = targetParams.isNotEmpty() && firstParameterName != null && firstParameterName.startsWith("\$this\$")
        val remainingParams = if (skipFirst) targetParams.drop(1) else targetParams
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

        // Memoised by containsKey rather than getOrPut, which treats a null value as absent: an
        // unreadable companion would otherwise be looked up again for every constructor getter.
        fun companionNamespace(companionInternalName: String): GetterTargetNamespace? {
            if (companionInternalName in companionNamespaces) return companionNamespaces[companionInternalName]
            val namespace = readCompanionNamespace(companionInternalName, lookupCompanionBytes)
            companionNamespaces[companionInternalName] = namespace
            return namespace
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

    /** The [GetterTargetNamespace] of a companion module's sibling class, or null when its bytes cannot be read or parsed. */
    private fun readCompanionNamespace(
        companionInternalName: String,
        lookupCompanionBytes: (String) -> ByteArray?,
    ): GetterTargetNamespace? {
        val bytes =
            try {
                lookupCompanionBytes(companionInternalName)
            } catch (_: Exception) {
                null
            } ?: return null
        val table =
            try {
                readMethodTable(bytes)
            } catch (_: Exception) {
                null
            } ?: return null
        return GetterTargetNamespace(
            table.methodAccess,
            table.localNames,
            table.firstLines,
            table.classAccess,
            targetClassName = companionInternalName.replace('/', '.'),
        )
    }

    /**
     * A class's method access flags, local variable names, first line numbers, and raw call
     * candidates, read once from its bytes. [isScalaClass] tells a pass-through resolution apart
     * from a lambda body scalac generates, the same distinction
     * [TypeMatchPolicy.methodMatcher] applies to a loaded class. [hasEnclosingMethod] is true only
     * for a body class: the JVM attaches an `EnclosingMethod` attribute to an anonymous or local
     * class, and kotlinc attaches the same attribute to a function reference, a suspend lambda, and
     * an object expression. See ADR 0024's body-class rule. [interfaceInternalNames] says whether a
     * body class implements a handler interface, which the forwarder table needs (ADR 0035).
     */
    internal class MethodTable(
        val classAccess: Int,
        val methodAccess: Map<Pair<String, String>, Int>,
        val localNames: Map<Pair<String, String>, Map<Int, String>>,
        val firstLines: Map<Pair<String, String>, Int>,
        val rawCandidatesByMethod: Map<Pair<String, String>, List<RawCandidate>> = emptyMap(),
        val isScalaClass: Boolean = false,
        val hasEnclosingMethod: Boolean = false,
        val rawReferencesByMethod: Map<Pair<String, String>, Set<String>> = emptyMap(),
        val internalName: String = "",
        val superInternalName: String? = null,
        val interfaceInternalNames: List<String> = emptyList(),
    ) {
        /**
         * A body class the type matcher turns away by [TypeMatchPolicy.isTurnedAwayByShape], so
         * none of its methods ever has a probe: a synthetic class, such as each function or
         * property reference and each `$sam$` wrapper kotlinc makes, or a suspend function's own
         * continuation. See ADR 0034.
         */
        val isUnprobedBodyClass: Boolean
            get() =
                hasEnclosingMethod &&
                    TypeMatchPolicy.isTurnedAwayByShape(
                        internalName.replace('/', '.'),
                        classAccess and Opcodes.ACC_SYNTHETIC != 0,
                    ) { superInternalName?.replace('/', '.') }
    }

    /**
     * A minimal reader for a class this agent is not instrumenting: what
     * [resolveScalaGetterSites] needs to resolve a constructor default getter against a companion
     * module class's own `<init>`, and what [resolveCallEdges] needs to resolve a cross-class
     * pass-through against its owner's own bytecode. Unlike [analyze], every method's first line
     * and raw candidates are recorded unconditionally, since no method or branch tier runs against
     * this class to gate it by eligibility.
     */
    private fun readMethodTable(classBytes: ByteArray): MethodTable {
        var classAccess = 0
        var internalName = ""
        var superInternalName: String? = null
        var interfaceInternalNames: List<String> = emptyList()
        var hasEnclosingMethod = false
        val methodAccess = mutableMapOf<Pair<String, String>, Int>()
        val localNames = mutableMapOf<Pair<String, String>, MutableMap<Int, String>>()
        val firstLines = mutableMapOf<Pair<String, String>, Int>()
        val rawCandidatesByMethod = mutableMapOf<Pair<String, String>, MutableList<RawCandidate>>()
        val rawReferencesByMethod = mutableMapOf<Pair<String, String>, MutableSet<String>>()

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
                    internalName = name
                    superInternalName = superName
                    interfaceInternalNames = interfaces?.toList() ?: emptyList()
                }

                override fun visitOuterClass(
                    owner: String,
                    name: String?,
                    descriptor: String?,
                ) {
                    hasEnclosingMethod = true
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
                    val candidatesForMethod = rawCandidatesByMethod.getOrPut(name to descriptor) { mutableListOf() }
                    val referencesForMethod = rawReferencesByMethod.getOrPut(name to descriptor) { LinkedHashSet() }
                    recordSignatureReferences(referencesForMethod, descriptor, signature, exceptions)
                    return object : CallCandidateMethodVisitor(internalName, candidatesForMethod, referencesForMethod) {
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
        val isScalaClass = ScalaClassDetector.isScalaClass(classBytes)
        return MethodTable(
            classAccess,
            methodAccess,
            localNames,
            firstLines,
            rawCandidatesByMethod,
            isScalaClass,
            hasEnclosingMethod,
            rawReferencesByMethod,
            internalName,
            superInternalName,
            interfaceInternalNames,
        )
    }
}
