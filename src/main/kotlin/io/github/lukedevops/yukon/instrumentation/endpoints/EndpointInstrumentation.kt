package io.github.lukedevops.yukon.instrumentation.endpoints

import io.github.lukedevops.yukon.bootstrap.YukonEndpoints
import io.github.lukedevops.yukon.instrumentation.BootstrapHolder
import io.github.lukedevops.yukon.instrumentation.TypeMatchPolicy
import io.github.lukedevops.yukon.instrumentation.endpoints.api.AdviceBinder
import io.github.lukedevops.yukon.instrumentation.endpoints.api.EndpointModule
import io.github.lukedevops.yukon.registry.EndpointRegistry
import net.bytebuddy.agent.builder.AgentBuilder
import net.bytebuddy.agent.builder.ResettableClassFileTransformer
import net.bytebuddy.description.type.TypeDescription
import net.bytebuddy.matcher.ElementMatchers.nameStartsWith
import net.bytebuddy.utility.JavaModule
import java.lang.System.Logger.Level
import java.lang.instrument.Instrumentation
import java.util.WeakHashMap

/**
 * Wires every discovered [EndpointModule]'s registration and dispatch advice into the JVM.
 *
 * This is a separate `AgentBuilder`/transformer pipeline from
 * [io.github.lukedevops.yukon.instrumentation.YukonInstrumentation]: the method tier defines a
 * field and rewrites branches, which needs `REBASE`/`REDEFINE`, while an endpoint module only ever
 * adds advice to an existing method body. Using `DECORATE` for this pipeline (see [install]) keeps
 * an endpoint module's classes out of the annotation-legality validation that
 * `REBASE`/`REDEFINE` runs, the one [YukonInstrumentation] otherwise has to work around class by
 * class for `@file:JvmName`-style classes.
 *
 * When any module names a handler interface, [install] also installs a [LambdaFactoryHook], so a
 * handler written as a lambda or a method reference can be named (ADR 0035). [lambdaFactoryShape]
 * is what that hook checks the JDK against. [handlerForwarders] is the forwarder table the method
 * tier fills, which turns a reported pass-through into the method it forwards to.
 */
class EndpointInstrumentation(
    private val registry: EndpointRegistry,
    private val modules: List<EndpointModule>,
    lambdaFactoryShape: LambdaFactoryShape = LambdaFactoryShape.JDK,
    private val handlerForwarders: HandlerForwarders = HandlerForwarders(),
) {
    private val log = System.getLogger(EndpointInstrumentation::class.java.name)
    private val agentClassLoader = EndpointInstrumentation::class.java.classLoader
    private val lambdaFactoryHook = LambdaFactoryHook(lambdaFactoryShape)

    /** Endpoints a module declared during a transform, held until that transform produces bytes. */
    private val pendingDeclarations = PendingDeclarations()

    /** Whether this thread is holding declarations from a transform; for tests. */
    internal fun pendingDeclarationCount(): Int = pendingDeclarations.pendingCount()

    /**
     * One [AdviceBinder] per target classloader, since Spring's own types (unlike the JDK's
     * `HttpServer`, which loads on the bootstrap loader) live on the application's classloader,
     * for example Spring Boot's `LaunchedClassLoader`, not this agent's own. A [WeakHashMap] under
     * [adviceBinderLock] keeps a retired classloader collectible instead of pinning it for the
     * life of the process; the bootstrap loader is a `null` key at the JVM level, which
     * [WeakHashMap] cannot hold, so it is cached separately in [bootLoaderAdviceBinder].
     */
    private val adviceBinderLock = Any()
    private val adviceBindersByLoader = WeakHashMap<ClassLoader, AdviceBinder>()
    private var bootLoaderAdviceBinder: AdviceBinder? = null

    private fun adviceBinderFor(targetClassLoader: ClassLoader?): AdviceBinder =
        synchronized(adviceBinderLock) {
            if (targetClassLoader == null) {
                bootLoaderAdviceBinder ?: AdviceBinder(agentClassLoader, null).also { bootLoaderAdviceBinder = it }
            } else {
                adviceBindersByLoader.getOrPut(targetClassLoader) { AdviceBinder(agentClassLoader, targetClassLoader) }
            }
        }

    /**
     * Installs the bootstrap holder (idempotent if [io.github.lukedevops.yukon.instrumentation.YukonInstrumentation]
     * already installed it), points the endpoint seam at this registry, adds a module read edge
     * from every boot module a module declares needing one, then installs one `AgentBuilder`
     * covering every discovered module's type matcher and advice. Last, it installs the lambda
     * factory hook for every handler interface a module names, if there is one.
     */
    fun install(instrumentation: Instrumentation): ResettableClassFileTransformer {
        BootstrapHolder.install(instrumentation)
        YukonEndpoints.install(RegistryResolver(registry, modules, pendingDeclarations, handlerForwarders))
        addSeamReadEdges(instrumentation)

        if (modules.isEmpty()) {
            log.log(Level.INFO, "yukon: no endpoint modules were discovered, no framework's endpoints will be instrumented")
        } else {
            log.log(Level.INFO, "yukon: installing endpoint modules: ${modules.joinToString(", ") { it.name }}")
        }

        var builder: AgentBuilder =
            AgentBuilder
                .Default()
                // DECORATE only weaves advice into existing method bodies; it never adds a field
                // or a type initializer, and it skips the annotation-legality validation that
                // REBASE/REDEFINE runs, which is what trips on a class carrying an
                // illegally-targeted annotation such as @kotlin.jvm.JvmName. See "Classes
                // ByteBuddy can't safely redefine" in this project's CLAUDE.md.
                .with(AgentBuilder.TypeStrategy.Default.DECORATE)
                .with(AgentBuilder.InitializationStrategy.NoOp.INSTANCE)
                .disableClassFormatChanges()
                // Replaces AgentBuilder's own default ignore matcher, which skips bootstrap-loader
                // classes among others. The JDK's own HttpServer classes load on the bootstrap
                // loader, and an endpoint module needs to match them.
                .ignore(nameStartsWith<TypeDescription>(TypeMatchPolicy.AGENT_PACKAGE_PREFIX))
                .with(EndpointTransformListener())

        for (module in modules) {
            builder =
                builder.type(module.typeMatcher()).transform { typeBuilder, typeDescription, classLoader, _, _ ->
                    // Anything the module declares from here is held until the rewrite produces
                    // bytes; see PendingDeclarations. The mark is where this module's own
                    // declarations start, since another module matching the same class may have
                    // staged some already.
                    val mark = pendingDeclarations.begin()
                    try {
                        module.transform(typeBuilder, typeDescription, adviceBinderFor(classLoader), classLoader)
                    } catch (t: Throwable) {
                        // This catch is why a throwing module needs the rollback: it returns the
                        // builder unchanged, so the transform still succeeds as far as ByteBuddy
                        // is concerned, the listener commits, and whatever this module declared
                        // before it threw would land in the registry for a class that never got
                        // its advice.
                        //
                        // The rollback covers this class only. moduleFailed below switches the
                        // module off for the whole process, so routes it already declared for
                        // earlier classes keep their manifest rows while their advice stops
                        // counting: those do read as never called. The disabled list is the only
                        // signal for that; see STATUS.md.
                        pendingDeclarations.rollbackTo(mark)
                        log.log(Level.WARNING, "yukon: endpoint module ${module.name} failed to transform ${typeDescription.name}", t)
                        YukonEndpoints.moduleFailed(module.name, t)
                        typeBuilder
                    }
                }
        }

        val transformer = builder.installOn(instrumentation)

        val handlerInterfaces = modules.flatMapTo(sortedSetOf()) { it.handlerInterfaces }
        if (handlerInterfaces.isNotEmpty()) {
            lambdaFactoryHook.install(instrumentation, handlerInterfaces)
        }
        return transformer
    }

    /** Removes the endpoint advice transformer, and the lambda factory hook if this instance installed one. */
    fun uninstall(
        instrumentation: Instrumentation,
        transformer: ResettableClassFileTransformer,
    ) {
        transformer.reset(instrumentation, AgentBuilder.RedefinitionStrategy.DISABLED)
        lambdaFactoryHook.uninstall(instrumentation)
    }

    /**
     * Adds a module read edge from every boot module an [EndpointModule] names in
     * [EndpointModule.bootModulesNeedingSeamRead] to the endpoint seam's own module, the same way
     * OpenTelemetry's agent reaches `jdk.httpserver`. The seam lives on the bootstrap loader's
     * unnamed module precisely so any classloader can reach it, but a named module's own module
     * descriptor still gates what it is allowed to read, so `jdk.httpserver`'s own classes cannot
     * call into the seam without this edge.
     *
     * A boot module name no module needs, or that this JDK does not have, is logged at INFO and
     * skipped: that framework's advice simply never matches anything on this JVM.
     */
    private fun addSeamReadEdges(instrumentation: Instrumentation) {
        val bootModuleNames = modules.flatMapTo(sortedSetOf()) { it.bootModulesNeedingSeamRead }
        if (bootModuleNames.isEmpty()) return
        val seamModule = Class.forName(BootstrapHolder.ENDPOINTS_CLASS_NAME, false, null).module
        for (name in bootModuleNames) {
            val bootModule = ModuleLayer.boot().findModule(name).orElse(null)
            if (bootModule == null) {
                log.log(Level.INFO, "yukon: boot module '$name' is not present in this JDK, its endpoint module will not match anything")
                continue
            }
            instrumentation.redefineModule(bootModule, setOf(seamModule), emptyMap(), emptyMap(), emptySet(), emptyMap())
        }
    }

    /**
     * Commits what a transform declared once its bytes exist, and reports a transform failure
     * ByteBuddy caught on its own, outside any module's own try/catch.
     *
     * `onTransformation` runs only after `make()` has produced the bytes, so an endpoint declared
     * from inside the transform callback reaches the registry only for a class that really was
     * woven. `onComplete` runs whatever the outcome, so a failed transform's declarations are
     * dropped rather than left staged on the thread.
     */
    private inner class EndpointTransformListener : AgentBuilder.Listener.Adapter() {
        override fun onTransformation(
            typeDescription: TypeDescription,
            classLoader: ClassLoader?,
            module: JavaModule?,
            loaded: Boolean,
            dynamicType: net.bytebuddy.dynamic.DynamicType,
        ) {
            pendingDeclarations.commit()
        }

        override fun onError(
            typeName: String,
            classLoader: ClassLoader?,
            module: JavaModule?,
            loaded: Boolean,
            throwable: Throwable,
        ) {
            log.log(
                Level.WARNING,
                "yukon: endpoint instrumentation failed for $typeName, class will run without endpoint tracking",
                throwable,
            )
        }

        override fun onComplete(
            typeName: String,
            classLoader: ClassLoader?,
            module: JavaModule?,
            loaded: Boolean,
        ) {
            pendingDeclarations.discard()
        }
    }
}
