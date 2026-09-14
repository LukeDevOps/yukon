package io.github.lukedevops.yukon.endpoints.ktor2;

import io.github.lukedevops.yukon.bootstrap.YukonEndpoints;
import io.ktor.server.routing.HttpMethodRouteSelector;
import io.ktor.server.routing.PathSegmentConstantRouteSelector;
import io.ktor.server.routing.PathSegmentOptionalParameterRouteSelector;
import io.ktor.server.routing.PathSegmentParameterRouteSelector;
import io.ktor.server.routing.PathSegmentTailcardRouteSelector;
import io.ktor.server.routing.PathSegmentWildcardRouteSelector;
import io.ktor.server.routing.Route;
import io.ktor.server.routing.RouteSelector;
import java.util.ArrayDeque;
import java.util.Deque;
import net.bytebuddy.asm.Advice;

/**
 * Woven onto {@code Route.handle}, Ktor's own registration hook. It runs once per handler lambda
 * as application code builds the routing tree, with the handler object already in hand and the
 * route it was attached to available as {@code this}.
 *
 * <p>{@code Route.handle} is the registration hook this module uses instead of the public {@code
 * RoutingCallStarted} event, because {@code RoutingCallStarted} only fires at dispatch time and
 * subscribing to it would need advice to implement a Kotlin function type. {@code handlers} itself
 * is {@code internal} and out of reach from Java advice, but {@code handle(handler)} that appends
 * to it is public, so this advice binds there instead.
 *
 * <p>The route node itself, not a rebuilt key, is the identity this advice registers under: {@code
 * this} is exactly the object {@link ExecuteResultAdvice} receives as its own {@code route}
 * argument at dispatch time, so no key needs to be reconstructed or compared by value the way
 * Spring MVC's advice has to.
 *
 * <p>The walk from this route up to the root is duplicated in {@link ExecuteResultAdvice} rather
 * than shared: an advice class is inlined into the woven bytecode, not called into, so a helper
 * method on this class or a shared one would remain a real method call at the instrumented site,
 * needing the target classloader to resolve this advice class at runtime. Only path selectors
 * contribute a segment; anything else (headers, content type, query parameters, host, {@code
 * AndRouteSelector}/{@code OrRouteSelector}) contributes nothing, so a route built from more than
 * path and verb merges with any sibling that shares the same path, an accepted v1 simplification.
 *
 * <p>This method is deliberately one flat body with no private helper method of its own; see the
 * Spring MVC module's {@code RegisterHandlerMethodAdvice} Javadoc for why.
 */
public class HandleAdvice {
    private static final String MODULE = "ktor-2";

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void onEnter(@Advice.This Object node, @Advice.Argument(0) Object body) {
        try {
            Deque<String> segments = new ArrayDeque<>();
            String verb = null;
            Route current = (Route) node;
            while (current != null) {
                RouteSelector selector = current.getSelector();
                if (verb == null && selector instanceof HttpMethodRouteSelector) {
                    verb = ((HttpMethodRouteSelector) selector).getMethod().getValue();
                } else if (selector instanceof PathSegmentConstantRouteSelector) {
                    segments.addFirst(((PathSegmentConstantRouteSelector) selector).getValue());
                } else if (selector instanceof PathSegmentTailcardRouteSelector) {
                    PathSegmentTailcardRouteSelector tailcard = (PathSegmentTailcardRouteSelector) selector;
                    String prefix = tailcard.getPrefix() == null ? "" : tailcard.getPrefix();
                    segments.addFirst(prefix + "{" + tailcard.getName() + "...}");
                } else if (selector instanceof PathSegmentOptionalParameterRouteSelector) {
                    PathSegmentOptionalParameterRouteSelector optional = (PathSegmentOptionalParameterRouteSelector) selector;
                    String prefix = optional.getPrefix() == null ? "" : optional.getPrefix();
                    String suffix = optional.getSuffix() == null ? "" : optional.getSuffix();
                    segments.addFirst(prefix + "{" + optional.getName() + "?}" + suffix);
                } else if (selector instanceof PathSegmentParameterRouteSelector) {
                    PathSegmentParameterRouteSelector parameter = (PathSegmentParameterRouteSelector) selector;
                    String prefix = parameter.getPrefix() == null ? "" : parameter.getPrefix();
                    String suffix = parameter.getSuffix() == null ? "" : parameter.getSuffix();
                    segments.addFirst(prefix + "{" + parameter.getName() + "}" + suffix);
                } else if (selector instanceof PathSegmentWildcardRouteSelector) {
                    segments.addFirst("*");
                }
                current = current.getParent();
            }
            String verbName = verb == null ? "*" : verb;
            String template = "/" + String.join("/", segments);
            YukonEndpoints.register(
                    MODULE, node, verbName, template, null, body == null ? null : body.getClass().getName(), null, null);
        } catch (Throwable t) {
            YukonEndpoints.moduleFailed(MODULE, t);
        }
    }
}
