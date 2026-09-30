package dev.otherlode.instrumentation.endpoints

import dev.otherlode.instrumentation.endpoints.api.AdviceBinder
import dev.otherlode.instrumentation.endpoints.api.EndpointModule
import net.bytebuddy.description.type.TypeDescription
import net.bytebuddy.dynamic.DynamicType
import net.bytebuddy.matcher.ElementMatcher
import net.bytebuddy.matcher.ElementMatchers.named

/**
 * Simulates a module whose advice does not match the framework version present, proving
 * [EndpointInstrumentation] disables the module through [dev.otherlode.bootstrap.OtherlodeEndpoints.moduleFailed]
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
        classLoader: ClassLoader?,
    ): DynamicType.Builder<*> =
        builder.visit(advice.bind("dev.otherlode.endpoints.fake.BrokenRouterInvokeAdvice").on(named("invoke")))
}
