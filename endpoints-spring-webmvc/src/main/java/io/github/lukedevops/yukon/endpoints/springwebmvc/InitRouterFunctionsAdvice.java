package io.github.lukedevops.yukon.endpoints.springwebmvc;

import io.github.lukedevops.yukon.bootstrap.YukonEndpoints;
import net.bytebuddy.asm.Advice;

/**
 * Woven onto {@code RouterFunctionMapping.initRouterFunctions()}, the private method that fills
 * the private {@code routerFunction} field from every {@code RouterFunction} bean in the
 * application context. It runs once, the first time Spring needs the mapping's route table,
 * before any request is dispatched.
 *
 * <p>{@code routerFunction} is bound as a plain {@code Object}, never as {@code RouterFunction}:
 * this class carries no symbolic reference to that type at all. The object is handed unopened to
 * {@link YukonEndpoints#declare}, which routes it to {@code SpringWebMvcModule.declare}, the one
 * piece of this module's own code that knows how to walk it. A {@code RouterFunction} only
 * reveals its routes to a visitor it accepts, something advice itself cannot implement; see ADR
 * 0017 for why this needs a second seam call instead of reading registration arguments the way
 * {@link RegisterHandlerMethodAdvice} and {@link RegisterUrlHandlerAdvice} do.
 *
 * <p>This method is deliberately one flat body with no private helper method of its own; see
 * {@link RegisterHandlerMethodAdvice}'s Javadoc for why.
 */
public class InitRouterFunctionsAdvice {
    private static final String MODULE = "spring-webmvc";

    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void onExit(@Advice.FieldValue("routerFunction") Object routerFunction) {
        try {
            if (routerFunction != null) {
                YukonEndpoints.declare(MODULE, routerFunction);
            }
        } catch (Throwable t) {
            YukonEndpoints.moduleFailed(MODULE, t);
        }
    }
}
