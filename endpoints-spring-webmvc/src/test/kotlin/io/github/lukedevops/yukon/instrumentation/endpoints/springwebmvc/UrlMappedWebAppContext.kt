package io.github.lukedevops.yukon.instrumentation.endpoints.springwebmvc

import org.springframework.context.annotation.AnnotatedBeanDefinitionReader
import org.springframework.mock.web.MockServletContext
import org.springframework.web.context.WebApplicationContext
import org.springframework.web.context.support.GenericWebApplicationContext
import org.springframework.web.servlet.config.annotation.DelegatingWebMvcConfiguration
import java.util.function.Supplier

/**
 * Builds and refreshes a [WebApplicationContext] wiring [TestController]'s annotation-mapped
 * endpoints alongside a `SimpleUrlHandlerMapping` bean that maps a `/static` wildcard prefix to
 * [staticHandler] and the exact path `/legacy/old` to [legacyHandler].
 *
 * `SimpleUrlHandlerMapping` and its supertype `AbstractUrlHandlerMapping` must not load before
 * [io.github.lukedevops.yukon.instrumentation.endpoints.EndpointInstrumentation.install] has run:
 * an `AgentBuilder` transformer only weaves advice into a class as it loads. Gradle's JUnit
 * Platform test discovery, though, loads every compiled class under this module's test source
 * set (not only the ones carrying `@Test` methods) to check whether each one is a potential test
 * container, and that happens before any test method runs. If `SimpleUrlHandlerMapping` appeared
 * as an ordinary compile-time type anywhere in this test source set, such as a class literal or a
 * local variable's declared type, the JVM would resolve it, and its supertype, while verifying
 * whichever class carried that reference during that same discovery pass, well before
 * `install`. This function therefore reaches `SimpleUrlHandlerMapping` only by name, through
 * plain reflection, so no class file in this test source set carries a symbolic reference to it
 * at all: nothing resolves the type until this function's own body actually runs, from inside a
 * test method, after `install` already has.
 *
 * `DelegatingWebMvcConfiguration` is registered directly rather than via `@EnableWebMvc` on a
 * fixture `@Configuration` class, so the `SimpleUrlHandlerMapping` bean can be built from a
 * [registerBean][org.springframework.context.support.GenericApplicationContext.registerBean]
 * supplier that closes over [staticHandler] and [legacyHandler] directly, rather than from a
 * fixture class whose own instances a caller could not otherwise observe.
 */
fun buildUrlMappedWebApplicationContext(
    staticHandler: Any,
    legacyHandler: Any,
): WebApplicationContext {
    val context = GenericWebApplicationContext()
    context.servletContext = MockServletContext()
    AnnotatedBeanDefinitionReader(context).register(DelegatingWebMvcConfiguration::class.java)
    // A Supplier built explicitly, not as a trailing lambda: registerBean's last parameter is a
    // BeanDefinitionCustomizer vararg, so a trailing lambda binds there instead of to the
    // Supplier before it.
    context.registerBean("testController", TestController::class.java, Supplier { TestController() })

    @Suppress("UNCHECKED_CAST")
    val mappingClass = Class.forName("org.springframework.web.servlet.handler.SimpleUrlHandlerMapping") as Class<Any>
    val constructor = mappingClass.getDeclaredConstructor()
    val setUrlMap = mappingClass.getMethod("setUrlMap", Map::class.java)
    context.registerBean(
        "urlHandlerMapping",
        mappingClass,
        Supplier {
            val mapping = constructor.newInstance()
            setUrlMap.invoke(mapping, mapOf("/static/**" to staticHandler, "/legacy/old" to legacyHandler))
            mapping
        },
    )
    context.refresh()
    return context
}
