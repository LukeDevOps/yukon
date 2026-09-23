package io.github.lukedevops.yukon.instrumentation.branch

import io.github.lukedevops.yukon.export.CallEdge
import io.github.lukedevops.yukon.export.CallEdgeKind
import net.bytebuddy.dynamic.ClassFileLocator
import net.bytebuddy.jar.asm.ClassReader
import net.bytebuddy.jar.asm.ClassVisitor
import net.bytebuddy.jar.asm.MethodVisitor
import net.bytebuddy.jar.asm.Opcodes
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Proves on real kotlinc output that a body class the agent never probes is a pass-through (ADR
 * 0034). kotlinc marks every function and property reference class synthetic, so its creator's
 * `CREATES` edge goes to the function or accessor the reference names. A suspend function's own
 * continuation is turned away too. In neither case does any edge name the unprobed class.
 */
class UnprobedBodyClassPassThroughTest {
    private val lookup: (String) -> ByteArray? = { internalName ->
        ClassFileLocator
            .ForFolder(File("build/classes/kotlin/test"))
            .locate(internalName.replace('/', '.'))
            .takeIf { it.isResolved }
            ?.resolve()
    }

    private fun analyze(
        simpleName: String,
        lookup: (String) -> ByteArray? = this.lookup,
    ): BranchSiteAnalyzer.Analysis =
        BranchSiteAnalyzer.analyze(
            File("build/classes/kotlin/test/com/example/target/$simpleName.class").readBytes(),
            lookup,
            listOf("com.example.target"),
            emptyList(),
        ) { name, _ -> !name.startsWith("access\$") }

    private val owner = "com.example.target.BodyKindTarget"

    private fun creates(
        name: String,
        descriptor: String,
    ) = CallEdge(owner, name, descriptor, virtual = false, kind = CallEdgeKind.CREATES)

    @Test
    fun `a function reference gives its creator a creation edge to the function, adapted or not`() {
        val analysis = analyze("BodyKindTarget")

        assertEquals(listOf(creates("twice", "(I)I")), analysis.callsOf("functionReference", "()Lkotlin/jvm/functions/Function1;"))
        assertEquals(listOf(creates("twice", "(I)I")), analysis.callsOf("adaptedFunctionReference", "()Lkotlin/jvm/functions/Function1;"))
    }

    @Test
    fun `a reference to a private function reaches it through the accessor kotlinc puts in between`() {
        assertEquals(
            listOf(CallEdge("com.example.target.FunctionReferenceTarget", "secret", "()I", virtual = false, kind = CallEdgeKind.CREATES)),
            analyze("FunctionReferenceTarget").callsOf("viaReference", "()I"),
        )
    }

    @Test
    fun `a property reference gives its creator a creation edge to each accessor, bound, unbound or mutable`() {
        val analysis = analyze("BodyKindTarget")

        assertEquals(listOf(creates("getBase", "()I")), analysis.callsOf("boundPropertyReference", "()Lkotlin/jvm/functions/Function0;"))
        assertEquals(listOf(creates("getBase", "()I")), analysis.callsOf("unboundPropertyReference", "()Lkotlin/jvm/functions/Function1;"))
        assertEquals(
            listOf(creates("getCounter", "()I"), creates("setCounter", "(I)V")),
            analysis.callsOf("mutablePropertyReference", "()Lkotlin/jvm/functions/Function0;"),
        )
    }

    /**
     * The constructor reference's `invoke` wraps the function value in a `$sam$` class, which is
     * synthetic too. The wrapper only calls the function value, which is out of scope, so nothing
     * in scope is left to reach.
     */
    @Test
    fun `a fun interface constructor reference and its SAM wrapper leave no edge behind`() {
        assertEquals(
            emptyList(),
            analyze("BodyKindTargetKt").callsOf("funInterfaceConstructorReference", "()Lkotlin/jvm/functions/Function1;"),
        )
    }

    @Test
    fun `a pass-through reference class adds its own references to its creator`() {
        val references = analyze("BodyKindTarget").referencesOf("functionReference", "()Lkotlin/jvm/functions/Function1;")

        assertTrue("kotlin.jvm.internal.FunctionReferenceImpl" in references, references.toString())
    }

    @Test
    fun `a reference class whose bytes cannot be read keeps a constructor edge to it`() {
        val analysis = analyze("BodyKindTarget") { null }

        assertEquals(
            listOf(CallEdge("$owner\$functionReference\$1", "<init>", "(Ljava/lang/Object;)V", virtual = false)),
            analysis.callsOf("functionReference", "()Lkotlin/jvm/functions/Function1;"),
        )
    }

    @Test
    fun `a suspend function's continuation leaves no edge behind, since its invokeSuspend only calls back into the function`() {
        val analysis = analyze("CoroutineTargetKt")
        val coroutineOwner = "com.example.target.CoroutineTargetKt"

        assertEquals(
            listOf(CallEdge(coroutineOwner, "pauseLater", "(Lkotlin/coroutines/Continuation;)Ljava/lang/Object;", virtual = false)),
            analysis.callsOf("twoPointsSuspending", "(ILkotlin/coroutines/Continuation;)Ljava/lang/Object;"),
        )
        assertEquals(
            listOf(CallEdge(coroutineOwner, "twoPointsSuspending", "(ILkotlin/coroutines/Continuation;)Ljava/lang/Object;", virtual = false)),
            analyze("CoroutineTargetKt\$twoPointsSuspending\$1").callsOf("invokeSuspend", "(Ljava/lang/Object;)Ljava/lang/Object;"),
            "the continuation's invokeSuspend calls the function back, so its substituted edge is a self-edge",
        )
        assertEquals(
            listOf(CallEdge(coroutineOwner, "pauseNow", "(Lkotlin/coroutines/Continuation;)Ljava/lang/Object;", virtual = false)),
            analyze("Holder").callsOf("member", "(ILkotlin/coroutines/Continuation;)Ljava/lang/Object;"),
            "a member suspend function's continuation is passed through the same way",
        )
    }

    @Test
    fun `no edge anywhere in the coroutine fixture names a continuation class`() {
        val continuationEdges =
            listOf("CoroutineTargetKt", "Holder").flatMap { simpleName ->
                val analysis = analyze(simpleName)
                methodsOf(simpleName).flatMap { (name, descriptor) -> analysis.callsOf(name, descriptor) }
            }.filter { edge -> continuationClasses.any { edge.className == "com.example.target.$it" } }

        assertEquals(emptyList(), continuationEdges)
    }

    /** kotlinc's continuation classes in the coroutine fixture: the ones extending `ContinuationImpl`. */
    private val continuationClasses =
        listOf("CoroutineTargetKt\$twoPoints\$1", "CoroutineTargetKt\$twoPointsSuspending\$1", "CoroutineTargetKt\$compareRefs\$1", "Holder\$member\$1")

    private fun methodsOf(simpleName: String): List<Pair<String, String>> {
        val methods = mutableListOf<Pair<String, String>>()
        ClassReader(File("build/classes/kotlin/test/com/example/target/$simpleName.class").readBytes()).accept(
            object : ClassVisitor(Opcodes.ASM9) {
                override fun visitMethod(
                    access: Int,
                    name: String,
                    descriptor: String,
                    signature: String?,
                    exceptions: Array<out String>?,
                ): MethodVisitor? {
                    methods += name to descriptor
                    return null
                }
            },
            ClassReader.SKIP_CODE,
        )
        return methods
    }

    @Test
    fun `a body class that is not synthetic still gets its constructor and creation edges`() {
        val analysis = analyze("BodyKindTarget")

        assertEquals(
            listOf(
                CallEdge("$owner\$localClass\$Local", "<init>", "(Lcom/example/target/BodyKindTarget;)V", virtual = false),
                CallEdge("$owner\$localClass\$Local", "run", "()V", virtual = true, kind = CallEdgeKind.CREATES),
            ),
            analysis.callsOf("localClass", "()Ljava/lang/Runnable;"),
        )
    }
}
