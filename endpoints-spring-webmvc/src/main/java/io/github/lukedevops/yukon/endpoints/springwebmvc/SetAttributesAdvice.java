package io.github.lukedevops.yukon.endpoints.springwebmvc;

import io.github.lukedevops.yukon.bootstrap.YukonEndpoints;
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
                String handlerClassName = handlerFunction.getClass().isHidden() ? null : handlerFunction.getClass().getName();
                entry = YukonEndpoints.recordDispatch(MODULE, key, verb, pattern, null, handlerClassName);
            }
            YukonEndpoints.hit(entry);
        } catch (Throwable t) {
            YukonEndpoints.moduleFailed(MODULE, t);
        }
    }
}
