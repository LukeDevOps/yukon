package io.github.lukedevops.yukon.instrumentation.endpoints.otelbridge

import io.github.lukedevops.yukon.instrumentation.endpoints.api.AdviceBinder
import io.github.lukedevops.yukon.instrumentation.endpoints.api.EndpointModule
import net.bytebuddy.description.method.MethodDescription
import net.bytebuddy.description.type.TypeDescription
import net.bytebuddy.dynamic.DynamicType
import net.bytebuddy.matcher.ElementMatcher
import net.bytebuddy.matcher.ElementMatchers.named
import net.bytebuddy.matcher.ElementMatchers.namedOneOf
import net.bytebuddy.matcher.ElementMatchers.takesArguments

/** The unshaded class name a plain `opentelemetry-instrumentation-api` dependency loads under. */
internal const val UNSHADED_EXTRACTOR_NAME = "io.opentelemetry.instrumentation.api.semconv.http.HttpServerAttributesExtractor"

/** The same class's name inside the OpenTelemetry Java agent's own shaded jar, verified against a real 2.31.1 agent jar. */
internal const val SHADED_EXTRACTOR_NAME =
    "io.opentelemetry.javaagent.shaded.instrumentation.api.semconv.http.HttpServerAttributesExtractor"

private const val ADVICE_CLASS = "io.github.lukedevops.yukon.endpoints.otelbridge.OnEndAdvice"

/**
 * Route bridge endpoint module. Counts the route OpenTelemetry's own HTTP server instrumentation
 * resolved for a request, for a framework this project has no dedicated endpoint module for. See
 * ADR 0019 for the full design, its rejected alternatives, and why it only ever discovers
 * endpoints by dispatch and never declares one.
 *
 * Matches both the unshaded `opentelemetry-instrumentation-api` artifact's own
 * `HttpServerAttributesExtractor` and the OpenTelemetry Java agent's relocated copy of the same
 * class, and weaves [OnEndAdvice] onto the exit of `onEnd`. [OnEndAdvice] is written once against
 * the unshaded names; for the relocated copy, [AdviceBinder] rewrites every `io/opentelemetry/...`
 * reference in its bytecode to the agent's `io/opentelemetry/javaagent/shaded/...` prefix before
 * binding, so one advice class serves both deployments.
 *
 * Off by default. See [io.github.lukedevops.yukon.config.AgentConfig.otelBridgeEnabled], which
 * [io.github.lukedevops.yukon.Agent] reads to decide whether this module is even discovered.
 */
class OtelBridgeModule : EndpointModule {
    override val name: String = "otel"

    override fun typeMatcher(): ElementMatcher<in TypeDescription> = namedOneOf(UNSHADED_EXTRACTOR_NAME, SHADED_EXTRACTOR_NAME)

    override fun transform(
        builder: DynamicType.Builder<*>,
        typeDescription: TypeDescription,
        advice: AdviceBinder,
        classLoader: ClassLoader?,
    ): DynamicType.Builder<*> {
        val onEnd = named<MethodDescription>("onEnd").and(takesArguments(5))
        return builder.visit(advice.bind(ADVICE_CLASS, remapPrefixesFor(typeDescription.name)).on(onEnd))
    }
}

/**
 * The `io/opentelemetry/...` internal-name prefix rewrite [OnEndAdvice] needs to weave onto
 * [typeName], keyed only on whether [typeName] is the shaded or the unshaded extractor name.
 * Empty for the unshaded name, since [OnEndAdvice] is already written against it directly.
 *
 * Prefix order matters only in that the longest must be tried first: `io/opentelemetry/api/`
 * must never swallow `io/opentelemetry/instrumentation/api/`. [AdviceBinder.bind] already sorts by
 * length itself, so the order these are written in here is for readability only.
 *
 * `internal` rather than `private` so [OtelBridgeModuleTest] can pin the exact rewrite this
 * module asks for, directly, without needing the real relocated OpenTelemetry javaagent jar on
 * the test classpath to prove `transform` chose the right prefixes for the shaded name.
 */
internal fun remapPrefixesFor(typeName: String): Map<String, String> =
    if (typeName == SHADED_EXTRACTOR_NAME) {
        mapOf(
            "io/opentelemetry/instrumentation/api/" to "io/opentelemetry/javaagent/shaded/instrumentation/api/",
            "io/opentelemetry/context/" to "io/opentelemetry/javaagent/shaded/io/opentelemetry/context/",
            "io/opentelemetry/api/" to "io/opentelemetry/javaagent/shaded/io/opentelemetry/api/",
        )
    } else {
        emptyMap()
    }
