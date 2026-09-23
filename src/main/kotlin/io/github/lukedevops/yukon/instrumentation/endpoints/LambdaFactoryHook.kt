package io.github.lukedevops.yukon.instrumentation.endpoints

import io.github.lukedevops.yukon.bootstrap.YukonEndpoints
import io.github.lukedevops.yukon.endpoints.lambdafactory.SpinInnerClassAdvice
import io.github.lukedevops.yukon.instrumentation.BootstrapHolder
import net.bytebuddy.agent.builder.AgentBuilder
import net.bytebuddy.agent.builder.ResettableClassFileTransformer
import net.bytebuddy.asm.Advice
import net.bytebuddy.description.method.MethodDescription
import net.bytebuddy.description.type.TypeDescription
import net.bytebuddy.dynamic.DynamicType
import net.bytebuddy.matcher.ElementMatchers.named
import net.bytebuddy.matcher.ElementMatchers.none
import net.bytebuddy.matcher.ElementMatchers.returns
import net.bytebuddy.matcher.ElementMatchers.takesNoArguments
import net.bytebuddy.utility.JavaModule
import java.lang.System.Logger.Level
import java.lang.instrument.Instrumentation
import java.lang.invoke.MethodHandleInfo
import java.lang.reflect.Field

/**
 * The members of the JDK's lambda factory that [LambdaFactoryHook] reads: a no-argument method
 * on [factoryClass] that returns the spun `Class`, and two fields it declares or inherits. The
 * advice in `SpinInnerClassAdvice` reads the fields [JDK] names.
 *
 * These are JDK internals. [JDK] was checked against JDK 21, 22, 25, 26 and 27. A test swaps one
 * name to simulate a JDK where a member is gone.
 */
data class LambdaFactoryShape(
    val factoryClass: String,
    val spinMethod: String,
    val interfaceClassField: String,
    val implInfoField: String,
) {
    /**
     * Checks the running JDK by reflection, without touching any member. Returns why the shape
     * does not match, or null when it does.
     */
    fun problem(): String? {
        val factory =
            try {
                Class.forName(factoryClass, false, null)
            } catch (e: ClassNotFoundException) {
                return "$factoryClass is not in this JDK"
            }
        val spin =
            factory.declaredMethods.firstOrNull { it.name == spinMethod && it.parameterCount == 0 }
                ?: return "$factoryClass has no $spinMethod()"
        if (spin.returnType != Class::class.java) {
            return "$factoryClass.$spinMethod() returns ${spin.returnType.name}, not java.lang.Class"
        }
        return fieldProblem(factory, interfaceClassField, Class::class.java)
            ?: fieldProblem(factory, implInfoField, MethodHandleInfo::class.java)
    }

    private fun fieldProblem(
        factory: Class<*>,
        name: String,
        type: Class<*>,
    ): String? {
        val field = findField(factory, name) ?: return "$factoryClass has no field $name"
        return if (field.type == type) null else "$factoryClass.$name is a ${field.type.name}, not ${type.name}"
    }

    private fun findField(
        start: Class<*>,
        name: String,
    ): Field? {
        var type: Class<*>? = start
        while (type != null) {
            type.declaredFields.firstOrNull { it.name == name }?.let { return it }
            type = type.superclass
        }
        return null
    }

    companion object {
        /** The members as the JDK names them. */
        val JDK =
            LambdaFactoryShape(
                factoryClass = "java.lang.invoke.InnerClassLambdaMetafactory",
                spinMethod = "spinInnerClass",
                interfaceClassField = "interfaceClass",
                implInfoField = "implInfo",
            )
    }
}

/**
 * Watches the JDK's lambda factory so a handler written as a lambda or a method reference can be
 * named (ADR 0035).
 *
 * The factory class is loaded before `premain` runs, so the hook retransforms it. It adds advice
 * to one method and changes nothing else in the class. It installs only when [shape] matches the
 * running JDK. When the shape does not match, or the retransform fails, it logs one line and
 * installs nothing. Hidden handlers then get no join, as they would without the hook.
 */
class LambdaFactoryHook(
    private val shape: LambdaFactoryShape = LambdaFactoryShape.JDK,
) {
    private val log = System.getLogger(LambdaFactoryHook::class.java.name)
    private var transformer: ResettableClassFileTransformer? = null

    /**
     * Records lambda classes for [handlerInterfaces] from here on. Returns whether the hook is on.
     * An empty set installs nothing and logs nothing.
     */
    fun install(
        instrumentation: Instrumentation,
        handlerInterfaces: Set<String>,
    ): Boolean {
        if (handlerInterfaces.isEmpty()) return false
        val problem = shape.problem()
        if (problem != null) {
            switchedOff(problem)
            return false
        }
        val listener = OutcomeListener()
        var installed: ResettableClassFileTransformer? = null
        try {
            addJavaBaseReadEdge(instrumentation)
            YukonEndpoints.installHandlerInterfaces(handlerInterfaces)
            installed = builder(listener).installOn(instrumentation)
            val failure = listener.failure()
            if (failure != null) {
                undo(instrumentation, installed)
                switchedOff(failure)
                return false
            }
            transformer = installed
            return true
        } catch (t: Throwable) {
            if (installed != null) undo(instrumentation, installed)
            YukonEndpoints.installHandlerInterfaces(emptySet())
            switchedOff(t.toString())
            return false
        }
    }

    /** Stops recording and restores the lambda factory's own bytecode. Does nothing if the hook is off. */
    fun uninstall(instrumentation: Instrumentation) {
        val current = transformer ?: return
        transformer = null
        undo(instrumentation, current)
    }

    private fun builder(listener: OutcomeListener): AgentBuilder =
        AgentBuilder
            .Default()
            .with(AgentBuilder.TypeStrategy.Default.DECORATE)
            .with(AgentBuilder.InitializationStrategy.NoOp.INSTANCE)
            .disableClassFormatChanges()
            .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
            .with(AgentBuilder.RedefinitionStrategy.DiscoveryStrategy.Explicit(Class.forName(shape.factoryClass, false, null)))
            .with(listener as AgentBuilder.RedefinitionStrategy.Listener)
            .with(listener as AgentBuilder.Listener)
            // The default ignore matcher skips bootstrap classes, and the factory is one.
            .ignore(none<TypeDescription>())
            .type(named<TypeDescription>(shape.factoryClass))
            .transform { builder: DynamicType.Builder<*>, _, _, _, _ ->
                builder.visit(
                    Advice.to(SpinInnerClassAdvice::class.java).on(
                        named<MethodDescription>(shape.spinMethod).and(takesNoArguments()).and(returns(Class::class.java)),
                    ),
                )
            }

    /**
     * The advice is inlined into `java.base`, a named module that cannot read the seam's unnamed
     * module on the bootstrap loader until this edge exists.
     */
    private fun addJavaBaseReadEdge(instrumentation: Instrumentation) {
        val seamModule = Class.forName(BootstrapHolder.ENDPOINTS_CLASS_NAME, false, null).module
        val javaBase = Any::class.java.module
        instrumentation.redefineModule(javaBase, setOf(seamModule), emptyMap(), emptyMap(), emptySet(), emptyMap())
    }

    private fun undo(
        instrumentation: Instrumentation,
        installed: ResettableClassFileTransformer,
    ) {
        YukonEndpoints.installHandlerInterfaces(emptySet())
        try {
            installed.reset(instrumentation, AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
        } catch (t: Throwable) {
            log.log(Level.WARNING, "yukon: could not restore the lambda factory: $t")
        }
    }

    private fun switchedOff(reason: String) {
        log.log(Level.WARNING, "yukon: handler lambdas and method references will not be named on this JVM: $reason")
    }

    /**
     * Collects what went wrong while the factory was retransformed. ByteBuddy reports a failed
     * transform to [onError] and leaves the class as it was, and the JVM's own refusal arrives
     * in [onComplete]. Either one means the hook is off.
     */
    private inner class OutcomeListener :
        AgentBuilder.Listener.Adapter(),
        AgentBuilder.RedefinitionStrategy.Listener {
        private var transformed = false
        private var error: String? = null

        fun failure(): String? = error ?: if (transformed) null else "${shape.factoryClass} was not transformed"

        override fun onTransformation(
            typeDescription: TypeDescription,
            classLoader: ClassLoader?,
            module: JavaModule?,
            loaded: Boolean,
            dynamicType: DynamicType,
        ) {
            if (typeDescription.name == shape.factoryClass) transformed = true
        }

        override fun onError(
            typeName: String,
            classLoader: ClassLoader?,
            module: JavaModule?,
            loaded: Boolean,
            throwable: Throwable,
        ) {
            if (error == null) error = "transforming $typeName failed: $throwable"
        }

        override fun onBatch(
            index: Int,
            batch: List<Class<*>>,
            types: List<Class<*>>,
        ) {}

        override fun onError(
            index: Int,
            batch: List<Class<*>>,
            throwable: Throwable,
            types: List<Class<*>>,
        ): Iterable<List<Class<*>>> {
            if (error == null) error = "retransforming ${shape.factoryClass} failed: $throwable"
            return emptyList()
        }

        override fun onComplete(
            amount: Int,
            types: List<Class<*>>,
            failures: Map<List<Class<*>>, Throwable>,
        ) {
            if (error == null && failures.isNotEmpty()) {
                error = "retransforming ${shape.factoryClass} failed: ${failures.values.first()}"
            }
        }
    }
}
