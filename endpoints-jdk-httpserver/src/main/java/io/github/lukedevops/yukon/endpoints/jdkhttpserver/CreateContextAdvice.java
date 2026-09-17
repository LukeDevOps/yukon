package io.github.lukedevops.yukon.endpoints.jdkhttpserver;

import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import io.github.lukedevops.yukon.bootstrap.YukonEndpoints;
import java.lang.reflect.Method;
import net.bytebuddy.asm.Advice;

/**
 * Woven onto both overloads of {@code ServerImpl.createContext}, reporting the new context to the
 * endpoint seam as a registered endpoint. A context is a prefix match with no verb constraint, so
 * the verb is always {@code "*"} and the verbatim template is the context's own path exactly as
 * registered. The one-argument overload creates a context with no handler yet, reported here as a
 * null handler class; {@link SetHandlerAdvice} fills it in once one is attached.
 *
 * <p>When a handler is present and its class is not hidden ({@link Class#isHidden()} false), the
 * handler join names the method the framework actually invokes, {@code handle}, and the class that
 * declares it, which is the handler's own class for an override and a shared base class for a
 * subclass that inherits {@code handle}. A hidden class, generated for a Java or Kotlin SAM lambda
 * through {@code invokedynamic}, has no stable name across runs, so its class, method, and
 * descriptor are all reported as null instead.
 *
 * <p>The handler-class lookup is inlined here rather than shared with {@link SetHandlerAdvice} and
 * {@link FindContextAdvice} through a helper class: {@code ServerImpl} loads on the bootstrap
 * classloader, an {@link net.bytebuddy.asm.Advice} class's own bytecode is inlined directly into
 * it, but a helper class it merely called into would not be. Such a call would fail to resolve
 * from the bootstrap loader with a {@link NoClassDefFoundError}, since this module's own classes
 * live on the system loader.
 */
public class CreateContextAdvice {
    private static final String MODULE = "jdk-httpserver";
    private static final String HANDLE_DESCRIPTOR = "(Lcom/sun/net/httpserver/HttpExchange;)V";

    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void onExit(@Advice.Return HttpContext context) {
        try {
            HttpHandler handler = context.getHandler();
            String handlerClass = null;
            String handlerMethod = null;
            String handlerDescriptor = null;
            if (handler != null) {
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
            }
            YukonEndpoints.register(
                    MODULE, context, "*", context.getPath(), null, handlerClass, handlerMethod, handlerDescriptor);
        } catch (Throwable t) {
            YukonEndpoints.moduleFailed(MODULE, t);
        }
    }
}
