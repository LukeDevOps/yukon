package io.github.lukedevops.yukon.instrumentation

import io.github.lukedevops.yukon.export.KotlinKind
import net.bytebuddy.description.annotation.AnnotationDescription
import net.bytebuddy.description.method.MethodDescription
import net.bytebuddy.description.type.TypeDescription
import net.bytebuddy.matcher.ElementMatcher
import net.bytebuddy.matcher.ElementMatchers.any
import net.bytebuddy.matcher.ElementMatchers.isAbstract
import net.bytebuddy.matcher.ElementMatchers.isBridge
import net.bytebuddy.matcher.ElementMatchers.isNative
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
     * An empty include list matches nothing, whatever the exclude list holds. The agent refuses to
     * start without include rules (ADR 0033), and giving the empty list the same meaning here means
     * no other path into this policy (a test harness, the testkit, an embedding of the agent) can
     * reach "instrument everything" by accident. Exclusion always wins: a class matched by both
     * lists is not included, and the agent's own package is never included.
     */
    fun isIncluded(
        className: String,
        instrumentedPackagePrefixes: List<String>,
        excludedPackagePrefixes: List<String>,
    ): Boolean {
        if (className.startsWith(AGENT_PACKAGE_PREFIX)) return false
        if (instrumentedPackagePrefixes.none { isUnderPrefix(className, it) }) return false
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

    /**
     * The types this agent instruments: in scope by [isIncluded], and not turned away by
     * [isTurnedAwayByShape].
     */
    fun typeNameMatcher(
        instrumentedPackagePrefixes: List<String>,
        excludedPackagePrefixes: List<String>,
    ): ElementMatcher.Junction<TypeDescription> =
        any<TypeDescription>().and { typeDescription: TypeDescription ->
            isIncluded(typeDescription.name, instrumentedPackagePrefixes, excludedPackagePrefixes) &&
                !isTurnedAwayByShape(
                    typeDescription.name,
                    typeDescription.isSynthetic,
                    { superClassNameOf(typeDescription) },
                    { kotlinKindOf(typeDescription) },
                )
        }

    /**
     * Whether [typeNameMatcher] turns a class away for what the class itself states, not for where
     * it lives: it is synthetic, a framework generated it at runtime ([isRuntimeGenerated]), or it
     * is a suspend function's own continuation ([isContinuationSuperclass]). [className] and the
     * result of [superClassName] are dotted. [superClassName] is read only when the other tests
     * pass, since a type description may have to resolve it.
     *
     * A synthetic class whose [kotlinKind] is [KotlinKind.MULTIFILE_CLASS_PART] is not turned away.
     * kotlinc marks each part of a multi-file facade synthetic, but the part holds the code of one
     * source file, and the facade holds only forwarders to it (ADR 0041). [kotlinKind] is read only
     * for a synthetic class.
     *
     * [io.github.lukedevops.yukon.instrumentation.branch.BranchSiteAnalyzer] asks the same question
     * of a class it reads from bytes, and passes through a body class this turns away. Both call
     * this one function, so the class the agent never probes and the class the analyser passes
     * through are always the same class. See ADR 0034.
     */
    fun isTurnedAwayByShape(
        className: String,
        isSynthetic: Boolean,
        superClassName: () -> String?,
        kotlinKind: () -> KotlinKind,
    ): Boolean =
        (isSynthetic && kotlinKind() != KotlinKind.MULTIFILE_CLASS_PART) ||
            isRuntimeGenerated(className) ||
            isContinuationSuperclass(superClassName())

    /**
     * A dotted annotation type name shaped like `kotlin.Metadata`: one package segment, then
     * `Metadata`. A shape, not a literal, since `shadowJar` rewrites a literal starting with
     * `kotlin.` in this agent's own code.
     */
    private val KOTLIN_METADATA_NAME_SHAPE = Regex("^[^.]+\\.Metadata$")

    /**
     * The kind kotlinc gives [typeDescription] in the `k` element of its `kotlin.Metadata`, by
     * [KotlinKind.ofMetadataKind]. [KotlinKind.NONE] when the class carries no such annotation. An
     * annotation whose `k` cannot be read counts as the element's default, the same as a missing
     * one. See ADR 0041.
     */
    fun kotlinKindOf(typeDescription: TypeDescription): KotlinKind {
        val metadata =
            try {
                typeDescription.declaredAnnotations.firstOrNull { KOTLIN_METADATA_NAME_SHAPE.matches(it.annotationType.name) }
            } catch (_: Exception) {
                null
            } ?: return KotlinKind.NONE
        val k =
            try {
                metadata.getValue("k").resolve() as? Int
            } catch (_: Exception) {
                null
            }
        return KotlinKind.ofMetadataKind(k)
    }

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
     *
     * javassist's `ProxyFactory`, which older Hibernate and Weld use, appends `_$$_jvst`, three hex
     * digits from the generator's hash code, `_` and a hex counter to the superclass's name. Read
     * out of `ProxyFactory.nameGenerator` in javassist 3.30.2-GA.
     */
    private val RUNTIME_GENERATED_NAME_MARKERS = listOf("\$\$SpringCGLIB\$\$", "BySpringCGLIB\$\$", "_\$\$_jvst")

    /**
     * Parts of a name that ByteBuddy's naming strategies put between the base name and a random
     * tail: `<base>$ByteBuddy$<random>` for a type made by a default `ByteBuddy` instance
     * (`NamingStrategy.SuffixingRandom`), `<instrumented>$auxiliary$<random>` for an auxiliary
     * type, and `<mocked>$MockitoMock$<random>` for a Mockito subclass mock. The tail is
     * `RandomString` output, letters and digits only, so it is always the last part of the name.
     * Read out of `NamingStrategy`, `ByteBuddy`, `AuxiliaryType` and `RandomString` in ByteBuddy
     * 1.18.12, and `SubclassBytecodeGenerator` in mockito-core 5.14.2.
     */
    private val RANDOM_TAILED_PARTS = setOf("ByteBuddy", "auxiliary", "MockitoMock")

    /**
     * The simple name the JDK gives a dynamic proxy class: `$Proxy` and a counter, straight after
     * the package. A proxy lands in the adopter's own package when one of its interfaces is not
     * public; otherwise its package is `jdk.proxyN` or `com.sun.proxy`. A proxy class is final and
     * not synthetic, so only its name tells it apart. Read out of `java.lang.reflect.Proxy` in JDK
     * 11, 21 and 22.
     */
    private val JDK_PROXY_SIMPLE_NAME = Regex("\\\$Proxy\\d+")

    /**
     * The suffixes Hibernate appends, after a `$`, to the name of an entity or embeddable when it
     * generates a class beside it: the lazy-loading proxy, the basic proxy and the instantiator.
     * 6.6 and 7.4 append each with nothing after it; 5.6 appended ByteBuddy's `$<random>`, and
     * one more `$` for the two proxies under `hibernate.bytecode.enforce_legacy_proxy_classnames`.
     * Read out of `ByteBuddyProxyHelper`, `BasicProxyFactoryImpl` and `BytecodeProviderImpl` in
     * hibernate-core 5.6.15, 6.6.58 and 7.4.10.
     */
    private val HIBERNATE_GENERATED_SUFFIXES = setOf("HibernateProxy", "HibernateBasicProxy", "HibernateInstantiator")

    /**
     * The one Hibernate suffix with more after it in the same part of the name. The access
     * optimizer, and in 6.6 and 7.4 its bridge (`HibernateAccessOptimizerBridge`), carry
     * `encodeName`'s output: per property, a hex digit for how it is read and written, then the
     * property's name. An entity with no properties, or 5.6's and the over-long fallback's
     * `$<random>`, leaves nothing after the suffix.
     */
    private const val HIBERNATE_ACCESS_OPTIMIZER = "HibernateAccessOptimizer"

    /**
     * Whether [className] is a class Hibernate generated beside an entity. Each marker is matched
     * as a whole `$`-separated part of the name after the first, never as a substring, so a class
     * the adopter wrote with the same words in its name is never turned away: a nested
     * `Util$HibernateProxyUnwrapper`, or a top-level class called `HibernateProxy`.
     */
    private fun isHibernateGenerated(className: String): Boolean =
        "\$Hibernate" in className &&
            className.substringAfterLast('.').split('$').drop(1).any { part ->
                part in HIBERNATE_GENERATED_SUFFIXES || isHibernateAccessOptimizer(part)
            }

    private fun isHibernateAccessOptimizer(part: String): Boolean {
        if (!part.startsWith(HIBERNATE_ACCESS_OPTIMIZER)) return false
        val encoded = part.removePrefix(HIBERNATE_ACCESS_OPTIMIZER).removePrefix("Bridge")
        return encoded.isEmpty() || encoded[0] in '0'..'9' || encoded[0] in 'a'..'f'
    }

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
     *
     * Spring's CGLIB and javassist are recognised by a marker anywhere in the name, Hibernate by a
     * suffix that makes up a whole part of the name, ByteBuddy and Mockito by a whole part with a
     * random tail after it, and JDK proxies by their whole simple name.
     */
    fun isRuntimeGenerated(className: String): Boolean =
        RUNTIME_GENERATED_NAME_MARKERS.any { it in className } ||
            isHibernateGenerated(className) ||
            isRandomTailed(className) ||
            JDK_PROXY_SIMPLE_NAME.matches(className.substringAfterLast('.'))

    /**
     * Whether a part of [className]'s simple name after the first is one of [RANDOM_TAILED_PARTS]
     * with at least one part after it. Matched as a whole part, as Hibernate's suffixes are, so an
     * adopter's `Config$ByteBuddySettings` is kept, and never as the last part, so a nested class
     * the adopter named `ByteBuddy` is kept too.
     */
    private fun isRandomTailed(className: String): Boolean =
        className
            .substringAfterLast('.')
            .split('$')
            .drop(1)
            .dropLast(1)
            .any { it in RANDOM_TAILED_PARTS }

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
     * Whether [superClassName], dotted, is the direct superclass of a suspend function's own
     * continuation class: `final class ...
     * extends kotlin.coroutines.jvm.internal.ContinuationImpl` (or `RestrictedContinuationImpl`
     * for restricted suspension), kotlinc's own name for it. Such a class holds no code the
     * adopter wrote: its `invokeSuspend` runs only on resumption after a real suspension, so on a
     * function that never suspends it reads as never hit and, as a body class, roots a false
     * unreached cluster. See ADR 0025.
     *
     * A class extending `SuspendLambda` is not caught by this check: `SuspendLambda` itself extends
     * `ContinuationImpl`, but a suspend lambda's direct superclass is `SuspendLambda`, and it holds
     * the adopter's own body.
     */
    fun isContinuationSuperclass(superClassName: String?): Boolean =
        superClassName != null && CONTINUATION_SUPERCLASS_SUFFIXES.any { superClassName.endsWith(it) }

    /**
     * [typeDescription]'s direct superclass, dotted, or null when it has none or it cannot be
     * resolved. Only the superclass's own name is wanted, never its members. Under a lazily
     * resolving pool (`TypePool.Default.WithLazyResolution`, which ByteBuddy's `AgentBuilder` uses
     * by default and `StaticBaselineScanner` builds for every root) a superclass no locator can
     * find still answers to its name, so a continuation class is recognised even when the Kotlin
     * stdlib sits in a dependency jar the scan never opens. Under an eagerly resolving pool the
     * erasure lookup throws `TypePool.Resolution.NoSuchTypeException` instead; that is caught and
     * reads as "not a continuation", so no pool choice can throw out of a type matcher.
     */
    private fun superClassNameOf(typeDescription: TypeDescription): String? =
        try {
            typeDescription.superClass?.asErasure()?.name
        } catch (_: Exception) {
            null
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
    ): Boolean = isJavacLambdaBodyName(name) || (isScalaClass && isScalacLambdaBodyName(name))

    /**
     * Whether [name] is one a compiler gives a lambda body the source never named. The three
     * compiler shapes are:
     * - javac: `lambda$<method>$N`, private and synthetic;
     * - scalac: `$anonfun$...`, synthetic, but never the `$adapted` boxing forwarder Scala 2 emits
     *   beside a body;
     * - kotlinc: `<method>$lambda$N`, private static and not synthetic, with one more `$N` for each
     *   level of nesting (`main$lambda$0$0` for a lambda inside `main$lambda$0`).
     *
     * The name alone proves nothing, since kotlinc's shape is not marked synthetic and a person can
     * write a method with a `$` in its name. So [io.github.lukedevops.yukon.instrumentation.branch.BranchSiteAnalyzer]
     * also requires an `invokedynamic` in the method's own class to name it as the implementation.
     * A named method passed by reference passes that test and fails this one. The compiler fixtures
     * pin each shape. See ADR 0034.
     */
    fun isLambdaBodyName(name: String): Boolean =
        isJavacLambdaBodyName(name) || isScalacLambdaBodyName(name) || KOTLINC_LAMBDA_BODY_NAME.matches(name)

    private fun isJavacLambdaBodyName(name: String): Boolean = name.startsWith("lambda\$")

    private fun isScalacLambdaBodyName(name: String): Boolean = name.startsWith("\$anonfun\$") && !name.endsWith("\$adapted")

    private val KOTLINC_LAMBDA_BODY_NAME = Regex("^.+\\\$lambda\\\$\\d+(\\\$\\d+)*$")

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
