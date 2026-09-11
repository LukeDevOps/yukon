package io.github.lukedevops.yukon.instrumentation

import io.github.lukedevops.yukon.advice.MethodEntryAdvice
import io.github.lukedevops.yukon.advice.ProbeIndex
import io.github.lukedevops.yukon.config.AgentConfig
import io.github.lukedevops.yukon.export.ProbeKind
import io.github.lukedevops.yukon.instrumentation.branch.BranchProbeAsmVisitorWrapper
import io.github.lukedevops.yukon.instrumentation.branch.BranchSite
import io.github.lukedevops.yukon.instrumentation.branch.BranchSiteAnalyzer
import io.github.lukedevops.yukon.registry.ProbeMeta
import io.github.lukedevops.yukon.registry.ProbeRegistry
import net.bytebuddy.agent.builder.AgentBuilder
import net.bytebuddy.agent.builder.ResettableClassFileTransformer
import net.bytebuddy.asm.Advice
import net.bytebuddy.description.method.MethodDescription
import net.bytebuddy.description.method.MethodList
import net.bytebuddy.description.modifier.Ownership
import net.bytebuddy.description.modifier.SyntheticState
import net.bytebuddy.description.modifier.Visibility
import net.bytebuddy.description.type.TypeDescription
import net.bytebuddy.dynamic.ClassFileLocator
import net.bytebuddy.dynamic.DynamicType
import net.bytebuddy.implementation.LoadedTypeInitializer
import net.bytebuddy.matcher.ElementMatcher
import net.bytebuddy.matcher.ElementMatchers.`is`
import net.bytebuddy.matcher.ElementMatchers.isAbstract
import net.bytebuddy.matcher.ElementMatchers.isBridge
import net.bytebuddy.matcher.ElementMatchers.isSynthetic
import net.bytebuddy.matcher.ElementMatchers.isTypeInitializer
import net.bytebuddy.matcher.ElementMatchers.nameStartsWith
import net.bytebuddy.matcher.ElementMatchers.not
import java.io.IOException
import java.lang.instrument.Instrumentation

/**
 * Wires method-entry probes into every type matched by [AgentConfig.instrumentedPackagePrefixes]
 * (or every non-agent type when that list is empty). Each matched type is registered with
 * [ProbeRegistry] once and given its own synthetic static field holding the resulting counts
 * array, so every probe in that class reaches [MethodEntryAdvice] with a direct reference to
 * its own array and a constant slot index, no lookup keyed by class or method name involved.
 *
 * Registers a transformer for classes as they load; it does not retransform
 * classes already loaded when [install] runs, matching the agent's static
 * `premain` attach model where the transformer is registered before any
 * application class has loaded.
 */
class YukonInstrumentation(
    private val config: AgentConfig,
    private val registry: ProbeRegistry,
) {
    fun install(instrumentation: Instrumentation): ResettableClassFileTransformer =
        AgentBuilder
            .Default()
            .type(typeMatcher())
            .transform { builder, typeDescription, classLoader, _, _ -> instrument(builder, typeDescription, classLoader) }
            .installOn(instrumentation)

    private fun typeMatcher(): ElementMatcher.Junction<TypeDescription> {
        val excluded: ElementMatcher.Junction<TypeDescription> =
            not(isSynthetic<TypeDescription>()).and(not(nameStartsWith(AGENT_PACKAGE_PREFIX)))
        val prefixes = config.instrumentedPackagePrefixes
        if (prefixes.isEmpty()) return excluded
        val includesAny =
            prefixes
                .map { nameStartsWith<TypeDescription>(it) }
                .reduce { a, b -> a.or(b) }
        return excluded.and(includesAny)
    }

    private fun instrument(
        builder: DynamicType.Builder<*>,
        typeDescription: TypeDescription,
        classLoader: ClassLoader?,
    ): DynamicType.Builder<*> {
        val methods = typeDescription.declaredMethods.filter(methodMatcher())
        if (methods.isEmpty()) return builder

        val branchSites = findBranchSites(typeDescription, classLoader, methods)

        val methodProbes = methods.map { ProbeMeta(ProbeKind.METHOD, it.internalName, it.descriptor, line = -1) }
        // Each site becomes two adjacent slots: the even one counts the outcome that jumps
        // to the site's original target, the odd one counts falling through past the jump.
        val branchProbes =
            branchSites.flatMap { site ->
                listOf(
                    ProbeMeta(ProbeKind.BRANCH, site.methodName, site.methodDescriptor, site.line, branchIndex = site.siteIndex * 2),
                    ProbeMeta(ProbeKind.BRANCH, site.methodName, site.methodDescriptor, site.line, branchIndex = site.siteIndex * 2 + 1),
                )
            }
        val probes = methodProbes + branchProbes

        val layoutHash =
            ProbeLayoutHash.of(
                methods.map { it.internalName + it.descriptor } +
                    branchSites.map { "${it.methodName}${it.methodDescriptor}#branch${it.siteIndex}" },
            )
        val counts = registry.register(typeDescription.name, layoutHash, probes)

        var instrumented =
            builder
                .defineField(
                    MethodEntryAdvice.PROBE_ARRAY_FIELD,
                    LongArray::class.java,
                    Visibility.PRIVATE,
                    Ownership.STATIC,
                    SyntheticState.SYNTHETIC,
                ).initializer(LoadedTypeInitializer.ForStaticField(MethodEntryAdvice.PROBE_ARRAY_FIELD, counts))

        methods.forEachIndexed { index, method ->
            instrumented =
                instrumented.visit(
                    Advice
                        .withCustomMapping()
                        .bind(ProbeIndex::class.java, index)
                        .to(MethodEntryAdvice::class.java)
                        .on(`is`(method)),
                )
        }

        if (branchSites.isNotEmpty()) {
            val eligible = methods.map { it.internalName to it.descriptor }.toSet()
            instrumented =
                instrumented.visit(
                    BranchProbeAsmVisitorWrapper(
                        eligibleMethods = { name, descriptor -> (name to descriptor) in eligible },
                        probeIndexBase = methodProbes.size,
                    ),
                )
        }

        return instrumented
    }

    /**
     * Re-reads the class's own original bytecode to find its conditional jumps, since
     * ByteBuddy's transform callback hands over type metadata, not the class bytes
     * themselves. If the bytes can't be located or read, this class just gets no branch
     * probes; method-entry tracking is unaffected.
     */
    private fun findBranchSites(
        typeDescription: TypeDescription,
        classLoader: ClassLoader?,
        methods: MethodList<*>,
    ): List<BranchSite> {
        val locator =
            if (classLoader != null) {
                ClassFileLocator.ForClassLoader.of(classLoader)
            } else {
                ClassFileLocator.ForClassLoader.ofBootLoader()
            }
        val eligible = methods.map { it.internalName to it.descriptor }.toSet()
        return try {
            val resolution = locator.locate(typeDescription.name)
            if (!resolution.isResolved) return emptyList()
            BranchSiteAnalyzer.analyze(resolution.resolve()) { name, descriptor -> (name to descriptor) in eligible }
        } catch (_: IOException) {
            emptyList()
        }
    }

    private fun methodMatcher(): ElementMatcher.Junction<MethodDescription> =
        not(isAbstract<MethodDescription>())
            .and(not(isSynthetic()))
            .and(not(isBridge()))
            .and(not(isTypeInitializer()))

    private companion object {
        const val AGENT_PACKAGE_PREFIX = "io.github.lukedevops.yukon."
    }
}
