package io.github.lukedevops.yukon.endpoints.ktor3;

import io.github.lukedevops.yukon.bootstrap.YukonEndpoints;
import io.ktor.server.routing.HttpMethodRouteSelector;
import io.ktor.server.routing.PathSegmentConstantRouteSelector;
import io.ktor.server.routing.PathSegmentOptionalParameterRouteSelector;
import io.ktor.server.routing.PathSegmentParameterRouteSelector;
import io.ktor.server.routing.PathSegmentTailcardRouteSelector;
import io.ktor.server.routing.PathSegmentWildcardRouteSelector;
import io.ktor.server.routing.RouteSelector;
import io.ktor.server.routing.RoutingNode;
import java.util.ArrayDeque;
import java.util.Deque;
import net.bytebuddy.asm.Advice;

/**
 * Woven onto {@code RoutingRoot.executeResult}, Ktor's own dispatch point. It runs once per
 * matched request, with the winning node already resolved, before that node's own handler
 * pipeline runs. {@code executeResult} is private and {@code suspend}, so on the JVM it takes a
 * trailing {@code Continuation} parameter; matching it by name alone and binding argument index 1
 * (the node, after the {@code PipelineContext} at index 0) is what {@link Advice.Argument} needs
 * here, since a {@code suspend} method's real parameter count is not what its Kotlin signature
 * shows.
 *
 * <p>{@link HandleAdvice} registers under the node's own identity, so the first lookup here
 * usually succeeds. A node this module never saw register, such as one whose {@code handle} ran
 * before this module installed, falls back to walking the same node up to the root inline and
 * recording it as discovered by dispatch, so the hit is never dropped. The walk is duplicated from
 * {@link HandleAdvice} rather than shared; see that class's Javadoc for why.
 *
 * <p>This method is deliberately one flat body with no private helper method of its own; see the
 * Spring MVC module's {@code RegisterHandlerMethodAdvice} Javadoc for why.
 */
public class ExecuteResultAdvice {
    private static final String MODULE = "ktor-3";

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void onEnter(@Advice.Argument(1) Object route) {
        try {
            Object entry = YukonEndpoints.lookup(MODULE, route);
            if (entry == null) {
                Deque<String> segments = new ArrayDeque<>();
                String verb = null;
                RoutingNode current = (RoutingNode) route;
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
                entry = YukonEndpoints.recordDispatch(MODULE, route, verbName, template, null, null);
            }
            YukonEndpoints.hit(entry);
        } catch (Throwable t) {
            YukonEndpoints.moduleFailed(MODULE, t);
        }
    }
}
