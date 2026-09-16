package io.github.lukedevops.yukon.instrumentation.branch

import io.github.lukedevops.yukon.export.CallEdge
import net.bytebuddy.dynamic.ClassFileLocator
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Proves [BranchSiteAnalyzer]'s call-edge and supertype recording (ADR 0024): raw candidates from
 * `invokestatic`/`invokevirtual`/`invokespecial`/`invokeinterface` and `invokedynamic`, the
 * same-class virtual override, the in-scope filter, self-edge dropping, deduplication, and both
 * same-class and cross-class `$default` pass-through resolution.
 */
class CallEdgeAnalyzerTest {
    private fun readTargetBytes(simpleName: String): ByteArray =
        File("build/classes/kotlin/test/com/example/target/$simpleName.class").readBytes()

    private val includePackages = listOf("com.example.target", "com.example.other")

    /** Resolves another com.example.target class's bytes, the way [io.github.lukedevops.yukon.instrumentation.YukonInstrumentation] does. */
    private val lookup: (String) -> ByteArray? = { internalName ->
        val locator = ClassFileLocator.ForFolder(File("build/classes/kotlin/test"))
        val resolution = locator.locate(internalName.replace('/', '.'))
        if (resolution.isResolved) resolution.resolve() else null
    }

    private fun analyzeCallEdgeTarget(): BranchSiteAnalyzer.Analysis =
        BranchSiteAnalyzer.analyze(
            readTargetBytes("CallEdgeTarget"),
            lookup,
            includePackages,
            emptyList(),
        ) { _, _ -> true }

    @Test
    fun `a private same-class call is a direct, non-virtual edge`() {
        val analysis = analyzeCallEdgeTarget()

        assertEquals(
            listOf(CallEdge("com.example.target.CallEdgeTarget", "privateHelper", "()I", virtual = false)),
            analysis.callsOf("callsPrivateMethod", "()I"),
        )
    }

    @Test
    fun `a constructor and a public method on another in-scope class are both recorded`() {
        val analysis = analyzeCallEdgeTarget()

        assertEquals(
            listOf(
                CallEdge("com.example.other.OtherTarget", "<init>", "()V", virtual = false),
                CallEdge("com.example.other.OtherTarget", "doSomething", "()V", virtual = true),
            ),
            analysis.callsOf("callsOtherClass", "()I"),
        )
    }

    @Test
    fun `a static in-scope function is a non-virtual edge to its own file-facade class`() {
        val analysis = analyzeCallEdgeTarget()

        assertEquals(
            listOf(CallEdge("com.example.target.CallEdgeTargetKt", "staticHelper", "()I", virtual = false)),
            analysis.callsOf("callsStaticFunction", "()I"),
        )
    }

    @Test
    fun `a JDK call is excluded by the in-scope filter`() {
        val analysis = analyzeCallEdgeTarget()

        assertEquals(emptyList(), analysis.callsOf("callsJdkMethod", "()J"))
    }

    @Test
    fun `a Kotlin stdlib call is excluded by the in-scope filter`() {
        val analysis = analyzeCallEdgeTarget()

        assertEquals(emptyList(), analysis.callsOf("callsKotlinStdlib", "()Ljava/lang/String;"))
    }

    @Test
    fun `an interface call through an interface-typed parameter is virtual`() {
        val analysis = analyzeCallEdgeTarget()

        assertEquals(
            listOf(CallEdge("com.example.target.Classifier", "classify", "(I)I", virtual = true)),
            analysis.callsOf("callsInterfaceMethod", "(Lcom/example/target/Classifier;)I"),
        )
    }

    @Test
    fun `a direct self-call is dropped`() {
        val analysis = analyzeCallEdgeTarget()

        assertEquals(emptyList(), analysis.callsOf("callsSelfRecursively", "(I)I"))
    }

    @Test
    fun `a cross-class Kotlin default pass-through resolves to its final, non-virtual target`() {
        val analysis = analyzeCallEdgeTarget()

        assertEquals(
            listOf(
                CallEdge(
                    "com.example.target.DefaultArgumentTarget",
                    "f",
                    "(IILjava/lang/String;J)I",
                    virtual = false,
                ),
            ),
            analysis.callsOf("callsWithDefaultArgument", "(Lcom/example/target/DefaultArgumentTarget;)I"),
        )
    }

    @Test
    fun `a lambda passed to a non-inline function reaches the lambda body and the function itself`() {
        val analysis = analyzeCallEdgeTarget()

        assertEquals(
            setOf(
                CallEdge("com.example.target.CallEdgeTarget", "callsLambda\$lambda\$0", "(I)I", virtual = false),
                CallEdge("com.example.target.CallEdgeTargetKt", "applyOp", "(Lkotlin/jvm/functions/Function1;I)I", virtual = false),
            ),
            analysis.callsOf("callsLambda", "()I").toSet(),
        )
    }

    @Test
    fun `a javac lambda body is reached with virtual false and a method reference target with virtual true`() {
        val bytes = File("build/classes/java/test/com/example/target/LambdaTarget.class").readBytes()
        val analysis = BranchSiteAnalyzer.analyze(bytes, includePackages = includePackages) { _, _ -> true }

        assertEquals(
            listOf(CallEdge("com.example.target.LambdaTarget", "lambda\$classifyViaLambda\$0", "(I)I", virtual = false)),
            analysis.callsOf("classifyViaLambda", "(I)I"),
        )
        assertEquals(
            listOf(CallEdge("com.example.target.LambdaTarget", "ship", "()Ljava/lang/String;", virtual = true)),
            analysis.callsOf("shipViaMethodReference", "()Ljava/lang/String;"),
        )
    }

    @Test
    fun `supertypes are read for a class`() {
        val analysis =
            BranchSiteAnalyzer.analyze(readTargetBytes("CallEdgeTarget"), includePackages = includePackages) { _, _ -> true }

        assertEquals("java.lang.Object", analysis.superClassName)
        assertEquals(emptyList(), analysis.interfaceNames)
    }

    @Test
    fun `supertypes are read for an interface`() {
        val analysis = BranchSiteAnalyzer.analyze(readTargetBytes("Classifier"), includePackages = includePackages) { _, _ -> true }

        assertEquals("java.lang.Object", analysis.superClassName, "an interface's own super_class entry is java.lang.Object too")
        assertEquals(emptyList(), analysis.interfaceNames)
    }

    @Test
    fun `an implementing class's supertypes name both its superclass and its interface`() {
        val analysis = BranchSiteAnalyzer.analyze(readTargetBytes("ClassifierImpl"), includePackages = includePackages) { _, _ -> true }

        assertEquals("java.lang.Object", analysis.superClassName)
        assertEquals(listOf("com.example.target.Classifier"), analysis.interfaceNames)
    }

    @Test
    fun `narrowing the in-scope packages changes calls but not the sites feeding the layout hash`() {
        val bytes = readTargetBytes("CallEdgeTarget")

        val wideScope = BranchSiteAnalyzer.analyze(bytes, includePackages = includePackages) { _, _ -> true }
        val narrowScope = BranchSiteAnalyzer.analyze(bytes, includePackages = listOf("com.example.target.CallEdgeTarget")) { _, _ -> true }

        // com.example.other is out of scope under the narrower list, so callsOtherClass's edges
        // differ between the two analyses...
        assertTrue(wideScope.callsOf("callsOtherClass", "()I").isNotEmpty())
        assertTrue(narrowScope.callsOf("callsOtherClass", "()I").isEmpty())
        // ...while every input YukonInstrumentation actually feeds into ProbeLayoutHash.of --
        // branch sites and default sites, read straight off the bytecode -- stays identical, since
        // neither depends on includePackages/excludePackages. Method signatures are unaffected the
        // same way, since they come from ByteBuddy's own declaredMethods filter, not from this
        // analysis at all. See ADR 0024.
        assertEquals(wideScope.sites, narrowScope.sites)
        assertEquals(wideScope.defaultSites, narrowScope.defaultSites)
    }

    @Test
    fun `a pass-through cycle terminates instead of looping, yielding no edge`() {
        val bytes = readTargetBytes("CycleTarget")

        val analysis = BranchSiteAnalyzer.analyze(bytes, includePackages = includePackages) { name, _ -> name == "real" }

        assertEquals(emptyList(), analysis.callsOf("real", "()I"))
    }

    @Test
    fun `a same-class call to an abstract method stays a virtual edge instead of vanishing as a pass-through`() {
        // The real method matcher never probes an abstract method, so it is not eligible here either.
        val analysis =
            BranchSiteAnalyzer.analyze(readTargetBytes("TemplateTarget"), includePackages = includePackages) { name, _ -> name != "step" }

        assertEquals(
            listOf(CallEdge("com.example.target.TemplateTarget", "step", "()I", virtual = true)),
            analysis.callsOf("run", "()I"),
        )
    }

    @Test
    fun `a call to a method the class only inherits stays an edge even though it is not in the method table`() {
        val analysis = BranchSiteAnalyzer.analyze(readTargetBytes("SubTarget"), includePackages = includePackages) { _, _ -> true }

        val edge = analysis.callsOf("go", "()I").single()
        assertEquals("inherited", edge.methodName)
        assertTrue(edge.virtual, "an inherited method could be overridden further down; the collector resolves it")
    }
}
