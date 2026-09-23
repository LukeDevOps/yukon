package io.github.lukedevops.yukon.instrumentation.endpoints.springwebmvc

import io.github.lukedevops.yukon.bootstrap.YukonEndpoints
import io.github.lukedevops.yukon.instrumentation.endpoints.api.AdviceBinder
import io.github.lukedevops.yukon.instrumentation.endpoints.api.EndpointModule
import net.bytebuddy.description.method.MethodDescription
import net.bytebuddy.description.type.TypeDescription
import net.bytebuddy.dynamic.DynamicType
import net.bytebuddy.matcher.ElementMatcher
import net.bytebuddy.matcher.ElementMatchers.named
import net.bytebuddy.matcher.ElementMatchers.namedOneOf
import net.bytebuddy.matcher.ElementMatchers.takesArguments
import java.lang.System.Logger.Level
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicBoolean

private const val HANDLER_METHOD_MAPPING = "org.springframework.web.servlet.handler.AbstractHandlerMethodMapping"
private const val REQUEST_MAPPING_HANDLER_MAPPING = "org.springframework.web.servlet.mvc.method.RequestMappingInfoHandlerMapping"
private const val URL_HANDLER_MAPPING = "org.springframework.web.servlet.handler.AbstractUrlHandlerMapping"
private const val ROUTER_FUNCTION_MAPPING = "org.springframework.web.servlet.function.support.RouterFunctionMapping"
private const val ROUTER_FUNCTION = "org.springframework.web.servlet.function.RouterFunction"
private const val REQUEST_PREDICATE = "org.springframework.web.servlet.function.RequestPredicate"
private const val ROUTER_FUNCTIONS_VISITOR = "org.springframework.web.servlet.function.RouterFunctions\$Visitor"
private const val REQUEST_PREDICATES_VISITOR = "org.springframework.web.servlet.function.RequestPredicates\$Visitor"
private const val SERVER_REQUEST = "org.springframework.web.servlet.function.ServerRequest"
private const val HANDLER_FUNCTION = "org.springframework.web.servlet.function.HandlerFunction"

/** `HandlerFunction.handle`'s descriptor, the same on every supported Spring version. */
private const val HANDLE_DESCRIPTOR =
    "(Lorg/springframework/web/servlet/function/ServerRequest;)Lorg/springframework/web/servlet/function/ServerResponse;"
private const val ADVICE_PACKAGE = "io.github.lukedevops.yukon.endpoints.springwebmvc"
private const val MODULE_NAME = "spring-webmvc"

/**
 * Endpoint module for Spring MVC (`spring-webmvc`), covering Spring Framework 5.3, 6.x and 7.x
 * with one module. Every advice class binds only framework types Spring itself already passes
 * between its own methods, never a `javax`/`jakarta` servlet type, so nothing here needs a
 * per-major-version variant.
 *
 * `AbstractHandlerMethodMapping.registerHandlerMethod` is the registration hook: it runs once per
 * handler method as Spring builds its route table. `RequestMappingInfoHandlerMapping.handleMatch`
 * is the dispatch point: it runs once per matched request, before the handler runs, with the
 * winning `RequestMappingInfo` already narrowed to the matched pattern and verb.
 *
 * A Spring Boot Actuator endpoint registers through a `RequestMappingInfoHandlerMapping` subclass,
 * so it is covered automatically. `AbstractUrlHandlerMapping` covers the URL-mapped side instead:
 * `SimpleUrlHandlerMapping`, `BeanNameUrlHandlerMapping`, and Spring Boot's static-resource and
 * webjars mappings all extend it, and its `registerHandler`/`buildPathExposingHandler` pair is
 * that hierarchy's own registration hook and dispatch point.
 *
 * `RouterFunctionMapping` covers Spring's functional routing style. Its routes are declared as a
 * `RouterFunction` object built once and revealed only to a visitor, never through a registration
 * hook's own arguments, so this side uses the second seam call, [declare], instead: advice on
 * `initRouterFunctions` hands the built `RouterFunction` to [declare], which walks it with a
 * [Proxy] implementing `RouterFunctions.Visitor` (and, for each route's predicate, a second
 * [Proxy] implementing `RequestPredicates.Visitor`), since neither visitor type is on this
 * module's own classloader. `setAttributes` is this style's dispatch point: it runs once per
 * matched request, after functional routing has already resolved the winning pattern and handler.
 */
class SpringWebMvcModule : EndpointModule {
    override val name: String = MODULE_NAME

    private val log = System.getLogger(SpringWebMvcModule::class.java.name)
    private val noPathPredicateLogged = AtomicBoolean(false)

    /**
     * A functional route's handler passed as a lambda or a method reference is a `HandlerFunction`
     * lambda. The name is the same in Spring 5.3, 6.2 and 7.0.
     */
    override val handlerInterfaces: Set<String> = setOf(HANDLER_FUNCTION)

    override fun typeMatcher(): ElementMatcher<in TypeDescription> =
        namedOneOf(HANDLER_METHOD_MAPPING, REQUEST_MAPPING_HANDLER_MAPPING, URL_HANDLER_MAPPING, ROUTER_FUNCTION_MAPPING)

    override fun transform(
        builder: DynamicType.Builder<*>,
        typeDescription: TypeDescription,
        advice: AdviceBinder,
        classLoader: ClassLoader?,
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

            URL_HANDLER_MAPPING -> {
                builder
                    .visit(
                        advice
                            .bind("$ADVICE_PACKAGE.RegisterUrlHandlerAdvice")
                            .on(
                                named<MethodDescription>("registerHandler")
                                    .and(takesArguments(String::class.java, Any::class.java)),
                            ),
                    ).visit(
                        advice
                            .bind("$ADVICE_PACKAGE.BuildPathExposingHandlerAdvice")
                            .on(named<MethodDescription>("buildPathExposingHandler").and(takesArguments(4))),
                    )
            }

            ROUTER_FUNCTION_MAPPING -> {
                builder
                    .visit(
                        advice
                            .bind("$ADVICE_PACKAGE.InitRouterFunctionsAdvice")
                            .on(named<MethodDescription>("initRouterFunctions").and(takesArguments(0))),
                    ).visit(
                        advice
                            .bind("$ADVICE_PACKAGE.SetAttributesAdvice")
                            .on(named<MethodDescription>("setAttributes").and(takesArguments(3))),
                    )
            }

            else -> {
                builder
            }
        }

    /**
     * Walks [frameworkObject] (a `RouterFunction`) with a [Proxy] implementing
     * `RouterFunctions.Visitor`, registering every route it finds through [YukonEndpoints.register].
     *
     * Neither `RouterFunction` nor its visitor types are on this module's own classloader, so
     * every framework type here is reached only by name, through reflection and the classloader
     * [frameworkObject] itself came from. A route's full path is the join of every enclosing
     * `startNested`/`endNested` prefix with the route's own predicate-derived path; see
     * [walkPredicate] for how one predicate's verbs and paths, including `and`/`or` combinations,
     * are collected.
     */
    override fun declare(frameworkObject: Any) {
        val loader = frameworkObject.javaClass.classLoader
        val routerFunctionClass = Class.forName(ROUTER_FUNCTION, false, loader)
        if (!routerFunctionClass.isInstance(frameworkObject)) return

        val routerFunctionsVisitorClass = Class.forName(ROUTER_FUNCTIONS_VISITOR, false, loader)
        val requestPredicateClass = Class.forName(REQUEST_PREDICATE, false, loader)
        val requestPredicatesVisitorClass = Class.forName(REQUEST_PREDICATES_VISITOR, false, loader)
        val acceptMethod = routerFunctionClass.getMethod("accept", routerFunctionsVisitorClass)

        val prefixStack = ArrayDeque<Set<String>>()

        fun currentPrefixes(): Set<String> = prefixStack.lastOrNull() ?: setOf("")

        val routerVisitor =
            InvocationHandler { proxy, method, args ->
                when (method.name) {
                    "startNested" -> {
                        val result = walkPredicate(args[0]!!, requestPredicateClass, requestPredicatesVisitorClass, loader)
                        val ownPrefixes =
                            result.alternatives
                                .mapNotNull { it.path }
                                .toSet()
                                .ifEmpty { setOf("") }
                        prefixStack.addLast(crossJoinPaths(currentPrefixes(), ownPrefixes))
                    }

                    "endNested" -> {
                        prefixStack.removeLastOrNull()
                    }

                    "route" -> {
                        val predicateResult = walkPredicate(args[0]!!, requestPredicateClass, requestPredicatesVisitorClass, loader)
                        registerRoute(predicateResult, currentPrefixes(), args[1]!!)
                    }

                    "resources", "attributes", "unknown" -> {
                        // Not declared: a `resources` route's pattern lives inside its enclosing
                        // nest and is discovered at dispatch; `attributes` carries no path; a
                        // whole `unknown` router subtree is never visited any deeper than this.
                    }

                    "toString" -> {
                        return@InvocationHandler "SpringWebMvcModule.RouterFunctionsVisitor"
                    }

                    "hashCode" -> {
                        return@InvocationHandler System.identityHashCode(proxy)
                    }

                    "equals" -> {
                        return@InvocationHandler (args?.getOrNull(0) === proxy)
                    }
                }
                null
            }
        val visitor = Proxy.newProxyInstance(loader, arrayOf(routerFunctionsVisitorClass), routerVisitor)
        acceptMethod.invoke(frameworkObject, visitor)
    }

    /**
     * Registers one `route(predicate, handlerFunction)` call for every (prefix, alternative)
     * combination, or logs once at INFO and registers nothing when [predicateResult] carries no
     * path at all, or contained a predicate this walk could not interpret.
     */
    private fun registerRoute(
        predicateResult: PredicateResult,
        prefixes: Set<String>,
        handlerFunction: Any,
    ) {
        if (predicateResult.unknown || predicateResult.alternatives.none { it.path != null }) {
            if (noPathPredicateLogged.compareAndSet(false, true)) {
                log.log(
                    Level.INFO,
                    "yukon: a $MODULE_NAME functional route had no path predicate this walk could resolve; " +
                        "it will be discovered at dispatch instead",
                )
            }
            return
        }
        val handler = handlerJoin(handlerFunction)
        for (alternative in predicateResult.alternatives) {
            val path = alternative.path ?: continue
            val verb = alternative.verb ?: "*"
            for (prefix in prefixes) {
                val template = joinPaths(prefix, path)
                val key = listOf(handlerFunction, template, verb)
                YukonEndpoints.register(
                    MODULE_NAME,
                    key,
                    verb,
                    template,
                    null,
                    handler?.className,
                    handler?.methodName,
                    handler?.descriptor,
                )
            }
        }
    }

    /** The class, method and descriptor an endpoint record names as its handler. */
    private class HandlerJoin(
        val className: String,
        val methodName: String?,
        val descriptor: String?,
    )

    /**
     * The handler join for a functional route's `HandlerFunction`: the `handle` method Spring
     * invokes and the class that declares it, so a handler inheriting `handle` from a base class
     * joins to the probe on that base. A hidden class, spun for a lambda or a method reference,
     * joins to the method the lambda calls, as [YukonEndpoints.lambdaImplementation] recorded it
     * (ADR 0035). When nothing was recorded it gets no join. A class the reflection cannot see
     * `handle` on is reported by name alone. `ServerRequest` is resolved through the handler's own
     * loader rather than named here, so this module never links against Spring.
     */
    private fun handlerJoin(handlerFunction: Any): HandlerJoin? {
        val type = handlerFunction.javaClass
        if (type.isHidden) {
            val implementation = YukonEndpoints.lambdaImplementation(type) ?: return null
            return HandlerJoin(implementation.className, implementation.methodName, implementation.descriptor)
        }
        return try {
            val serverRequest = Class.forName(SERVER_REQUEST, false, type.classLoader)
            val handle = type.getMethod("handle", serverRequest)
            HandlerJoin(handle.declaringClass.name, "handle", HANDLE_DESCRIPTOR)
        } catch (_: ReflectiveOperationException) {
            HandlerJoin(type.name, null, null)
        }
    }
}

/** One resolved (verb, path) pairing from a predicate tree; either half is null when unconstrained. */
private data class Alternative(
    val verb: String?,
    val path: String?,
)

/** Every alternative a predicate tree can match as, plus whether any part of it could not be interpreted. */
private class PredicateResult(
    val alternatives: Set<Alternative>,
    val unknown: Boolean,
)

private val NEUTRAL = PredicateResult(setOf(Alternative(null, null)), unknown = false)

/**
 * Visits [predicate] with a [Proxy] implementing `RequestPredicates.Visitor` and returns the
 * [PredicateResult] it produces.
 *
 * A leaf predicate (`method`, `path`) pushes one [PredicateResult] onto a small operand stack;
 * `and`/`or` combine the two most recently pushed results into one, `endAnd` taking the Cartesian
 * product of their alternatives and `endOr` their union; `endNegate` discards its one operand and
 * pushes [NEUTRAL], treating a negated subtree as contributing nothing, per this module's own
 * design notes. Every other leaf (`pathExtension`, `header`, `param`, a 7.0-only `version`) is
 * neutral too: none of them narrow a route's identity. `unknown` pushes a result marked
 * unrecognisable, which propagates through any `and`/`or` it takes part in. One `accept` call
 * therefore always leaves exactly one result on the stack, which this function pops and returns.
 */
private fun walkPredicate(
    predicate: Any,
    requestPredicateClass: Class<*>,
    visitorClass: Class<*>,
    loader: ClassLoader?,
): PredicateResult {
    val stack = ArrayDeque<PredicateResult>()
    val handler =
        InvocationHandler { proxy, method, args ->
            when (method.name) {
                "method" -> {
                    @Suppress("UNCHECKED_CAST")
                    val verbs = (args[0] as Set<Any>).map { it.javaClass.getMethod("name").invoke(it) as String }.toSet()
                    stack.addLast(
                        if (verbs.isEmpty()) {
                            NEUTRAL
                        } else {
                            PredicateResult(verbs.map { Alternative(it, null) }.toSet(), unknown = false)
                        },
                    )
                }

                "path" -> {
                    stack.addLast(PredicateResult(setOf(Alternative(null, args[0] as String)), unknown = false))
                }

                "pathExtension", "header", "param", "version" -> {
                    stack.addLast(NEUTRAL)
                }

                "startAnd", "and", "startOr", "or", "startNegate" -> {
                    // Pure markers: every combination is resolved at its matching end* call below.
                }

                // Every operand is popped tolerantly: a predicate whose own `accept` describes it
                // to the visitor not at all pushes nothing, and a missing operand narrows nothing,
                // exactly as a neutral leaf does. Popping it strictly would raise
                // NoSuchElementException out of the visitor proxy, which the seam can only treat
                // as this module failing, taking every Spring endpoint in the process down with it.
                "endAnd" -> {
                    val right = stack.removeLastOrNull() ?: NEUTRAL
                    val left = stack.removeLastOrNull() ?: NEUTRAL
                    stack.addLast(combineAnd(left, right))
                }

                "endOr" -> {
                    val right = stack.removeLastOrNull() ?: NEUTRAL
                    val left = stack.removeLastOrNull() ?: NEUTRAL
                    stack.addLast(PredicateResult(left.alternatives + right.alternatives, left.unknown || right.unknown))
                }

                "endNegate" -> {
                    stack.removeLastOrNull()
                    stack.addLast(NEUTRAL)
                }

                "unknown" -> {
                    stack.addLast(PredicateResult(setOf(Alternative(null, null)), unknown = true))
                }

                "toString" -> {
                    return@InvocationHandler "SpringWebMvcModule.RequestPredicatesVisitor"
                }

                "hashCode" -> {
                    return@InvocationHandler System.identityHashCode(proxy)
                }

                "equals" -> {
                    return@InvocationHandler (args?.getOrNull(0) === proxy)
                }
            }
            null
        }
    val visitor = Proxy.newProxyInstance(loader, arrayOf(visitorClass), handler)
    requestPredicateClass.getMethod("accept", visitorClass).invoke(predicate, visitor)
    return stack.removeLastOrNull() ?: NEUTRAL
}

private fun combineAnd(
    left: PredicateResult,
    right: PredicateResult,
): PredicateResult {
    val combined = mutableSetOf<Alternative>()
    for (a in left.alternatives) {
        for (b in right.alternatives) {
            val verb = a.verb ?: b.verb
            val path =
                when {
                    a.path == null -> b.path
                    b.path == null -> a.path
                    else -> joinPaths(a.path, b.path)
                }
            combined += Alternative(verb, path)
        }
    }
    return PredicateResult(combined, left.unknown || right.unknown)
}

/** Joins [prefix] and [suffix] with a single slash, collapsing any doubled slash the join produces. */
private fun joinPaths(
    prefix: String,
    suffix: String,
): String = "$prefix/$suffix".replace(Regex("/+"), "/")

private fun crossJoinPaths(
    outer: Set<String>,
    inner: Set<String>,
): Set<String> = outer.flatMap { o -> inner.map { i -> joinPaths(o, i) } }.toSet()
