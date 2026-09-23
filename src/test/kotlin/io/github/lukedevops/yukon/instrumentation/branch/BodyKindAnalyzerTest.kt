package io.github.lukedevops.yukon.instrumentation.branch

import io.github.lukedevops.yukon.export.BodyKind
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Proves [BranchSiteAnalyzer]'s `body_kind` and `source_name` (ADR 0034) on real kotlinc and javac output. */
class BodyKindAnalyzerTest {
    private fun analyze(
        root: String,
        simpleName: String,
    ): BranchSiteAnalyzer.Analysis =
        BranchSiteAnalyzer.analyze(
            File("$root/com/example/target/$simpleName.class").readBytes(),
            includePackages = listOf("com.example.target"),
        ) { _, _ -> true }

    private fun kotlinClass(simpleName: String) = analyze("build/classes/kotlin/test", simpleName)

    private fun javaClass(simpleName: String) = analyze("build/classes/java/test", simpleName)

    private fun assertKind(
        expected: BodyKind,
        analysis: BranchSiteAnalyzer.Analysis,
    ) {
        assertEquals(expected, analysis.bodyKind)
        if (expected != BodyKind.LOCAL_CLASS) assertNull(analysis.sourceName, "only a local class has a source name")
    }

    @Test
    fun `a javac anonymous class is an anonymous class, in a method or in a field initializer`() {
        assertKind(BodyKind.ANONYMOUS_CLASS, javaClass("AnonymousClassTarget\$1"))
        assertKind(BodyKind.ANONYMOUS_CLASS, javaClass("BodyKindJavaTarget\$1"))
    }

    @Test
    fun `a javac local class is a local class named as the source names it`() {
        val local = javaClass("BodyKindJavaTarget\$1Local")

        assertEquals(BodyKind.LOCAL_CLASS, local.bodyKind)
        assertEquals("Local", local.sourceName)
        assertEquals("LocalRunnable", javaClass("AnonymousClassTarget\$1LocalRunnable").sourceName)
    }

    @Test
    fun `a Kotlin object expression is an object expression`() {
        assertKind(BodyKind.OBJECT_EXPRESSION, kotlinClass("ObjectExpressionTarget\$makeHandler\$1"))
        assertKind(BodyKind.OBJECT_EXPRESSION, kotlinClass("CreationEdgeTarget\$makesBodyClass\$1"))
    }

    @Test
    fun `a Kotlin local class is a local class named as the source names it`() {
        val local = kotlinClass("BodyKindTarget\$localClass\$Local")

        assertEquals(BodyKind.LOCAL_CLASS, local.bodyKind)
        assertEquals("Local", local.sourceName)
    }

    @Test
    fun `a suspend lambda, a restricted suspend lambda and a lambda kotlinc compiles to a class are lambda classes`() {
        assertKind(BodyKind.LAMBDA_CLASS, kotlinClass("BodyKindTarget\$suspendLambda\$1"))
        assertKind(BodyKind.LAMBDA_CLASS, kotlinClass("SuspendLambdaTarget\$usesSuspend\$1"))
        assertKind(BodyKind.LAMBDA_CLASS, kotlinClass("BodyKindTarget\$restrictedSuspendLambda\$1"))
        assertKind(BodyKind.LAMBDA_CLASS, kotlinClass("BodyKindTarget\$serializableLambda\$1"))
    }

    @Test
    fun `a top-level class, a named nested class and a named object are not body classes`() {
        assertKind(BodyKind.NONE, kotlinClass("BodyKindTarget"))
        assertKind(BodyKind.NONE, kotlinClass("BodyKindTargetKt"))
        assertKind(BodyKind.NONE, kotlinClass("NamedNestedTarget\$Nested"))
        assertKind(BodyKind.NONE, kotlinClass("NamedObjectTarget"))
        assertKind(BodyKind.NONE, javaClass("BodyKindJavaTarget"))
        assertKind(BodyKind.NONE, javaClass("BodyKindJavaTarget\$Nested"))
    }
}
