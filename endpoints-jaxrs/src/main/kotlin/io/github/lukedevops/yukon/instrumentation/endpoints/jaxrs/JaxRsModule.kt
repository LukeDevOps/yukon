package io.github.lukedevops.yukon.instrumentation.endpoints.jaxrs

import io.github.lukedevops.yukon.bootstrap.YukonEndpoints
import io.github.lukedevops.yukon.instrumentation.endpoints.api.AdviceBinder
import io.github.lukedevops.yukon.instrumentation.endpoints.api.EndpointModule
import net.bytebuddy.description.annotation.AnnotationSource
import net.bytebuddy.description.method.MethodDescription
import net.bytebuddy.description.type.TypeDescription
import net.bytebuddy.dynamic.DynamicType
import net.bytebuddy.matcher.ElementMatcher
import net.bytebuddy.matcher.ElementMatchers.isAbstract
import net.bytebuddy.matcher.ElementMatchers.isInterface
import net.bytebuddy.matcher.ElementMatchers.isStatic
import net.bytebuddy.matcher.ElementMatchers.isSynthetic
import net.bytebuddy.matcher.ElementMatchers.not
import java.lang.System.Logger.Level
import java.util.concurrent.atomic.AtomicBoolean

private const val MODULE = "jaxrs"
private const val ADVICE_PACKAGE = "io.github.lukedevops.yukon.endpoints.jaxrs"
private const val WILDCARD_VERB = "*"
private const val JERSEY_MARKER_RESOURCE = "org/glassfish/jersey/server/model/Resource.class"

private val NAMESPACES = listOf("javax.ws.rs", "jakarta.ws.rs")
private val VERBS = listOf("GET", "POST", "PUT", "DELETE", "HEAD", "OPTIONS", "PATCH")
private val PATH_NAMES = NAMESPACES.map { "$it.Path" }.toSet()
private val VERB_NAMES = NAMESPACES.flatMap { namespace -> VERBS.map { verb -> "$namespace.$verb" } }.toSet()
private val HTTP_METHOD_NAMES = NAMESPACES.map { "$it.HttpMethod" }.toSet()

/**
 * A supertype named under one of these prefixes can never itself carry a JAX-RS annotation, so a
 * supertype walk never recurses into one. This bounds every walk in this module: the JDK's own
 * type hierarchy (`Object`, `Serializable`, `Comparable`, and the `jdk.`/`sun.` internals behind
 * every bootstrap-loaded class, which reach this matcher too because the endpoint pipeline does
 * not ignore the bootstrap loader), Kotlin's own supertypes, and the JAX-RS specification's own
 * interfaces are all excluded from view, leaving only an adopter's own API interfaces and abstract
 * base classes to visit.
 */
private val SKIPPED_SUPERTYPE_PREFIXES = listOf("java.", "jdk.", "sun.", "com.sun.", "kotlin.", "jakarta.", "javax.")

private val ELIGIBLE_METHOD: ElementMatcher.Junction<MethodDescription> =
    not(isStatic<MethodDescription>()).and(not(isAbstract())).and(not(isSynthetic()))

/**
 * Endpoint module for JAX-RS, covering both the `javax.ws.rs` and `jakarta.ws.rs` namespaces with
 * one module and no compile-time dependency on either.
 *
 * JAX-RS has no registration hook comparable to Spring's handler-method mapping or Ktor's
 * `handle`: a resource class declares its routes purely through annotations, read once by the
 * framework's own runtime when it builds its route table. There is nothing to advise for
 * "declaration", so this module reads the annotations itself, directly from the [TypeDescription]
 * ByteBuddy hands to [transform], and calls the endpoint seam from this ordinary Kotlin code
 * rather than from woven advice. That is safe here specifically because this code runs inside the
 * agent's own JVM context, never inlined into the resource class's bytecode: the Kotlin-intrinsics
 * hazard that keeps [io.github.lukedevops.yukon.endpoints.jaxrs.ResourceMethodAdvice] a Java class
 * does not apply to a class that is only ever loaded on the agent's own classloader.
 *
 * Method-level annotations are inherited by the specification's own rule: a method the concrete
 * class declares with no JAX-RS annotation of its own inherits the annotations of the method it
 * overrides or implements. Class-level `@Path` is not inherited per the specification, but Jersey
 * honours one declared on a superclass or interface, so this module does too, only when Jersey is
 * present on the resource class's own loader. See [resolveAnnotationSource] and
 * [resolveClassPath], and ADR 0020 for the full reasoning.
 *
 * [jerseyPresent] answers whether Jersey is on a resource class's own loader; the default reads a
 * marker resource off that loader without loading any class. A test supplies its own function to
 * force either branch without adding a second JAX-RS runtime to the build. The constructor is
 * `@JvmOverloads` so [java.util.ServiceLoader], which needs a genuine no-argument constructor at
 * the bytecode level, can still instantiate this class.
 */
class JaxRsModule
    @JvmOverloads
    constructor(
        private val jerseyPresent: (ClassLoader?) -> Boolean = ::isJerseyPresent,
    ) : EndpointModule {
        override val name: String = MODULE

        private val log = System.getLogger(JaxRsModule::class.java.name)
        private val classPathIgnoredWarned = AtomicBoolean(false)
        private val interfaceConflictWarned = AtomicBoolean(false)

        /**
         * A concrete class is in scope if it, or any supertype in its superclass chain or
         * interface graph, carries `@Path`, or declares a method carrying `@Path` or one of the
         * seven standard verb annotations.
         *
         * The class's own declared annotations and methods are checked first, since that already
         * covers the common case of a resource class with no supertype of its own. Only when that
         * check fails does this walk supertypes at all, bounded by [SKIPPED_SUPERTYPE_PREFIXES]. A
         * supertype whose erasure cannot be resolved, from a [TypeDescription] backed by a
         * [net.bytebuddy.pool.TypePool] rather than a loaded class, is skipped rather than failing
         * the match.
         *
         * A custom verb annotation built on `@HttpMethod` (see [resolveVerb]) is not part of this
         * check: a method carrying only such an annotation, with no `@Path` anywhere in scope,
         * would not bring the class into scope. This is an accepted v1 gap no fixture in this
         * module's tests hits, since every custom-verb method here sits under a class already
         * matched by its own `@Path`.
         */
        override fun typeMatcher(): ElementMatcher<in TypeDescription> =
            not(isInterface<TypeDescription>())
                .and(not(isAbstract()))
                .and(ElementMatcher<TypeDescription> { type -> declaresOwnPathOrVerb(type) || hasInheritedPathOrVerb(type) })

        /**
         * Reads every eligible declared method for a verb and a route template, binds
         * [io.github.lukedevops.yukon.endpoints.jaxrs.ResourceMethodAdvice], registers each method
         * found through the endpoint seam, then weaves the advice onto exactly those methods.
         *
         * Binding comes before registering on purpose, so the step most likely to fail leaves
         * nothing declared rather than a half-read route list. Reading an annotation off a type
         * description can throw too, for an annotation type the pool cannot resolve, which is
         * why the rollback in `EndpointInstrumentation` matters and not only this ordering.
         *
         * Beyond that, nothing declared here reaches the registry until the rewrite has produced
         * bytes: the declarations are staged per transform and committed by
         * `EndpointInstrumentation`'s listener, which also covers a failure inside ByteBuddy's
         * own weaving after this method returns.
         *
         * A method with neither a verb annotation nor `@Path`, own or inherited, is not an
         * endpoint and is left alone. A method with `@Path` but no verb is a sub-resource locator:
         * it registers with verb `*`, the same convention this project's other
         * unconstrained-verb endpoints already use.
         */
        override fun transform(
            builder: DynamicType.Builder<*>,
            typeDescription: TypeDescription,
            advice: AdviceBinder,
            classLoader: ClassLoader?,
        ): DynamicType.Builder<*> {
            val classPath = resolveClassPath(typeDescription, classLoader)
            val matched =
                typeDescription.declaredMethods.filter(ELIGIBLE_METHOD).mapNotNull { method ->
                    val source = resolveAnnotationSource(method, typeDescription)
                    val verb = resolveVerb(source)
                    val methodPath = pathValue(source)
                    if (verb == null &&
                        methodPath == null
                    ) {
                        null
                    } else {
                        Triple(method, verb ?: WILDCARD_VERB, combineTemplate(classPath, methodPath))
                    }
                }
            if (matched.isEmpty()) return builder

            val boundAdvice = advice.bind("$ADVICE_PACKAGE.ResourceMethodAdvice")
            for ((method, verb, template) in matched) {
                val key = "${typeDescription.name}#${method.internalName}${method.descriptor}"
                YukonEndpoints.register(MODULE, key, verb, template, null, typeDescription.name, method.internalName, method.descriptor)
            }
            val matchedMethods = matched.mapTo(mutableSetOf()) { (method, _, _) -> method.internalName to method.descriptor }
            return builder.visit(boundAdvice.on { method -> (method.internalName to method.descriptor) in matchedMethods })
        }

        /**
         * The annotation source for [method]: itself if it carries any annotation in either JAX-RS
         * namespace, otherwise the method it overrides or implements, per the specification's
         * annotation inheritance rule.
         *
         * Any JAX-RS annotation on [method] itself, including one with no bearing on routing such
         * as `@Produces`, switches inheritance off entirely: a method that only adds a produced
         * media type to an otherwise-inherited operation is not itself a JAX-RS method by the
         * specification's own rule, and reports as such here too.
         *
         * The superclass chain is searched first, nearest first, then every interface [type]
         * declares, depth-first in declaration order with each interface's own superinterfaces
         * visited before its next sibling. The first candidate found carrying a JAX-RS annotation
         * wins. When the interface search finds more than one candidate and two of them disagree
         * on verb or path, the first in declaration order still wins, and this logs a WARNING once
         * for the life of this module naming the class and method.
         *
         * A method found with no JAX-RS annotation anywhere, own or inherited, is returned as-is:
         * [resolveVerb] and [pathValue] both read no verb and no path from it, so the caller skips
         * it as not an endpoint.
         */
        private fun resolveAnnotationSource(
            method: MethodDescription.InDefinedShape,
            type: TypeDescription,
        ): AnnotationSource {
            if (hasOwnJaxRsAnnotation(method)) return method
            return findSuperclassMethod(type, method)
                ?: findInterfaceMethod(type, method)
                ?: method
        }

        private fun findSuperclassMethod(
            type: TypeDescription,
            target: MethodDescription.InDefinedShape,
        ): AnnotationSource? {
            var current = superErasure(type)
            while (current != null) {
                val candidate = current
                val match = runCatching { candidate.declaredMethods.firstOrNull { matchingAnnotated(it, target) } }.getOrNull()
                if (match != null) return match
                current = superErasure(candidate)
            }
            return null
        }

        private fun findInterfaceMethod(
            type: TypeDescription,
            target: MethodDescription.InDefinedShape,
        ): AnnotationSource? {
            val matches = mutableListOf<MethodDescription.InDefinedShape>()

            fun walk(iface: TypeDescription) {
                runCatching { iface.declaredMethods.firstOrNull { matchingAnnotated(it, target) } }.getOrNull()?.let { matches += it }
                interfaceErasures(iface).forEach(::walk)
            }
            interfaceErasures(type).forEach(::walk)

            val first = matches.firstOrNull() ?: return null
            val conflicting = matches.drop(1).any { !sameVerbAndPath(first, it) }
            if (conflicting && interfaceConflictWarned.compareAndSet(false, true)) {
                log.log(
                    Level.WARNING,
                    "yukon: jaxrs: ${type.name}#${target.internalName} inherits conflicting JAX-RS annotations " +
                        "from more than one interface, using ${first.declaringType.name}",
                )
            }
            return first
        }

        private fun matchingAnnotated(
            candidate: MethodDescription.InDefinedShape,
            target: MethodDescription.InDefinedShape,
        ): Boolean = sameSignature(candidate, target) && hasOwnJaxRsAnnotation(candidate)

        /**
         * The class-level route prefix for [type]: its own `@Path` when it has one, otherwise, only
         * when [jerseyPresent] says Jersey is on [classLoader], the nearest superclass or first
         * interface carrying one, in the order Jersey's own `ModelHelper.getAnnotatedResourceClass`
         * resolves it.
         *
         * A supertype `@Path` found while Jersey is absent is not used, since neither RESTEasy nor
         * the specification inherits a class-level annotation; this logs an INFO line once for the
         * life of this module the first time that happens, naming the class, so an adopter running
         * a non-Jersey runtime can see why a template it expected has no class prefix.
         */
        private fun resolveClassPath(
            type: TypeDescription,
            classLoader: ClassLoader?,
        ): String? {
            pathValue(type)?.let { return it }
            val inherited = findInheritedClassPath(type) ?: return null
            if (jerseyPresent(classLoader)) return inherited
            if (classPathIgnoredWarned.compareAndSet(false, true)) {
                log.log(
                    Level.INFO,
                    "yukon: jaxrs: class-level @Path on a supertype of ${type.name} is ignored: " +
                        "RESTEasy and the JAX-RS specification do not inherit it",
                )
            }
            return null
        }

        /**
         * Jersey's own resolution order (`ModelHelper.getAnnotatedResourceClass`): walking up the
         * superclass chain, the first class carrying `@Path` wins outright; failing that, the first
         * direct interface carrying one, taken from the nearest class in the chain that has such an
         * interface, [type] itself included.
         */
        private fun findInheritedClassPath(type: TypeDescription): String? {
            var foundInterfacePath: String? = null
            var current: TypeDescription? = superErasure(type)
            if (foundInterfacePath == null) foundInterfacePath = interfaceErasures(type).firstNotNullOfOrNull { pathValue(it) }
            while (current != null) {
                pathValue(current)?.let { return it }
                if (foundInterfacePath == null) foundInterfacePath = interfaceErasures(current).firstNotNullOfOrNull { pathValue(it) }
                current = superErasure(current)
            }
            return foundInterfacePath
        }

        /** The verb for [method], or null if it carries neither a standard verb annotation nor a custom one. */
        private fun resolveVerb(source: AnnotationSource): String? {
            for (annotation in source.declaredAnnotations) {
                val annotationType = annotation.annotationType
                if (annotationType.name in VERB_NAMES) return annotationType.name.substringAfterLast('.')
                val metaAnnotation = annotationType.declaredAnnotations.firstOrNull { it.annotationType.name in HTTP_METHOD_NAMES }
                if (metaAnnotation != null) {
                    val value = metaAnnotation.getValue("value").resolve(String::class.java)
                    if (!value.isNullOrBlank()) return value.uppercase()
                }
            }
            return null
        }

        /** The raw `value` of a `@Path` annotation declared directly on [source], or null if there is none. */
        private fun pathValue(source: AnnotationSource): String? {
            val annotation = source.declaredAnnotations.firstOrNull { it.annotationType.name in PATH_NAMES } ?: return null
            return annotation.getValue("value").resolve(String::class.java)
        }

        /** Whether [a] and [b] resolve to the same verb and the same `@Path` value, used to detect an interface conflict. */
        private fun sameVerbAndPath(
            a: AnnotationSource,
            b: AnnotationSource,
        ): Boolean = resolveVerb(a) == resolveVerb(b) && pathValue(a) == pathValue(b)
    }

/**
 * Joins [classPath] and [methodPath], trimming surrounding slashes off each before joining so
 * neither an empty class-level `@Path` nor a missing one produces a doubled slash. Empty becomes
 * the root template `/`.
 *
 * Any regex constraint inside a `{name: pattern}` segment, such as JAX-RS's own `{id: \d+}`, is
 * left as written here: [io.github.lukedevops.yukon.registry.RouteTemplateNormalizer] strips it
 * later, from the verbatim template this function returns, the same way it already does for every
 * other framework's raw route spelling.
 */
private fun combineTemplate(
    classPath: String?,
    methodPath: String?,
): String {
    val segments = listOfNotNull(classPath, methodPath).map { it.trim('/') }.filter { it.isNotEmpty() }
    return if (segments.isEmpty()) "/" else segments.joinToString("/", prefix = "/")
}

/** Whether [source] carries any annotation in either JAX-RS namespace, not only a verb or `@Path`. */
private fun hasOwnJaxRsAnnotation(source: AnnotationSource): Boolean =
    source.declaredAnnotations.any { annotation ->
        NAMESPACES.any { namespace -> annotation.annotationType.name.startsWith("$namespace.") }
    }

/** Whether [type] itself, ignoring any supertype, carries `@Path` or declares a method carrying `@Path` or a standard verb. */
private fun declaresOwnPathOrVerb(type: TypeDescription): Boolean =
    pathAnnotated(type) || type.declaredMethods.any { pathAnnotated(it) || verbAnnotated(it) }

private fun pathAnnotated(source: AnnotationSource): Boolean = source.declaredAnnotations.any { it.annotationType.name in PATH_NAMES }

private fun verbAnnotated(source: AnnotationSource): Boolean = source.declaredAnnotations.any { it.annotationType.name in VERB_NAMES }

/**
 * Whether any supertype of [type], superclass chain or interface graph, carries `@Path` or
 * declares a method carrying `@Path` or a standard verb. See [SKIPPED_SUPERTYPE_PREFIXES] for the
 * walk's bound, and the walk never revisits a supertype name it has already seen, which also
 * guards against a cyclical or diamond interface graph.
 */
private fun hasInheritedPathOrVerb(type: TypeDescription): Boolean {
    val visited = mutableSetOf<String>()

    fun walk(candidate: TypeDescription): Boolean {
        if (!visited.add(candidate.name)) return false
        if (declaresOwnPathOrVerb(candidate)) return true
        val parent = superErasure(candidate)
        if (parent != null && walk(parent)) return true
        return interfaceErasures(candidate).any { walk(it) }
    }

    val parent = superErasure(type)
    if (parent != null && walk(parent)) return true
    return interfaceErasures(type).any { walk(it) }
}

private fun isSkippedSupertype(name: String): Boolean = SKIPPED_SUPERTYPE_PREFIXES.any { name.startsWith(it) }

private fun sameSignature(
    a: MethodDescription,
    b: MethodDescription,
): Boolean = a.internalName == b.internalName && a.descriptor == b.descriptor

/**
 * [type]'s superclass, erased and resolved, or null when [type] has none, its erasure cannot be
 * resolved from a [net.bytebuddy.pool.TypePool]-backed description, or it falls under
 * [SKIPPED_SUPERTYPE_PREFIXES].
 */
private fun superErasure(type: TypeDescription): TypeDescription? {
    val erased = runCatching { type.superClass?.let(::resolveErasure) }.getOrNull() ?: return null
    return if (isSkippedSupertype(erased.name)) null else erased
}

/** [type]'s own declared interfaces, erased, resolved, and filtered by [SKIPPED_SUPERTYPE_PREFIXES]. */
private fun interfaceErasures(type: TypeDescription): List<TypeDescription> =
    runCatching { type.interfaces.toList() }
        .getOrDefault(emptyList())
        .mapNotNull(::resolveErasure)
        .filterNot { isSkippedSupertype(it.name) }

/** The erasure of [generic], or null if it cannot be resolved from a [net.bytebuddy.pool.TypePool]-backed description. */
private fun resolveErasure(generic: TypeDescription.Generic): TypeDescription? = runCatching { generic.asErasure() }.getOrNull()

/** Whether Jersey is present on [classLoader] (the system loader when null), checked as a resource lookup, never a class load. */
private fun isJerseyPresent(classLoader: ClassLoader?): Boolean =
    (classLoader ?: ClassLoader.getSystemClassLoader()).getResource(JERSEY_MARKER_RESOURCE) != null
