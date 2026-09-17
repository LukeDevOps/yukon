package io.github.lukedevops.yukon.endpoints.jdkhttpserver;

import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import io.github.lukedevops.yukon.bootstrap.YukonEndpoints;
import java.lang.reflect.Method;
import net.bytebuddy.asm.Advice;

/**
 * Woven onto {@code HttpContextImpl.setHandler}, attaching a handler class to a context that
 * {@link CreateContextAdvice} registered without one, through the one-argument
 * {@code createContext(String)} overload.
 *
 * <p>The handler join follows the same rule {@link CreateContextAdvice} applies: a non-hidden
 * handler class reports the {@code handle} method and the class that declares it, and a hidden
 * class (a Java or Kotlin SAM lambda) reports null for all three fields. A null class is still
 * safe to attach: {@link YukonEndpoints#attachHandler} is a no-op for a null class, so it can never
 * erase a real join a later {@code setHandler} call replaces with a lambda.
 */
public class SetHandlerAdvice {
    private static final String MODULE = "jdk-httpserver";
    private static final String HANDLE_DESCRIPTOR = "(Lcom/sun/net/httpserver/HttpExchange;)V";

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void onEnter(@Advice.This HttpContext context, @Advice.Argument(0) HttpHandler handler) {
        try {
            if (handler == null) return;
            Object entry = YukonEndpoints.lookup(MODULE, context);
            if (entry == null) return;
            String handlerClass = null;
            String handlerMethod = null;
            String handlerDescriptor = null;
            Class<?> handlerType = handler.getClass();
            if (!handlerType.isHidden()) {
                try {
                    Method method = handlerType.getMethod("handle", HttpExchange.class);
                    handlerClass = method.getDeclaringClass().getName();
                    handlerMethod = "handle";
                    handlerDescriptor = HANDLE_DESCRIPTOR;
                } catch (NoSuchMethodException e) {
                    handlerClass = handlerType.getName();
                }
            }
            YukonEndpoints.attachHandler(MODULE, entry, handlerClass, handlerMethod, handlerDescriptor);
        } catch (Throwable t) {
            YukonEndpoints.moduleFailed(MODULE, t);
        }
    }
}
