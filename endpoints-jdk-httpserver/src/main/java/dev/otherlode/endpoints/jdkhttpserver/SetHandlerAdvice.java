package dev.otherlode.endpoints.jdkhttpserver;

import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import dev.otherlode.bootstrap.OtherlodeEndpoints;
import java.lang.reflect.Method;
import net.bytebuddy.asm.Advice;

/**
 * Woven onto {@code HttpContextImpl.setHandler}, attaching a handler class to a context that
 * {@link CreateContextAdvice} registered without one, through the one-argument
 * {@code createContext(String)} overload.
 *
 * <p>The handler join follows the same rule {@link CreateContextAdvice} applies: a non-hidden
 * handler class reports the {@code handle} method and the class that declares it. A hidden class
 * (a Java or Kotlin lambda or method reference) reports the method its lambda calls, as {@link
 * OtherlodeEndpoints#lambdaImplementation} recorded it, or null for all three fields when nothing was
 * recorded. A null class is still safe to attach: {@link OtherlodeEndpoints#attachHandler} is a no-op
 * for a null class.
 *
 * <p>The advice runs on exit, and only on a normal return. The JDK refuses a second handler with
 * an {@code IllegalArgumentException} ("handler already set"), and a handler it refused must never
 * become the join.
 */
public class SetHandlerAdvice {
    private static final String MODULE = "jdk-httpserver";
    private static final String HANDLE_DESCRIPTOR = "(Lcom/sun/net/httpserver/HttpExchange;)V";

    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void onExit(@Advice.This HttpContext context, @Advice.Argument(0) HttpHandler handler) {
        try {
            if (handler == null) return;
            Object entry = OtherlodeEndpoints.lookup(MODULE, context);
            if (entry == null) return;
            String handlerClass = null;
            String handlerMethod = null;
            String handlerDescriptor = null;
            Class<?> handlerType = handler.getClass();
            if (handlerType.isHidden()) {
                OtherlodeEndpoints.LambdaImplementation implementation = OtherlodeEndpoints.lambdaImplementation(handlerType);
                if (implementation != null) {
                    handlerClass = implementation.className;
                    handlerMethod = implementation.methodName;
                    handlerDescriptor = implementation.descriptor;
                }
            } else {
                try {
                    Method method = handlerType.getMethod("handle", HttpExchange.class);
                    handlerClass = method.getDeclaringClass().getName();
                    handlerMethod = "handle";
                    handlerDescriptor = HANDLE_DESCRIPTOR;
                } catch (NoSuchMethodException e) {
                    handlerClass = handlerType.getName();
                }
            }
            OtherlodeEndpoints.attachHandler(MODULE, entry, handlerClass, handlerMethod, handlerDescriptor);
        } catch (Throwable t) {
            OtherlodeEndpoints.moduleFailed(MODULE, t);
        }
    }
}
