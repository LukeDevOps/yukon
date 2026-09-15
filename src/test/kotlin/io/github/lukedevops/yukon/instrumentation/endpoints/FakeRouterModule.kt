package io.github.lukedevops.yukon.instrumentation.endpoints

import io.github.lukedevops.yukon.bootstrap.YukonEndpoints
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
        classLoader: ClassLoader?,
    ): DynamicType.Builder<*> =
        builder
            .visit(advice.bind("io.github.lukedevops.yukon.endpoints.fake.FakeRouterAddRouteAdvice").on(named("addRoute")))
            .visit(advice.bind("io.github.lukedevops.yukon.endpoints.fake.FakeRouterInvokeAdvice").on(named("invoke")))
            .visit(advice.bind("io.github.lukedevops.yukon.endpoints.fake.FakeRouterPublishAdvice").on(named("publishRoutes")))

    /**
     * Walks [frameworkObject] (a [com.example.framework.FakeRouter]) reflectively, since its own
     * type is not visible from this module's classloader, and registers every route it holds
     * through [YukonEndpoints.register]. Mirrors how a real module such as `SpringWebMvcModule`
     * walks a `RouterFunction` it cannot reference by type either.
     */
    override fun declare(frameworkObject: Any) {
        val router = frameworkObject.javaClass

        @Suppress("UNCHECKED_CAST")
        val routes = router.getMethod("routes").invoke(frameworkObject) as List<Any>
        for (route in routes) {
            val routeClass = route.javaClass
            val verb = routeClass.getField("verb").get(route) as String
            val path = routeClass.getField("path").get(route) as String
            val handler = routeClass.getField("handler").get(route)
            YukonEndpoints.register("fake-router", "$verb $path", verb, path, null, handler.javaClass.name, null, null)
        }
    }
}
