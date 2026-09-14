package io.github.lukedevops.yukon.endpoints.jdkhttpserver;

import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpHandler;
import io.github.lukedevops.yukon.bootstrap.YukonEndpoints;
import net.bytebuddy.asm.Advice;

/**
 * Woven onto both overloads of {@code ServerImpl.createContext}, reporting the new context to the
 * endpoint seam as a registered endpoint. A context is a prefix match with no verb constraint, so
 * the verb is always {@code "*"} and the verbatim template is the context's own path exactly as
 * registered. The one-argument overload creates a context with no handler yet, reported here as a
 * null handler class; {@link SetHandlerAdvice} fills it in once one is attached.
 *
 * <p>The handler-class lookup is inlined here rather than shared with {@link FindContextAdvice}
 * through a helper class: {@code ServerImpl} loads on the bootstrap classloader, an
 * {@link net.bytebuddy.asm.Advice} class's own bytecode is inlined directly into it, but a
 * helper class it merely called into would not be. Such a call would fail to resolve from the
 * bootstrap loader with a {@link NoClassDefFoundError}, since this module's own classes live on
 * the system loader.
 */
public class CreateContextAdvice {
    private static final String MODULE = "jdk-httpserver";

    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void onExit(@Advice.Return HttpContext context) {
        try {
            HttpHandler handler = context.getHandler();
            String handlerClass = handler == null ? null : handler.getClass().getName();
            YukonEndpoints.register(MODULE, context, "*", context.getPath(), null, handlerClass, null, null);
        } catch (Throwable t) {
            YukonEndpoints.moduleFailed(MODULE, t);
        }
    }
}
