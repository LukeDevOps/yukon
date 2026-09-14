package io.github.lukedevops.yukon.instrumentation.endpoints.jaxrs

import io.github.lukedevops.yukon.bootstrap.YukonEndpoints
import io.github.lukedevops.yukon.instrumentation.endpoints.api.AdviceBinder
import io.github.lukedevops.yukon.instrumentation.endpoints.api.EndpointModule
import net.bytebuddy.description.annotation.AnnotationSource
import net.bytebuddy.description.method.MethodDescription
import net.bytebuddy.description.type.TypeDescription
import net.bytebuddy.dynamic.DynamicType
import net.bytebuddy.matcher.ElementMatcher
import net.bytebuddy.matcher.ElementMatchers.declaresMethod
import net.bytebuddy.matcher.ElementMatchers.isAbstract
import net.bytebuddy.matcher.ElementMatchers.isAnnotatedWith
import net.bytebuddy.matcher.ElementMatchers.isInterface
import net.bytebuddy.matcher.ElementMatchers.isStatic
import net.bytebuddy.matcher.ElementMatchers.isSynthetic
import net.bytebuddy.matcher.ElementMatchers.namedOneOf
import net.bytebuddy.matcher.ElementMatchers.not

private const val MODULE = "jaxrs"
private const val ADVICE_PACKAGE = "io.github.lukedevops.yukon.endpoints.jaxrs"
private const val WILDCARD_VERB = "*"

private val NAMESPACES = listOf("javax.ws.rs", "jakarta.ws.rs")
private val VERBS = listOf("GET", "POST", "PUT", "DELETE", "HEAD", "OPTIONS", "PATCH")
private val PATH_NAMES = NAMESPACES.map { "$it.Path" }.toSet()
private val VERB_NAMES = NAMESPACES.flatMap { namespace -> VERBS.map { verb -> "$namespace.$verb" } }.toSet()
private val HTTP_METHOD_NAMES = NAMESPACES.map { "$it.HttpMethod" }.toSet()
private val TYPE_MATCHER_METHOD_ANNOTATIONS = (VERB_NAMES + PATH_NAMES).toTypedArray()

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
 * Only annotations declared directly on the class and on its own declared methods are read.
 * Interface-declared JAX-RS annotations, inherited by an implementing class, are not: matching
 * would need a supertype walk this module does not do in v1, the same boundary the type matcher
 * states below.
 *
 * `@ApplicationPath` is deliberately not part of an endpoint's identity: it lives on a JAX-RS
 * `Application` subclass, not on the resource class, so it is not known at the point a resource
 * class transforms, and an identity must never change after an endpoint is first registered.
 */
class JaxRsModule : EndpointModule {
    override val name: String = MODULE

    /**
     * A concrete class that either carries `@Path` itself or declares a method carrying `@Path`
     * or one of the seven standard verb annotations. A custom verb annotation built on
     * `@HttpMethod` (see [resolveVerb]) is not part of this check: a method carrying only such an
     * annotation, with no `@Path` anywhere on the class, would not bring the class into scope, an
     * accepted v1 gap the fixture in this module's tests does not hit, since every method there
     * sits under a class already matched by its own `@Path`.
     */
    override fun typeMatcher(): ElementMatcher<in TypeDescription> =
        not(isInterface<TypeDescription>())
            .and(not(isAbstract()))
            .and(
                declaresMethod<TypeDescription>(isAnnotatedWith<MethodDescription>(namedOneOf(*TYPE_MATCHER_METHOD_ANNOTATIONS)))
                    .or(isAnnotatedWith<TypeDescription>(namedOneOf(*PATH_NAMES.toTypedArray()))),
            )

    /**
     * Reads every eligible declared method for a verb and a route template, registers each one
     * found through the endpoint seam, then weaves [io.github.lukedevops.yukon.endpoints.jaxrs.ResourceMethodAdvice]
     * onto exactly those methods.
     *
     * A method with neither a verb annotation nor `@Path` is not an endpoint and is left alone. A
     * method with `@Path` but no verb is a sub-resource locator: it registers with verb `*`, the
     * same convention this project's other unconstrained-verb endpoints already use.
     */
    override fun transform(
        builder: DynamicType.Builder<*>,
        typeDescription: TypeDescription,
        advice: AdviceBinder,
    ): DynamicType.Builder<*> {
        val classPath = pathValue(typeDescription)
        val matchedMethods = mutableSetOf<Pair<String, String>>()

        for (method in typeDescription.declaredMethods.filter(ELIGIBLE_METHOD)) {
            val verb = resolveVerb(method)
            val methodPath = pathValue(method)
            if (verb == null && methodPath == null) continue

            val template = combineTemplate(classPath, methodPath)
            val key = "${typeDescription.name}#${method.internalName}${method.descriptor}"
            YukonEndpoints.register(
                MODULE,
                key,
                verb ?: WILDCARD_VERB,
                template,
                null,
                typeDescription.name,
                method.internalName,
                method.descriptor,
            )
            matchedMethods += method.internalName to method.descriptor
        }

        if (matchedMethods.isEmpty()) return builder
        return builder.visit(
            advice
                .bind("$ADVICE_PACKAGE.ResourceMethodAdvice")
                .on { method -> (method.internalName to method.descriptor) in matchedMethods },
        )
    }

    /**
     * The verb for [method], or null if it carries neither a standard verb annotation nor a
     * custom one.
     *
     * A standard verb annotation (`@GET`, `@POST`, ...) is a direct match by name. A custom verb,
     * such as an adopter's own `@PURGE`, is not itself one of [VERB_NAMES]; JAX-RS recognises it
     * instead through its own `@HttpMethod("PURGE")` meta-annotation, declared on the custom
     * annotation's own type rather than on [method]. Resolving it needs one more hop: for each
     * annotation declared on [method], this reads that annotation's own declared annotations and
     * looks for `@HttpMethod` among them.
     */
    private fun resolveVerb(method: MethodDescription.InDefinedShape): String? {
        for (annotation in method.declaredAnnotations) {
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

    /**
     * Joins [classPath] and [methodPath], trimming surrounding slashes off each before joining so
     * neither an empty class-level `@Path` nor a missing one produces a doubled slash. Empty
     * becomes the root template `/`.
     *
     * Any regex constraint inside a `{name: pattern}` segment, such as JAX-RS's own
     * `{id: \d+}`, is left as written here: [io.github.lukedevops.yukon.registry.RouteTemplateNormalizer]
     * strips it later, from the verbatim template this method returns, the same way it already
     * does for every other framework's raw route spelling.
     */
    private fun combineTemplate(
        classPath: String?,
        methodPath: String?,
    ): String {
        val segments = listOfNotNull(classPath, methodPath).map { it.trim('/') }.filter { it.isNotEmpty() }
        return if (segments.isEmpty()) "/" else segments.joinToString("/", prefix = "/")
    }
}
