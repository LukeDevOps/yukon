package io.github.lukedevops.yukon.instrumentation.endpoints

import io.github.lukedevops.yukon.bootstrap.YukonEndpoints
import io.github.lukedevops.yukon.registry.EndpointRegistry
import io.github.lukedevops.yukon.registry.HandlerRef

/**
 * Adapts [EndpointRegistry] to the [YukonEndpoints.Resolver] shape the endpoint seam calls
 * through. [YukonEndpoints] lives in the bootstrap module and cannot reference [EndpointRegistry]
 * directly, so every call it forwards passes through the untyped [Any] shapes [Resolver][YukonEndpoints.Resolver]
 * declares; this is the one place those are cast back to [EndpointRegistry.EndpointEntry].
 */
class RegistryResolver(
    private val registry: EndpointRegistry,
) : YukonEndpoints.Resolver {
    override fun lookup(key: Any): Any? = registry.lookup(key)

    override fun register(
        key: Any,
        framework: String,
        verb: String?,
        verbatimTemplate: String,
        contextPath: String?,
        handlerClass: String?,
        handlerMethod: String?,
        handlerDescriptor: String?,
    ): Any =
        registry.register(
            key = key,
            framework = framework,
            verb = verb,
            verbatimTemplate = verbatimTemplate,
            contextPath = contextPath,
            handler = handlerClass?.let { HandlerRef(it, handlerMethod, handlerDescriptor) },
        )

    override fun recordDispatch(
        key: Any,
        framework: String,
        verb: String?,
        verbatimTemplate: String,
        contextPath: String?,
        handlerClass: String?,
    ): Any = registry.recordDispatch(key, framework, verb, verbatimTemplate, contextPath, handlerClass)

    override fun hit(entry: Any) {
        (entry as EndpointRegistry.EndpointEntry).hit()
    }

    override fun attachHandler(
        entry: Any,
        handlerClass: String?,
        handlerMethod: String?,
        handlerDescriptor: String?,
    ) {
        if (handlerClass == null) return
        registry.attachHandler(entry as EndpointRegistry.EndpointEntry, HandlerRef(handlerClass, handlerMethod, handlerDescriptor))
    }

    override fun disableModule(
        module: String,
        reason: String,
    ) {
        registry.recordDisabledModule(module, reason)
    }
}
