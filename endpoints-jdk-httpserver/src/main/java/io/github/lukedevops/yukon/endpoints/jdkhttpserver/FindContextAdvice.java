package io.github.lukedevops.yukon.endpoints.jdkhttpserver;

import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpHandler;
import io.github.lukedevops.yukon.bootstrap.YukonEndpoints;
import net.bytebuddy.asm.Advice;

/**
 * Woven onto the two-argument {@code ContextList.findContext(String, String)}, the one call the
 * request path makes to match a request to its context. The dispatch key is the returned {@link
 * HttpContext} object itself, never a string rebuilt per request. A null return is the
 * framework's own 404 path and counts nothing. A key {@link YukonEndpoints#lookup} does not
 * resolve means no registration hook saw this context, so it is recorded here as discovered by
 * dispatch instead.
 */
public class FindContextAdvice {
    private static final String MODULE = "jdk-httpserver";

    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void onExit(@Advice.Return HttpContext context) {
        try {
            if (context == null) return;
            Object entry = YukonEndpoints.lookup(MODULE, context);
            if (entry == null) {
                HttpHandler handler = context.getHandler();
                String handlerClass = handler == null ? null : handler.getClass().getName();
                entry = YukonEndpoints.recordDispatch(MODULE, context, "*", context.getPath(), null, handlerClass);
            }
            YukonEndpoints.hit(entry);
        } catch (Throwable t) {
            YukonEndpoints.moduleFailed(MODULE, t);
        }
    }
}
