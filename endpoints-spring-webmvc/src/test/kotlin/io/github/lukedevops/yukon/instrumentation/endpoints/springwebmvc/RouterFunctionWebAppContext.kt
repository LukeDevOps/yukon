package io.github.lukedevops.yukon.instrumentation.endpoints.springwebmvc

import org.springframework.context.annotation.AnnotatedBeanDefinitionReader
import org.springframework.mock.web.MockServletContext
import org.springframework.web.context.WebApplicationContext
import org.springframework.web.context.support.GenericWebApplicationContext
import org.springframework.web.servlet.config.annotation.DelegatingWebMvcConfiguration
import org.springframework.web.servlet.function.RouterFunction
import org.springframework.web.servlet.function.ServerResponse
import java.util.function.Supplier

/**
 * Builds and refreshes a [WebApplicationContext] wiring [routerFunction] as the application's
 * only functional route table.
 *
 * `DelegatingWebMvcConfiguration` registers a `RouterFunctionMapping` bean unconditionally,
 * whether or not a `RouterFunction` bean exists in the context, and that mapping discovers
 * whatever `RouterFunction` beans exist through the context itself at `afterPropertiesSet` time
 * rather than needing one handed to its constructor. Registering [routerFunction] as a plain bean
 * is therefore enough; nothing in this file needs to name `RouterFunctionMapping` itself, unlike
 * [buildUrlMappedWebApplicationContext]'s own precaution for `SimpleUrlHandlerMapping`, which
 * this module's advice does target directly and which must not load before
 * `EndpointInstrumentation.install` has run.
 */
fun buildFunctionalWebApplicationContext(routerFunction: RouterFunction<ServerResponse>): WebApplicationContext {
    val context = GenericWebApplicationContext()
    context.servletContext = MockServletContext()
    AnnotatedBeanDefinitionReader(context).register(DelegatingWebMvcConfiguration::class.java)

    @Suppress("UNCHECKED_CAST")
    val routerFunctionClass = RouterFunction::class.java as Class<Any>
    context.registerBean("functionalRoutes", routerFunctionClass, Supplier { routerFunction })

    context.refresh()
    return context
}
