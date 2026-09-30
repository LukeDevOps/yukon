package dev.otherlode.endpoints.jdkhttpserver;

import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import dev.otherlode.bootstrap.OtherlodeEndpoints;
import java.lang.reflect.Method;
import net.bytebuddy.asm.Advice;

/**
 * Woven onto the two-argument {@code ContextList.findContext(String, String)}, the one call the
 * request path makes to match a request to its context. The dispatch key is the returned {@link
 * HttpContext} object itself, never a string rebuilt per request. A null return is the
 * framework's own 404 path and counts nothing. A key {@link OtherlodeEndpoints#lookup} does not
 * resolve means no registration hook saw this context, so it is recorded here as discovered by
 * dispatch instead.
 *
 * <p>A context discovered this way resolves its handler join the same way {@link
 * CreateContextAdvice} does: a non-hidden handler class reports {@code handle} and the class that
 * declares it. A hidden class reports the method its lambda calls, as {@link
 * OtherlodeEndpoints#lambdaImplementation} recorded it, or null for all three fields when nothing was
 * recorded. {@code recordDispatch} only
 * takes a class, so the method and descriptor are attached separately, through {@link
 * OtherlodeEndpoints#attachHandler}, once {@code recordDispatch} has resolved an entry.
 */
public class FindContextAdvice {
    private static final String MODULE = "jdk-httpserver";
    private static final String HANDLE_DESCRIPTOR = "(Lcom/sun/net/httpserver/HttpExchange;)V";

    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void onExit(@Advice.Return HttpContext context) {
        try {
            if (context == null) return;
            Object entry = OtherlodeEndpoints.lookup(MODULE, context);
            if (entry == null) {
                HttpHandler handler = context.getHandler();
                String handlerClass = null;
                String handlerMethod = null;
                String handlerDescriptor = null;
                if (handler != null) {
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
                }
                entry = OtherlodeEndpoints.recordDispatch(MODULE, context, "*", context.getPath(), null, handlerClass);
                if (entry != null) {
                    OtherlodeEndpoints.attachHandler(MODULE, entry, handlerClass, handlerMethod, handlerDescriptor);
                }
            }
            OtherlodeEndpoints.hit(entry);
        } catch (Throwable t) {
            OtherlodeEndpoints.moduleFailed(MODULE, t);
        }
    }
}
