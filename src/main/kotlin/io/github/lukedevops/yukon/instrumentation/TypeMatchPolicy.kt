package io.github.lukedevops.yukon.instrumentation

import net.bytebuddy.description.annotation.AnnotationDescription
import net.bytebuddy.description.method.MethodDescription
import net.bytebuddy.description.type.TypeDescription
import net.bytebuddy.matcher.ElementMatcher
import net.bytebuddy.matcher.ElementMatchers.isAbstract
import net.bytebuddy.matcher.ElementMatchers.isBridge
import net.bytebuddy.matcher.ElementMatchers.isNative
import net.bytebuddy.matcher.ElementMatchers.isSynthetic
import net.bytebuddy.matcher.ElementMatchers.isTypeInitializer
import net.bytebuddy.matcher.ElementMatchers.not
import java.lang.annotation.ElementType

/**
 * Which types and methods this agent instruments, and which types it refuses to touch at all.
 *
 * Shared between [YukonInstrumentation] (matching a type as it loads) and the static baseline
 * scanner (matching a type read directly from bytecode, never loaded). Both need the exact same
 * answer for the exact same type, or a class could be classified as confidently dead by one tier
 * while the other tier would have skipped it as unsafe to instrument. This is also why
 * [isIncluded] and [typeNameMatcher] take both an include list and an exclude list rather than
 * offering an include-only overload: an overload that silently ignored excludes would be exactly
 * how the two tiers could drift apart.
 */
object TypeMatchPolicy {
    /** The agent's own classes are never instrumented, whatever `includePackages` says. */
    const val AGENT_PACKAGE_PREFIX = "io.github.lukedevops.yukon."

    /**
     * Whether a fully qualified [className] is in scope: matched by [instrumentedPackagePrefixes]
     * and not matched by [excludedPackagePrefixes]. This is the string-only half of
     * [typeNameMatcher], usable before a [TypeDescription] exists at all (the class-bytes capture,
     * the static scanner's pre-filter).
     *
     * An empty include list means everything outside the agent's own package. Exclusion always
     * wins: a class matched by both lists is not included.
     */
    fun isIncluded(
        className: String,
        instrumentedPackagePrefixes: List<String>,
        excludedPackagePrefixes: List<String>,
    ): Boolean {
        if (className.startsWith(AGENT_PACKAGE_PREFIX)) return false
        val matchedByIncludes = instrumentedPackagePrefixes.isEmpty() || instrumentedPackagePrefixes.any { isUnderPrefix(className, it) }
        if (!matchedByIncludes) return false
        return excludedPackagePrefixes.none { isUnderPrefix(className, it) }
    }

    /**
     * A prefix matches on a package or class boundary only: `com.acme` matches `com.acme.Foo` and
     * the class `com.acme` itself with its nested classes, but not `com.acmeinternal.Foo`. A plain
     * `startsWith` would match the latter, silently widening the instrumented or excluded set.
     * Both [isIncluded]'s include and exclude lists use this same rule.
     */
    fun isUnderPrefix(
        className: String,
        prefix: String,
    ): Boolean = className == prefix || className.startsWith("$prefix.") || className.startsWith("$prefix$")

    fun typeNameMatcher(
        instrumentedPackagePrefixes: List<String>,
        excludedPackagePrefixes: List<String>,
    ): ElementMatcher.Junction<TypeDescription> =
        not(isSynthetic<TypeDescription>())
            .and { typeDescription -> isIncluded(typeDescription.name, instrumentedPackagePrefixes, excludedPackagePrefixes) }

    /**
     * Native methods are excluded along with abstract ones: neither has a body to plant a probe in.
     * ByteBuddy silently declines to weave advice into a native method, so without this exclusion
     * such a method would get a slot in the counts array that reads zero forever and be reported
     * as dead code that can never, by construction, be observed running.
     *
     * Most synthetic methods are excluded too: a bridge, a `$default` forwarder, an `access$`
     * accessor and the like hold no code of the adopter's own, so a probe on one would either
     * duplicate a probe already on the method it forwards to or, for a bridge, read zero forever
     * whenever callers use the exact signature and so never invoke it. Two synthetic shapes are
     * the adopter's own code and stay eligible: a lambda body, which javac names
     * `lambda$<method>$N`, and a lambda body scalac names `$anonfun$<method>$N`, only inside a
     * class scalac itself compiled ([isScalaClass], from [ScalaClassDetector]) so an unrelated
     * synthetic method of the same shape on a non-Scala class stays excluded. This is the same
     * allow-list JaCoCo's `SyntheticFilter` applies, with one refinement: Scala 2 emits a boxing
     * forwarder `$anonfun$<method>$N$adapted` beside each body without marking it as a bridge,
     * where Scala 3 marks its `$anonfun$adapted$N` as one, so the `$adapted` suffix is excluded
     * explicitly and both compilers yield one probe per lambda. Kotlin needs no entry here, since
     * its lambda bodies are plain private static methods, never synthetic.
     */
    fun methodMatcher(isScalaClass: Boolean): ElementMatcher.Junction<MethodDescription> =
        not(isAbstract<MethodDescription>())
            .and(not(isNative()))
            .and(not(isBridge()))
            .and(not(isTypeInitializer()))
            .and { method -> !method.isSynthetic || isProbedLambdaBody(method.name, isScalaClass) }

    /** Whether a synthetic method named [name] is a lambda body worth a probe. See [methodMatcher]. */
    private fun isProbedLambdaBody(
        name: String,
        isScalaClass: Boolean,
    ): Boolean = name.startsWith("lambda\$") || (isScalaClass && name.startsWith("\$anonfun\$") && !name.endsWith("\$adapted"))

    /**
     * The declared annotation that makes [typeDescription] unsafe for ByteBuddy to redefine, or
     * null if there is none.
     *
     * ByteBuddy refuses to redefine any type carrying a declared annotation whose own `@Target`
     * does not legally support [ElementType.TYPE]. Kotlin's compiler attaches
     * `@kotlin.jvm.JvmName` directly onto the class file for a `@file:JvmName`-annotated source
     * file, even though that annotation's own `@Target` only covers functions, properties, and
     * files, not classes. See "Classes ByteBuddy can't safely redefine" in this project's
     * `CLAUDE.md` for the full failure mode this exists to catch.
     */
    fun unsafeAnnotation(typeDescription: TypeDescription): AnnotationDescription? =
        typeDescription.declaredAnnotations.firstOrNull { !it.isSupportedOn(ElementType.TYPE) }
}
