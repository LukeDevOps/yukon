package io.github.lukedevops.yukon.endpoints.springwebmvc;

import io.github.lukedevops.yukon.bootstrap.YukonEndpoints;
import java.util.List;
import net.bytebuddy.asm.Advice;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.context.support.ApplicationObjectSupport;
import org.springframework.util.ClassUtils;

/**
 * Woven onto {@code AbstractUrlHandlerMapping.registerHandler(String, Object)}, the registration
 * hook shared by every URL-mapped handler mapping: {@code SimpleUrlHandlerMapping}, {@code
 * BeanNameUrlHandlerMapping}, and Spring Boot's static-resource and webjars mappings. It runs once
 * per URL path as a mapping builds its route table. The array-argument overload,
 * {@code registerHandler(String[], String)}, delegates to this single-path overload, so advising
 * only this signature covers both without weaving twice.
 *
 * <p>A URL-mapped handler carries no verb, so every endpoint this advice declares uses verb
 * {@code "*"}, the same convention {@link HandleMatchAdvice} uses for an unconstrained {@code
 * RequestMapping}. The handler behind a URL mapping is an object, not a method, so the join
 * attached here is class-only; {@link RegisterHandlerMethodAdvice} is what attaches a method join,
 * for the annotation-mapped handlers it covers.
 *
 * <p>This method is deliberately one flat body with no private helper method of its own; see
 * {@link RegisterHandlerMethodAdvice}'s Javadoc for why.
 */
public class RegisterUrlHandlerAdvice {
    private static final String MODULE = "spring-webmvc";

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void onEnter(
            @Advice.This Object mapping,
            @Advice.Argument(0) String urlPath,
            @Advice.Argument(1) Object handler) {
        try {
            Class<?> handlerType = null;
            if (handler instanceof String) {
                // A bean registered by name is resolved through the mapping's own context, the
                // same lookup RegisterHandlerMethodAdvice uses for a name-registered controller.
                BeanFactory applicationContext = ((ApplicationObjectSupport) mapping).getApplicationContext();
                if (applicationContext != null) handlerType = applicationContext.getType((String) handler);
            } else if (handler != null) {
                handlerType = handler.getClass();
            }
            // An endpoint whose handler cannot be named is still an endpoint: it is registered
            // with no join rather than dropped, so it can never read as absent.
            String handlerClassName = handlerType == null ? null : ClassUtils.getUserClass(handlerType).getName();

            List<String> key = List.of(urlPath, "*");
            YukonEndpoints.register(MODULE, key, "*", urlPath, null, handlerClassName, null, null);
        } catch (Throwable t) {
            YukonEndpoints.moduleFailed(MODULE, t);
        }
    }
}
