package io.github.lukedevops.yukon.instrumentation

import io.github.lukedevops.yukon.advice.MaskArgument
import io.github.lukedevops.yukon.advice.MethodEntryAdvice
import io.github.lukedevops.yukon.advice.OmissionBase
import io.github.lukedevops.yukon.advice.OptionalArgumentAdvice
import io.github.lukedevops.yukon.advice.OptionalBits
import io.github.lukedevops.yukon.advice.ProbeIndex
import io.github.lukedevops.yukon.bootstrap.YukonProbeArrays
import io.github.lukedevops.yukon.config.AgentConfig
import io.github.lukedevops.yukon.export.ProbeKind
import io.github.lukedevops.yukon.instrumentation.branch.BranchProbeAsmVisitorWrapper
import io.github.lukedevops.yukon.instrumentation.branch.BranchSiteAnalyzer
import io.github.lukedevops.yukon.instrumentation.staticscan.StaticBaselineMismatchDetector
import io.github.lukedevops.yukon.registry.ProbeMeta
import io.github.lukedevops.yukon.registry.ProbeRegistry
import net.bytebuddy.ByteBuddy
import net.bytebuddy.agent.builder.AgentBuilder
import net.bytebuddy.agent.builder.ResettableClassFileTransformer
import net.bytebuddy.asm.Advice
import net.bytebuddy.description.annotation.AnnotationDescription
import net.bytebuddy.description.field.FieldDescription
import net.bytebuddy.description.method.MethodDescription
import net.bytebuddy.description.method.MethodList
import net.bytebuddy.description.method.ParameterDescription
import net.bytebuddy.description.modifier.FieldManifestation
import net.bytebuddy.description.modifier.Ownership
import net.bytebuddy.description.modifier.SyntheticState
import net.bytebuddy.description.modifier.Visibility
import net.bytebuddy.description.type.TypeDescription
import net.bytebuddy.dynamic.ClassFileLocator
import net.bytebuddy.dynamic.DynamicType
import net.bytebuddy.implementation.Implementation
import net.bytebuddy.implementation.bytecode.ByteCodeAppender
import net.bytebuddy.implementation.bytecode.StackManipulation
import net.bytebuddy.implementation.bytecode.constant.ClassConstant
import net.bytebuddy.implementation.bytecode.constant.IntegerConstant
import net.bytebuddy.implementation.bytecode.constant.LongConstant
import net.bytebuddy.implementation.bytecode.constant.TextConstant
import net.bytebuddy.implementation.bytecode.member.FieldAccess
import net.bytebuddy.implementation.bytecode.member.MethodInvocation
import net.bytebuddy.jar.asm.MethodVisitor
import net.bytebuddy.matcher.ElementMatcher
import net.bytebuddy.matcher.ElementMatchers.named
import net.bytebuddy.matcher.ElementMatchers.none
import net.bytebuddy.matcher.ElementMatchers.takesArguments
import net.bytebuddy.utility.JavaModule
import java.io.IOException
import java.lang.System.Logger.Level
import java.lang.instrument.Instrumentation

/** A woven `$default` method's per-method constants for [OptionalArgumentAdvice]. */
private class DefaultSiteBinding(
    val base: Int,
    val optionalBits: Int,
    val maskParameterIndex: Int,
)

private fun bindingFor(
    bindings: Map<Pair<String, String>, DefaultSiteBinding>,
    instrumentedMethod: MethodDescription,
): DefaultSiteBinding =
    bindings[instrumentedMethod.internalName to instrumentedMethod.descriptor]
        ?: throw IllegalStateException(
            "yukon: no omission binding for ${instrumentedMethod.internalName}${instrumentedMethod.descriptor}",
        )

/**
 * Wires method-entry probes into every type matched by [AgentConfig.instrumentedPackagePrefixes].
 * If that list is empty, every type outside the agent's own package is matched, less the
 * bootstrap and platform loaders' classes that ByteBuddy's `AgentBuilder` ignores by default.
 *
 * Each matched type is registered with [ProbeRegistry] once, at transform time. It gets its own
 * `public static final long[]` field, and a `<clinit>` prelude that fills it with one call to the
 * bootstrap-resident [YukonProbeArrays], which asks the registry for the array registered a
 * moment earlier. Every probe in that class then reaches [MethodEntryAdvice] with a direct read
 * of its own array and a constant slot index. No lookup by class or method name is involved on
 * any hot path; the one lookup happens once per class, at initialisation.
 *
 * Interfaces are instrumented the same way. The JVM only allows public static final fields on an
 * interface, which rules out setting the field reflectively after load (the JDK refuses
 * reflective writes to static finals); a woven `<clinit>` is the one mechanism that works for
 * classes and interfaces alike, so it is the only one used.
 *
 * This registers a transformer for classes as they load. It does not retransform classes already
 * loaded when [install] runs. That matches the agent's static `premain` attach model, where the
 * transformer is registered before any application class has loaded.
 */
class YukonInstrumentation(
    private val config: AgentConfig,
    private val registry: ProbeRegistry,
    private val staticBaselineMismatchDetector: StaticBaselineMismatchDetector = StaticBaselineMismatchDetector(),
) {
    private val log = System.getLogger(YukonInstrumentation::class.java.name)
    private val classBytesCapture = ClassBytesCapture(::isCandidateInternalName)

    /**
     * Installs the bootstrap holder, points it at this registry, then registers two transformers
     * in this order: [ClassBytesCapture], then ByteBuddy's. Both are registered as not
     * retransformation-capable, so the JVM calls them in registration order and the capture
     * always runs just before ByteBuddy for the same class on the same thread.
     *
     * Throws [BootstrapInstallException], before registering anything, if the holder cannot be
     * made bootstrap-visible. Without it every instrumented class would fail in its own
     * `<clinit>`, so not instrumenting at all is the only safe answer.
     *
     * The returned transformer's `reset` only removes ByteBuddy's; call [uninstall] to remove both.
     */
    fun install(instrumentation: Instrumentation): ResettableClassFileTransformer {
        BootstrapHolder.install(instrumentation)
        YukonProbeArrays.install { className, layoutHash, _, classLoader -> registry.lookup(className, layoutHash, classLoader) }
        instrumentation.addTransformer(classBytesCapture, false)
        return AgentBuilder
            // ByteBuddy's own default ignores every synthetic method, copying it through
            // unrewritten no matter what a later .visit()/.method() matcher asks for: a Kotlin
            // $default method is exactly such a method, and the omission tier's whole job is to
            // weave advice onto it. Every other tier already gates what it touches through its
            // own explicit matchers (methodMatcher, typeMatcher), so lifting ByteBuddy's blanket
            // exclusion here does not widen what actually gets instrumented.
            .Default(ByteBuddy().ignore(none()))
            // No LoadedTypeInitializer is ever used, so ByteBuddy has nothing to run after load
            // and no reason to inject its Nexus class into the bootstrap loader via Unsafe.
            .with(AgentBuilder.InitializationStrategy.NoOp.INSTANCE)
            .with(TransformFailureListener())
            .type(typeMatcher())
            .transform { builder, typeDescription, classLoader, _, _ -> instrument(builder, typeDescription, classLoader) }
            .installOn(instrumentation)
    }

    /** Removes both transformers [install] registered. */
    fun uninstall(
        instrumentation: Instrumentation,
        transformer: ResettableClassFileTransformer,
    ) {
        transformer.reset(instrumentation, AgentBuilder.RedefinitionStrategy.DISABLED)
        instrumentation.removeTransformer(classBytesCapture)
    }

    /** String-only pre-filter for the capture, the package part of [typeMatcher] without resolving a type. */
    private fun isCandidateInternalName(internalName: String): Boolean =
        TypeMatchPolicy.isIncluded(internalName.replace('/', '.'), config.instrumentedPackagePrefixes, config.excludedPackagePrefixes)

    /**
     * A class transform can still fail after [instrument] has already called
     * [ProbeRegistry.register]. ByteBuddy only rewrites and validates the bytecode once that
     * callback returns, so this failure is reported later than the registration that caused it.
     *
     * Left alone, the manifest would permanently list that class's probes as known but never hit.
     * That looks identical to genuinely dead code. Rolling the registration back on failure keeps
     * the manifest honest instead: a class this agent could not safely instrument is simply
     * absent, not falsely reported as "dead".
     */
    private inner class TransformFailureListener : AgentBuilder.Listener.Adapter() {
        override fun onError(
            typeName: String,
            classLoader: ClassLoader?,
            module: JavaModule?,
            loaded: Boolean,
            throwable: Throwable,
        ) {
            log.log(Level.WARNING, "yukon: instrumentation failed for $typeName, class will run uninstrumented", throwable)
            registry.unregister(typeName, classLoader)
            registry.recordSkipped(typeName, throwable.message ?: throwable.toString())
        }
    }

    private fun typeMatcher(): ElementMatcher.Junction<TypeDescription> =
        TypeMatchPolicy
            .typeNameMatcher(config.instrumentedPackagePrefixes, config.excludedPackagePrefixes)
            .and { typeDescription -> isSafeToInstrument(typeDescription) }

    /**
     * `AgentBuilder` commits to rebasing a type the moment it matches `.type(...)`. This happens
     * before [instrument] (the `.transform()` callback) ever runs, so a type excluded here never
     * reaches that callback at all.
     *
     * That early commitment is why this is the only point that can actually prevent the crash
     * described below, rather than just contain its aftermath. Returning the original builder
     * unchanged from [instrument] does not help: ByteBuddy's later `.make()` call still crashes
     * on the already-rebased type regardless.
     *
     * ByteBuddy refuses to redefine any type that carries a declared annotation whose own
     * `@Target` does not legally support [ElementType.TYPE]. It throws `IllegalStateException`
     * deep inside its own validation. Kotlin's compiler attaches `@kotlin.jvm.JvmName` directly
     * onto the class file for any `@file:JvmName`-annotated source file, even though that
     * annotation's own `@Target` only covers functions, properties, and files, not classes. This
     * trips the same check: legal bytecode, but not a shape ByteBuddy's redefinition path
     * accepts.
     */
    private fun isSafeToInstrument(typeDescription: TypeDescription): Boolean {
        val unsupported = TypeMatchPolicy.unsafeAnnotation(typeDescription) ?: return true
        registry.recordSkipped(
            typeDescription.name,
            "@${unsupported.annotationType.name} is not a legal annotation on a class per its own @Target",
        )
        return false
    }

    private fun instrument(
        builder: DynamicType.Builder<*>,
        typeDescription: TypeDescription,
        classLoader: ClassLoader?,
    ): DynamicType.Builder<*> {
        val methods = typeDescription.declaredMethods.filter(methodMatcher())

        // Analysed regardless of whether methods is empty: a type whose only concrete content is
        // a Kotlin $default method (an interface declaring only an abstract method plus its
        // default, with no other probe-worthy method) would otherwise never reach the omission
        // tier below at all.
        val analysis = analyzeBytecode(typeDescription, classLoader, methods)
        if (methods.isEmpty() && analysis.defaultSites.isEmpty()) return builder
        val branchSites = analysis.sites

        // A resolved Scala default getter (ADR 0023) keeps its ordinary method-tier slot and
        // advice; only its manifest row changes, from a METHOD probe under the getter's own name
        // to an OPTIONAL_ARGUMENT probe naming the target it fills a default for.
        val scalaGetterSitesByKey = analysis.scalaGetterSites.associateBy { it.getterName to it.getterDescriptor }
        val methodProbes =
            methods.map {
                val getterSite = scalaGetterSitesByKey[it.internalName to it.descriptor]
                if (getterSite != null) {
                    ProbeMeta(
                        ProbeKind.OPTIONAL_ARGUMENT,
                        getterSite.targetName,
                        getterSite.targetDescriptor,
                        line = analysis.firstLineOf(getterSite.targetName, getterSite.targetDescriptor),
                        inline = false,
                        parameterIndex = getterSite.parameterIndex,
                        parameterName = getterSite.parameterName,
                        overridable = getterSite.overridable,
                    )
                } else {
                    ProbeMeta(
                        ProbeKind.METHOD,
                        it.internalName,
                        it.descriptor,
                        line = analysis.firstLineOf(it.internalName, it.descriptor),
                        inline = analysis.isInline(it.internalName, it.descriptor),
                    )
                }
            }
        // Each site contributes `outcomeCount` adjacent slots: 2 for a conditional jump, or the
        // case count plus one for a switch. BranchProbeAsmVisitorWrapper allocates them in this
        // same order. A branch inside an inline method's body is just as invisible to a Kotlin
        // caller as the method probe itself, so it inherits the same flag.
        val branchProbes =
            branchSites
                .flatMap { site -> List(site.outcomeCount) { site } }
                .mapIndexed { branchIndex, site ->
                    ProbeMeta(
                        ProbeKind.BRANCH,
                        site.methodName,
                        site.methodDescriptor,
                        site.line,
                        branchIndex = branchIndex,
                        inline = analysis.isInline(site.methodName, site.methodDescriptor),
                    )
                }
        // Slots are packed per default site, one per optional parameter, appended after the
        // method and branch slots: bit i's slot is siteBase + bitCount(optionalBits & ((1 << i) - 1)),
        // never one slot per value parameter, so a required parameter's bit (never set) never
        // reserves a slot nobody increments.
        val defaultSites = analysis.defaultSites
        var omissionBase = methodProbes.size + branchProbes.size
        val omissionSiteBases = mutableMapOf<Pair<String, String>, Int>()
        val omissionProbes =
            defaultSites.flatMap { site ->
                omissionSiteBases[site.defaultName to site.defaultDescriptor] = omissionBase
                val slots =
                    (0 until Int.SIZE_BITS)
                        .filter { bit -> (site.optionalBits shr bit) and 1 == 1 }
                        .map { bit ->
                            ProbeMeta(
                                ProbeKind.OPTIONAL_ARGUMENT,
                                site.targetName,
                                site.targetDescriptor,
                                line = analysis.firstLineOf(site.targetName, site.targetDescriptor),
                                inline = analysis.isInline(site.targetName, site.targetDescriptor),
                                parameterIndex = bit,
                                parameterName = site.parameterNames[bit] ?: "",
                                overridable = site.overridable,
                            )
                        }
                omissionBase += slots.size
                slots
            }
        for (site in defaultSites) {
            if (site.higherMaskTested) {
                log.log(
                    Level.INFO,
                    "yukon: ${typeDescription.name}#${site.defaultName} tests a mask int past the first; " +
                        "only the first 32 optional parameters are counted",
                )
            }
        }
        for ((name, descriptor) in analysis.unresolvedDefaultSites) {
            log.log(
                Level.INFO,
                "yukon: ${typeDescription.name}#$name$descriptor looks like a Kotlin default-argument method " +
                    "but its target could not be uniquely resolved, or no mask test was found; no omission probes woven",
            )
        }
        for ((name, descriptor) in analysis.unresolvedScalaGetterSites) {
            log.log(
                Level.INFO,
                "yukon: ${typeDescription.name}#$name$descriptor looks like a Scala default getter but its target " +
                    "could not be uniquely resolved; reported as an ordinary method probe",
            )
        }
        val probes = methodProbes + branchProbes + omissionProbes

        val layoutHash =
            ProbeLayoutHash.of(
                methods.map { it.internalName + it.descriptor } +
                    branchSites.map { "${it.methodName}${it.methodDescriptor}#branch${it.siteIndex}x${it.outcomeCount}" } +
                    defaultSites.map { "${it.defaultName}${it.defaultDescriptor}#optional${it.optionalBits}" },
            )
        val counts = registry.register(typeDescription.name, layoutHash, probes, classLoader)
        if (staticBaselineMismatchDetector.shouldWarnAbout(typeDescription.name)) {
            log.log(
                Level.WARNING,
                "yukon: ${typeDescription.name} registered dynamically but was not in the static baseline computed " +
                    "at startup for this process - the static scan may have a blind spot for this deployment " +
                    "(see \"Static baseline\" in this project's CLAUDE.md)",
            )
        }

        var instrumented =
            builder
                .defineField(
                    MethodEntryAdvice.PROBE_ARRAY_FIELD,
                    LongArray::class.java,
                    Visibility.PUBLIC,
                    Ownership.STATIC,
                    FieldManifestation.FINAL,
                    SyntheticState.SYNTHETIC,
                ).initializer(ProbeArrayInitializer(typeDescription.name, layoutHash, counts.size))

        // One Advice visitor for the whole class, with each method's slot resolved from its
        // signature at weave time. One visitor per method would stack N method visitors, each
        // checking every method against its own matcher, so transform cost grew with the square of
        // the method count.
        val slotBySignature = methods.withIndex().associate { (index, method) -> (method.internalName to method.descriptor) to index }
        instrumented =
            instrumented.visit(
                Advice
                    .withCustomMapping()
                    .bind(ProbeIndexMapping(slotBySignature))
                    .to(MethodEntryAdvice::class.java)
                    .on { method -> (method.internalName to method.descriptor) in slotBySignature },
            )

        if (branchSites.isNotEmpty()) {
            val eligible = methods.map { it.internalName to it.descriptor }.toSet()
            instrumented =
                instrumented.visit(
                    BranchProbeAsmVisitorWrapper(
                        eligibleMethods = { name, descriptor -> (name to descriptor) in eligible },
                        probeIndexBase = methodProbes.size,
                        branchSlotCapacity = branchProbes.size,
                        onSiteCountMismatch = { expected, actual ->
                            log.log(
                                Level.WARNING,
                                "yukon: ${typeDescription.name} has $actual branch probe slots at rewrite time but " +
                                    "$expected were sized from its analysed bytecode; the bytes being rewritten differ " +
                                    "from the bytes analysed, so its branch probes are unreliable and any site past " +
                                    "capacity is left uninstrumented",
                            )
                        },
                    ),
                )
        }

        if (defaultSites.isNotEmpty()) {
            val bindings =
                defaultSites.associateBy(
                    { it.defaultName to it.defaultDescriptor },
                    {
                        DefaultSiteBinding(
                            omissionSiteBases.getValue(it.defaultName to it.defaultDescriptor),
                            it.optionalBits,
                            it.maskParameterIndex,
                        )
                    },
                )
            instrumented =
                instrumented.visit(
                    Advice
                        .withCustomMapping()
                        .bind(OmissionBaseMapping(bindings))
                        .bind(OptionalBitsMapping(bindings))
                        .bind(MaskArgumentMapping(bindings))
                        .to(OptionalArgumentAdvice::class.java)
                        .on { method -> (method.internalName to method.descriptor) in bindings },
                )
        }

        return instrumented
    }

    /**
     * Finds the class's conditional jumps, and each method's first line number, in the bytes the
     * JVM is actually about to define, as captured by [ClassBytesCapture] just before ByteBuddy's
     * transform. ByteBuddy's own callback only hands over type metadata, not the class bytes.
     *
     * Falls back to the class's own classloader resource when nothing was captured (a class
     * defined outside the ordinary transformer chain, or loaded by a test harness that bypasses
     * [install]). If the bytes cannot be located or read at all, this class gets no branch probes
     * and no method line numbers. Method-entry tracking is unaffected.
     */
    private fun analyzeBytecode(
        typeDescription: TypeDescription,
        classLoader: ClassLoader?,
        methods: MethodList<*>,
    ): BranchSiteAnalyzer.Analysis {
        val eligible = methods.map { it.internalName to it.descriptor }.toSet()
        val bytes =
            classBytesCapture.take(typeDescription.internalName)
                ?: locateClassBytes(typeDescription, classLoader)
                ?: return BranchSiteAnalyzer.Analysis.EMPTY
        return BranchSiteAnalyzer.analyze(bytes) { name, descriptor -> (name to descriptor) in eligible }
    }

    /**
     * Resolves `@ProbeIndex` to the instrumented method's own slot, as a constant folded into the
     * inlined advice, so one [Advice] visitor serves every probed method in the class.
     */
    private class ProbeIndexMapping(
        private val slotBySignature: Map<Pair<String, String>, Int>,
    ) : Advice.OffsetMapping.Factory<ProbeIndex> {
        override fun getAnnotationType(): Class<ProbeIndex> = ProbeIndex::class.java

        override fun make(
            target: ParameterDescription.InDefinedShape,
            annotation: AnnotationDescription.Loadable<ProbeIndex>,
            adviceType: Advice.OffsetMapping.Factory.AdviceType,
        ): Advice.OffsetMapping =
            Advice.OffsetMapping { _, instrumentedMethod, _, _, _ ->
                val slot =
                    slotBySignature[instrumentedMethod.internalName to instrumentedMethod.descriptor]
                        ?: throw IllegalStateException(
                            "yukon: no probe slot for ${instrumentedMethod.internalName}${instrumentedMethod.descriptor}",
                        )
                Advice.OffsetMapping.Target.ForStackManipulation(IntegerConstant.forValue(slot))
            }
    }

    /** Resolves `@OmissionBase` to a woven `$default` method's first omission probe's slot. */
    private class OmissionBaseMapping(
        private val bindings: Map<Pair<String, String>, DefaultSiteBinding>,
    ) : Advice.OffsetMapping.Factory<OmissionBase> {
        override fun getAnnotationType(): Class<OmissionBase> = OmissionBase::class.java

        override fun make(
            target: ParameterDescription.InDefinedShape,
            annotation: AnnotationDescription.Loadable<OmissionBase>,
            adviceType: Advice.OffsetMapping.Factory.AdviceType,
        ): Advice.OffsetMapping =
            Advice.OffsetMapping { _, instrumentedMethod, _, _, _ ->
                Advice.OffsetMapping.Target.ForStackManipulation(IntegerConstant.forValue(bindingFor(bindings, instrumentedMethod).base))
            }
    }

    /** Resolves `@OptionalBits` to a woven `$default` method's optional-parameter bitmask. */
    private class OptionalBitsMapping(
        private val bindings: Map<Pair<String, String>, DefaultSiteBinding>,
    ) : Advice.OffsetMapping.Factory<OptionalBits> {
        override fun getAnnotationType(): Class<OptionalBits> = OptionalBits::class.java

        override fun make(
            target: ParameterDescription.InDefinedShape,
            annotation: AnnotationDescription.Loadable<OptionalBits>,
            adviceType: Advice.OffsetMapping.Factory.AdviceType,
        ): Advice.OffsetMapping =
            Advice.OffsetMapping { _, instrumentedMethod, _, _, _ ->
                Advice.OffsetMapping.Target.ForStackManipulation(
                    IntegerConstant.forValue(bindingFor(bindings, instrumentedMethod).optionalBits),
                )
            }
    }

    /**
     * Resolves `@MaskArgument` to a woven `$default` method's first mask `int`, read as a local
     * variable at its own offset: the mask parameter's index differs per method, so it cannot be
     * bound through a fixed `@Advice.Argument` index.
     */
    private class MaskArgumentMapping(
        private val bindings: Map<Pair<String, String>, DefaultSiteBinding>,
    ) : Advice.OffsetMapping.Factory<MaskArgument> {
        override fun getAnnotationType(): Class<MaskArgument> = MaskArgument::class.java

        override fun make(
            target: ParameterDescription.InDefinedShape,
            annotation: AnnotationDescription.Loadable<MaskArgument>,
            adviceType: Advice.OffsetMapping.Factory.AdviceType,
        ): Advice.OffsetMapping =
            Advice.OffsetMapping { _, instrumentedMethod, _, _, _ ->
                val maskParameterIndex = bindingFor(bindings, instrumentedMethod).maskParameterIndex
                val maskParameter = instrumentedMethod.parameters[maskParameterIndex]
                Advice.OffsetMapping.Target.ForVariable
                    .ReadOnly(maskParameter.type, maskParameter.offset)
            }
    }

    private fun locateClassBytes(
        typeDescription: TypeDescription,
        classLoader: ClassLoader?,
    ): ByteArray? {
        val locator =
            if (classLoader != null) {
                ClassFileLocator.ForClassLoader.of(classLoader)
            } else {
                ClassFileLocator.ForClassLoader.ofBootLoader()
            }
        return try {
            val resolution = locator.locate(typeDescription.name)
            if (resolution.isResolved) resolution.resolve() else null
        } catch (_: IOException) {
            null
        }
    }

    private fun methodMatcher(): ElementMatcher.Junction<MethodDescription> = TypeMatchPolicy.methodMatcher()

    /**
     * The `<clinit>` prelude that fills the counts field. It compiles to:
     *
     * ```
     * ldc        "<class name>"
     * ldc2_w     <layout hash>
     * ldc        <probe count>
     * ldc        <this class>
     * invokevirtual java/lang/Class.getClassLoader()
     * invokestatic  YukonProbeArrays.resolve(String, long, int, ClassLoader) long[]
     * putstatic  <this class>.$yukonProbeCounts
     * ```
     *
     * Every argument is a constant known at transform time. ByteBuddy runs this ahead of the
     * class's own original static initializer, so a static method probed in this class can be
     * called from that initializer and find the field already set.
     */
    private class ProbeArrayInitializer(
        private val className: String,
        private val layoutHash: Long,
        private val probeCount: Int,
    ) : ByteCodeAppender {
        override fun apply(
            methodVisitor: MethodVisitor,
            implementationContext: Implementation.Context,
            instrumentedMethod: MethodDescription,
        ): ByteCodeAppender.Size {
            val instrumentedType = implementationContext.instrumentedType
            val field = instrumentedType.declaredFields.filter(named<FieldDescription>(MethodEntryAdvice.PROBE_ARRAY_FIELD)).only
            val size =
                StackManipulation
                    .Compound(
                        TextConstant(className),
                        LongConstant.forValue(layoutHash),
                        IntegerConstant.forValue(probeCount),
                        ClassConstant.of(instrumentedType),
                        MethodInvocation.invoke(GET_CLASS_LOADER),
                        MethodInvocation.invoke(RESOLVE),
                        FieldAccess.forField(field).write(),
                    ).apply(methodVisitor, implementationContext)
            return ByteCodeAppender.Size(size.maximalSize, instrumentedMethod.stackSize)
        }

        private companion object {
            val GET_CLASS_LOADER: MethodDescription.InDefinedShape =
                TypeDescription.ForLoadedType
                    .of(Class::class.java)
                    .declaredMethods
                    .filter(named<MethodDescription>("getClassLoader").and(takesArguments(0)))
                    .only
            val RESOLVE: MethodDescription.InDefinedShape =
                TypeDescription.ForLoadedType
                    .of(YukonProbeArrays::class.java)
                    .declaredMethods
                    .filter(named<MethodDescription>("resolve"))
                    .only
        }
    }
}
