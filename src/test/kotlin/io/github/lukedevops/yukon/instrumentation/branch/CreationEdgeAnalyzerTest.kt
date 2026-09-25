package io.github.lukedevops.yukon.instrumentation.branch

import io.github.lukedevops.yukon.export.CallEdge
import io.github.lukedevops.yukon.export.CallEdgeKind
import net.bytebuddy.dynamic.ClassFileLocator
import net.bytebuddy.jar.asm.ClassWriter
import net.bytebuddy.jar.asm.Opcodes
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Proves [BranchSiteAnalyzer]'s ADR 0034 facts on real kotlinc and javac output: the kind and
 * captured count of each creation edge, which methods are lambda bodies, and the class's source
 * file. Also proves the interface each creation edge names (ADR 0042).
 */
class CreationEdgeAnalyzerTest {
    private val includePackages = listOf("com.example.target")

    private val lookup: (String) -> ByteArray? = { internalName ->
        val dottedName = internalName.replace('/', '.')
        listOf("build/classes/kotlin/test", "build/classes/java/test")
            .asSequence()
            .map { ClassFileLocator.ForFolder(File(it)).locate(dottedName) }
            .firstOrNull { it.isResolved }
            ?.resolve()
    }

    private fun analyze(
        root: String,
        simpleName: String,
        methodFilter: (String, String) -> Boolean = { _, _ -> true },
    ): BranchSiteAnalyzer.Analysis =
        BranchSiteAnalyzer.analyze(
            File("$root/com/example/target/$simpleName.class").readBytes(),
            lookup,
            includePackages,
            emptyList(),
            methodFilter = methodFilter,
        )

    /** Leaves out `withDefault$default`, as the method tier does, so the analyser treats it as a pass-through. */
    private fun kotlinTarget() = analyze("build/classes/kotlin/test", "CreationEdgeTarget") { name, _ -> !name.endsWith("\$default") }

    private fun javaTarget() = analyze("build/classes/java/test", "CreationEdgeJavaTarget") { name, _ -> name != "<clinit>" }

    private val kotlinOwner = "com.example.target.CreationEdgeTarget"
    private val javaOwner = "com.example.target.CreationEdgeJavaTarget"

    /** The interface kotlinc gives a lambda of type `(Int) -> Int`. */
    private val function1 = "kotlin.jvm.functions.Function1"

    @Test
    fun `a Kotlin lambda is a creation edge with nothing captured, and its body is a lambda body`() {
        val analysis = kotlinTarget()

        assertTrue(
            CallEdge(kotlinOwner, "plain\$lambda\$0", "(I)I", virtual = false, kind = CallEdgeKind.CREATES, implementedInterface = function1) in
                analysis.callsOf("plain", "()I"),
        )
        assertTrue(
            CallEdge("com.example.target.CallEdgeTargetKt", "applyOp", "(Lkotlin/jvm/functions/Function1;I)I", virtual = false) in
                analysis.callsOf("plain", "()I"),
            "the call to the function the lambda is passed to stays a CALL",
        )
        assertTrue(analysis.isLambdaBody("plain\$lambda\$0", "(I)I"))
        assertFalse(analysis.isLambdaBody("plain", "()I"))
    }

    @Test
    fun `a capturing Kotlin lambda counts its captured local, and a lambda capturing this counts the receiver it takes as a parameter`() {
        val analysis = kotlinTarget()

        assertTrue(
            CallEdge(
                kotlinOwner,
                "capturing\$lambda\$0",
                "(II)I",
                virtual = false,
                kind = CallEdgeKind.CREATES,
                capturedCount = 1,
                implementedInterface = function1,
            ) in analysis.callsOf("capturing", "(I)I"),
        )
        assertTrue(
            CallEdge(
                kotlinOwner,
                "capturingThis\$lambda\$0",
                "(Lcom/example/target/CreationEdgeTarget;I)I",
                virtual = false,
                kind = CallEdgeKind.CREATES,
                capturedCount = 1,
                implementedInterface = function1,
            ) in analysis.callsOf("capturingThis", "()I"),
        )
        assertTrue(analysis.isLambdaBody("capturing\$lambda\$0", "(II)I"))
        assertTrue(analysis.isLambdaBody("capturingThis\$lambda\$0", "(Lcom/example/target/CreationEdgeTarget;I)I"))
    }

    @Test
    fun `a nested Kotlin lambda is created by the lambda it is written in, not by the named method`() {
        val analysis = kotlinTarget()

        val nestedEdges = analysis.callsOf("nested", "()I")
        assertTrue(
            CallEdge(kotlinOwner, "nested\$lambda\$0", "(I)I", virtual = false, kind = CallEdgeKind.CREATES, implementedInterface = function1) in
                nestedEdges,
        )
        assertTrue(nestedEdges.none { it.methodName == "nested\$lambda\$0\$0" })
        assertTrue(
            CallEdge(
                kotlinOwner,
                "nested\$lambda\$0\$0",
                "(II)I",
                virtual = false,
                kind = CallEdgeKind.CREATES,
                capturedCount = 1,
                implementedInterface = function1,
            ) in analysis.callsOf("nested\$lambda\$0", "(I)I"),
        )
        assertTrue(analysis.isLambdaBody("nested\$lambda\$0", "(I)I"))
        assertTrue(analysis.isLambdaBody("nested\$lambda\$0\$0", "(II)I"))
    }

    @Test
    fun `a named method handed to a Java functional interface is a creation edge but not a lambda body`() {
        val analysis = kotlinTarget()

        assertEquals(
            listOf(
                CallEdge(
                    kotlinOwner,
                    "twice",
                    "(I)I",
                    virtual = false,
                    kind = CallEdgeKind.CREATES,
                    implementedInterface = "java.util.function.IntUnaryOperator",
                ),
            ),
            analysis.callsOf("samReference", "()I").filter { it.className == kotlinOwner },
        )
        assertFalse(analysis.isLambdaBody("twice", "(I)I"))
    }

    @Test
    fun `a lambda created inside a default-filling pass-through keeps its CREATES kind, and the target keeps its CALL`() {
        val analysis = kotlinTarget()

        assertEquals(
            listOf(
                CallEdge(
                    kotlinOwner,
                    "withDefault\$lambda\$0",
                    "(I)I",
                    virtual = false,
                    kind = CallEdgeKind.CREATES,
                    implementedInterface = function1,
                ),
                CallEdge(kotlinOwner, "withDefault", "(Lkotlin/jvm/functions/Function1;)I", virtual = false),
            ),
            analysis.callsOf("callsDefault", "()I"),
        )
        assertTrue(analysis.isLambdaBody("withDefault\$lambda\$0", "(I)I"), "an invokedynamic inside a pass-through still names it")
    }

    @Test
    fun `a body class's constructor edge stays a CALL while its other methods are creation edges`() {
        val analysis = kotlinTarget()

        assertEquals(
            listOf(
                CallEdge(
                    "com.example.target.CreationEdgeTarget\$makesBodyClass\$1",
                    "<init>",
                    "(Lcom/example/target/CreationEdgeTarget;)V",
                    virtual = false,
                ),
                CallEdge("com.example.target.CreationEdgeTarget\$makesBodyClass\$1", "run", "()V", virtual = true, kind = CallEdgeKind.CREATES),
            ),
            analysis.callsOf("makesBodyClass", "()Ljava/lang/Runnable;"),
        )
    }

    @Test
    fun `a javac lambda capturing a local counts it, and one capturing this does not count the receiver`() {
        val analysis = javaTarget()

        assertEquals(
            listOf(
                CallEdge(
                    javaOwner,
                    "lambda\$capturing\$0",
                    "(II)I",
                    virtual = false,
                    kind = CallEdgeKind.CREATES,
                    capturedCount = 1,
                    implementedInterface = "java.util.function.IntUnaryOperator",
                ),
            ),
            analysis.callsOf("capturing", "(I)I"),
        )
        assertEquals(
            listOf(
                CallEdge(
                    javaOwner,
                    "lambda\$capturingThis\$1",
                    "(I)I",
                    virtual = false,
                    kind = CallEdgeKind.CREATES,
                    implementedInterface = "java.util.function.IntUnaryOperator",
                ),
            ),
            analysis.callsOf("capturingThis", "()I"),
        )
        assertTrue(analysis.isLambdaBody("lambda\$capturing\$0", "(II)I"))
        assertTrue(analysis.isLambdaBody("lambda\$capturingThis\$1", "(I)I"))
    }

    @Test
    fun `javac method references are creation edges with nothing captured, and their targets are not lambda bodies`() {
        val analysis = javaTarget()
        val name = CallEdge(javaOwner, "name", "()Ljava/lang/String;", virtual = true, kind = CallEdgeKind.CREATES)

        assertEquals(
            listOf(name.copy(implementedInterface = "java.util.function.Supplier")),
            analysis.callsOf("boundReference", "()Ljava/util/function/Supplier;"),
        )
        assertEquals(
            listOf(name.copy(implementedInterface = "java.util.function.Function")),
            analysis.callsOf("unboundReference", "()Ljava/util/function/Function;"),
        )
        assertEquals(
            listOf(
                CallEdge(
                    javaOwner,
                    "constant",
                    "()I",
                    virtual = false,
                    kind = CallEdgeKind.CREATES,
                    implementedInterface = "java.util.function.IntSupplier",
                ),
            ),
            analysis.callsOf("staticReference", "()Ljava/util/function/IntSupplier;"),
        )
        assertEquals(
            listOf(
                CallEdge(
                    "com.example.target.CreationEdgeJavaTarget\$Box",
                    "<init>",
                    "(Ljava/lang/String;)V",
                    virtual = false,
                    kind = CallEdgeKind.CREATES,
                    implementedInterface = "java.util.function.Function",
                ),
            ),
            analysis.callsOf("nestedConstructorReference", "()Ljava/util/function/Function;"),
        )
        assertFalse(analysis.isLambdaBody("name", "()Ljava/lang/String;"))
        assertFalse(analysis.isLambdaBody("constant", "()I"))
    }

    @Test
    fun `javac turns an inner class constructor reference into a lambda body of its own`() {
        val analysis = javaTarget()

        assertEquals(
            CallEdge(
                javaOwner,
                "lambda\$innerConstructorReference\$2",
                "()Lcom/example/target/CreationEdgeJavaTarget\$Inner;",
                virtual = false,
                kind = CallEdgeKind.CREATES,
                implementedInterface = "java.util.function.Supplier",
            ),
            analysis.callsOf("innerConstructorReference", "()Ljava/util/function/Supplier;").single(),
        )
        assertTrue(analysis.isLambdaBody("lambda\$innerConstructorReference\$2", "()Lcom/example/target/CreationEdgeJavaTarget\$Inner;"))
    }

    @Test
    fun `a method the filter turns away is never a lambda body, even when an invokedynamic names it`() {
        val analysis =
            analyze("build/classes/kotlin/test", "CreationEdgeTarget") { name, _ -> name != "plain\$lambda\$0" }

        assertFalse(analysis.isLambdaBody("plain\$lambda\$0", "(I)I"))
    }

    @Test
    fun `an ordinary call edge is a CALL with nothing captured`() {
        val analysis = analyze("build/classes/kotlin/test", "CallEdgeTarget")

        assertTrue(analysis.callsOf("callsPrivateMethod", "()I").all { it.kind == CallEdgeKind.CALL && it.capturedCount == 0 })
    }

    @Test
    fun `the captured count leaves out a bound receiver and never goes below zero`() {
        assertEquals(2, BranchSiteAnalyzer.capturedCount("(II)Ljava/util/function/IntSupplier;", Opcodes.H_INVOKESTATIC))
        assertEquals(1, BranchSiteAnalyzer.capturedCount("(Lcom/acme/Foo;J)Ljava/lang/Runnable;", Opcodes.H_INVOKEVIRTUAL))
        assertEquals(1, BranchSiteAnalyzer.capturedCount("(Lcom/acme/Foo;[I)Ljava/lang/Runnable;", Opcodes.H_INVOKEINTERFACE))
        assertEquals(0, BranchSiteAnalyzer.capturedCount("(Lcom/acme/Foo;)Ljava/lang/Runnable;", Opcodes.H_INVOKESPECIAL))
        assertEquals(0, BranchSiteAnalyzer.capturedCount("()Ljava/util/function/Function;", Opcodes.H_INVOKEVIRTUAL))
        assertEquals(
            2,
            BranchSiteAnalyzer.capturedCount("(Ljava/lang/String;D)Ljava/util/function/Supplier;", Opcodes.H_NEWINVOKESPECIAL),
            "a constructor has no receiver to bind, so every captured value fills a parameter",
        )
    }

    @Test
    fun `the source file is read from the class file as it appears`() {
        assertEquals("CreationEdgeTarget.kt", kotlinTarget().sourceFile)
        assertEquals("CreationEdgeJavaTarget.java", javaTarget().sourceFile)
        assertEquals("CallEdgeTarget.kt", analyze("build/classes/kotlin/test", "CallEdgeTargetKt").sourceFile)
    }

    @Test
    fun `a class file with no SourceFile attribute has no source file`() {
        val writer = ClassWriter(0)
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "com/example/target/NoSource", null, "java/lang/Object", null)
        writer.visitEnd()

        val analysis = BranchSiteAnalyzer.analyze(writer.toByteArray(), includePackages = includePackages) { _, _ -> true }

        assertNull(analysis.sourceFile)
    }

    private val interfaceOwner = "com.example.target.ImplementedInterfaceTarget"
    private val javaInterfaceOwner = "com.example.target.ImplementedInterfaceJavaTarget"

    private fun interfaceTarget() = analyze("build/classes/kotlin/test", "ImplementedInterfaceTarget")

    private fun javaInterfaceTarget() = analyze("build/classes/java/test", "ImplementedInterfaceJavaTarget")

    @Test
    fun `each kotlinc creation edge names the interface its call site returns, Kotlin function types included`() {
        val analysis = interfaceTarget()

        assertEquals(
            listOf(
                CallEdge(
                    interfaceOwner,
                    "register\$lambda\$0",
                    "(Lcom/sun/net/httpserver/HttpExchange;)V",
                    virtual = false,
                    kind = CallEdgeKind.CREATES,
                    implementedInterface = "com.sun.net.httpserver.HttpHandler",
                ),
            ),
            analysis.callsOf("register", "(Lcom/sun/net/httpserver/HttpServer;)V"),
        )
        assertEquals(
            listOf(
                CallEdge(
                    interfaceOwner,
                    "startsThread\$lambda\$0",
                    "(Lcom/example/target/ImplementedInterfaceTarget;)V",
                    virtual = false,
                    kind = CallEdgeKind.CREATES,
                    capturedCount = 1,
                    implementedInterface = "java.lang.Runnable",
                ),
            ),
            analysis.callsOf("startsThread", "()Ljava/lang/Thread;"),
        )
        assertTrue(
            CallEdge(
                interfaceOwner,
                "funInterface\$lambda\$0",
                "(I)I",
                virtual = false,
                kind = CallEdgeKind.CREATES,
                implementedInterface = "com.example.target.IntStep",
            ) in analysis.callsOf("funInterface", "()I"),
        )
        assertEquals(
            listOf(
                CallEdge(
                    interfaceOwner,
                    "functionType\$lambda\$0",
                    "(I)I",
                    virtual = false,
                    kind = CallEdgeKind.CREATES,
                    implementedInterface = "kotlin.jvm.functions.Function1",
                ),
            ),
            analysis.callsOf("functionType", "()I"),
        )
    }

    @Test
    fun `a body class created with new names no interface, and no CALL edge names one`() {
        val analysis = interfaceTarget()

        assertEquals(
            listOf(
                CallEdge(
                    "com.example.target.ImplementedInterfaceTarget\$objectExpression\$1",
                    "<init>",
                    "(Lcom/example/target/ImplementedInterfaceTarget;)V",
                    virtual = false,
                ),
                CallEdge(
                    "com.example.target.ImplementedInterfaceTarget\$objectExpression\$1",
                    "run",
                    "()V",
                    virtual = true,
                    kind = CallEdgeKind.CREATES,
                ),
            ),
            analysis.callsOf("objectExpression", "()Ljava/lang/Runnable;"),
        )
        val calls =
            listOf("funInterface" to "()I", "startsThread\$lambda\$0" to "(Lcom/example/target/ImplementedInterfaceTarget;)V")
                .flatMap { (name, descriptor) -> analysis.callsOf(name, descriptor) }
                .filter { it.kind == CallEdgeKind.CALL }
        assertTrue(calls.isNotEmpty(), "the call to step and the call to touch are CALL edges")
        assertTrue(calls.all { it.implementedInterface == null })
    }

    @Test
    fun `a javac lambda names its interface, and an anonymous class created with new names none`() {
        val analysis = javaInterfaceTarget()

        assertEquals(
            listOf(
                CallEdge(
                    javaInterfaceOwner,
                    "lambda\$lambda\$0",
                    "()V",
                    virtual = false,
                    kind = CallEdgeKind.CREATES,
                    implementedInterface = "java.lang.Runnable",
                ),
            ),
            analysis.callsOf("lambda", "()Ljava/lang/Runnable;"),
        )
        assertEquals(
            listOf(
                CallEdge(
                    "com.example.target.ImplementedInterfaceJavaTarget\$1",
                    "<init>",
                    "(Lcom/example/target/ImplementedInterfaceJavaTarget;)V",
                    virtual = false,
                ),
                CallEdge("com.example.target.ImplementedInterfaceJavaTarget\$1", "run", "()V", virtual = true, kind = CallEdgeKind.CREATES),
            ),
            analysis.callsOf("anonymous", "()Ljava/lang/Runnable;"),
        )
    }

    @Test
    fun `one method handed to two interfaces gives two creation edges`() {
        val analysis = javaInterfaceTarget()

        assertEquals(
            listOf(
                CallEdge(
                    javaInterfaceOwner,
                    "touch",
                    "()I",
                    virtual = true,
                    kind = CallEdgeKind.CREATES,
                    implementedInterface = "java.util.function.IntSupplier",
                ),
                CallEdge(
                    javaInterfaceOwner,
                    "touch",
                    "()I",
                    virtual = true,
                    kind = CallEdgeKind.CREATES,
                    implementedInterface = "java.util.function.Supplier",
                ),
            ),
            analysis.callsOf("twoInterfaces", "()[Ljava/lang/Object;"),
        )
    }
}
