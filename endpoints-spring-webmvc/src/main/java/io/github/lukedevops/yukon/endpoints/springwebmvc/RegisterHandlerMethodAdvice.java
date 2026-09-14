package io.github.lukedevops.yukon.endpoints.springwebmvc;

import io.github.lukedevops.yukon.bootstrap.YukonEndpoints;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;
import net.bytebuddy.asm.Advice;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.context.support.ApplicationObjectSupport;
import org.springframework.util.ClassUtils;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;

/**
 * Woven onto {@code AbstractHandlerMethodMapping.registerHandlerMethod}, Spring MVC's own
 * registration hook. It runs once per handler method as Spring builds its route table, for every
 * {@code @Controller} bean, before any request is dispatched.
 *
 * <p>The dispatch key built here, {@code List.of(pattern, verb)}, must match {@link
 * HandleMatchAdvice} exactly: both sides build the same two-element list from the same {@link
 * RequestMappingInfo} accessors, and {@link List#equals} gives them value equality with no key
 * type of our own that would need to be visible from the framework's own classloader. One small
 * list is allocated per (pattern, verb) pair at registration time, negligible next to the
 * per-request allocation {@link HandleMatchAdvice} accepts for the same reason.
 *
 * <p>The context path is left out of the identity here on purpose. Reading it would need this
 * advice to name a servlet type, which would split this module by {@code javax}/{@code jakarta}
 * namespace the way {@link RequestMappingInfo} itself does not force. A context path is also
 * deployment configuration rather than part of the application's own route template, and is empty
 * by default on a Spring Boot embedded server, so the application-relative template is what this
 * module reports.
 *
 * <p>This method is deliberately one flat body with no private helper method of its own: {@link
 * Advice} inlines only the bytecode of the annotated method itself, so a call to a helper method,
 * even one declared on this same class, would remain a real method call in the woven bytecode,
 * requiring the target classloader to resolve this advice class at runtime. The JDK
 * {@code HttpServer} module's {@code CreateContextAdvice} carries the same reasoning for calling
 * into a separate helper class; it applies equally to a helper method on the advice class itself.
 */
public class RegisterHandlerMethodAdvice {
    private static final String MODULE = "spring-webmvc";

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void onEnter(
            @Advice.This Object mapping,
            @Advice.Argument(0) Object handler,
            @Advice.Argument(1) Method method,
            @Advice.Argument(2) Object info) {
        try {
            if (!(info instanceof RequestMappingInfo)) return;
            RequestMappingInfo mappingInfo = (RequestMappingInfo) info;

            Class<?> handlerType = null;
            if (handler instanceof String) {
                // A bean registered by name is resolved through the mapping's own context.
                // getApplicationContext() is public on ApplicationObjectSupport in every
                // supported Spring version; it is null only before the mapping is initialised,
                // which never happens for a registration, but a null is handled anyway.
                BeanFactory applicationContext = ((ApplicationObjectSupport) mapping).getApplicationContext();
                if (applicationContext != null) handlerType = applicationContext.getType((String) handler);
            } else if (handler != null) {
                handlerType = handler.getClass();
            }
            // An endpoint whose handler cannot be named is still an endpoint: it is registered
            // with no join rather than dropped, so it can never read as absent.
            String beanTypeName = handlerType == null ? null : ClassUtils.getUserClass(handlerType).getName();
            String methodName = beanTypeName == null ? null : method.getName();

            String descriptor =
                    beanTypeName == null
                            ? null
                            : MethodType.methodType(method.getReturnType(), method.getParameterTypes())
                                    .toMethodDescriptorString();

            Set<RequestMethod> verbs = mappingInfo.getMethodsCondition().getMethods();
            for (String pattern : mappingInfo.getPatternValues()) {
                if (verbs.isEmpty()) {
                    YukonEndpoints.register(
                            MODULE, List.of(pattern, "*"), "*", pattern, null, beanTypeName, methodName, descriptor);
                } else {
                    for (RequestMethod verb : verbs) {
                        String verbName = verb.name();
                        YukonEndpoints.register(
                                MODULE,
                                List.of(pattern, verbName),
                                verbName,
                                pattern,
                                null,
                                beanTypeName,
                                methodName,
                                descriptor);
                    }
                }
            }
        } catch (Throwable t) {
            YukonEndpoints.moduleFailed(MODULE, t);
        }
    }
}
