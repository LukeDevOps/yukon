package io.github.lukedevops.yukon.endpoints.fake;

import com.example.framework.FakeRouter;
import io.github.lukedevops.yukon.bootstrap.YukonEndpoints;
import net.bytebuddy.asm.Advice;

/**
 * Woven onto {@code FakeRouter.invoke}, counting a dispatch. Looks the route up by the same key
 * {@link FakeRouterAddRouteAdvice} registers it under; a route {@code addQuietly} added directly,
 * skipping {@link FakeRouterAddRouteAdvice}, is discovered here instead.
 */
public class FakeRouterInvokeAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void onEnter(@Advice.Argument(0) FakeRouter.Route route) {
        try {
            String key = route.verb + " " + route.path;
            Object entry = YukonEndpoints.lookup("fake-router", key);
            if (entry == null) {
                entry =
                        YukonEndpoints.recordDispatch(
                                "fake-router", key, route.verb, route.path, null, route.handler.getClass().getName());
            }
            YukonEndpoints.hit(entry);
        } catch (Throwable t) {
            YukonEndpoints.moduleFailed("fake-router", t);
        }
    }
}
