package io.github.lukedevops.yukon.endpoints.fake;

import com.example.framework.BrokenRouter;
import io.github.lukedevops.yukon.bootstrap.YukonEndpoints;
import net.bytebuddy.asm.Advice;

/** Woven onto {@code BrokenRouter.invoke}, simulating a framework version its advice does not match. */
public class BrokenRouterInvokeAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void onEnter(@Advice.Argument(0) BrokenRouter.Route route) {
        try {
            throw new NoSuchMethodError("simulated framework mismatch");
        } catch (Throwable t) {
            YukonEndpoints.moduleFailed("broken-router", t);
        }
    }
}
