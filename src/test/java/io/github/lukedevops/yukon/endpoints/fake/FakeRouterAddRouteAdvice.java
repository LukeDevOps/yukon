package io.github.lukedevops.yukon.endpoints.fake;

import io.github.lukedevops.yukon.bootstrap.YukonEndpoints;
import net.bytebuddy.asm.Advice;

/** Woven onto {@code FakeRouter.addRoute}, reporting a registered route to the endpoint seam. */
public class FakeRouterAddRouteAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void onEnter(
            @Advice.Argument(0) String verb,
            @Advice.Argument(1) String path,
            @Advice.Argument(2) Runnable handler) {
        YukonEndpoints.register("fake-router", verb + " " + path, verb, path, null, handler.getClass().getName(), null, null);
    }
}
