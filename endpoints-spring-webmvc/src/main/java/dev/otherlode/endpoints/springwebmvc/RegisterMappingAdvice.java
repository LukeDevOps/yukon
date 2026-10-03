package dev.otherlode.endpoints.springwebmvc;

import dev.otherlode.bootstrap.OtherlodeEndpoints;
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
 * Woven onto {@code AbstractHandlerMethodMapping.registerMapping(mapping, handler, method)}, the
 * public method for registering a handler method in code. Spring Boot Actuator registers every
 * operation this way, and it never passes through {@code registerHandlerMethod}, so without this
 * advice those endpoints would be found only once called and never listed as never called.
 *
 * <p>The body is {@link RegisterHandlerMethodAdvice}'s with the arguments in this method's order.
 * It is a copy rather than a shared helper for the reason that class's Javadoc gives: advice
 * inlines only its own method body.
 */
public class RegisterMappingAdvice {
    private static final String MODULE = "spring-webmvc";

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void onEnter(
            @Advice.This Object mapping,
            @Advice.Argument(0) Object info,
            @Advice.Argument(1) Object handler,
            @Advice.Argument(2) Method method) {
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
            // with no join rather than dropped, so it can never read as absent. The join names the
            // class that declares the method, where its probe lives: a mapping inherited from a
            // base controller or an interface default method is not declared on the bean's class.
            String beanTypeName =
                    handlerType == null ? null : ClassUtils.getUserClass(method.getDeclaringClass()).getName();
            String methodName = beanTypeName == null ? null : method.getName();

            String descriptor =
                    beanTypeName == null
                            ? null
                            : MethodType.methodType(method.getReturnType(), method.getParameterTypes())
                                    .toMethodDescriptorString();

            Set<RequestMethod> verbs = mappingInfo.getMethodsCondition().getMethods();
            for (String pattern : mappingInfo.getPatternValues()) {
                if (verbs.isEmpty()) {
                    OtherlodeEndpoints.register(
                            MODULE, List.of(pattern, "*"), "*", pattern, null, beanTypeName, methodName, descriptor);
                } else {
                    for (RequestMethod verb : verbs) {
                        String verbName = verb.name();
                        OtherlodeEndpoints.register(
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
            OtherlodeEndpoints.moduleFailed(MODULE, t);
        }
    }
}
