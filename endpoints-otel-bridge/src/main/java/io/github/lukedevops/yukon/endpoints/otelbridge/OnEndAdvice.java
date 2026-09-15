package io.github.lukedevops.yukon.endpoints.otelbridge;

import io.github.lukedevops.yukon.bootstrap.YukonEndpoints;
import io.opentelemetry.context.Context;
import io.opentelemetry.instrumentation.api.internal.HttpRouteState;
import java.util.List;
import net.bytebuddy.asm.Advice;

/**
 * Woven onto the exit of {@code HttpServerAttributesExtractor.onEnd}, the one point OpenTelemetry's
 * own HTTP server instrumentation calls once per server span, after every {@code
 * HttpServerRoute.update} source-priority call has already settled the final route. See ADR 0019
 * for why span end, not {@code HttpServerRoute.update} itself, is the read point: {@code update}
 * fires several times per request as higher-priority sources override lower ones, so no single
 * call to it knows the eventual winner.
 *
 * <p>Compiled against the unshaded {@code opentelemetry-instrumentation-api} artifact, verified at
 * its oldest supported 2.x release, 2.0.0. {@link
 * io.github.lukedevops.yukon.instrumentation.endpoints.otelbridge.OtelBridgeModule} binds this
 * same class a second way for the OpenTelemetry Java agent's own relocated copy of {@code
 * HttpServerAttributesExtractor}, with every {@code io/opentelemetry/...} reference in this
 * class's bytecode rewritten to that agent's {@code io/opentelemetry/javaagent/shaded/...} prefix
 * before binding, so one advice class serves both deployments.
 *
 * <p>A route this bridge reads for an identity another endpoint module already owns is left
 * alone: {@link YukonEndpoints#lookup} misses for a key no framework module ever bound, and {@link
 * YukonEndpoints#recordDispatchIfUnowned} then refuses to bind it either, since an entry for that
 * identity already exists under a different framework name. The request goes uncounted here,
 * exactly as ADR 0019 requires. A key {@code recordDispatchIfUnowned} has already refused stays
 * unbound, so the same identity repeats this one map miss and one refusal on every later request
 * for it; that repeated cost is small and accepted rather than adding a sentinel binding to skip
 * it.
 */
public class OnEndAdvice {
    private static final String MODULE = "otel";

    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void onExit(@Advice.Argument(1) Context context) {
        try {
            HttpRouteState state = HttpRouteState.fromContextOrNull(context);
            if (state == null) return;
            String route = state.getRoute();
            if (route == null) return;
            String method = state.getMethod();
            String verb = method == null ? "*" : method;
            List<String> key = List.of(verb, route);
            Object entry = YukonEndpoints.lookup(MODULE, key);
            if (entry == null) {
                entry = YukonEndpoints.recordDispatchIfUnowned(MODULE, key, verb, route, null, null);
            }
            YukonEndpoints.hit(entry);
        } catch (Throwable t) {
            YukonEndpoints.moduleFailed(MODULE, t);
        }
    }
}
