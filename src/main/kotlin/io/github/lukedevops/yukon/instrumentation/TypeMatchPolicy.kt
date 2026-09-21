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
            .and { typeDescription: TypeDescription ->
                isIncluded(typeDescription.name, instrumentedPackagePrefixes, excludedPackagePrefixes)
            }.and { typeDescription: TypeDescription -> !isRuntimeGenerated(typeDescription.name) }
            .and { typeDescription: TypeDescription -> !isCoroutineContinuation(typeDescription) }

    /**
     * Markers in the name of a class a framework synthesized in memory, carried in the middle of
     * the name rather than as a prefix: a proxy is named after the class it proxies, so it lands
     * in the adopter's own package and no package rule can tell it apart.
     *
     * Spring 6 and 7 use `$$SpringCGLIB$$`, with `FastClass` appended for the two helper classes
     * that go with an enhanced configuration class; Spring 5.3 tags the same classes
     * `BySpringCGLIB$$` behind the generating class's simple name (`$$EnhancerBySpringCGLIB$$`,
     * `$$FastClassBySpringCGLIB$$`). Both spellings were read out of `SpringNamingPolicy` and
     * `DefaultNamingPolicy` in spring-core 5.3.39, 6.2.19 and 7.0.9 rather than recalled.
     */
    private val RUNTIME_GENERATED_NAME_MARKERS = listOf("\$\$SpringCGLIB\$\$", "BySpringCGLIB\$\$")

    /**
     * Whether [className] names a class a framework generated at runtime, which this agent leaves
     * alone: it holds no code the adopter wrote, so a never-hit finding about it names nothing
     * anyone can delete. The class it proxies is instrumented normally and is where the real
     * signal lives, since a proxy reaches the method it overrides through `super`.
     *
     * Such a class also has no identity worth reporting. Its name carries a counter or a hash from
     * the order the generator happened to produce it in, so two instances of one service disagree
     * about it and a collector can never merge them into "never hit across the fleet"; it has no
     * `.class` file, so the static baseline scan cannot declare it and the blind-spot warning for
     * a class the scan missed fires on every one of them; and it carries no line numbers, so every
     * row it produces points at line -1. Left in, one `@Bean`-bearing Spring configuration class
     * put 274 of `demo-spring`'s 289 probes into three generated classes and took the demo's
     * report from 4 never-hit probes to 230.
     *
     * The rule is the generator's own naming, not the shape of the bytecode, because the
     * structural signals all collide with something real: Spring's CGLIB classes are neither
     * synthetic nor missing a `SourceFile` attribute (theirs reads `<generated>`), and the one
     * thing they do lack, line numbers, is exactly what a class compiled without debug info lacks
     * too, which ADR 0026 keeps and labels rather than drops. A bare `$$` test is no good either:
     * kotlinc puts `$$` in the name of a class it generates for a lambda passed to an inlined
     * function, and that class holds the adopter's body. See ADR 0029.
     */
    fun isRuntimeGenerated(className: String): Boolean = RUNTIME_GENERATED_NAME_MARKERS.any { it in className }

    /**
     * A dotted suffix of a suspend function's own continuation class's direct superclass. Matched
     * by suffix, never as a literal starting with `kotlin.`, since `shadowJar` rewrites such a
     * literal in this agent's own code.
     */
    internal val CONTINUATION_SUPERCLASS_SUFFIXES =
        listOf(
            ".coroutines.jvm.internal.ContinuationImpl",
            ".coroutines.jvm.internal.RestrictedContinuationImpl",
        )

    /**
     * Whether [typeDescription] is a suspend function's own continuation class: `final class ...
     * extends kotlin.coroutines.jvm.internal.ContinuationImpl` (or `RestrictedContinuationImpl`
     * for restricted suspension), kotlinc's own name for it. Such a class holds no code the
     * adopter wrote: its `invokeSuspend` runs only on resumption after a real suspension, so on a
     * function that never suspends it reads as never hit and, as a body class, roots a false
     * unreached cluster. See ADR 0025.
     *
     * Only the superclass's own name is wanted, never its members. Under a lazily resolving pool
     * (`TypePool.Default.WithLazyResolution`, which ByteBuddy's `AgentBuilder` uses by default and
     * `StaticBaselineScanner` builds for every root) a superclass no locator can find still
     * answers to its name, so a continuation class is recognised even when the Kotlin stdlib sits
     * in a dependency jar the scan never opens. Under an eagerly resolving pool the erasure lookup
     * throws `TypePool.Resolution.NoSuchTypeException` instead; that is caught and reads as "not a
     * continuation", so no pool choice can throw out of a type matcher. A class extending
     * `SuspendLambda` is not caught by this check: `SuspendLambda` itself extends
     * `ContinuationImpl`, but a suspend lambda's direct superclass is `SuspendLambda`, and it holds
     * the adopter's own body.
     */
    private fun isCoroutineContinuation(typeDescription: TypeDescription): Boolean {
        val superclassName =
            try {
                typeDescription.superClass?.asErasure()?.name
            } catch (_: Exception) {
                null
            } ?: return false
        return CONTINUATION_SUPERCLASS_SUFFIXES.any { superclassName.endsWith(it) }
    }

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

    /**
     * Whether a synthetic method named [name] is a lambda body worth a probe. See [methodMatcher].
     *
     * Also used by [io.github.lukedevops.yukon.instrumentation.branch.BranchSiteAnalyzer] to tell
     * a cross-class pass-through (a bridge, an `access$` accessor) apart from a lambda body when
     * resolving call edges, so both readers apply the exact same synthetic-method rule.
     */
    internal fun isProbedLambdaBody(
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
