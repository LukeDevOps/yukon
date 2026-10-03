package dev.otherlode.endpoints.springwebmvc;

import dev.otherlode.bootstrap.OtherlodeEndpoints;
import java.util.List;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import org.springframework.util.ClassUtils;
import org.springframework.web.servlet.handler.AbstractUrlHandlerMapping;

/**
 * Woven onto {@code AbstractUrlHandlerMapping.buildPathExposingHandler}, the dispatch point shared
 * by every URL-mapped handler mapping. It runs once per matched request, with the matched pattern
 * and the raw handler both already resolved, before the handler runs. {@code lookupHandler} is the
 * only caller that knows the pattern, but it takes an {@code HttpServletRequest} whose overloads
 * differ by servlet-API version, so this method is hooked instead.
 *
 * <p>The dispatch key built here, {@code List.of(bestMatchingPattern, "*")}, must match {@link
 * RegisterUrlHandlerAdvice} exactly, the same value-equality convention {@link HandleMatchAdvice}
 * and {@link RegisterHandlerMethodAdvice} use for this module's annotation-mapped side. A lookup
 * miss means a handler this module has not seen register, and the dispatch is still recorded
 * rather than dropped.
 *
 * <p>When no registered path matches, Spring falls back to the root handler for {@code "/"} or the
 * {@code "/*"} handler for anything else, and passes the raw lookup path in place of a pattern.
 * Recording that path would add one endpoint per distinct URL and leave {@code "/*"} uncounted. The
 * fallback is the only caller that passes null URI variables for a path that is not a registered
 * key (a direct match passes the key itself), so a miss with null variables counts against
 * {@code "/*"}, unless the path is {@code "/"} and the root handler served it.
 *
 * <p>This method is deliberately one flat body with no private helper method of its own; see
 * {@link RegisterHandlerMethodAdvice}'s Javadoc for why.
 */
public class BuildPathExposingHandlerAdvice {
    private static final String MODULE = "spring-webmvc";

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void onEnter(
            @Advice.This Object mapping,
            @Advice.Argument(0) Object rawHandler,
            @Advice.Argument(1) String bestMatchingPattern,
            @Advice.Argument(3) Map<?, ?> uriVariables) {
        try {
            String pattern = bestMatchingPattern;
            List<String> key = List.of(pattern, "*");
            Object entry = OtherlodeEndpoints.lookup(MODULE, key);
            if (entry == null
                    && uriVariables == null
                    && !("/".equals(pattern) && rawHandler == ((AbstractUrlHandlerMapping) mapping).getRootHandler())) {
                pattern = "/*";
                key = List.of(pattern, "*");
                entry = OtherlodeEndpoints.lookup(MODULE, key);
            }
            if (entry == null) {
                String handlerClassName =
                        (rawHandler == null || rawHandler instanceof String)
                                ? null
                                : ClassUtils.getUserClass(rawHandler.getClass()).getName();
                entry = OtherlodeEndpoints.recordDispatch(MODULE, key, "*", pattern, null, handlerClassName);
            }
            OtherlodeEndpoints.hit(entry);
        } catch (Throwable t) {
            OtherlodeEndpoints.moduleFailed(MODULE, t);
        }
    }
}
