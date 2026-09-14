package io.github.lukedevops.yukon.instrumentation.endpoints

import io.github.lukedevops.yukon.instrumentation.endpoints.api.AdviceBinder
import io.github.lukedevops.yukon.instrumentation.endpoints.api.EndpointModule
import net.bytebuddy.description.type.TypeDescription
import net.bytebuddy.dynamic.DynamicType
import net.bytebuddy.matcher.ElementMatcher
import net.bytebuddy.matcher.ElementMatchers.named

/**
 * Simulates a module whose advice does not match the framework version present, proving
 * [EndpointInstrumentation] disables the module through [io.github.lukedevops.yukon.bootstrap.YukonEndpoints.moduleFailed]
 * instead of letting the failure reach the application. Not registered as a service: this module
 * is built directly by [EndpointInstrumentationTest] rather than discovered.
 */
class BrokenRouterModule : EndpointModule {
    override val name: String = "broken-router"

    override fun typeMatcher(): ElementMatcher<in TypeDescription> = named("com.example.framework.BrokenRouter")

    override fun transform(
        builder: DynamicType.Builder<*>,
        typeDescription: TypeDescription,
        advice: AdviceBinder,
    ): DynamicType.Builder<*> =
        builder.visit(advice.bind("io.github.lukedevops.yukon.endpoints.fake.BrokenRouterInvokeAdvice").on(named("invoke")))
}
