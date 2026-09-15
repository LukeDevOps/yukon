package io.github.lukedevops.yukon.instrumentation.endpoints.ktor2

import io.github.lukedevops.yukon.instrumentation.endpoints.api.AdviceBinder
import io.github.lukedevops.yukon.instrumentation.endpoints.api.EndpointModule
import net.bytebuddy.description.method.MethodDescription
import net.bytebuddy.description.type.TypeDescription
import net.bytebuddy.dynamic.DynamicType
import net.bytebuddy.matcher.ElementMatcher
import net.bytebuddy.matcher.ElementMatchers.named
import net.bytebuddy.matcher.ElementMatchers.namedOneOf
import net.bytebuddy.matcher.ElementMatchers.takesArguments

private const val ROUTE = "io.ktor.server.routing.Route"
private const val ROUTING = "io.ktor.server.routing.Routing"
private const val ADVICE_PACKAGE = "io.github.lukedevops.yukon.endpoints.ktor2"

/**
 * Endpoint module for Ktor 2 (`ktor-2`), covering the `io.ktor:ktor-server-core-jvm` 2.x line.
 *
 * `Route.handle` is the registration hook: it runs once per handler lambda as application code
 * builds the routing tree, with the handler and the route it was attached to both in hand.
 * `Routing.executeResult` is the dispatch point: it runs once per matched request, before that
 * route's own handler pipeline runs, with the winning route already resolved. Both are advised
 * instead of the public `RoutingCallStarted` event, whose subscription would need advice to
 * implement a Kotlin function type; see [io.github.lukedevops.yukon.endpoints.ktor2.HandleAdvice]
 * for the full reasoning.
 *
 * The route node object itself is the identity this module keys endpoints under, since it is
 * exactly what both `handle` and `executeResult` hand over. A route selector that contributes
 * nothing to a path (headers, content type, query parameters, host, `AndRouteSelector`/
 * `OrRouteSelector`) means such a route merges with any sibling sharing the same path, an accepted
 * v1 simplification stated in `CLAUDE.md`'s "Endpoints" section.
 */
class Ktor2Module : EndpointModule {
    override val name: String = "ktor-2"

    override fun typeMatcher(): ElementMatcher<in TypeDescription> = namedOneOf(ROUTE, ROUTING)

    override fun transform(
        builder: DynamicType.Builder<*>,
        typeDescription: TypeDescription,
        advice: AdviceBinder,
        classLoader: ClassLoader?,
    ): DynamicType.Builder<*> =
        when (typeDescription.name) {
            ROUTE -> {
                builder.visit(
                    advice
                        .bind("$ADVICE_PACKAGE.HandleAdvice")
                        .on(named<MethodDescription>("handle").and(takesArguments(1))),
                )
            }

            ROUTING -> {
                builder.visit(
                    advice
                        .bind("$ADVICE_PACKAGE.ExecuteResultAdvice")
                        .on(named<MethodDescription>("executeResult")),
                )
            }

            else -> {
                builder
            }
        }
}
