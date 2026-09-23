package io.github.lukedevops.yukon.instrumentation.endpoints

import io.github.lukedevops.yukon.bootstrap.YukonEndpoints
import io.github.lukedevops.yukon.instrumentation.endpoints.api.EndpointModule
import io.github.lukedevops.yukon.registry.EndpointRegistry
import io.github.lukedevops.yukon.registry.HandlerRef
import java.lang.System.Logger.Level
import java.util.concurrent.ConcurrentHashMap

/**
 * Adapts [EndpointRegistry] to the [YukonEndpoints.Resolver] shape the endpoint seam calls
 * through. [YukonEndpoints] lives in the bootstrap module and cannot reference [EndpointRegistry]
 * directly, so every call it forwards passes through the untyped [Any] shapes [Resolver][YukonEndpoints.Resolver]
 * declares; this is the one place those are cast back to [EndpointRegistry.EndpointEntry].
 *
 * [modules], indexed by [EndpointModule.name], is who [declare] routes a framework object to: the
 * module that owns a framework is the only code that knows how to walk one of its objects.
 *
 * A handler join that names a method passes through [handlerForwarders] on its way in, from
 * [register] and [attachHandler], so a pass-through becomes the method it forwards to (ADR 0035).
 * A dispatch path reports its method through [attachHandler]. A join that names only a class, as
 * [recordDispatch] takes, is left as reported, since the table is keyed by method.
 */
class RegistryResolver(
    private val registry: EndpointRegistry,
    modules: List<EndpointModule> = emptyList(),
    private val pendingDeclarations: PendingDeclarations,
    private val handlerForwarders: HandlerForwarders = HandlerForwarders(),
) : YukonEndpoints.Resolver {
    private val log = System.getLogger(RegistryResolver::class.java.name)
    private val modulesByName = modules.associateBy { it.name }
    private val declareFailureLogged = ConcurrentHashMap.newKeySet<String>()
    private val unknownDeclareModuleLogged = ConcurrentHashMap.newKeySet<String>()

    override fun lookup(key: Any): Any? = registry.lookup(key)

    /**
     * Declares an endpoint, or holds it until the transform that declared it has produced bytes;
     * see [PendingDeclarations].
     *
     * A held declaration returns null rather than the entry. Nothing that declares from inside a
     * transform uses the return value, and the seam already answers null when a module is
     * disabled or no resolver is installed yet, so callers handle it.
     */
    override fun register(
        key: Any,
        framework: String,
        verb: String?,
        verbatimTemplate: String,
        contextPath: String?,
        handlerClass: String?,
        handlerMethod: String?,
        handlerDescriptor: String?,
    ): Any? {
        val declare = {
            registry.register(
                key = key,
                framework = framework,
                verb = verb,
                verbatimTemplate = verbatimTemplate,
                contextPath = contextPath,
                handler = handlerRef(handlerClass, handlerMethod, handlerDescriptor),
            )
            Unit
        }
        if (pendingDeclarations.stage(declare)) return null
        return registry.register(
            key = key,
            framework = framework,
            verb = verb,
            verbatimTemplate = verbatimTemplate,
            contextPath = contextPath,
            handler = handlerRef(handlerClass, handlerMethod, handlerDescriptor),
        )
    }

    private fun handlerRef(
        handlerClass: String?,
        handlerMethod: String?,
        handlerDescriptor: String?,
    ): HandlerRef? = handlerClass?.let { handlerForwarders.collapse(HandlerRef(it, handlerMethod, handlerDescriptor)) }

    override fun recordDispatch(
        key: Any,
        framework: String,
        verb: String?,
        verbatimTemplate: String,
        contextPath: String?,
        handlerClass: String?,
    ): Any = registry.recordDispatch(key, framework, verb, verbatimTemplate, contextPath, handlerClass)

    override fun recordDispatchIfUnowned(
        key: Any,
        framework: String,
        verb: String?,
        verbatimTemplate: String,
        contextPath: String?,
        handlerClass: String?,
    ): Any? = registry.recordDispatchIfUnowned(key, framework, verb, verbatimTemplate, contextPath, handlerClass)

    /**
     * Routes [frameworkObject] to the [EndpointModule] named [module], which walks it and
     * registers whatever it finds through [YukonEndpoints.register].
     *
     * An unknown module name is logged once and ignored: nothing registered a module under that
     * name, so there is nothing to hand the object to. A module whose walk throws is as broken as
     * one whose advice throws, so it goes through [YukonEndpoints.moduleFailed] like an advice
     * failure: the module is switched off at the seam, its dispatch advice stops counting, and it
     * lands in the manifest's disabled list. A walk that fails partway through cannot be trusted
     * to have found every route, and a half-declared list would let a collector call an endpoint
     * never called when it merely went undeclared; no data for that framework is the safer
     * report than partial data.
     */
    override fun declare(
        module: String,
        frameworkObject: Any,
    ) {
        val target = modulesByName[module]
        if (target == null) {
            if (unknownDeclareModuleLogged.add(module)) {
                log.log(Level.WARNING, "yukon: declare called for unknown endpoint module '$module', ignoring")
            }
            return
        }
        try {
            target.declare(frameworkObject)
        } catch (t: Throwable) {
            if (declareFailureLogged.add(module)) {
                log.log(Level.WARNING, "yukon: endpoint module $module failed while declaring its routes", t)
            }
            YukonEndpoints.moduleFailed(module, t)
        }
    }

    override fun hit(entry: Any) {
        (entry as EndpointRegistry.EndpointEntry).hit()
    }

    override fun attachHandler(
        entry: Any,
        handlerClass: String?,
        handlerMethod: String?,
        handlerDescriptor: String?,
    ) {
        val handler = handlerRef(handlerClass, handlerMethod, handlerDescriptor) ?: return
        registry.attachHandler(entry as EndpointRegistry.EndpointEntry, handler)
    }

    override fun disableModule(
        module: String,
        reason: String,
    ) {
        registry.recordDisabledModule(module, reason)
    }
}
