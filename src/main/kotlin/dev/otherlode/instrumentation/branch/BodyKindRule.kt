package dev.otherlode.instrumentation.branch

import dev.otherlode.export.BodyKind

/**
 * Says what kind of source body a body class was compiled from, the `body_kind` its class location
 * carries. It reads only facts a class file states, so [classify] is pure and [BranchSiteAnalyzer]
 * calls it once per class, after it reads the class header.
 *
 * The Kotlin runtime base classes are matched by shape: one top-level package segment, then the
 * base class's path inside it. The segment itself is not compared. `shadowJar` rewrites any string
 * in this agent's own code that starts with `kotlin/` or `kotlin.`, and even the bare `kotlin`, so
 * a literal would never match an adopter's class. [BranchSiteAnalyzer] matches `kotlin.Metadata`
 * by shape for the same reason. `ShadedBodyKindRuleTest` runs the rule from the shaded jar.
 *
 * A Kotlin function or property reference class gets no kind of its own. kotlinc marks it
 * synthetic, so the agent never sends it, and [BranchSiteAnalyzer] passes through it instead.
 */
internal object BodyKindRule {
    /** What a class's `InnerClasses` attribute says about the class itself. [innerName] is null for an anonymous entry. */
    data class OwnInnerClassEntry(
        val innerName: String?,
    )

    /** A class's [BodyKind], and the source name of a [BodyKind.LOCAL_CLASS]. [sourceName] is null for every other kind. */
    data class Classification(
        val kind: BodyKind,
        val sourceName: String? = null,
    ) {
        companion object {
            val NONE = Classification(BodyKind.NONE)
        }
    }

    /**
     * The superclasses kotlinc gives a lambda compiled to a class and every suspend lambda, less
     * their top-level `kotlin` segment.
     */
    private val lambdaBases =
        setOf(
            "jvm/internal/Lambda",
            "coroutines/jvm/internal/SuspendLambda",
            "coroutines/jvm/internal/RestrictedSuspendLambda",
        )

    /**
     * The class's [BodyKind]. Checks run in this order and the first match wins: no
     * `EnclosingMethod` attribute ([BodyKind.NONE], whatever else the class states), a lambda base
     * class, a named `InnerClasses` entry, an anonymous entry with Kotlin metadata, else an
     * anonymous class. A Kotlin lambda class also carries an anonymous entry and metadata, so the
     * base-class check must come first. Only the direct superclass is compared, since kotlinc
     * always extends a runtime base class directly.
     *
     * [superInternalName] is the class header's superclass, slashed. [ownInnerClassEntry] is the
     * `InnerClasses` entry whose inner class is this class, or null when it has none.
     * [hasKotlinMetadata] is true when the class carries a `kotlin.Metadata` annotation.
     */
    fun classify(
        hasEnclosingMethod: Boolean,
        superInternalName: String?,
        ownInnerClassEntry: OwnInnerClassEntry?,
        hasKotlinMetadata: Boolean,
    ): Classification {
        if (!hasEnclosingMethod) return Classification.NONE
        if (runtimeBaseName(superInternalName) in lambdaBases) return Classification(BodyKind.LAMBDA_CLASS)
        val innerName = ownInnerClassEntry?.innerName
        return when {
            innerName != null -> Classification(BodyKind.LOCAL_CLASS, innerName)
            ownInnerClassEntry != null && hasKotlinMetadata -> Classification(BodyKind.OBJECT_EXPRESSION)
            else -> Classification(BodyKind.ANONYMOUS_CLASS)
        }
    }

    /** [internalName] less its top-level package segment, such as `jvm/internal/Lambda`, or null when it has no package. */
    private fun runtimeBaseName(internalName: String?): String? {
        if (internalName == null) return null
        val slash = internalName.indexOf('/')
        if (slash <= 0) return null
        return internalName.substring(slash + 1)
    }
}
