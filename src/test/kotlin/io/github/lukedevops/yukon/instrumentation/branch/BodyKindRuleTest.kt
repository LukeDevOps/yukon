package io.github.lukedevops.yukon.instrumentation.branch

import io.github.lukedevops.yukon.export.BodyKind
import io.github.lukedevops.yukon.instrumentation.branch.BodyKindRule.Classification
import io.github.lukedevops.yukon.instrumentation.branch.BodyKindRule.OwnInnerClassEntry
import kotlin.test.Test
import kotlin.test.assertEquals

/** Proves each branch of [BodyKindRule.classify], in ADR 0034's order, on facts alone. */
class BodyKindRuleTest {
    private val anonymousEntry = OwnInnerClassEntry(innerName = null)

    private fun classify(
        hasEnclosingMethod: Boolean = true,
        superInternalName: String? = "java/lang/Object",
        ownInnerClassEntry: OwnInnerClassEntry? = anonymousEntry,
        hasKotlinMetadata: Boolean = false,
    ): Classification = BodyKindRule.classify(hasEnclosingMethod, superInternalName, ownInnerClassEntry, hasKotlinMetadata)

    @Test
    fun `a class with no EnclosingMethod attribute is not a body class, whatever else it states`() {
        assertEquals(Classification.NONE, classify(hasEnclosingMethod = false))
        assertEquals(Classification.NONE, classify(hasEnclosingMethod = false, superInternalName = "kotlin/jvm/internal/Lambda"))
        assertEquals(Classification.NONE, classify(hasEnclosingMethod = false, superInternalName = "kotlin/jvm/internal/FunctionReferenceImpl"))
        assertEquals(Classification.NONE, classify(hasEnclosingMethod = false, ownInnerClassEntry = OwnInnerClassEntry("Nested")))
        assertEquals(Classification.NONE, classify(hasEnclosingMethod = false, hasKotlinMetadata = true))
    }

    @Test
    fun `each Kotlin lambda base class gives a lambda class`() {
        for (base in listOf(
            "kotlin/jvm/internal/Lambda",
            "kotlin/coroutines/jvm/internal/SuspendLambda",
            "kotlin/coroutines/jvm/internal/RestrictedSuspendLambda",
        )) {
            assertEquals(Classification(BodyKind.LAMBDA_CLASS), classify(superInternalName = base, hasKotlinMetadata = true), base)
        }
    }

    @Test
    fun `the superclass rule comes before the InnerClasses entry`() {
        assertEquals(
            Classification(BodyKind.LAMBDA_CLASS),
            classify(superInternalName = "kotlin/jvm/internal/Lambda", ownInnerClassEntry = OwnInnerClassEntry("Named")),
        )
        assertEquals(Classification(BodyKind.LAMBDA_CLASS), classify(superInternalName = "kotlin/jvm/internal/Lambda", ownInnerClassEntry = null))
    }

    @Test
    fun `a named own InnerClasses entry gives a local class with that name`() {
        assertEquals(Classification(BodyKind.LOCAL_CLASS, "Local"), classify(ownInnerClassEntry = OwnInnerClassEntry("Local")))
        assertEquals(
            Classification(BodyKind.LOCAL_CLASS, "Local"),
            classify(ownInnerClassEntry = OwnInnerClassEntry("Local"), hasKotlinMetadata = true),
            "Kotlin metadata does not change a named entry",
        )
    }

    @Test
    fun `an unnamed own InnerClasses entry gives an object expression with Kotlin metadata and an anonymous class without`() {
        assertEquals(Classification(BodyKind.OBJECT_EXPRESSION), classify(hasKotlinMetadata = true))
        assertEquals(Classification(BodyKind.ANONYMOUS_CLASS), classify(hasKotlinMetadata = false))
    }

    @Test
    fun `a body class with no InnerClasses entry for itself is an anonymous class, with or without Kotlin metadata`() {
        assertEquals(Classification(BodyKind.ANONYMOUS_CLASS), classify(ownInnerClassEntry = null))
        assertEquals(Classification(BodyKind.ANONYMOUS_CLASS), classify(ownInnerClassEntry = null, hasKotlinMetadata = true))
    }

    @Test
    fun `a superclass the rule does not know falls through to the InnerClasses entry`() {
        assertEquals(
            Classification(BodyKind.OBJECT_EXPRESSION),
            classify(superInternalName = "kotlin/jvm/internal/CallableReference", hasKotlinMetadata = true),
            "CallableReference is the reference bases' own parent, and no compiler extends it directly",
        )
        assertEquals(
            Classification(BodyKind.OBJECT_EXPRESSION),
            classify(superInternalName = "kotlin/jvm/internal/FunctionReferenceImpl", hasKotlinMetadata = true),
            "a reference class has no kind of its own, since kotlinc marks it synthetic and it never reaches the wire",
        )
        assertEquals(
            Classification(BodyKind.ANONYMOUS_CLASS),
            classify(superInternalName = "com/acme/kotlin/jvm/internal/Lambda"),
            "the base class must sit directly under a top-level package",
        )
        assertEquals(Classification(BodyKind.ANONYMOUS_CLASS), classify(superInternalName = "kotlin"))
        assertEquals(Classification(BodyKind.ANONYMOUS_CLASS), classify(superInternalName = null))
    }
}
