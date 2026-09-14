package io.github.lukedevops.yukon.endpoints.jaxrs;

import io.github.lukedevops.yukon.bootstrap.YukonEndpoints;
import net.bytebuddy.asm.Advice;

/**
 * Woven onto every JAX-RS resource method {@code JaxRsModule} found declared and registered at
 * transform time. It runs once per invocation, which for a JAX-RS resource method only happens
 * once the framework has already matched a request to it, so this is both the registration join's
 * counterpart and the endpoint's own dispatch point in one.
 *
 * <p>{@link Advice.Origin}'s pattern language treats every {@code #} as introducing a sort
 * descriptor ({@code #t} for the declaring type, {@code #m} for the method name, {@code #d} for
 * the descriptor); a literal {@code #} in the output needs its own {@code #} escaped as {@code \#},
 * per {@link Advice.Origin#value()}'s own Javadoc. The pattern below, written in Java source as
 * {@code "#t\\##m#d"}, is therefore the four-part sequence {@code #t}, escaped {@code \#}, {@code
 * #m}, {@code #d}, which renders to the declaring type's binary name, a literal {@code #}, the
 * method's own name, then its descriptor: exactly the key {@code JaxRsModule} builds and registers
 * under for the same method. Both sides compute the key from constants ByteBuddy already resolves
 * at weave time, so this parameter compiles to one {@code ldc} of a fixed string at the
 * instrumented call site; nothing is built or allocated on the request path.
 *
 * <p>A miss on {@link YukonEndpoints#lookup} means the transform-time registration for this exact
 * key did not survive: a resolver installed after this class transformed, or the endpoint seam's
 * bounded replay buffer overflowed. There is no framework path metadata to fall back on here, the
 * way {@code Ktor3Module}'s dispatch advice can walk a route node to rebuild one, since a JAX-RS
 * method carries no such object at invocation time. This method calls {@link
 * YukonEndpoints#recordDispatch} with the key itself standing in for the verbatim template and
 * verb {@code "*"} instead, so the hit is still counted rather than dropped, under a template that
 * is visibly not an ordinary route. This path cannot log anything useful either, since the seam
 * gives no way to log once per request without itself becoming a cost on every request.
 *
 * <p>This method is deliberately one flat body with no private helper method of its own; see the
 * Spring MVC module's {@code RegisterHandlerMethodAdvice} Javadoc for why.
 */
public class ResourceMethodAdvice {
    private static final String MODULE = "jaxrs";

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void onEnter(@Advice.Origin("#t\\##m#d") String key) {
        try {
            Object entry = YukonEndpoints.lookup(MODULE, key);
            if (entry == null) {
                entry = YukonEndpoints.recordDispatch(MODULE, key, "*", key, null, null);
            }
            YukonEndpoints.hit(entry);
        } catch (Throwable t) {
            YukonEndpoints.moduleFailed(MODULE, t);
        }
    }
}
