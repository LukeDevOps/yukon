package io.github.lukedevops.yukon.endpoints.springwebmvc;

import io.github.lukedevops.yukon.bootstrap.YukonEndpoints;
import java.util.List;
import net.bytebuddy.asm.Advice;
import org.springframework.util.ClassUtils;

/**
 * Woven onto {@code AbstractUrlHandlerMapping.buildPathExposingHandler}, the dispatch point shared
 * by every URL-mapped handler mapping. It runs once per matched request, with the matched pattern
 * and the raw handler both already resolved, before the handler runs. {@code lookupHandler} is the
 * only caller that knows the pattern, but it takes an {@code HttpServletRequest} whose overloads
 * differ by servlet-API version, so this method is hooked instead.
 *
 * <p>The dispatch key built here, {@code List.of(bestMatchingPattern, "*")}, must match {@link
 * RegisterUrlHandlerAdvice} exactly, the same value-equality convention {@link HandleMatchAdvice}
 * and {@link RegisterHandlerMethodAdvice} already use for this module's annotation-mapped side. A
 * lookup miss means a handler mapped only through {@code registerHandler(String[], String)} with
 * lazy resolution, or a handler this module has otherwise not seen register; either way, the
 * dispatch is still recorded rather than dropped.
 *
 * <p>This method is deliberately one flat body with no private helper method of its own; see
 * {@link RegisterHandlerMethodAdvice}'s Javadoc for why.
 */
public class BuildPathExposingHandlerAdvice {
    private static final String MODULE = "spring-webmvc";

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void onEnter(
            @Advice.Argument(0) Object rawHandler,
            @Advice.Argument(1) String bestMatchingPattern) {
        try {
            List<String> key = List.of(bestMatchingPattern, "*");
            Object entry = YukonEndpoints.lookup(MODULE, key);
            if (entry == null) {
                String handlerClassName =
                        (rawHandler == null || rawHandler instanceof String)
                                ? null
                                : ClassUtils.getUserClass(rawHandler.getClass()).getName();
                entry = YukonEndpoints.recordDispatch(MODULE, key, "*", bestMatchingPattern, null, handlerClassName);
            }
            YukonEndpoints.hit(entry);
        } catch (Throwable t) {
            YukonEndpoints.moduleFailed(MODULE, t);
        }
    }
}
