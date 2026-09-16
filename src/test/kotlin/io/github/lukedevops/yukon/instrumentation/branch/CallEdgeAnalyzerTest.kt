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

    /** Resolves another class's bytes by internal name, the way [io.github.lukedevops.yukon.instrumentation.YukonInstrumentation] does. */
    private val lookup: (String) -> ByteArray? = { internalName ->
        val dottedName = internalName.replace('/', '.')
        listOf("build/classes/kotlin/test", "build/classes/java/test")
            .asSequence()
            .map { ClassFileLocator.ForFolder(File(it)).locate(dottedName) }
            .firstOrNull { it.isResolved }
            ?.resolve()
    }

    private fun analyzeCallEdgeTarget(): BranchSiteAnalyzer.Analysis =
        BranchSiteAnalyzer.analyze(
            readTargetBytes("CallEdgeTarget"),
            lookup,
            includePackages,
            emptyList(),
        ) { _, _ -> true }

    /**
     * Analyses another Kotlin fixture class the same way, with the real cross-class [lookup] by
     * default so pass-through resolution runs; [useLookup] false simulates an unreadable owner.
     */
    private fun analyzeTarget(
        simpleName: String,
        useLookup: Boolean = true,
    ): BranchSiteAnalyzer.Analysis =
        BranchSiteAnalyzer.analyze(
            readTargetBytes(simpleName),
            if (useLookup) lookup else { _ -> null },
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

    @Test
    fun `a nested class's call through an access$ accessor resolves to the private target, plus the owner's clinit`() {
        val analysis = analyzeTarget("AccessorTarget\$Inner")

        assertEquals(
            listOf(
                CallEdge("com.example.target.AccessorTarget", "secret", "()I", virtual = false),
                CallEdge("com.example.target.AccessorTarget", "<clinit>", "()V", virtual = false),
            ),
            analysis.callsOf("callSecret", "()I"),
        )
    }

    @Test
    fun `a field read through an access$ accessor yields only the owner's clinit, never an edge to the accessor`() {
        val analysis = analyzeTarget("AccessorTarget\$Inner")

        assertEquals(
            listOf(CallEdge("com.example.target.AccessorTarget", "<clinit>", "()V", virtual = false)),
            analysis.callsOf("readX", "()I"),
        )
    }

    @Test
    fun `a private companion member reached from the enclosing class resolves through the companion's own accessor`() {
        val analysis = analyzeTarget("AccessorTarget")

        assertEquals(
            listOf(
                CallEdge("com.example.target.AccessorTarget\$Companion", "companionSecret", "()I", virtual = false),
                CallEdge("com.example.target.AccessorTarget\$Companion", "<clinit>", "()V", virtual = false),
            ),
            analysis.callsOf("callCompanionSecret", "()I"),
        )
    }

    @Test
    fun `an unreadable owner leaves a cross-class synthetic callee as a verbatim edge to the accessor itself`() {
        val analysis = analyzeTarget("AccessorTarget\$Inner", useLookup = false)

        assertEquals(
            listOf(
                CallEdge(
                    "com.example.target.AccessorTarget",
                    "access\$secret",
                    "(Lcom/example/target/AccessorTarget;)I",
                    virtual = false,
                ),
            ),
            analysis.callsOf("callSecret", "()I"),
        )
    }

    @Test
    fun `a static read of another class's enum constant is an edge to that class's clinit`() {
        val analysis = analyzeTarget("StaticUseTarget")

        assertEquals(
            listOf(CallEdge("com.example.target.Suit", "<clinit>", "()V", virtual = false)),
            analysis.callsOf("readEnumConstant", "()Lcom/example/target/Suit;"),
        )
    }

    @Test
    fun `a static write and a static read of the same owner collapse into one deduplicated clinit edge`() {
        val analysis = analyzeTarget("StaticUseTarget")

        assertEquals(
            listOf(CallEdge("com.example.target.Config", "<clinit>", "()V", virtual = false)),
            analysis.callsOf("readAndWriteConfigFlag", "()Z"),
        )
    }

    @Test
    fun `a static read and a static write of a Java field are clinit edges even though the owner declares no clinit`() {
        val analysis = analyzeTarget("StaticUseTarget")

        assertEquals(
            listOf(CallEdge("com.example.other.OtherTarget", "<clinit>", "()V", virtual = false)),
            analysis.callsOf("readJavaStaticField", "()I"),
        )
        assertEquals(
            listOf(CallEdge("com.example.other.OtherTarget", "<clinit>", "()V", virtual = false)),
            analysis.callsOf("writeJavaStaticField", "()V"),
        )
    }

    @Test
    fun `a same-class static field use records no edge`() {
        val analysis = analyzeTarget("SelfStaticUser")

        assertEquals(emptyList(), analysis.callsOf("touchOwnCounter", "()I"))
    }

    @Test
    fun `an out-of-scope static read is excluded by the in-scope filter`() {
        val analysis = analyzeTarget("StaticUseTarget")

        assertEquals(emptyList(), analysis.callsOf("readSystemOut", "()Ljava/io/PrintStream;"))
    }

    @Test
    fun `instance field access on another in-scope class records no edge beyond the constructor edge`() {
        val analysis = analyzeTarget("StaticUseTarget")

        assertEquals(
            listOf(CallEdge("com.example.other.OtherTarget", "<init>", "()V", virtual = false)),
            analysis.callsOf("touchesOtherFieldAndConstructs", "()I"),
        )
    }

    @Test
    fun `a call to another in-scope class's final method is non-virtual`() {
        val analysis = analyzeTarget("StaticUseTarget")

        assertEquals(
            listOf(CallEdge("com.example.target.FinalMethodTarget", "method", "()I", virtual = false)),
            analysis.callsOf("callsFinalMethod", "(Lcom/example/target/FinalMethodTarget;)I"),
        )
    }
}
