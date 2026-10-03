package dev.otherlode.endpoints.springwebmvc;

import dev.otherlode.bootstrap.OtherlodeEndpoints;
import java.util.Iterator;
import java.util.List;
import net.bytebuddy.asm.Advice;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.condition.PathPatternsRequestCondition;
import org.springframework.web.servlet.mvc.condition.PatternsRequestCondition;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;

/**
 * Woven onto {@code RequestMappingInfoHandlerMapping.handleMatch(RequestMappingInfo, String,
 * HttpServletRequest)}, Spring MVC's dispatch point. It runs once per matched request, with the
 * winning {@link RequestMappingInfo} narrowed to the patterns that matched, best match first, and
 * the matched verb, before the handler runs. Binding only {@link Advice.Argument} 0 keeps this class free of any {@code
 * javax}/{@code jakarta} servlet type, so one module covers every Spring Framework major version
 * this agent supports.
 *
 * <p>The {@link RequestMappingInfo} handed to this method is a new object built per request by
 * {@code getMatchingCondition}, never the one {@link RegisterHandlerMethodAdvice} saw at
 * registration, so it cannot serve as a dispatch key by identity. The same {@code
 * List.of(pattern, verb)} key is rebuilt here instead, from the same two accessors {@link
 * RegisterHandlerMethodAdvice} used, so both sides agree by value. This allocates one small list
 * per request, accepted the same way {@link RegisterHandlerMethodAdvice} accepts its own
 * per-registration allocation, since a request already allocates far more than this to reach here.
 *
 * <p>This method is deliberately one flat body with no private helper method of its own; see
 * {@link RegisterHandlerMethodAdvice}'s Javadoc for why.
 */
public class HandleMatchAdvice {
    private static final String MODULE = "spring-webmvc";

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void onEnter(@Advice.Argument(0) RequestMappingInfo info) {
        try {
            // The best match comes first in the condition's own sorted set. getPatternValues()
            // copies the parsed patterns into a HashSet, which loses that order.
            String pattern;
            PathPatternsRequestCondition parsed = info.getPathPatternsCondition();
            if (parsed != null) {
                pattern = parsed.getFirstPattern().getPatternString();
            } else {
                PatternsRequestCondition legacy = info.getPatternsCondition();
                if (legacy == null) return;
                Iterator<String> patterns = legacy.getPatterns().iterator();
                if (!patterns.hasNext()) return;
                pattern = patterns.next();
            }

            String verb = "*";
            Iterator<RequestMethod> methods = info.getMethodsCondition().getMethods().iterator();
            if (methods.hasNext()) verb = methods.next().name();

            List<String> key = List.of(pattern, verb);
            Object entry = OtherlodeEndpoints.lookup(MODULE, key);
            if (entry == null) {
                entry = OtherlodeEndpoints.recordDispatch(MODULE, key, verb, pattern, null, null);
            }
            OtherlodeEndpoints.hit(entry);
        } catch (Throwable t) {
            OtherlodeEndpoints.moduleFailed(MODULE, t);
        }
    }
}
