package io.github.lukedevops.yukon.endpoints.fake;

import io.github.lukedevops.yukon.bootstrap.YukonEndpoints;
import net.bytebuddy.asm.Advice;

/**
 * Woven onto {@code FakeRouter.publishRoutes}, handing the router itself to the endpoint seam's
 * declare path, the fixture's stand-in for a framework whose routes are only readable by walking
 * an object it builds internally, such as Spring's {@code RouterFunction}.
 */
public class FakeRouterPublishAdvice {

    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void onExit(@Advice.This Object router) {
        try {
            YukonEndpoints.declare("fake-router", router);
        } catch (Throwable t) {
            YukonEndpoints.moduleFailed("fake-router", t);
        }
    }
}
