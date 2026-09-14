package io.github.lukedevops.yukon.instrumentation.endpoints

import io.github.lukedevops.yukon.instrumentation.endpoints.api.AdviceBinder
import io.github.lukedevops.yukon.instrumentation.endpoints.api.EndpointModule
import net.bytebuddy.description.type.TypeDescription
import net.bytebuddy.dynamic.DynamicType
import net.bytebuddy.matcher.ElementMatcher
import net.bytebuddy.matcher.ElementMatchers.named

/**
 * Proves [EndpointInstrumentation] end to end against a fixture "framework",
 * [com.example.framework.FakeRouter], instead of a real one. Registered under
 * `META-INF/services/io.github.lukedevops.yukon.instrumentation.endpoints.api.EndpointModule`
 * so [EndpointModules.discover] finds it for real.
 */
class FakeRouterModule : EndpointModule {
    override val name: String = "fake-router"

    override val bootModulesNeedingSeamRead: Set<String> = setOf("java.net.http")

    override fun typeMatcher(): ElementMatcher<in TypeDescription> = named("com.example.framework.FakeRouter")

    override fun transform(
        builder: DynamicType.Builder<*>,
        typeDescription: TypeDescription,
        advice: AdviceBinder,
    ): DynamicType.Builder<*> =
        builder
            .visit(advice.bind("io.github.lukedevops.yukon.endpoints.fake.FakeRouterAddRouteAdvice").on(named("addRoute")))
            .visit(advice.bind("io.github.lukedevops.yukon.endpoints.fake.FakeRouterInvokeAdvice").on(named("invoke")))
}
