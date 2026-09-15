package io.github.lukedevops.yukon.instrumentation.endpoints.ktor3

import io.github.lukedevops.yukon.instrumentation.endpoints.api.AdviceBinder
import io.github.lukedevops.yukon.instrumentation.endpoints.api.EndpointModule
import net.bytebuddy.description.method.MethodDescription
import net.bytebuddy.description.type.TypeDescription
import net.bytebuddy.dynamic.DynamicType
import net.bytebuddy.matcher.ElementMatcher
import net.bytebuddy.matcher.ElementMatchers.named
import net.bytebuddy.matcher.ElementMatchers.namedOneOf
import net.bytebuddy.matcher.ElementMatchers.takesArguments

private const val ROUTING_NODE = "io.ktor.server.routing.RoutingNode"
private const val ROUTING_ROOT = "io.ktor.server.routing.RoutingRoot"
private const val ADVICE_PACKAGE = "io.github.lukedevops.yukon.endpoints.ktor3"

/**
 * Endpoint module for Ktor 3 (`ktor-3`), covering the `io.ktor:ktor-server-core-jvm` 3.x line.
 *
 * `Route` became an interface in Ktor 3, with `RoutingNode` as the concrete class carrying
 * `parent`, `selector`, and `handle`, and `RoutingRoot` as the root that owns `executeResult`.
 * `RoutingNode.handle` is the registration hook: it runs once per handler lambda as application
 * code builds the routing tree, with the handler and the node it was attached to both in hand.
 * `RoutingRoot.executeResult` is the dispatch point: it runs once per matched request, before that
 * node's own handler pipeline runs, with the winning node already resolved. Both are advised
 * instead of the public `RoutingCallStarted` event, whose subscription would need advice to
 * implement a Kotlin function type; see [io.github.lukedevops.yukon.endpoints.ktor3.HandleAdvice]
 * for the full reasoning.
 *
 * The node object itself is the identity this module keys endpoints under, since it is exactly
 * what both `handle` and `executeResult` hand over. A route selector that contributes nothing to a
 * path (headers, content type, query parameters, host, `AndRouteSelector`/`OrRouteSelector`) means
 * such a route merges with any sibling sharing the same path, an accepted v1 simplification stated
 * in `CLAUDE.md`'s "Endpoints" section.
 */
class Ktor3Module : EndpointModule {
    override val name: String = "ktor-3"

    override fun typeMatcher(): ElementMatcher<in TypeDescription> = namedOneOf(ROUTING_NODE, ROUTING_ROOT)

    override fun transform(
        builder: DynamicType.Builder<*>,
        typeDescription: TypeDescription,
        advice: AdviceBinder,
        classLoader: ClassLoader?,
    ): DynamicType.Builder<*> =
        when (typeDescription.name) {
            ROUTING_NODE -> {
                builder.visit(
                    advice
                        .bind("$ADVICE_PACKAGE.HandleAdvice")
                        .on(named<MethodDescription>("handle").and(takesArguments(1))),
                )
            }

            ROUTING_ROOT -> {
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
