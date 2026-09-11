package io.github.lukedevops.yukon.instrumentation

import io.github.lukedevops.yukon.advice.MethodEntryAdvice
import io.github.lukedevops.yukon.config.AgentConfig
import io.github.lukedevops.yukon.export.ProbeKind
import io.github.lukedevops.yukon.registry.ProbeDispatch
import io.github.lukedevops.yukon.registry.ProbeMeta
import io.github.lukedevops.yukon.registry.ProbeRegistry
import net.bytebuddy.agent.builder.AgentBuilder
import net.bytebuddy.asm.Advice
import net.bytebuddy.description.method.MethodDescription
import net.bytebuddy.description.type.TypeDescription
import net.bytebuddy.dynamic.DynamicType
import net.bytebuddy.matcher.ElementMatcher
import net.bytebuddy.matcher.ElementMatchers.isAbstract
import net.bytebuddy.matcher.ElementMatchers.isBridge
import net.bytebuddy.matcher.ElementMatchers.isSynthetic
import net.bytebuddy.matcher.ElementMatchers.isTypeInitializer
import net.bytebuddy.matcher.ElementMatchers.nameStartsWith
import net.bytebuddy.matcher.ElementMatchers.not
import java.lang.instrument.Instrumentation

/**
 * Wires method-entry probes into every type matched by [AgentConfig.instrumentedPackagePrefixes]
 * (or every non-agent type when that list is empty). Each matched type is registered with
 * [ProbeRegistry] once, and its methods' dispatch keys point at the resulting array so
 * [MethodEntryAdvice] resolves straight to a counter on every call.
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
    fun install(instrumentation: Instrumentation) {
        AgentBuilder
            .Default()
            .type(typeMatcher())
            .transform { builder, typeDescription, _, _, _ -> instrument(builder, typeDescription) }
            .installOn(instrumentation)
    }

    private fun typeMatcher(): ElementMatcher.Junction<TypeDescription> {
        val excluded: ElementMatcher.Junction<TypeDescription> =
            not(isSynthetic<TypeDescription>()).and(not(nameStartsWith<TypeDescription>(AGENT_PACKAGE_PREFIX)))
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
    ): DynamicType.Builder<*> {
        val methods = typeDescription.declaredMethods.filter(methodMatcher())
        if (methods.isEmpty()) return builder

        val probes = methods.map { ProbeMeta(ProbeKind.METHOD, it.internalName, it.descriptor, line = -1) }
        val layoutHash = ProbeLayoutHash.of(methods.map { it.internalName + it.descriptor })
        val counts = registry.register(typeDescription.name, layoutHash, probes)

        methods.forEachIndexed { index, method ->
            ProbeDispatch.INSTANCE.register(originKey(typeDescription, method), counts, index)
        }

        return builder.visit(Advice.to(MethodEntryAdvice::class.java).on(methodMatcher()))
    }

    private fun methodMatcher(): ElementMatcher.Junction<MethodDescription> =
        not(isAbstract<MethodDescription>())
            .and(not(isSynthetic()))
            .and(not(isBridge()))
            .and(not(isTypeInitializer()))

    private fun originKey(
        type: TypeDescription,
        method: MethodDescription,
    ): String = "${type.name}:${method.internalName}:${method.descriptor}"

    private companion object {
        const val AGENT_PACKAGE_PREFIX = "io.github.lukedevops.yukon."
    }
}
