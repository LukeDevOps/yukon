package io.github.lukedevops.yukon.endpoints.springwebmvc;

import io.github.lukedevops.yukon.bootstrap.YukonEndpoints;
import java.lang.reflect.Method;
import java.util.List;
import net.bytebuddy.asm.Advice;
import org.springframework.web.servlet.function.RouterFunctions;
import org.springframework.web.servlet.function.ServerRequest;
import org.springframework.web.util.pattern.PathPattern;

/**
 * Woven onto {@code RouterFunctionMapping.setAttributes(HttpServletRequest, ServerRequest,
 * HandlerFunction)}, the point functional routing has already matched a request to a route and is
 * about to hand it to the winning handler. It runs once per matched request, before the handler
 * runs.
 *
 * <p>The matched pattern lives under {@link RouterFunctions#MATCHING_PATTERN_ATTRIBUTE} in {@code
 * request.attributes()} at this point; {@code setAttributes} itself only removes that attribute
 * after this advice's own entry point has already run. Its value is a {@code PathPattern} on
 * every version this advice was checked against, but is read defensively as either a {@code
 * PathPattern} or a plain {@code String}, since nothing in the surrounding API contract promises
 * the attribute stays a {@code PathPattern} forever.
 *
 * <p>The handler join names the method functional routing actually invokes: a {@code
 * HandlerFunction} whose class is not hidden reports {@code handle} and the class that declares
 * it, read through {@code getMethod("handle", ServerRequest.class)} since every implementation
 * must override that method publicly to satisfy the interface. A hidden class, generated for a
 * Kotlin SAM-converted lambda (or a Java lambda) through {@code invokedynamic}, has no stable name
 * across runs, so its class, method, and descriptor are all reported as null instead. A route the
 * declare walk registered already carries this join from registration, so the reflection here
 * runs only for a route discovered at dispatch, never on the per-request path, which stays one
 * attribute read and a lookup.
 *
 * <p>The dispatch key built here, {@code List.of(handlerFunction, pattern, verb)}, must match
 * {@code SpringWebMvcModule.declare}'s own key exactly, the same value-equality convention the
 * annotation-mapped side of this module already uses for {@link HandleMatchAdvice} and {@link
 * RegisterHandlerMethodAdvice}. A second lookup with verb {@code "*"} covers a route declared as
 * unconstrained; either miss means a route this module has not seen register, discovered here
 * instead of dropped.
 *
 * <p>This method is deliberately one flat body with no private helper method of its own; see
 * {@link RegisterHandlerMethodAdvice}'s Javadoc for why.
 */
public class SetAttributesAdvice {
    private static final String MODULE = "spring-webmvc";
    private static final String HANDLE_DESCRIPTOR =
            "(Lorg/springframework/web/servlet/function/ServerRequest;)Lorg/springframework/web/servlet/function/ServerResponse;";

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void onEnter(
            @Advice.Argument(1) ServerRequest request,
            @Advice.Argument(2) Object handlerFunction) {
        try {
            Object patternValue = request.attributes().get(RouterFunctions.MATCHING_PATTERN_ATTRIBUTE);
            if (patternValue == null) return;
            String pattern =
                    patternValue instanceof String
                            ? (String) patternValue
                            : ((PathPattern) patternValue).getPatternString();

            String verb = request.method().name();

            List<Object> key = List.of(handlerFunction, pattern, verb);
            Object entry = YukonEndpoints.lookup(MODULE, key);
            if (entry == null) {
                entry = YukonEndpoints.lookup(MODULE, List.of(handlerFunction, pattern, "*"));
            }
            if (entry == null) {
                String handlerClass = null;
                String handlerMethod = null;
                String handlerDescriptor = null;
                Class<?> handlerType = handlerFunction.getClass();
                if (!handlerType.isHidden()) {
                    try {
                        Method method = handlerType.getMethod("handle", ServerRequest.class);
                        handlerClass = method.getDeclaringClass().getName();
                        handlerMethod = "handle";
                        handlerDescriptor = HANDLE_DESCRIPTOR;
                    } catch (NoSuchMethodException e) {
                        handlerClass = handlerType.getName();
                    }
                }
                entry = YukonEndpoints.recordDispatch(MODULE, key, verb, pattern, null, handlerClass);
                if (entry != null) {
                    YukonEndpoints.attachHandler(MODULE, entry, handlerClass, handlerMethod, handlerDescriptor);
                }
            }
            YukonEndpoints.hit(entry);
        } catch (Throwable t) {
            YukonEndpoints.moduleFailed(MODULE, t);
        }
    }
}
