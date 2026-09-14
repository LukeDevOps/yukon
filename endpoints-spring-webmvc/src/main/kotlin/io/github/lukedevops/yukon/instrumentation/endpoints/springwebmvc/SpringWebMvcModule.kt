package io.github.lukedevops.yukon.instrumentation.endpoints.springwebmvc

import io.github.lukedevops.yukon.instrumentation.endpoints.api.AdviceBinder
import io.github.lukedevops.yukon.instrumentation.endpoints.api.EndpointModule
import net.bytebuddy.description.method.MethodDescription
import net.bytebuddy.description.type.TypeDescription
import net.bytebuddy.dynamic.DynamicType
import net.bytebuddy.matcher.ElementMatcher
import net.bytebuddy.matcher.ElementMatchers.named
import net.bytebuddy.matcher.ElementMatchers.namedOneOf
import net.bytebuddy.matcher.ElementMatchers.takesArguments

private const val HANDLER_METHOD_MAPPING = "org.springframework.web.servlet.handler.AbstractHandlerMethodMapping"
private const val REQUEST_MAPPING_HANDLER_MAPPING = "org.springframework.web.servlet.mvc.method.RequestMappingInfoHandlerMapping"
private const val ADVICE_PACKAGE = "io.github.lukedevops.yukon.endpoints.springwebmvc"

/**
 * Endpoint module for Spring MVC (`spring-webmvc`), covering Spring Framework 5.3, 6.x and 7.x
 * with one module. The dispatch advice binds only the matched `RequestMappingInfo`, never the
 * request itself, so no advice class in this module names a `javax`/`jakarta` servlet type and
 * nothing here needs a per-major-version variant.
 *
 * `AbstractHandlerMethodMapping.registerHandlerMethod` is the registration hook: it runs once per
 * handler method as Spring builds its route table. `RequestMappingInfoHandlerMapping.handleMatch`
 * is the dispatch point: it runs once per matched request, before the handler runs, with the
 * winning `RequestMappingInfo` already narrowed to the matched pattern and verb.
 *
 * A Spring Boot Actuator endpoint registers through a `RequestMappingInfoHandlerMapping` subclass,
 * so it is covered automatically. A `SimpleUrlHandlerMapping` (a URL-mapped handler, or a static
 * resource handler) is not `RequestMappingInfo`-based at all and is out of scope for this module.
 */
class SpringWebMvcModule : EndpointModule {
    override val name: String = "spring-webmvc"

    override fun typeMatcher(): ElementMatcher<in TypeDescription> = namedOneOf(HANDLER_METHOD_MAPPING, REQUEST_MAPPING_HANDLER_MAPPING)

    override fun transform(
        builder: DynamicType.Builder<*>,
        typeDescription: TypeDescription,
        advice: AdviceBinder,
    ): DynamicType.Builder<*> =
        when (typeDescription.name) {
            HANDLER_METHOD_MAPPING -> {
                builder.visit(
                    advice
                        .bind("$ADVICE_PACKAGE.RegisterHandlerMethodAdvice")
                        .on(named<MethodDescription>("registerHandlerMethod").and(takesArguments(3))),
                )
            }

            REQUEST_MAPPING_HANDLER_MAPPING -> {
                builder.visit(
                    advice
                        .bind("$ADVICE_PACKAGE.HandleMatchAdvice")
                        .on(named<MethodDescription>("handleMatch").and(takesArguments(3))),
                )
            }

            else -> {
                builder
            }
        }
}
