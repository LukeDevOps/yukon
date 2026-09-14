package io.github.lukedevops.yukon.endpoints.jdkhttpserver;

import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpHandler;
import io.github.lukedevops.yukon.bootstrap.YukonEndpoints;
import net.bytebuddy.asm.Advice;

/**
 * Woven onto {@code HttpContextImpl.setHandler}, attaching a handler class to a context that
 * {@link CreateContextAdvice} registered without one, through the one-argument
 * {@code createContext(String)} overload.
 */
public class SetHandlerAdvice {
    private static final String MODULE = "jdk-httpserver";

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void onEnter(@Advice.This HttpContext context, @Advice.Argument(0) HttpHandler handler) {
        try {
            if (handler == null) return;
            Object entry = YukonEndpoints.lookup(MODULE, context);
            if (entry != null) {
                YukonEndpoints.attachHandler(MODULE, entry, handler.getClass().getName(), null, null);
            }
        } catch (Throwable t) {
            YukonEndpoints.moduleFailed(MODULE, t);
        }
    }
}
