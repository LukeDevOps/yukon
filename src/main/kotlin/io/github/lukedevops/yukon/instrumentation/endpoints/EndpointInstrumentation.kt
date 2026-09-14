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
 */
class EndpointInstrumentation(
    private val registry: EndpointRegistry,
    private val modules: List<EndpointModule>,
) {
    private val log = System.getLogger(EndpointInstrumentation::class.java.name)
    private val adviceBinder = AdviceBinder(EndpointInstrumentation::class.java.classLoader)

    /**
     * Installs the bootstrap holder (idempotent if [io.github.lukedevops.yukon.instrumentation.YukonInstrumentation]
     * already installed it), points the endpoint seam at this registry, adds a module read edge
     * from every boot module a module declares needing one, then installs one `AgentBuilder`
     * covering every discovered module's type matcher and advice.
     */
    fun install(instrumentation: Instrumentation): ResettableClassFileTransformer {
        BootstrapHolder.install(instrumentation)
        YukonEndpoints.install(RegistryResolver(registry))
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
                builder.type(module.typeMatcher()).transform { typeBuilder, typeDescription, _, _, _ ->
                    try {
                        module.transform(typeBuilder, typeDescription, adviceBinder)
                    } catch (t: Throwable) {
                        log.log(Level.WARNING, "yukon: endpoint module ${module.name} failed to transform ${typeDescription.name}", t)
                        YukonEndpoints.moduleFailed(module.name, t)
                        typeBuilder
                    }
                }
        }

        return builder.installOn(instrumentation)
    }

    fun uninstall(
        instrumentation: Instrumentation,
        transformer: ResettableClassFileTransformer,
    ) {
        transformer.reset(instrumentation, AgentBuilder.RedefinitionStrategy.DISABLED)
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

    /** Reports a transform failure ByteBuddy caught on its own, outside any module's own try/catch. */
    private inner class EndpointTransformListener : AgentBuilder.Listener.Adapter() {
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
    }
}
